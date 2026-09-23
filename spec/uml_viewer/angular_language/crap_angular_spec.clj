(ns uml-viewer.angular-language.crap-angular-spec
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [speclj.core :refer :all]))

(def script (.getPath (io/resource "uml_viewer/angular_language/crap_angular.js")))

(def service-ts
  (str "export class X {\n"                                     ; 1
       "  total = computed(() => this.a() ? 1 : 2);\n"          ; 2
       "  send(id: string) {\n"                                 ; 3
       "    if (id && this.ok) {\n"                             ; 4
       "      return this.list.map(v => v > 1 ? v : 0);\n"      ; 5
       "    }\n"                                                ; 6
       "    const inner = () => {\n"                            ; 7
       "      return 1;\n"                                      ; 8
       "    };\n"                                               ; 9
       "    return inner();\n"                                  ; 10
       "  }\n"                                                  ; 11
       "}\n"                                                    ; 12
       "export function helper(a: number) {\n"                  ; 13
       "  return a ?? 0;\n"                                     ; 14
       "}\n"))                                                  ; 15

(defn- stmt [line col] {:start {:line line :column col} :end {:line line :column (+ col 5)}})

(defn- coverage-json [file]
  (let [stmts [[2 30 1] [4 4 1] [5 6 1] [5 30 0] [7 4 1] [8 6 0] [10 4 1] [14 2 0]]]
    (str "{\"" file "\": {\"path\": \"" file "\", \"statementMap\": {"
         (apply str (interpose ", " (map-indexed (fn [i [l c _]]
                                                   (str "\"" i "\": "
                                                        "{\"start\": {\"line\": " l ", \"column\": " c "},"
                                                        " \"end\": {\"line\": " l ", \"column\": " (+ c 5) "}}"))
                                                 stmts)))
         "}, \"s\": {"
         (apply str (interpose ", " (map-indexed (fn [i [_ _ n]] (str "\"" i "\": " n)) stmts)))
         "}}}")))

(defn- project []
  ;; Under target/ so the script finds this repo's node_modules/typescript.
  (let [dir (.getAbsoluteFile (io/file "target" (str "ng-crap-" (System/nanoTime))))
        svc (io/file dir "src/app/x.service.ts")]
    (io/make-parents svc)
    (spit svc service-ts)
    (spit (io/file dir "src/app/lonely.ts") "export const guard = () => true;\n")
    (spit (io/file dir "src/app/x.service.spec.ts") "export const ignored = () => 1;\n")
    (spit (io/file dir "coverage.json") (coverage-json (.getPath svc)))
    dir))

(def typescript? (.isDirectory (io/file "node_modules" "typescript")))

(describe "angular crap snapshot"
  (with-all run
    (when typescript?
      (let [dir (project)
            res (shell/sh "node" script "coverage.json" "src/app" ".metrics/crap.edn" :dir (str dir))
            out (io/file dir ".metrics/crap.edn")]
        (assoc res :snapshot (when (.exists out) (edn/read-string (slurp out)))))))
  (with-all by-key
    (into {} (map (juxt (juxt :namespace :name) identity) (:entries (:snapshot @run)))))
  (before (when-not typescript? (pending "run `npm install` in uml-viewer for the Angular specs")))

  (it "writes .metrics/crap.edn and reports the count"
    (should= 0 (:exit @run))
    (should-contain "5 functions" (:err @run)))

  (it "names methods, signal properties, nested consts, functions, and guards"
    (should= #{["src/app/x.service.ts" "X.total"] ["src/app/x.service.ts" "X.send"]
               ["src/app/x.service.ts" "X.send.inner"] ["src/app/x.service.ts" "helper"]
               ["src/app/lonely.ts" "guard"]}
             (set (keys @by-key))))

  (it "counts classic McCabe, folding anonymous callbacks into their entry"
    (should= 2 (:complexity (@by-key ["src/app/x.service.ts" "X.total"])))
    (should= 4 (:complexity (@by-key ["src/app/x.service.ts" "X.send"])))
    (should= 1 (:complexity (@by-key ["src/app/x.service.ts" "X.send.inner"])))
    (should= 2 (:complexity (@by-key ["src/app/x.service.ts" "helper"]))))

  (it "measures each entry's own statements, leaving named children out"
    (should= 100.0 (:coverage (@by-key ["src/app/x.service.ts" "X.total"])))
    (should= 80.0 (:coverage (@by-key ["src/app/x.service.ts" "X.send"])))
    (should= 0.0 (:coverage (@by-key ["src/app/x.service.ts" "X.send.inner"])))
    (should= 0.0 (:coverage (@by-key ["src/app/x.service.ts" "helper"]))))

  (it "treats a file missing from the report as uncovered"
    (should= 0.0 (:coverage (@by-key ["src/app/lonely.ts" "guard"])))
    (should= 2.0 (:crap (@by-key ["src/app/lonely.ts" "guard"]))))

  (it "computes CRAP and sorts worst first"
    (should= 4.13 (:crap (@by-key ["src/app/x.service.ts" "X.send"])))
    (should= 6.0 (:crap (@by-key ["src/app/x.service.ts" "helper"])))
    (should= "helper" (:name (first (:entries (:snapshot @run)))))))
