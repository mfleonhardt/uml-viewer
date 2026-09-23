(ns uml-viewer.application.detail-spec
  (:require [speclj.core :refer :all]
            [uml-viewer.application.detail :as detail]
            [uml-viewer.engine.compose :as compose]
            [uml-viewer.domain.ir :as ir]))

(defn- call [sym & args]
  (apply (ns-resolve 'uml-viewer.application.detail sym) args))

(defn scene []
  (compose/compile-diagram
    (ir/normalize
      {:title "Tiny"
       :packages
       [{:id :p :label "Domain"
         :classes [{:id :a :name "A"
                    :ns "demo.a"
                    :coverage 0.9
                    :crap 1.2
                    :ops [{:name "go" :args ["x"] :returns "void"
                           :coverage 0.75
                           :cc 2
                           :crap 1.8
                           :killed 3
                           :survived 1
                           :uncovered 2
                           :sites 6}
                          {:name "hide" :private true :crap 4.0}]}
                   {:id :b :name "B"}]}]
       :edges [{:from :a :to :b :kind :dependency}]})))

(describe "rel-phrase"
  (it "names each edge kind in both directions"
    (doseq [[kind out in]
            [[:inheritance "extends" "extended by"]
             [:implements "implements" "implemented by"]
             [:association "associates with" "associated from"]
             [:dependency "depends on" "used by"]
             [:aggregation "aggregates" "aggregated by"]
             [:composition "composes" "composed in"]]]
      (should= out (call 'rel-phrase kind true))
      (should= in (call 'rel-phrase kind false))))

  (it "falls back to to/from for an unknown kind"
    (should= "to" (call 'rel-phrase :other true))
    (should= "from" (call 'rel-phrase :other false))))

(describe "detail"
  (it "builds a class card with coverage, ops, and relationships"
    (let [s (scene)
          model (detail/model s :a)
          rows (detail/rows model)
          kinds (map :kind rows)
          go (first (filter #(= "+ go(x) : void" (:text %)) rows))
          hide (first (filter #(= "- hide" (:text %)) rows))
          cls (first (filter #(and (= :stats (:kind %)) (= "A" (:text %))) rows))
          rel (first (filter #(= :rel (:kind %)) rows))]
      (should= "A" (get-in model [:class :name]))
      (should= "demo.a" (:ns model))
      (should= 0.9 (get-in model [:class :coverage]))
      (should (some #{:name :module :stats :group-header :col-header :rel} kinds))
      (should= "demo.a" (:text (first (filter :module rows))))
      (let [mod (first (filter :module rows))]
        (should (detail/module-at rows (+ (:y mod) 1)))
        (should-not (detail/module-at rows (+ (:y go) 1))))
      (should= "90%" (:cov-s cls))
      (should= "1.2μ" (:crap-s cls))
      (should-be-nil (:cc-s cls))
      (should= "75%" (:cov-s go))
      (should= "2" (:cc-s go))
      (should= "1.8" (:crap-s go))
      (should hide)
      (should (:private hide))
      (should= "4.0" (:crap-s hide))
      (should= "3" (:killed-s go))
      (should= "1" (:survived-s go))
      (should= "2" (:uncovered-s go))
      (should= "3" (:killed-s cls))
      (should= "1" (:survived-s cls))
      (should= "2" (:uncovered-s cls))
      (should= "---no mutation sites---" (:mut-note hide))
      (should= :b (:id rel))
      (should= :b (detail/rel-at rows (+ (:y rel) 1)))
      (should= "go" (:op-name go))
      (should= "go" (detail/member-at rows (+ (:y go) 1)))
      (should-be-nil (detail/member-at rows (:y cls)))
      (should= "hide" (detail/member-at rows (+ (:y hide) 1)))))

  (it "shows killed and survived when an older snapshot omitted sites"
    (let [s (compose/compile-diagram
              (ir/normalize
                {:packages
                 [{:id :p :label "P"
                   :classes [{:id :w :name "WindData"
                              :killed 5 :survived 1 :uncovered 0 :sites 0
                              :ops [{:name "radius-bounds" :text "radius-bounds"
                                     :killed 5 :survived 0 :uncovered 0 :sites 0}]}]}]
                 :edges []}))
          rows (detail/rows (detail/model s :w))
          cls (first (filter #(and (= :stats (:kind %)) (= "WindData" (:text %))) rows))
          op (first (filter #(= "radius-bounds" (:op-name %)) rows))]
      (should-be-nil (:mut-note cls))
      (should-be-nil (:mut-note op))
      (should= "5" (:killed-s cls))
      (should= "1" (:survived-s cls))
      (should= "5" (:killed-s op))
      (should= "0" (:survived-s op))))

  (it "does not treat untested operators as no mutation sites"
    (let [s (compose/compile-diagram
              (ir/normalize
                {:packages
                 [{:id :p :label "P"
                   :classes [{:id :d :name "D"
                              :ops [{:name "go" :text "go()"
                                     :killed 0 :survived 0 :uncovered 0 :sites 4}]}]}]
                 :edges []}))
          rows (detail/rows (detail/model s :d))
          go (first (filter #(= "go" (:op-name %)) rows))]
      (should-be-nil (:mut-note go))
      (should= "0" (:killed-s go))))

  (it "shows class CRAP as μ, omits CC, and prefixes μ/max/σ with Crap"
    (let [s (compose/compile-diagram
              (ir/normalize
                {:packages
                 [{:id :p :label "P"
                   :classes [{:id :d :name "Detail"
                              :crap {:mu 14.2 :max 134.6 :sigma 34.9}
                              :cc 65}]}]
                 :edges []}))
          rows (detail/rows (detail/model s :d))
          cls (first (filter #(and (= :stats (:kind %)) (= "Detail" (:text %))) rows))
          header (first (filter #(= :crap (:kind %)) rows))]
      (should= "14.2μ" (:crap-s cls))
      (should-be-nil (:cc-s cls))
      (should (re-find #"^Crap μ" (:text header)))
      (should (re-find #"max 134\.6" (:text header)))))

  (it "returns nil for an unknown class"
    (should-be-nil (detail/model (scene) :nope)))

  (it "lays out Crap, CC, Cov, killed, survived, and uncovered columns"
    (let [cols (detail/column-layout)]
      (should= ["Crap" "CC" "Cov" "killed" "survived" "uncovered"] (map :label cols))
      (should (apply < (map :left cols)))))

  (it "spans --crap-- and --mutation-- over their columns"
    (let [cols (detail/column-layout)
          groups (detail/group-layout)
          crap-cols (filter #(= :crap (:group %)) cols)
          mut-cols (filter #(= :mutation (:group %)) cols)]
      (should= ["--crap--" "--mutation--"] (map :label groups))
      (should= (:left (first crap-cols)) (:left (first groups)))
      (should= (:right (last crap-cols)) (:right (first groups)))
      (should= (:left (first mut-cols)) (:left (second groups)))
      (should= (:right (last mut-cols)) (:right (second groups)))
      (should (< (:right (first groups)) (:left (second groups))))))

  (it "does not treat a field row as a relationship hit"
    (let [rows (detail/rows (detail/model (scene) :a))
          name-row (first (filter #(= :name (:kind %)) rows))]
      (should-be-nil (detail/rel-at rows (:y name-row)))))

  (it "lists ops without metric columns when nothing on the card has a metric"
    (let [s (compose/compile-diagram
              (ir/normalize
                {:packages
                 [{:id :p :label "P"
                   :classes [{:id :r :name "Role"
                              :fields [{:text "assumed by: lambda"}]
                              :ops [{:name "read" :text "Table  dynamodb: GetItem"}
                                    {:name "hid" :text "hid" :private true}]}]}]}))
          rows (detail/rows (detail/model s :r))
          kinds (set (map :kind rows))
          read-op (first (filter #(= "read" (:op-name %)) rows))
          hid (first (filter #(= "hid" (:op-name %)) rows))]
      (should-not (some #{:group-header :col-header :stats} kinds))
      (should (some #(and (= :heading (:kind %)) (= "Operations" (:text %))) rows))
      (should= :op (:kind read-op))
      (should= "+ Table  dynamodb: GetItem" (:text read-op))
      (should-be-nil (:mut-note read-op))
      (should= "- hid" (:text hid))
      (should (:private hid))
      (should= "read" (detail/member-at rows (+ (:y read-op) 1)))
      (should (some #(= "assumed by: lambda" (:text %)) rows))))

  (it "keeps the metric table when any op has a metric"
    (let [rows (detail/rows (detail/model (scene) :a))]
      (should (some #(= :col-header (:kind %)) rows))
      (should-not (some #(= :op (:kind %)) rows))))

  (it "keeps unique relationship names and lengthens only the ones that collide"
    (let [rels [{:id :app.chat.routes :name "routes"}
                {:id :app.files.routes :name "routes"}
                {:id :app.files.routes :name "routes"}
                {:id :app.csrf :name "Csrf"}
                {:id :x.admin.v1.routes :name "routes"}
                {:id :y.admin.v1.routes :name "routes"}]
          names (mapv :name (detail/distinct-names rels))]
      (should= ["chat.routes" "files.routes" "files.routes" "Csrf"
                "x.admin.v1.routes" "y.admin.v1.routes"]
               names)))

  (it "leaves a card with no collisions alone"
    (let [model (detail/model (scene) :a)]
      (should= ["B"] (mapv :name (:rels model))))))
