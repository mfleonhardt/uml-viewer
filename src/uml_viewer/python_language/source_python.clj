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

(defn- block-end
  "Index just past the block whose header starts at `start` with `indent`:
  the first later non-blank line indented no deeper, or the end."
  [source start indent]
  (let [nl (str/index-of source "\n" start)]
    (loop [pos (if nl (inc nl) (count source))]
      (if (>= pos (count source))
        (count source)
        (let [eol (or (str/index-of source "\n" pos) (count source))
              line (subs source pos eol)]
          (if (and (not (str/blank? line)) (<= (indent-of line) indent))
            pos
            (recur (inc eol))))))))

(defn- member-start
  "{:start :indent} of the def/class a dotted qualname names, or nil.
  `Class.method` finds `class Class`, then `def method` inside its block."
  [source member-name]
  (loop [parts (str/split (str member-name) #"\.") from 0 to (count source) found nil]
    (if-let [part (first parts)]
      (let [m (doto (member-matcher source part) (.region (int from) (int to)))]
        (when (and (.find m) (or (nil? found) (> (count (.group m 1)) (:indent found))))
          (let [start (.start m) indent (count (.group m 1))]
            (recur (rest parts) (.end m) (block-end source start indent)
                   {:start start :indent indent}))))
      found)))

(defn extract-member
  "Source text of the def/class named `member-name` (a dotted qualname is
  fine): its header line plus every following line indented deeper (or
  blank). Nil when absent."
  [source member-name]
  (when (and source member-name)
    (when-let [{:keys [start indent]} (member-start source member-name)]
      (let [lines (str/split-lines (subs source start))
            body (take-while #(or (str/blank? %) (> (indent-of %) indent))
                             (rest lines))]
        (str/trimr (str/join "\n" (cons (first lines) body)))))))

(defn member-line
  "1-based line of `member-name` (a dotted qualname is fine) in `source`, or nil."
  [source member-name]
  (when (and source member-name)
    (when-let [{:keys [start]} (member-start source member-name)]
      (inc (count (re-seq #"\n" (subs source 0 start)))))))

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
