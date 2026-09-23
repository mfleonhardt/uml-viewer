(ns uml-viewer.python-language.source-python
  "Python LanguageSource: src/ module path + def/async def/class slice."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [uml-viewer.source :as source])
  (:import [java.util.regex Pattern]))

(defn module->source-path
  [module-name]
  (when module-name
    (let [rel (str/replace (str module-name) "." "/")
          candidates [(str "src/" rel ".py")
                      (str "src/" rel "/__init__.py")]]
      (first (filter #(.exists (io/file %)) candidates)))))

(defn- member-matcher [source member-name]
  (.matcher (Pattern/compile
              (str "(?m)^([ \\t]*)(?:async\\s+def|def|class)\\s+"
                   (Pattern/quote (str member-name))
                   "\\b"))
            source))

(defn- indent-of [line]
  (count (re-find #"^[ \t]*" line)))

(defn extract-member
  "Source text of the def/class named `member-name`: its header line plus
  every following line indented deeper (or blank). Nil when absent."
  [source member-name]
  (when (and source member-name)
    (let [m (member-matcher source member-name)]
      (when (.find m)
        (let [start (.start m)
              base (count (.group m 1))
              lines (str/split-lines (subs source start))
              body (take-while #(or (str/blank? %) (> (indent-of %) base))
                               (rest lines))]
          (str/trimr (str/join "\n" (cons (first lines) body))))))))

(defn member-line
  "1-based line of `member-name` in `source`, or nil."
  [source member-name]
  (when (and source member-name)
    (let [m (member-matcher source member-name)]
      (when (.find m)
        (inc (count (re-seq #"\n" (subs source 0 (.start m)))))))))

(defrecord PythonSource []
  source/LanguageSource
  (locate [_ ident]
    (module->source-path (:ns ident)))
  (extract [_ source ident]
    (extract-member source (:name ident)))
  (start-line [_ source ident]
    (member-line source (:name ident)))
  (title [_ ident]
    (str (:ns ident) "." (:name ident))))

(def impl (->PythonSource))

(source/register! :python impl)
