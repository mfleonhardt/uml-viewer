(ns uml-viewer.main.ir-generator
  (:require [uml-viewer.clojure-language.graph-clojure]
            [uml-viewer.python-language.graph-python]
            [uml-viewer.cdk-language.graph-cdk]
            [uml-viewer.angular-language.graph-angular]
            [uml-viewer.graph :as graph]
            [uml-viewer.application.ir-generator :as ir-generator])
  (:gen-class))

(defn graph-for
  "Registered LanguageGraph for the policy's `:lang` (default `:clojure`)."
  [policy]
  (let [lang (keyword (name (or (:lang policy) :clojure)))]
    (or (graph/lookup lang)
        (throw (ex-info (str "no LanguageGraph for " lang) {:lang lang})))))

(defn -main [& args]
  (let [policy (or (first args) "examples/uml-viewer.policy.edn")
        out (second args)
        impl (graph-for (ir-generator/read-policy policy))]
    (println "Wrote" (ir-generator/generate impl policy out))
    (shutdown-agents)))
