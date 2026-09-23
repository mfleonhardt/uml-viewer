(ns uml-viewer.angular-language.source-angular
  "Angular LanguageSource: the class :ns is the .ts file path; members are
  methods, functions, or classes found by name."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [uml-viewer.source :as source])
  (:import [java.util.regex Pattern]))

(defn ns->source-path
  "The .ts file an Angular class came from, when it exists."
  [ns-name]
  (when (and ns-name (str/ends-with? (str ns-name) ".ts")
             (.isFile (io/file (str ns-name))))
    (str ns-name)))

(defn- member-matcher [source member-name]
  (.matcher (Pattern/compile
              (str "(?m)^[ \\t]*(?:export\\s+)?(?:default\\s+)?"
                   "(?:(?:public|private|protected|static|readonly|async|override|abstract|get|set)\\s+)*"
                   "(?:function\\s*\\*?\\s*|class\\s+|(?:const|let)\\s+)?"
                   (Pattern/quote (str member-name))
                   "(?:\\s*[(<=:{]|\\s+(?:extends|implements)\\b)"))
            source))

(defn- member-start
  "Index where the dotted qualname's last part is declared, or nil. Each part
  is looked for after the previous one: `ChatService.send` finds `class
  ChatService`, then `send` below it; `X.send.inner` finds the const."
  [source member-name]
  (loop [parts (str/split (str member-name) #"\.") from 0 found nil]
    (if-let [part (first parts)]
      (let [m (member-matcher source part)]
        (when (.find m (int from))
          (recur (rest parts) (.end m) (.start m))))
      found)))

(defn member-line
  "1-based line where `member-name` (a dotted qualname is fine) is declared
  in `source`, or nil."
  [source member-name]
  (when (and source member-name)
    (when-let [start (member-start source member-name)]
      (inc (count (re-seq #"\n" (subs source 0 start)))))))

(defn extract-member
  "The declaration line of `member-name`, or nil. (The window shows the whole
  file; this only has to prove the member exists.)"
  [source member-name]
  (when-let [line (member-line source member-name)]
    (nth (str/split-lines source) (dec line))))

(defrecord AngularSource []
  source/LanguageSource
  (locate [_ ident]
    (ns->source-path (:ns ident)))
  (extract [_ source ident]
    (extract-member source (:name ident)))
  (start-line [_ source ident]
    (member-line source (:name ident)))
  (title [_ ident]
    (str (:ns ident) " " (:name ident))))

(def impl (->AngularSource))

(source/register! :angular impl)
