(ns uml-viewer.cdk-language.graph-cdk
  "CDK LanguageGraph: runs scan_cdk.py over `cdk synth` output (CloudFormation
  templates) and reads its EDN deployment graph. The policy's `:src` is the
  folder holding the synthesized templates. UML_VIEWER_PYTHON picks the
  interpreter."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [uml-viewer.graph :as graph]))

(def scanner-resource "uml_viewer/cdk_language/scan_cdk.py")

(defn python-bin []
  (or (System/getenv "UML_VIEWER_PYTHON") "python3"))

(defn scan-command
  "Argv that pipes the scanner on stdin: `python3 - root`."
  [python root]
  [python "-" (str root)])

(defn run-scanner
  "EDN graph text for the templates under `root`."
  [root]
  (let [script (slurp (io/resource scanner-resource))
        {:keys [exit out err]} (apply shell/sh
                                      (concat (scan-command (python-bin) root)
                                              [:in script]))]
    (when (seq err)
      (binding [*out* *err*] (print err) (flush)))
    (when-not (zero? exit)
      (throw (ex-info (str "scan_cdk.py failed (exit " exit ")")
                      {:root root :exit exit :err err})))
    out))

(defrecord CdkGraph []
  graph/LanguageGraph
  (scan [_ root _opts]
    (edn/read-string (run-scanner root))))

(def impl (->CdkGraph))

(graph/register! :cdk impl)
