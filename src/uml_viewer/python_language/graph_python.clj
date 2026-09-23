(ns uml-viewer.python-language.graph-python
  "Python LanguageGraph: runs scan_python.py (stdlib `ast`, nothing imported)
  and reads its EDN graph. Set UML_VIEWER_PYTHON to pick the interpreter."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [uml-viewer.graph :as graph]))

(def scanner-resource "uml_viewer/python_language/scan_python.py")

(defn python-bin []
  (or (System/getenv "UML_VIEWER_PYTHON") "python3"))

(defn scan-command
  "Argv that pipes the scanner on stdin: `python3 - root prefix`."
  [python root prefix]
  [python "-" (str root) (str (or prefix ""))])

(defn run-scanner
  "EDN graph text for `root`. Scanner warnings go to stderr."
  [root prefix]
  (let [script (slurp (io/resource scanner-resource))
        {:keys [exit out err]} (apply shell/sh
                                      (concat (scan-command (python-bin) root prefix)
                                              [:in script]))]
    (when (seq err)
      (binding [*out* *err*] (print err) (flush)))
    (when-not (zero? exit)
      (throw (ex-info (str "scan_python.py failed (exit " exit ")")
                      {:root root :exit exit :err err})))
    out))

(defrecord PythonGraph []
  graph/LanguageGraph
  (scan [_ root opts]
    (edn/read-string (run-scanner root (:prefix opts)))))

(def impl (->PythonGraph))

(graph/register! :python impl)
