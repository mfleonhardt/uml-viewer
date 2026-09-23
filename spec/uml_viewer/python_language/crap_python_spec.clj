(ns uml-viewer.python-language.crap-python-spec
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [speclj.core :refer :all]))

(def script (.getPath (io/resource "uml_viewer/python_language/crap_python.py")))

(def mod-py
  (str "import os\n"                             ; 1
       "\n"                                      ; 2
       "def simple():\n"                         ; 3
       "    return 1\n"                          ; 4
       "\n"                                      ; 5
       "def branchy(x, y):\n"                    ; 6
       "    \"\"\"doc\"\"\"\n"                   ; 7
       "    if x and y:\n"                       ; 8
       "        return 1\n"                      ; 9
       "    elif x:\n"                           ; 10
       "        return 2\n"                      ; 11
       "    for i in range(3):\n"                ; 12
       "        pass\n"                          ; 13
       "    return [z for z in range(x) if z]\n" ; 14
       "\n"                                      ; 15
       "class Box:\n"                            ; 16
       "    def method(self):\n"                 ; 17
       "        def helper():\n"                 ; 18
       "            if self:\n"                  ; 19
       "                return 1\n"              ; 20
       "        return helper\n"))               ; 21

(def coverage-json
  "{\"files\": {
     \"src/pkg/mod.py\": {\"executed_lines\": [1, 3, 4, 6, 8, 9, 16, 17, 18, 21],
                         \"missing_lines\": [10, 11, 12, 13, 14, 19, 20]},
     \"src/pkg/__init__.py\": {\"executed_lines\": [1, 2], \"missing_lines\": []}}}")

(defn- project []
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "uml-py-crap-" (System/nanoTime)))]
    (doseq [[rel body] [["src/pkg/__init__.py" "def init_fn():\n    return 1\n"]
                        ["src/pkg/mod.py" mod-py]
                        ["src/other.py" "def lonely():\n    return 0\n"]
                        ["coverage.json" coverage-json]]]
      (let [f (io/file dir rel)]
        (io/make-parents f)
        (spit f body)))
    dir))

(defn- crap [cc pct]
  (+ (* cc cc (Math/pow (- 1 (/ pct 100.0)) 3)) cc))

(describe "python crap snapshot"
  (with-all run
    (let [dir (project)
          out (io/file dir ".metrics/crap.edn")
          res (shell/sh "python3" script "coverage.json" "src" ".metrics/crap.edn"
                        :dir (str dir))]
      (assoc res :snapshot (when (.exists out) (edn/read-string (slurp out))))))
  (with-all by-key
    (into {} (map (juxt (juxt :namespace :name) identity)
                  (:entries (:snapshot @run)))))

  (it "writes .metrics/crap.edn and reports the count"
    (should= 0 (:exit @run))
    (should-contain "6 functions" (:err @run)))

  (it "names entries by module and dotted qualname"
    (should= #{["pkg.mod" "simple"] ["pkg.mod" "branchy"] ["pkg.mod" "Box.method"]
               ["pkg.mod" "Box.method.helper"] ["pkg" "init_fn"] ["other" "lonely"]}
             (set (keys @by-key))))

  (it "counts McCabe complexity, leaving nested defs to their own entry"
    (should= 1 (:complexity (@by-key ["pkg.mod" "simple"])))
    (should= 7 (:complexity (@by-key ["pkg.mod" "branchy"])))
    (should= 1 (:complexity (@by-key ["pkg.mod" "Box.method"])))
    (should= 2 (:complexity (@by-key ["pkg.mod" "Box.method.helper"]))))

  (it "measures coverage over each function's own body"
    (should= 100.0 (:coverage (@by-key ["pkg.mod" "simple"])))
    (should= 28.57 (:coverage (@by-key ["pkg.mod" "branchy"])))
    (should= 100.0 (:coverage (@by-key ["pkg.mod" "Box.method"])))
    (should= 0.0 (:coverage (@by-key ["pkg.mod" "Box.method.helper"])))
    (should= 100.0 (:coverage (@by-key ["pkg" "init_fn"]))))

  (it "treats a file missing from the report as uncovered"
    (should= 0.0 (:coverage (@by-key ["other" "lonely"])))
    (should= 2.0 (:crap (@by-key ["other" "lonely"]))))

  (it "computes CRAP and sorts worst first"
    (let [e (@by-key ["pkg.mod" "branchy"])]
      (should (< (Math/abs (- (crap 7 (* 100.0 (/ 2 7))) (:crap e))) 0.01)))
    (should= 6.0 (:crap (@by-key ["pkg.mod" "Box.method.helper"])))
    (should= "branchy" (:name (first (:entries (:snapshot @run)))))))
