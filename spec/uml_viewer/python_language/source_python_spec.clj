(ns uml-viewer.python-language.source-python-spec
  (:require [speclj.core :refer :all]
            [uml-viewer.source :as source]
            [uml-viewer.clojure-language.source-clojure :as clj-source]
            [uml-viewer.python-language.source-python :as py-source]))

(def sample
  (str "import os\n\n"
       "class Store:\n"
       "    def get(self):\n"
       "        return 1\n\n"
       "    async def put(self, x):\n"
       "        return x\n\n"
       "def run():\n"
       "    return Store()\n"
       "run_later = 2\n"))

(describe "python extractor"
  (it "maps a module to a .py file under src/"
    (should= "src/uml_viewer/python_language/scan_python.py"
             (py-source/module->source-path "uml_viewer.python_language.scan_python"))
    (should-be-nil (py-source/module->source-path "no.such.module")))

  (it "extracts a top-level def up to the next dedent"
    (should= "def run():\n    return Store()" (py-source/extract-member sample "run")))

  (it "extracts a class with its methods"
    (let [s (py-source/extract-member sample "Store")]
      (should (.startsWith s "class Store:"))
      (should (.contains s "async def put"))
      (should-not (.contains s "def run"))))

  (it "extracts an async method"
    (should= "    async def put(self, x):\n        return x"
             (py-source/extract-member sample "put")))

  (it "does not match a longer name"
    (should-be-nil (py-source/extract-member sample "run_later")))

  (it "finds the 1-based line of a member"
    (should= 3 (py-source/member-line sample "Store"))
    (should= 10 (py-source/member-line sample "run"))
    (should-be-nil (py-source/member-line sample "missing")))

  (it "resolves a dotted qualname inside its class, not the first match"
    (let [src (str "class A:\n"
                   "    def go(self):\n"
                   "        return 'a'\n\n"
                   "class B:\n"
                   "    def go(self):\n"
                   "        return 'b'\n\n"
                   "def outer():\n"
                   "    def inner():\n"
                   "        return 1\n"
                   "    return inner\n")]
      (should= 2 (py-source/member-line src "A.go"))
      (should= 6 (py-source/member-line src "B.go"))
      (should= "    def go(self):\n        return 'b'" (py-source/extract-member src "B.go"))
      (should= 10 (py-source/member-line src "outer.inner"))
      (should-be-nil (py-source/member-line src "C.go"))
      (should-be-nil (py-source/member-line src "B.stop"))
      (should-be-nil (py-source/member-line src "A.inner")))))

(describe "first-located source"
  (it "sends each ident to the language that can find its file"
    (let [both (source/first-located clj-source/impl py-source/impl)]
      (should= "src/uml_viewer/source.clj" (source/locate both {:ns "uml-viewer.source"}))
      (should= "src/uml_viewer/python_language/scan_python.py"
               (source/locate both {:ns "uml_viewer.python_language.scan_python"}))
      (should= "uml_viewer.python_language.scan_python.main"
               (source/title both {:ns "uml_viewer.python_language.scan_python"
                                   :name "main"}))
      (should-be-nil (source/locate both {:ns "no.such.module"}))))

  (it "opens a python member through member-source"
    (let [both (source/first-located clj-source/impl py-source/impl)
          m (source/member-source both {:ns "uml_viewer.python_language.scan_python"
                                        :name "scan"})]
      (should= "src/uml_viewer/python_language/scan_python.py" (:file m))
      (should (pos? (:line m)))
      (should (.endsWith (:title m) (str ":" (:line m)))))))
