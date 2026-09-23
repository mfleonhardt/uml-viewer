(ns uml-viewer.application.detail
  (:require [clojure.string :as str]
            [uml-viewer.engine.hit :as hit]
            [uml-viewer.engine.layout :as layout]
            [uml-viewer.application.overlay :as overlay]))

(def width 640)
(def height 700)
(def pad 16)
(def col-gap 10)

(def columns
  [{:id :crap :label "Crap" :key :crap-s :w 56 :group :crap}
   {:id :cc :label "CC" :key :cc-s :w 32 :group :crap}
   {:id :cov :label "Cov" :key :cov-s :w 44 :group :crap}
   {:id :killed :label "killed" :key :killed-s :w 52 :group :mutation}
   {:id :survived :label "survived" :key :survived-s :w 60 :group :mutation}
   {:id :uncovered :label "uncovered" :key :uncovered-s :w 78 :group :mutation}])

(def group-labels
  {:crap "--crap--"
   :mutation "--mutation--"})

(def ^:private rel-phrases
  {:inheritance ["extends" "extended by"]
   :implements ["implements" "implemented by"]
   :association ["associates with" "associated from"]
   :dependency ["depends on" "used by"]
   :aggregation ["aggregates" "aggregated by"]
   :composition ["composes" "composed in"]})

(defn- rel-phrase [kind outgoing?]
  (let [[out in] (get rel-phrases kind ["to" "from"])]
    (if outgoing? out in)))

(defn- id-tail [id k]
  (let [segs (str/split (name id) #"\.")]
    (str/join "." (take-last (min k (count segs)) segs))))

(defn distinct-names
  "Rel maps with `:name` unchanged when it is unique among the distinct
  other classes, else the shortest dotted tail of the id that tells the
  colliding ones apart (`chat.routes` vs `files.routes`)."
  [rels]
  (let [ids (distinct (map :id rels))
        base (into {} (map (juxt :id :name) rels))]
    (loop [k 1 labels base]
      (let [clashes (->> ids
                         (group-by labels)
                         vals
                         (filter #(> (count %) 1))
                         (apply concat)
                         set)]
        (if (or (empty? clashes)
                (every? #(<= (count (str/split (name %) #"\.")) k) clashes))
          (mapv #(assoc % :name (labels (:id %))) rels)
          (recur (inc k)
                 (reduce (fn [m id] (assoc m id (id-tail id (inc k)))) labels clashes)))))))

(defn model
  "Class card for the detail window, or nil if `id` is unknown."
  [scene id]
  (when-let [c (hit/class-by-id scene id)]
    {:class c
     :ns (overlay/class-namespace c)
     :package (hit/package-by-id scene (:package c))
     :title (get-in scene [:diagram :title])
     :rels (distinct-names
             (mapv (fn [e]
                     (let [out? (= id (:from e))
                           oid (if out? (:to e) (:from e))
                           other (hit/class-by-id scene oid)]
                       {:id oid
                        :name (or (:name other) (name oid))
                        :kind (:kind e)
                        :label (:label e)
                        :outgoing? out?
                        :phrase (rel-phrase (:kind e) out?)}))
                   (hit/connected-edges scene id)))}))

(defn column-layout
  "Columns from the right edge. Each has :left and :right."
  []
  (loop [cols (reverse columns) x (- width pad) acc ()]
    (if (empty? cols)
      (vec acc)
      (let [c (first cols)
            right x
            left (- x (:w c))]
        (recur (rest cols) (- left col-gap)
               (cons (assoc c :left left :right right) acc))))))

(defn group-layout
  "Spans of `--crap--` / `--mutation--` over their columns."
  []
  (->> (column-layout)
       (partition-by :group)
       (mapv (fn [cs]
               {:id (:group (first cs))
                :label (get group-labels (:group (first cs)))
                :left (:left (first cs))
                :right (:right (last cs))}))))

(defn- crap-mu [crap]
  (cond
    (nil? crap) nil
    (number? crap) (double crap)
    :else (some-> (:mu crap) double)))

(defn- sum-key [xs k]
  (when (some #(some? (get % k)) xs)
    (long (reduce + 0 (map #(or (get % k) 0) xs)))))

(defn- format-num [n]
  (when n
    (format "%.1f" (double n))))

(defn- site-count [killed survived uncovered]
  (+ (or killed 0) (or survived 0) (or uncovered 0)))

(defn- no-sites? [{:keys [killed survived uncovered sites]}]
  (let [counted (site-count killed survived uncovered)]
    (cond
      (pos? counted) false
      (some? sites) (zero? sites)
      :else true)))

(defn- format-cells [{:keys [crap-mu cc coverage killed survived uncovered sites class-row?]}]
  (let [base {:crap-s (when crap-mu
                        (if class-row?
                          (str (format-num crap-mu) "μ")
                          (format-num crap-mu)))
              :crap-n crap-mu
              :cc-s (when (and cc (not class-row?)) (str (long cc)))
              :cov-s (layout/format-coverage coverage)
              :coverage coverage
              :killed killed
              :survived survived
              :uncovered uncovered
              :sites sites}]
    (if (no-sites? base)
      (assoc base :mut-note "---no mutation sites---")
      (assoc base
        :killed-s (when killed (str (long killed)))
        :survived-s (when survived (str (long survived)))
        :uncovered-s (when uncovered (str (long uncovered)))))))

(defn- class-metrics [c]
  (let [ops (:ops c)]
    {:class-row? true
     :crap-mu (crap-mu (:crap c))
     :coverage (:coverage c)
     :killed (or (:killed c) (sum-key ops :killed))
     :survived (or (:survived c) (sum-key ops :survived))
     :uncovered (or (:uncovered c) (sum-key ops :uncovered))
     :sites (or (:sites c) (sum-key ops :sites))}))

(defn- op-metrics [op]
  {:crap-mu (crap-mu (:crap op))
   :cc (:cc op)
   :coverage (:coverage op)
   :killed (:killed op)
   :survived (:survived op)
   :uncovered (:uncovered op)
   :sites (:sites op)})

(defn- emit [acc kind text extra]
  (let [{:keys [rows y]} acc
        h (or (:h extra) layout/line-h)]
    {:rows (conj rows (merge {:kind kind :text text :y y :h h} extra))
     :y (+ y h)}))

(defn- heading [acc label]
  (-> acc
      (update :y + 12)
      (emit :heading label {})))

(defn- op-label [op]
  (str (if (:private op) "- " "+ ") (:text op)))

(defn- emit-table [acc c]
  (let [acc (update acc :y + 12)
        acc (emit acc :group-header "" {})
        acc (emit acc :col-header "" {})
        acc (emit acc :stats (:name c) (format-cells (class-metrics c)))]
    (reduce (fn [acc op]
              (emit acc :stats (op-label op)
                    (assoc (format-cells (op-metrics op))
                      :private (boolean (:private op))
                      :op-name (:name op))))
            acc
            (:ops c))))

(defn- metric? [m]
  (boolean (some some? ((juxt :crap-mu :cc :coverage :killed :survived :uncovered :sites) m))))

(defn- any-metrics?
  "True when the class or any of its ops carries a CRAP or mutation number."
  [c]
  (boolean (some metric? (cons (class-metrics c) (map op-metrics (:ops c))))))

(defn- emit-ops
  "Ops as plain rows under a heading: no metric columns to leave blank."
  [acc c]
  (reduce (fn [acc op]
            (emit acc :op (op-label op)
                  {:private (boolean (:private op)) :op-name (:name op)}))
          (heading acc "Operations")
          (:ops c)))

(defn rows
  "Laid-out lines for `model`. Y is in content space (scroll separately)."
  [model]
  (when model
    (let [c (:class model)
          pack (str "package  "
                    (or (:label (:package model))
                        (some-> (:package c) name)))
          acc {:rows [] :y pad}
          acc (emit acc :name (:name c) {})
          acc (if (some? (:level c))
                (emit acc :muted (str "Level " (:level c)) {})
                acc)
          acc (if-let [st (:stereotype c)]
                (emit acc :muted (str "«" (name st) "»") {})
                acc)
          acc (if-let [ns-name (:ns model)]
                (emit acc :module ns-name {:module true})
                acc)
          acc (emit acc :muted pack {})
          acc (if-let [t (:title model)]
                (emit acc :muted t {})
                acc)
          acc (if-let [s (layout/format-crap (:crap c))]
                (emit acc :crap s {})
                acc)
          acc (cond
                (and (seq (:ops c)) (not (any-metrics? c)))
                (emit-ops acc c)

                (or (seq (:ops c))
                    (crap-mu (:crap c))
                    (:coverage c)
                    (:cc c)
                    (:killed c)
                    (:survived c))
                (emit-table acc c)

                :else acc)
          acc (if (seq (:fields c))
                (reduce (fn [acc f]
                          (emit acc :field (:text f) {}))
                        (heading acc "Fields")
                        (:fields c))
                acc)
          acc (if (seq (:rels model))
                (reduce (fn [acc r]
                          (let [text (str (:phrase r) "  " (:name r)
                                          (when (:label r)
                                            (str "  «" (:label r) "»")))]
                            (emit acc :rel text {:id (:id r)})))
                        (heading acc "Relationships")
                        (:rels model))
                acc)]
      (:rows acc))))

(defn content-h [rows]
  (if (seq rows)
    (+ pad (:y (last rows)) (:h (last rows)))
    (* 2 pad)))

(defn rel-at
  "Class id of the relationship row under content-y, or nil."
  [rows y]
  (some (fn [row]
          (when (and (= :rel (:kind row))
                     (<= (:y row) y (+ (:y row) (:h row) -1)))
            (:id row)))
        rows))

(defn member-at
  "Op name of the member row under content-y, or nil."
  [rows y]
  (some (fn [row]
          (when (and (:op-name row)
                     (<= (:y row) y (+ (:y row) (:h row) -1)))
            (:op-name row)))
        rows))

(defn module-at
  "True when content-y is on the module (namespace) row."
  [rows y]
  (boolean
    (some (fn [row]
            (and (:module row)
                 (<= (:y row) y (+ (:y row) (:h row) -1))))
          rows)))
