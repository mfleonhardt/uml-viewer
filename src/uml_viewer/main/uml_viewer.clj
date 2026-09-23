(ns uml-viewer.main.uml-viewer
  (:require [uml-viewer.adapters.core :as core]
            [uml-viewer.clojure-language.source-clojure :as clj-source]
            [uml-viewer.python-language.source-python :as py-source]
            [uml-viewer.angular-language.source-angular :as ng-source]
            [uml-viewer.source :as source]
            [uml-viewer.domain.log :as log])
  (:gen-class))

(defn -main [& args]
  (log/install-exception-log!)
  (try
    (apply core/start! (source/first-located clj-source/impl py-source/impl ng-source/impl) args)
    (catch Throwable t
      (log/log-exception! t "start!")
      (System/exit 1))))
