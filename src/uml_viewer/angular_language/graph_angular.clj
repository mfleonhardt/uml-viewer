(ns uml-viewer.angular-language.graph-angular
  "Angular LanguageGraph: pipes scan_angular.js to node, which parses the
  project's TypeScript with its own `typescript` package, and reads the EDN
  graph. UML_VIEWER_NODE picks the node binary; UML_VIEWER_TYPESCRIPT points
  at a typescript package when the project has none."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [uml-viewer.graph :as graph]))

(def scanner-resource "uml_viewer/angular_language/scan_angular.js")

(defn node-bin []
  (or (System/getenv "UML_VIEWER_NODE") "node"))

(defn scan-command
  "Argv that pipes the scanner on stdin: `node - root`."
  [node root]
  [node "-" (str root)])

(defn run-scanner
  "EDN graph text for the TypeScript under `root`. Warnings go to stderr."
  [root]
  (let [script (slurp (io/resource scanner-resource))
        {:keys [exit out err]} (apply shell/sh
                                      (concat (scan-command (node-bin) root)
                                              [:in script]))]
    (when (seq err)
      (binding [*out* *err*] (print err) (flush)))
    (when-not (zero? exit)
      (throw (ex-info (str "scan_angular.js failed (exit " exit ")")
                      {:root root :exit exit :err err})))
    out))

(defrecord AngularGraph []
  graph/LanguageGraph
  (scan [_ root _opts]
    (edn/read-string (run-scanner root))))

(def impl (->AngularGraph))

(graph/register! :angular impl)
