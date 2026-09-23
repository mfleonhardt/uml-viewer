(ns uml-viewer.source)

(defprotocol LanguageSource
  (locate [this ident]
    "Path to the file that should contain `ident`, or nil.")
  (extract [this source ident]
    "Source span for `ident` from `source` text, or nil.")
  (start-line [this source ident]
    "1-based line of `ident` in `source`, or nil.")
  (title [this ident]
    "Window title for this member."))

(defonce ^:private languages (atom {}))

(defn register!
  "Install `impl` as the extractor for `lang` (e.g. `:clojure`)."
  [lang impl]
  (swap! languages assoc lang impl)
  lang)

(defn lookup
  [lang]
  (get @languages lang))

(defn- owner [impls ident]
  (first (filter #(locate % ident) impls)))

(defrecord FirstLocated [impls]
  LanguageSource
  (locate [_ ident]
    (some #(locate % ident) impls))
  (extract [_ source ident]
    (when-let [impl (owner impls ident)]
      (extract impl source ident)))
  (start-line [_ source ident]
    (when-let [impl (owner impls ident)]
      (start-line impl source ident)))
  (title [_ ident]
    (title (or (owner impls ident) (first impls)) ident)))

(defn first-located
  "One `LanguageSource` over `impls`: each call goes to the first impl
  that can locate the ident's file. Lets one viewer open any language."
  [& impls]
  (->FirstLocated (vec impls)))

(defn- from-impl [impl ident lang]
  (when impl
    (when-let [path (locate impl ident)]
      (let [src (slurp path)
            named? (seq (str (:name ident)))]
        (when (or (not named?) (extract impl src ident))
          {:title (if named?
                    (str path ":" (or (start-line impl src ident) 1))
                    path)
           :file path
           :body src
           :line (when named? (start-line impl src ident))
           :lang lang})))))

(defn member-source
  "Locate a member. `ident` is a map with at least `:name`.
  One-arg form looks up the extractor by `:lang` (default `:clojure`).
  Two-arg form takes a `LanguageSource` impl, or a lang keyword.
  Returns `{:title :file :body :line :lang}` — `body` is the whole file,
  `line` is where the member starts — or nil."
  ([ident]
   (member-source (or (:lang ident) :clojure) ident))
  ([lang-or-impl ident]
   (if (keyword? lang-or-impl)
     (from-impl (lookup lang-or-impl) ident lang-or-impl)
     (from-impl lang-or-impl ident (or (:lang ident) :clojure)))))
