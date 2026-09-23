"""CRAP snapshot for Python, in the shape uml-viewer overlays.

Joins a coverage.py JSON report with cyclomatic complexity computed from
source (stdlib `ast`; nothing is imported or run) and writes .metrics/crap.edn:

    {:entries [{:name "Class.method" :namespace "pkg.mod" :complexity 4
                :coverage 87.5 :crap 4.1} ...]}

- :namespace is the dotted module name, the same as scan_python.py's class :ns.
- :name is the function's dotted qualname (`func`, `Class.method`,
  `outer.inner`). Every def / async def gets an entry.
- :complexity is McCabe, radon-style: 1, plus 1 per if/elif, for, while,
  except handler, conditional expression, comprehension `for` and `if`, extra
  `and`/`or` operand, and match case. A nested def or class is its own entry
  and is not counted in the outer function; a lambda is.
- :coverage is the percentage of the function's statements that ran, from
  the report's executed_lines / missing_lines. The span is the body (not the
  decorators or `def` line), minus nested def/class bodies. A function with
  no statements is 100; a file missing from the report is 0.
- :crap is cc^2 * (1 - coverage)^3 + cc.

Usage: python3 crap_python.py COVERAGE_JSON SRC_ROOT OUT_EDN
Report paths are matched against the files under SRC_ROOT whether they are
absolute, relative to the current directory, or relative to SRC_ROOT's
parent (coverage.py's default when run from the project root).
"""

import ast
import json
import sys
from pathlib import Path

sys.dont_write_bytecode = True  # no __pycache__ next to the viewer's sources
sys.path.insert(0, str(Path(__file__).resolve().parent))
from scan_python import module_name, source_files  # noqa: E402

NESTED = (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)


def own_nodes(fn):
    """Nodes inside `fn`'s body, not descending into nested defs/classes."""
    stack = list(fn.body)
    while stack:
        node = stack.pop()
        yield node
        if isinstance(node, NESTED):
            continue
        stack.extend(ast.iter_child_nodes(node))


def complexity(fn):
    cc = 1
    for node in own_nodes(fn):
        if isinstance(node, (ast.If, ast.For, ast.AsyncFor, ast.While,
                             ast.ExceptHandler, ast.IfExp)):
            cc += 1
        elif isinstance(node, ast.comprehension):
            cc += 1 + len(node.ifs)
        elif isinstance(node, ast.BoolOp):
            cc += len(node.values) - 1
        elif isinstance(node, ast.match_case):
            cc += 1
    return cc


def body_lines(fn):
    """Line numbers of `fn`'s own body: first body line to the end, minus
    the bodies of nested defs/classes (their `def`/`class` lines stay)."""
    lines = set(range(fn.body[0].lineno, last_line(fn) + 1))
    for node in own_nodes(fn):
        if isinstance(node, NESTED) and node.body:
            lines -= set(range(node.body[0].lineno, last_line(node) + 1))
    return lines


def last_line(node):
    return node.end_lineno or node.lineno


def functions(tree):
    """(qualname, node) for every def in `tree`, outermost first."""
    out = []

    def walk(nodes, prefix):
        for node in nodes:
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
                name = prefix + node.name
                out.append((name, node))
                walk(node.body, name + ".")
            elif isinstance(node, ast.ClassDef):
                walk(node.body, prefix + node.name + ".")
            else:
                walk(list(ast.iter_child_nodes(node)), prefix)

    walk(tree.body, "")
    return out


def coverage_pct(fn, executed, missing):
    lines = body_lines(fn)
    ran = len(lines & executed)
    stmts = ran + len(lines & missing)
    return 100.0 if stmts == 0 else 100.0 * ran / stmts


def crap(cc, pct):
    return cc * cc * (1 - pct / 100.0) ** 3 + cc


def report_index(report, root):
    """Resolved source path -> (executed lines, missing lines)."""
    bases = [Path.cwd(), root.parent, Path("/")]
    out = {}
    for key, data in (report.get("files") or {}).items():
        for base in bases:
            p = (base / key).resolve()
            if p.is_file():
                out[p] = (set(data.get("executed_lines", [])),
                          set(data.get("missing_lines", [])))
                break
    return out


def entries(report, root):
    root = Path(root).resolve()
    index = report_index(report, root)
    out = []
    for path in source_files(root):
        mod = module_name(path, root)
        if not mod:
            continue
        try:
            tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
        except (SyntaxError, UnicodeDecodeError, ValueError) as e:
            print(f"crap_python: skipped {path}: {e}", file=sys.stderr)
            continue
        executed, missing = index.get(path.resolve(), (set(), None))
        for qualname, fn in functions(tree):
            cc = complexity(fn)
            if missing is None:
                pct = 0.0
            else:
                pct = coverage_pct(fn, executed, missing)
            out.append({"name": qualname, "namespace": mod, "complexity": cc,
                        "coverage": round(pct, 2), "crap": round(crap(cc, pct), 2)})
    out.sort(key=lambda e: (-e["crap"], e["namespace"], e["name"]))
    return out


def edn_str(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


def to_edn(rows):
    lines = []
    for e in rows:
        lines.append(" {:name " + edn_str(e["name"])
                     + " :namespace " + edn_str(e["namespace"])
                     + f" :complexity {e['complexity']}"
                     + f" :coverage {e['coverage']}"
                     + f" :crap {e['crap']}}}")
    return "{:entries [\n" + "\n".join(lines) + "\n]}\n"


def main(argv):
    if len(argv) != 4:
        print(__doc__, file=sys.stderr)
        return 2
    report = json.loads(Path(argv[1]).read_text(encoding="utf-8"))
    rows = entries(report, argv[2])
    out = Path(argv[3])
    out.parent.mkdir(parents=True, exist_ok=True)
    tmp = out.with_name(out.name + ".tmp")
    tmp.write_text(to_edn(rows), encoding="utf-8")
    tmp.replace(out)
    print(f"crap_python: {len(rows)} functions -> {out}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
