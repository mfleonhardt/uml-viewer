(ns uml-viewer.angular-language.graph-angular-spec
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [speclj.core :refer :all]
            [uml-viewer.graph :as graph]
            [uml-viewer.angular-language.graph-angular :as ng-graph]
            [uml-viewer.angular-language.source-angular :as ng-source]))

(def files
  {"app.routes.ts"
   (str "import { Routes } from '@angular/router';\n"
        "import { authGuard } from './auth/auth.guard';\n"
        "export const routes: Routes = [\n"
        "  { path: '', loadComponent: () => import('./chat/chat.page').then(m => m.ChatPage),\n"
        "    canActivate: [authGuard] },\n"
        "];\n")
   "auth/auth.guard.ts"
   (str "import { CanActivateFn } from '@angular/router';\n"
        "export const authGuard: CanActivateFn = () => true;\n")
   "chat/chat.page.ts"
   (str "import { Component, input, output } from '@angular/core';\n"
        "import { ChatService } from '../services/chat.service';\n"
        "import { Base } from '../shared/base';\n"
        "@Component({ selector: 'app-chat', template: '' })\n"
        "export class ChatPage extends Base {\n"
        "  sessionId = input<string>();\n"
        "  sent = output<string>();\n"
        "}\n")
   "chat/chat.component.ts"
   (str "import { Component } from '@angular/core';\n"
        "@Component({ selector: 'app-chat-view', template: '' })\n"
        "export class ChatComponent {}\n")
   "services/chat.service.ts"
   (str "import { Injectable, inject, computed } from '@angular/core';\n"
        "import { HttpClient } from '@angular/common/http';\n"
        "import { Message } from '../models/message.model';\n"
        "@Injectable({ providedIn: 'root' })\n"
        "export class ChatService {\n"
        "  private http = inject(HttpClient);\n"
        "  private baseUrl = computed(() => `${this.config.apiUrl()}/chat`);\n"
        "  list() { return this.http.get<Message[]>(this.baseUrl()); }\n"
        "  send(id: string) {\n"
        "    const url = `${this.baseUrl()}/${id}/send`;\n"
        "    return this.http.post(url, {});\n"
        "  }\n"
        "}\n")
   "models/message.model.ts" "export interface Message { id: string }\n"
   "shared/base.ts" "export class Base {}\n"
   "shared/base.spec.ts" "import { Base } from './base';\n"})

(defn- project-root []
  ;; Under the repo's (gitignored) target/ so the scanner finds this repo's
  ;; node_modules/typescript by walking up, as it would in an Angular project.
  (let [dir (io/file "target" (str "ng-graph-" (System/nanoTime)) "src" "app")]
    (doseq [[rel body] files]
      (let [f (io/file dir rel)]
        (io/make-parents f)
        (spit f body)))
    (.getAbsolutePath dir)))

(def typescript? (.isDirectory (io/file "node_modules" "typescript")))

(describe "angular graph"
  (with-all root (project-root))
  (with-all scanned (when typescript? (graph/scan (graph/lookup :angular) @root {})))
  (with-all by-id (into {} (map (juxt :id identity) (:classes @scanned))))
  (with-all edges (set (map (juxt :from :to :kind) (:edges @scanned))))
  (before (when-not typescript? (pending "run `npm install` in uml-viewer for the Angular specs")))

  (it "registers under :angular"
    (should-be-same ng-graph/impl (graph/lookup :angular)))

  (it "pipes the scanner on stdin with the root"
    (should= ["node" "-" "/r"] (ng-graph/scan-command "node" "/r")))

  (it "makes one class per file, named without its Angular suffix unless two would clash"
    (should= #{:app :auth.auth :chat.chat_page :chat.chat_component :services.chat
               :models.message :shared.base
               :angular.router :angular.core :angular.common.http}
             (set (keys @by-id)))
    (should= "chat" (get-in @by-id [:services.chat :name]))
    (should= "chat.page" (get-in @by-id [:chat.chat_page :name]))
    (should (str/ends-with? (get-in @by-id [:services.chat :ns]) "src/app/services/chat.service.ts"))
    (should= :angular (get-in @by-id [:services.chat :lang]))
    (should (get-in @by-id [:angular.core :foreign])))

  (it "reads stereotypes from decorators, function types, and type-only files"
    (should= :page (get-in @by-id [:chat.chat_page :stereotype]))
    (should= :component (get-in @by-id [:chat.chat_component :stereotype]))
    (should= :service (get-in @by-id [:services.chat :stereotype]))
    (should= :guard (get-in @by-id [:auth.auth :stereotype]))
    (should= :routes (get-in @by-id [:app :stereotype]))
    (should= :interface (get-in @by-id [:models.message :stereotype]))
    (should-be-nil (get-in @by-id [:shared.base :stereotype])))

  (it "links static and lazy imports, and extends as inheritance"
    (should (contains? @edges [:app :auth.auth :dependency]))
    (should (contains? @edges [:app :chat.chat_page :dependency]))
    (should (contains? @edges [:chat.chat_page :services.chat :dependency]))
    (should (contains? @edges [:chat.chat_page :shared.base :inheritance]))
    (should (contains? @edges [:services.chat :models.message :dependency]))
    (should (contains? @edges [:services.chat :angular.common.http :dependency])))

  (it "skips spec files"
    (should-not (some #(str/includes? (str (:ns %)) ".spec.ts") (:classes @scanned))))

  (it "lists a component's selector, inputs, and outputs"
    (should= ["selector: app-chat" "inputs: sessionId" "outputs: sent"]
             (mapv :text (get-in @by-id [:chat.chat_page :fields]))))

  (it "lists HTTP calls with this-members and local consts resolved"
    (should= ["GET {apiUrl}/chat" "POST {apiUrl}/chat/{id}/send"]
             (mapv :text (get-in @by-id [:services.chat :fields])))))

(describe "angular source"
  (it "opens the class's own .ts file"
    (let [f (io/file "target" (str "ng-src-" (System/nanoTime) ".ts"))]
      (io/make-parents f)
      (spit f "export class A {}\n")
      (should= (str f) (ng-source/ns->source-path (str f)))
      (should-be-nil (ng-source/ns->source-path "no/such/file.ts"))
      (should-be-nil (ng-source/ns->source-path "not.a.ts.file"))))

  (it "finds methods, functions, and classes by name"
    (let [src (str "import x from 'y';\n"
                   "export class ChatService {\n"
                   "  private readonly http = inject(HttpClient);\n"
                   "  async send(id: string) {}\n"
                   "  list<T>() {}\n"
                   "}\n"
                   "export function helper() {}\n"
                   "export const authGuard: CanActivateFn = () => true;\n")]
      (should= 2 (ng-source/member-line src "ChatService"))
      (should= 3 (ng-source/member-line src "http"))
      (should= 4 (ng-source/member-line src "send"))
      (should= 5 (ng-source/member-line src "list"))
      (should= 7 (ng-source/member-line src "helper"))
      (should= 8 (ng-source/member-line src "authGuard"))
      (should= "  async send(id: string) {}" (ng-source/extract-member src "send"))
      (should-be-nil (ng-source/member-line src "missing"))))

  (it "resolves the dotted qualnames CRAP rows carry"
    (let [src (str "export class A {\n"
                   "  send() {}\n"
                   "}\n"
                   "export class B {\n"
                   "  constructor() {}\n"
                   "  get total() { return 1; }\n"
                   "  send() {\n"
                   "    const inner = () => 1;\n"
                   "  }\n"
                   "}\n")]
      (should= 2 (ng-source/member-line src "A.send"))
      (should= 7 (ng-source/member-line src "B.send"))
      (should= 5 (ng-source/member-line src "B.constructor"))
      (should= 6 (ng-source/member-line src "B.total"))
      (should= 8 (ng-source/member-line src "B.send.inner"))
      (should= "  send() {" (ng-source/extract-member src "B.send"))
      (should-be-nil (ng-source/member-line src "C.send")))))
