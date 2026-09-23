// Angular LanguageGraph scanner for uml-viewer.
//
// Parses every .ts file under ROOT (not *.spec.ts / *.d.ts) with the
// TypeScript compiler API (nothing is compiled or run) and prints the graph as
// EDN on stdout:
//
//   {:classes [{:id :session.components.chat-input.chat-input :name "chat-input"
//               :ns "src/app/session/components/chat-input/chat-input.component.ts"
//               :stereotype :component :lang :angular :fields [...]} ...]
//    :edges [{:from :a :to :b :kind :dependency} ...]}
//
// A file is a class. Its id is the folder path plus the file name without its
// Angular suffix (.component, .page, .service, ...), which the stereotype
// already says; the suffix stays only when two files in a folder would clash.
// :ns is the file path as given (ROOT-relative to the working directory), so
// the source window can open it.
//
// Edges: static imports and re-exports of project files, and dynamic
// import('...') such as lazy routes, are :dependency. Extending a class from a
// project file is :inheritance; implementing an interface from one is
// :implements. Package imports become foreign classes, `@scope/name/sub` as
// `scope.name.sub`, so a policy :foreign prefix like `angular` collapses them.
//
// Stereotypes: @Component (page for *.page.ts), @Injectable service, @Pipe,
// @Directive; a const typed CanActivateFn/CanMatchFn/CanDeactivateFn guard,
// HttpInterceptorFn interceptor, ResolveFn resolver, Routes routes,
// ApplicationConfig config; a file of only interfaces/types/enums interface.
//
// Fields: a component's selector, inputs and outputs (signal input()/model()/
// output() or @Input/@Output); any file's HTTP calls as `VERB url`, with
// ${...} parts written {name} and a variable URL resolved from its const in
// the same function.
//
// TypeScript is loaded from UML_VIEWER_TYPESCRIPT, else the nearest
// node_modules/typescript above ROOT (an Angular project always has it).
//
// Usage: node scan_angular.js ROOT        (or piped: node - ROOT)

"use strict";
const fs = require("fs");
const path = require("path");

const SKIP_DIRS = new Set(["node_modules", "dist", ".angular", "coverage", ".git"]);
const SUFFIXES = ["component", "page", "service", "guard", "interceptor", "pipe",
  "directive", "resolver", "routes", "config", "model", "models", "store",
  "types", "utils", "util", "constants", "module"];
const HTTP_VERBS = new Set(["get", "post", "put", "patch", "delete", "head", "request"]);
const FN_TYPES = {
  CanActivateFn: "guard", CanActivateChildFn: "guard", CanMatchFn: "guard",
  CanDeactivateFn: "guard", HttpInterceptorFn: "interceptor", ResolveFn: "resolver",
  Routes: "routes", ApplicationConfig: "config",
};
const DECORATORS = { Component: "component", Injectable: "service", Pipe: "pipe", Directive: "directive" };
const MAX_HTTP = 8;

function loadTypescript(root) {
  if (process.env.UML_VIEWER_TYPESCRIPT) return require(process.env.UML_VIEWER_TYPESCRIPT);
  let dir = path.resolve(root);
  for (;;) {
    const cand = path.join(dir, "node_modules", "typescript");
    if (fs.existsSync(cand)) return require(cand);
    const up = path.dirname(dir);
    if (up === dir) break;
    dir = up;
  }
  throw new Error("scan_angular: typescript not found above " + root +
    " (run npm ci there, or set UML_VIEWER_TYPESCRIPT)");
}

function sourceFiles(root) {
  const out = [];
  (function walk(dir) {
    for (const ent of fs.readdirSync(dir, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
      if (ent.isDirectory()) {
        if (!SKIP_DIRS.has(ent.name)) walk(path.join(dir, ent.name));
      } else if (ent.name.endsWith(".ts") && !ent.name.endsWith(".spec.ts") && !ent.name.endsWith(".d.ts")) {
        out.push(path.join(dir, ent.name));
      }
    }
  })(root);
  return out;
}

function segment(s) {
  return s.replace(/[^A-Za-z0-9_-]/g, "_") || "_";
}

function splitStem(file) {
  const stem = path.basename(file, ".ts");
  const dot = stem.lastIndexOf(".");
  if (dot > 0 && SUFFIXES.includes(stem.slice(dot + 1))) {
    return { base: stem.slice(0, dot), suffix: stem.slice(dot + 1), stem };
  }
  return { base: stem, suffix: null, stem };
}

function assignIds(files, root) {
  const byDir = new Map();
  for (const f of files) {
    const d = path.dirname(f);
    if (!byDir.has(d)) byDir.set(d, []);
    byDir.get(d).push(f);
  }
  const ids = new Map();
  for (const [dir, fs_] of byDir) {
    const counts = {};
    for (const f of fs_) { const b = splitStem(f).base; counts[b] = (counts[b] || 0) + 1; }
    const rel = path.relative(root, dir);
    const dirSegs = rel ? rel.split(path.sep).map(segment) : [];
    for (const f of fs_) {
      const s = splitStem(f);
      const last = counts[s.base] > 1 ? `${s.base}_${s.suffix || "ts"}` : s.base;
      ids.set(f, { id: [...dirSegs, segment(last)].join("."), name: counts[s.base] > 1 ? s.stem : s.base });
    }
  }
  return ids;
}

function resolveImport(fromFile, spec, known) {
  if (!spec.startsWith(".")) return null;
  const base = path.resolve(path.dirname(fromFile), spec);
  for (const c of [base, base + ".ts", path.join(base, "index.ts")]) {
    if (known.has(c)) return c;
  }
  return undefined;
}

function foreignId(spec) {
  return spec.replace(/^@/, "").split("/").map(segment).join(".");
}

function decoratorsOf(ts, node) {
  const ds = (ts.canHaveDecorators && ts.canHaveDecorators(node) && ts.getDecorators(node)) || node.decorators || [];
  return ds.map((d) => {
    const e = d.expression;
    const callee = ts.isCallExpression(e) ? e.expression : e;
    return { name: ts.isIdentifier(callee) ? callee.text : callee.getText(), call: ts.isCallExpression(e) ? e : null };
  });
}

function objProp(ts, obj, key) {
  if (!obj || !ts.isObjectLiteralExpression(obj)) return null;
  for (const p of obj.properties) {
    if (ts.isPropertyAssignment(p) && p.name && p.name.getText() === key) return p.initializer;
  }
  return null;
}

function literalText(ts, node) {
  if (!node) return null;
  if (ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node)) return node.text;
  return null;
}

function exprLabel(ts, e) {
  if (ts.isCallExpression(e)) return exprLabel(ts, e.expression);
  if (ts.isPropertyAccessExpression(e)) return e.name.text;
  if (ts.isIdentifier(e)) return e.text;
  return "…";
}

// URL text for an HTTP call argument. `ctx` is {scope, cls, depth}: the
// enclosing function body (for local consts) and class (for this.x members).
function urlText(ts, node, ctx) {
  if (!node) return "?";
  const lit = literalText(ts, node);
  if (lit !== null) return lit;
  if (ts.isTemplateExpression(node)) {
    let s = node.head.text;
    for (const span of node.templateSpans) s += part(ts, span.expression, ctx) + span.literal.text;
    return s;
  }
  if (ts.isBinaryExpression(node) && node.operatorToken.kind === ts.SyntaxKind.PlusToken) {
    return urlText(ts, node.left, ctx) + urlText(ts, node.right, ctx);
  }
  if (ts.isIdentifier(node) && ctx && ctx.scope) {
    const init = findConst(ts, ctx.scope, node.text, node.pos);
    if (init) return urlText(ts, init, { ...ctx, scope: null });
  }
  return part(ts, node, ctx);
}

// A `${...}` part: a this.member (field, computed(), getter) is inlined from
// its initializer, a few levels deep; anything else is written {name}.
function part(ts, e, ctx) {
  const init = ctx && ctx.cls && (ctx.depth || 0) < 3 && thisMember(ts, ctx.cls, e);
  if (init) return urlText(ts, init, { scope: null, cls: ctx.cls, depth: (ctx.depth || 0) + 1 });
  return `{${exprLabel(ts, e)}}`;
}

function thisMember(ts, cls, e) {
  const target = ts.isCallExpression(e) ? e.expression : e;
  if (!ts.isPropertyAccessExpression(target) || target.expression.kind !== ts.SyntaxKind.ThisKeyword) return null;
  const name = target.name.text;
  for (const m of cls.members) {
    if (!m.name || m.name.getText() !== name) continue;
    if (ts.isPropertyDeclaration(m) && m.initializer) return unwrapValue(ts, m.initializer);
    if (ts.isGetAccessorDeclaration(m) && m.body) {
      const ret = m.body.statements.find((s) => ts.isReturnStatement(s));
      if (ret && ret.expression) return ret.expression;
    }
  }
  return null;
}

// computed(() => x), signal(x), () => x  ->  x
function unwrapValue(ts, init) {
  if (ts.isCallExpression(init) && /^(computed|signal)$/.test(init.expression.getText()) && init.arguments[0]) {
    return unwrapValue(ts, init.arguments[0]);
  }
  if (ts.isArrowFunction(init) && !ts.isBlock(init.body)) return init.body;
  return init;
}

function enclosingClass(ts, node) {
  for (let n = node.parent; n; n = n.parent) {
    if (ts.isClassDeclaration(n) || ts.isClassExpression(n)) return n;
  }
  return null;
}

function findConst(ts, scope, name, before) {
  let found = null;
  (function visit(n) {
    if (n.pos > before) return;
    if (ts.isVariableDeclaration(n) && ts.isIdentifier(n.name) && n.name.text === name && n.initializer) found = n.initializer;
    ts.forEachChild(n, visit);
  })(scope);
  return found;
}

function enclosingFunction(ts, node) {
  for (let n = node.parent; n; n = n.parent) {
    if (ts.isFunctionLike(n) && n.body) return n.body;
  }
  return null;
}

function isHttpObject(ts, e) {
  const t = ts.isPropertyAccessExpression(e) ? e.name.text : ts.isIdentifier(e) ? e.text : "";
  return /^(http|httpClient|_http)$/i.test(t);
}

function scanFile(ts, file, text, known) {
  const sf = ts.createSourceFile(file, text, ts.ScriptTarget.Latest, true, ts.ScriptKind.TS);
  const deps = new Set(), foreign = new Set(), bound = new Map();
  const heritage = [];
  let stereotype = null, valueDecls = 0, typeDecls = 0;
  const fields = [], http = new Set();

  const noteSpec = (spec, names) => {
    const target = resolveImport(file, spec, known);
    if (target) { deps.add(target); for (const n of names) bound.set(n, target); }
    else if (target === null) foreign.add(spec);
  };

  function visit(node) {
    if ((ts.isImportDeclaration(node) || ts.isExportDeclaration(node)) &&
        node.moduleSpecifier && ts.isStringLiteral(node.moduleSpecifier)) {
      const names = [];
      const c = node.importClause;
      if (c) {
        if (c.name) names.push(c.name.text);
        const nb = c.namedBindings;
        if (nb && ts.isNamedImports(nb)) for (const el of nb.elements) names.push(el.name.text);
        if (nb && ts.isNamespaceImport(nb)) names.push(nb.name.text);
      }
      noteSpec(node.moduleSpecifier.text, names);
    } else if (ts.isCallExpression(node) && node.expression.kind === ts.SyntaxKind.ImportKeyword) {
      const spec = literalText(ts, node.arguments[0]);
      if (spec) noteSpec(spec, []);
    } else if (ts.isCallExpression(node) && ts.isPropertyAccessExpression(node.expression) &&
               HTTP_VERBS.has(node.expression.name.text) && isHttpObject(ts, node.expression.expression)) {
      const verb = node.expression.name.text;
      const args = node.arguments;
      const urlArg = verb === "request" ? args[1] : args[0];
      const method = verb === "request" ? (literalText(ts, args[0]) || "?").toUpperCase() : verb.toUpperCase();
      http.add(`${method} ${urlText(ts, urlArg, { scope: enclosingFunction(ts, node), cls: enclosingClass(ts, node) })}`);
    }
    ts.forEachChild(node, visit);
  }
  visit(sf);

  for (const st of sf.statements) {
    if (ts.isClassDeclaration(st)) {
      valueDecls++;
      for (const d of decoratorsOf(ts, st)) {
        if (DECORATORS[d.name] && !stereotype) {
          stereotype = DECORATORS[d.name] === "component" && file.endsWith(".page.ts") ? "page" : DECORATORS[d.name];
          if (d.name === "Component" || d.name === "Directive" || d.name === "Pipe") {
            const arg = d.call && d.call.arguments[0];
            const sel = literalText(ts, objProp(ts, arg, d.name === "Pipe" ? "name" : "selector"));
            if (sel) fields.push(`${d.name === "Pipe" ? "name" : "selector"}: ${sel}`);
          }
        }
      }
      const inputs = [], outputs = [];
      for (const m of st.members) {
        if (!ts.isPropertyDeclaration(m) || !m.name) continue;
        const nm = m.name.getText();
        const decos = decoratorsOf(ts, m).map((d) => d.name);
        const init = m.initializer;
        const callee = init && ts.isCallExpression(init) ? init.expression.getText() : "";
        if (decos.includes("Input") || /^(input|input\.required|model|model\.required)$/.test(callee)) inputs.push(nm);
        if (decos.includes("Output") || /^(output|outputFromObservable)$/.test(callee)) outputs.push(nm);
      }
      if (inputs.length) fields.push(`inputs: ${inputs.join(", ")}`);
      if (outputs.length) fields.push(`outputs: ${outputs.join(", ")}`);
      for (const h of st.heritageClauses || []) {
        const kind = h.token === ts.SyntaxKind.ExtendsKeyword ? "inheritance" : "implements";
        for (const t of h.types) {
          const root_ = t.expression;
          const nm = ts.isIdentifier(root_) ? root_.text : ts.isPropertyAccessExpression(root_) ? root_.expression.getText() : null;
          if (nm && bound.has(nm)) heritage.push([bound.get(nm), kind]);
        }
      }
    } else if (ts.isVariableStatement(st)) {
      valueDecls++;
      for (const d of st.declarationList.declarations) {
        const t = d.type && d.type.getText().replace(/<.*$/, "");
        if (t && FN_TYPES[t] && !stereotype) stereotype = FN_TYPES[t];
      }
    } else if (ts.isFunctionDeclaration(st)) {
      valueDecls++;
    } else if (ts.isInterfaceDeclaration(st) || ts.isTypeAliasDeclaration(st) || ts.isEnumDeclaration(st)) {
      typeDecls++;
    }
  }
  if (!stereotype && typeDecls > 0 && valueDecls === 0) stereotype = "interface";

  const httpList = [...http].sort();
  if (httpList.length) {
    for (const h of httpList.slice(0, MAX_HTTP)) fields.push(h);
    if (httpList.length > MAX_HTTP) fields.push(`+${httpList.length - MAX_HTTP} more http calls`);
  }
  return { deps, foreign, heritage, stereotype, fields };
}

function ednStr(s) {
  return '"' + String(s).replace(/\\/g, "\\\\").replace(/"/g, '\\"') + '"';
}

function ednMap(m) {
  const items = [];
  for (const [k, v] of Object.entries(m)) {
    if (v === undefined || v === null) continue;
    if (Array.isArray(v)) items.push(`:${k} [` + v.map(ednMap).join(" ") + "]");
    else if (v === true) items.push(`:${k} true`);
    else if (["id", "from", "to", "kind", "stereotype", "lang"].includes(k)) items.push(`:${k} :${v}`);
    else items.push(`:${k} ${ednStr(v)}`);
  }
  return "{" + items.join(" ") + "}";
}

function scan(rootArg) {
  const root = path.resolve(rootArg);
  const ts = loadTypescript(root);
  const files = sourceFiles(root);
  const known = new Set(files);
  const ids = assignIds(files, root);
  const classes = [], edges = new Map(), foreigns = new Set();
  const edge = (from, to, kind) => {
    if (from === to) return;
    const key = `${from} ${to}`;
    const rank = { dependency: 0, implements: 4, inheritance: 4 };
    if (!edges.has(key) || rank[kind] > rank[edges.get(key)]) edges.set(key, kind);
  };
  for (const f of files) {
    const { id, name } = ids.get(f);
    let r;
    try {
      r = scanFile(ts, f, fs.readFileSync(f, "utf8"), known);
    } catch (e) {
      process.stderr.write(`scan_angular: skipped ${f}: ${e.message}\n`);
      continue;
    }
    const c = { id, name, ns: path.join(rootArg, path.relative(root, f)), stereotype: r.stereotype, lang: "angular" };
    if (r.fields.length) c.fields = r.fields.map((t) => ({ text: t }));
    classes.push(c);
    for (const d of r.deps) edge(id, ids.get(d).id, "dependency");
    for (const [d, kind] of r.heritage) edge(id, ids.get(d).id, kind);
    for (const s of r.foreign) { const fid = foreignId(s); foreigns.add(fid); edge(id, fid, "dependency"); }
  }
  for (const fid of [...foreigns].sort()) classes.push({ id: fid, name: fid, ns: fid, foreign: true });
  const edgeList = [...edges.entries()].sort().map(([k, kind]) => {
    const [from, to] = k.split(" ");
    return { from, to, kind };
  });
  return { classes, edges: edgeList };
}

function main() {
  const args = process.argv.slice(2);
  if (!args[0]) {
    process.stderr.write("usage: node scan_angular.js ROOT\n");
    process.exit(2);
  }
  const { classes, edges } = scan(args[0]);
  const out = ["{:classes ["];
  for (const c of classes) out.push(" " + ednMap(c));
  out.push("]", " :edges [");
  for (const e of edges) out.push(" " + ednMap(e));
  out.push("]}");
  process.stdout.write(out.join("\n") + "\n");
}

module.exports = { loadTypescript, sourceFiles };

// Run when invoked directly or piped on stdin (`node - ROOT`), not when required.
if (require.main === module || !require.main) main();
