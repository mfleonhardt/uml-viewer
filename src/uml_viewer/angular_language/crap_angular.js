// CRAP snapshot for Angular/TypeScript, in the shape uml-viewer overlays.
//
// Joins an Istanbul coverage-final.json (what Vitest's v8 or istanbul
// provider writes with the `json` reporter) with cyclomatic complexity from
// the TypeScript AST, and writes .metrics/crap.edn:
//
//   {:entries [{:name "ChatService.send" :namespace "src/app/services/chat.service.ts"
//               :complexity 3 :coverage 66.67 :crap 3.11} ...]}
//
// - :namespace is the file path as given (SRC_ROOT-relative to the working
//   directory), the same as scan_angular.js's class :ns.
// - :name is a dotted qualname: `fn`, `Class.method`, `Class.constructor`,
//   `Class.prop` for a getter/setter or a property whose initializer holds
//   functions (arrow fields, computed(() => ...), effect(...)), `name` for a
//   const whose initializer holds functions (guards, routes), and
//   `outer.inner` for named functions inside functions.
// - :complexity is classic McCabe (as radon counts Python): 1, plus 1 per
//   if, ?:, for/for-in/for-of, while, do, catch, non-default case, and
//   && / || / ??. Optional chaining and default parameters are not counted
//   (ESLint's `complexity` rule counts both). Anonymous callbacks count in
//   the entry that contains them, since a card has no row for them (ESLint
//   scores each separately); named nested functions are their own entry.
// - :coverage is the percentage of the entry's own statements (Istanbul
//   statementMap) that ran, excluding nested named entries. No statements is
//   100; a file missing from the report (never imported by a test) is 0.
// - :crap is cc^2 * (1 - coverage)^3 + cc.
//
// Usage: node crap_angular.js COVERAGE_JSON SRC_ROOT OUT_EDN

"use strict";
const fs = require("fs");
const path = require("path");
const { loadTypescript, sourceFiles } = require("./scan_angular.js");

function isFn(ts, n) {
  return ts.isFunctionDeclaration(n) || ts.isFunctionExpression(n) || ts.isArrowFunction(n) ||
    ts.isMethodDeclaration(n) || ts.isConstructorDeclaration(n) ||
    ts.isGetAccessorDeclaration(n) || ts.isSetAccessorDeclaration(n);
}

function containsFn(ts, node) {
  let found = false;
  (function visit(n) {
    if (found) return;
    if (isFn(ts, n)) { found = true; return; }
    ts.forEachChild(n, visit);
  })(node);
  return found;
}

// Named entries in a file: {name, node (span), nested: [entry...]} in a flat
// list, each with its span node and the nodes it contains directly.
function entries(ts, sf) {
  const out = [];
  function add(name, span, parent) {
    const e = { name, span, children: [] };
    if (parent) parent.children.push(e);
    out.push(e);
    return e;
  }
  function visit(node, prefix, owner) {
    if (ts.isClassDeclaration(node) || ts.isClassExpression(node)) {
      const cname = node.name ? node.name.text : "(class)";
      for (const m of node.members) {
        const mname = m.name ? m.name.getText(sf) : null;
        if (ts.isConstructorDeclaration(m) && m.body) {
          walkBody(m.body, add(prefix + cname + ".constructor", m.body, owner), prefix + cname + ".constructor.");
        } else if ((ts.isMethodDeclaration(m) || ts.isGetAccessorDeclaration(m) || ts.isSetAccessorDeclaration(m)) && m.body && mname) {
          walkBody(m.body, add(prefix + cname + "." + mname, m.body, owner), prefix + cname + "." + mname + ".");
        } else if (ts.isPropertyDeclaration(m) && m.initializer && mname && containsFn(ts, m.initializer)) {
          walkBody(m.initializer, add(prefix + cname + "." + mname, m.initializer, owner), prefix + cname + "." + mname + ".");
        }
      }
      return;
    }
    if (ts.isFunctionDeclaration(node) && node.body && node.name) {
      walkBody(node.body, add(prefix + node.name.text, node.body, owner), prefix + node.name.text + ".");
      return;
    }
    if (ts.isVariableDeclaration(node) && ts.isIdentifier(node.name) && node.initializer &&
        containsFn(ts, node.initializer)) {
      walkBody(node.initializer, add(prefix + node.name.text, node.initializer, owner), prefix + node.name.text + ".");
      return;
    }
    ts.forEachChild(node, (c) => visit(c, prefix, owner));
  }
  function walkBody(body, owner, prefix) {
    ts.forEachChild(body, (c) => visit(c, prefix, owner));
  }
  visit(sf, "", null);
  return out;
}

// Complexity of an entry's span, not descending into its named children.
function complexity(ts, e) {
  const skip = new Set(e.children.map((c) => c.span));
  let cc = 1;
  (function visit(n) {
    if (skip.has(n)) return;
    switch (n.kind) {
      case ts.SyntaxKind.IfStatement:
      case ts.SyntaxKind.ConditionalExpression:
      case ts.SyntaxKind.ForStatement:
      case ts.SyntaxKind.ForInStatement:
      case ts.SyntaxKind.ForOfStatement:
      case ts.SyntaxKind.WhileStatement:
      case ts.SyntaxKind.DoStatement:
      case ts.SyntaxKind.CatchClause:
      case ts.SyntaxKind.CaseClause:
        cc += 1;
        break;
      case ts.SyntaxKind.BinaryExpression: {
        const op = n.operatorToken.kind;
        if (op === ts.SyntaxKind.AmpersandAmpersandToken || op === ts.SyntaxKind.BarBarToken ||
            op === ts.SyntaxKind.QuestionQuestionToken) cc += 1;
        break;
      }
    }
    ts.forEachChild(n, visit);
  })(e.span);
  return cc;
}

// [start, end] as comparable [line, col] pairs (Istanbul: 1-based line, 0-based col).
function spanOf(sf, node) {
  const a = sf.getLineAndCharacterOfPosition(node.getStart(sf));
  const b = sf.getLineAndCharacterOfPosition(node.getEnd());
  return [[a.line + 1, a.character], [b.line + 1, b.character]];
}

function le(p, q) {
  return p[0] < q[0] || (p[0] === q[0] && p[1] <= q[1]);
}

function inside(pos, span) {
  return le(span[0], pos) && le(pos, span[1]);
}

function coveragePct(sf, e, stmts) {
  const own = spanOf(sf, e.span);
  const kids = e.children.map((c) => spanOf(sf, c.span));
  let ran = 0, total = 0;
  for (const s of stmts) {
    if (!inside(s.start, own) || kids.some((k) => inside(s.start, k))) continue;
    total += 1;
    if (s.count > 0) ran += 1;
  }
  return total === 0 ? 100 : (100 * ran) / total;
}

function reportIndex(report) {
  const out = new Map();
  for (const [key, data] of Object.entries(report)) {
    const file = path.resolve(data.path || key);
    const stmts = Object.entries(data.statementMap || {}).map(([id, loc]) => ({
      start: [loc.start.line, loc.start.column],
      count: (data.s || {})[id] || 0,
    }));
    out.set(file, stmts);
  }
  return out;
}

function round2(x) {
  return Math.round(x * 100) / 100;
}

function crap(cc, pct) {
  return cc * cc * Math.pow(1 - pct / 100, 3) + cc;
}

function snapshot(report, srcArg) {
  const root = path.resolve(srcArg);
  const ts = loadTypescript(root);
  const index = reportIndex(report);
  const rows = [];
  for (const file of sourceFiles(root)) {
    let sf;
    try {
      sf = ts.createSourceFile(file, fs.readFileSync(file, "utf8"), ts.ScriptTarget.Latest, true, ts.ScriptKind.TS);
    } catch (e) {
      process.stderr.write(`crap_angular: skipped ${file}: ${e.message}\n`);
      continue;
    }
    const ns = path.join(srcArg, path.relative(root, file));
    const stmts = index.get(file);
    for (const e of entries(ts, sf)) {
      const cc = complexity(ts, e);
      const pct = stmts ? coveragePct(sf, e, stmts) : 0;
      rows.push({ name: e.name, namespace: ns, complexity: cc, coverage: round2(pct), crap: round2(crap(cc, pct)) });
    }
  }
  rows.sort((a, b) => b.crap - a.crap || a.namespace.localeCompare(b.namespace) || a.name.localeCompare(b.name));
  return rows;
}

function ednStr(s) {
  return '"' + String(s).replace(/\\/g, "\\\\").replace(/"/g, '\\"') + '"';
}

// Always a decimal (100.0, not 100), as the Python snapshot writes it.
function num(x) {
  return Number.isInteger(x) ? x.toFixed(1) : String(x);
}

function toEdn(rows) {
  const lines = rows.map((e) => ` {:name ${ednStr(e.name)} :namespace ${ednStr(e.namespace)}` +
    ` :complexity ${e.complexity} :coverage ${num(e.coverage)} :crap ${num(e.crap)}}`);
  return "{:entries [\n" + lines.join("\n") + "\n]}\n";
}

function main() {
  const [coverageJson, srcRoot, outEdn] = process.argv.slice(2);
  if (!outEdn) {
    process.stderr.write("usage: node crap_angular.js COVERAGE_JSON SRC_ROOT OUT_EDN\n");
    process.exit(2);
  }
  const rows = snapshot(JSON.parse(fs.readFileSync(coverageJson, "utf8")), srcRoot);
  fs.mkdirSync(path.dirname(outEdn), { recursive: true });
  const tmp = outEdn + ".tmp";
  fs.writeFileSync(tmp, toEdn(rows));
  fs.renameSync(tmp, outEdn);
  process.stderr.write(`crap_angular: ${rows.length} functions -> ${outEdn}\n`);
}

main();
