(ns uml-viewer.python-language.graph-python-spec
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [speclj.core :refer :all]
            [uml-viewer.graph :as graph]
            [uml-viewer.python-language.graph-python :as py-graph]))

(defn- spit-py [dir rel content]
  (let [f (io/file dir rel)]
    (io/make-parents f)
    (spit f content)
    f))

(defn- demo-tree []
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "uml-py-graph-" (System/nanoTime)))]
    (spit-py dir "pkg/__init__.py" "")
    (spit-py dir "pkg/ports.py"
             "from typing import Protocol\n\nclass Store(Protocol):\n    def get(self): ...\n")
    (spit-py dir "pkg/base.py"
             (str "from abc import ABC, abstractmethod\n\n"
                  "class Base(ABC):\n    @abstractmethod\n    def run(self): ...\n"))
    (spit-py dir "pkg/plain.py" "class Plain:\n    pass\n")
    (spit-py dir "pkg/sub/__init__.py" "thing = 1\n")
    (spit-py dir "pkg/impl.py"
             (str "import os\n"
                  "import boto3.session\n"
                  "from typing import TYPE_CHECKING\n"
                  "from .ports import Store\n"
                  "from pkg import base\n"
                  "from pkg.plain import Plain\n"
                  "if TYPE_CHECKING:\n"
                  "    from pkg.sub import thing\n\n"
                  "class Impl(Store, base.Base, Plain):\n    pass\n\n"
                  "def lazy():\n    from ..other import x\n    return x\n"))
    (spit-py dir "other.py" "x = 1\n")
    (spit-py dir "tool/status.py" "def mk(): ...\n")
    (spit-py dir "tool/handler.py" "from status import mk\n")
    (spit-py dir "broken.py" "def (:\n")
    dir))

(describe "python graph"
  (with-all scanned
    (let [err (java.io.StringWriter.)
          g (binding [*err* err]
              (graph/scan (graph/lookup :python) (demo-tree) {:prefix ""}))]
      {:graph g :err (str err)}))

  (it "registers under :python"
    (should-be-same py-graph/impl (graph/lookup :python)))

  (it "pipes the scanner on stdin with root and prefix"
    (should= ["python3" "-" "/r" "pkg"] (py-graph/scan-command "python3" "/r" "pkg"))
    (should= ["python3" "-" "/r" ""] (py-graph/scan-command "python3" "/r" nil)))

  (it "makes one class per module, named by its last segment"
    (let [by-id (into {} (map (juxt :id identity) (:classes (:graph @scanned))))]
      (should= #{:pkg :pkg.ports :pkg.base :pkg.plain :pkg.sub :pkg.impl
                 :other :tool.status :tool.handler :boto3.session}
               (set (keys by-id)))
      (should= "impl" (get-in by-id [:pkg.impl :name]))
      (should= "pkg.impl" (get-in by-id [:pkg.impl :ns]))
      (should= :python (get-in by-id [:pkg.impl :lang]))
      (should= :interface (get-in by-id [:pkg.ports :stereotype]))
      (should= :abstract (get-in by-id [:pkg.base :stereotype]))
      (should-be-nil (get-in by-id [:pkg.plain :stereotype]))
      (should (get-in by-id [:boto3.session :foreign]))))

  (it "turns absolute, relative, TYPE_CHECKING, and lazy imports into dependencies"
    (let [edges (set (map (juxt :from :to :kind) (:edges (:graph @scanned))))]
      (should (contains? edges [:pkg.impl :pkg.ports :dependency]))
      (should (contains? edges [:pkg.impl :pkg.base :dependency]))
      (should (contains? edges [:pkg.impl :pkg.plain :dependency]))
      (should (contains? edges [:pkg.impl :pkg.sub :dependency]))
      (should (contains? edges [:pkg.impl :other :dependency]))
      (should (contains? edges [:pkg.impl :boto3.session :dependency]))))

  (it "drops stdlib imports"
    (should-not (some #(#{:os :typing} (:to %)) (:edges (:graph @scanned)))))

  (it "marks subclassing: implements for interface/abstract bases, inheritance otherwise"
    (let [edges (set (map (juxt :from :to :kind) (:edges (:graph @scanned))))]
      (should (contains? edges [:pkg.impl :pkg.ports :implements]))
      (should (contains? edges [:pkg.impl :pkg.base :implements]))
      (should (contains? edges [:pkg.impl :pkg.plain :inheritance]))))

  (it "resolves Lambda-style sibling imports"
    (let [edges (set (map (juxt :from :to :kind) (:edges (:graph @scanned))))]
      (should (contains? edges [:tool.handler :tool.status :dependency]))))

  (it "skips unparseable files and says so on stderr"
    (should (str/includes? (:err @scanned) "skipped"))
    (should-not (some #(= :broken (:id %)) (:classes (:graph @scanned)))))

  (it "strips the prefix from ids"
    (let [g (binding [*err* (java.io.StringWriter.)]
              (graph/scan (graph/lookup :python) (demo-tree) {:prefix "pkg"}))
          ids (set (map :id (:classes g)))]
      (should (contains? ids :impl))
      (should (contains? ids :other))
      (should (contains? (set (map (juxt :from :to) (:edges g))) [:impl :ports])))))
