"""Python LanguageGraph scanner for uml-viewer.

Reads every .py file under ROOT with `ast` (nothing is imported or run) and
prints the project graph as EDN on stdout:

    {:classes [{:id :pkg.mod :name "mod" :ns "pkg.mod" ...} ...]
     :edges [{:from :pkg.mod :to :pkg.other :kind :dependency} ...]}

A module is a class. `import` / `from ... import` of another project module
is a :dependency, including lazy imports inside functions and imports under
`if TYPE_CHECKING:`. Relative imports resolve against the importing package.
A module that defines a `typing.Protocol` class is `:stereotype :interface`;
one that defines an ABC or `@abstractmethod` is `:stereotype :abstract`.
Subclassing a class imported from a project module is :implements when that
module is an interface or abstract, :inheritance otherwise. Non-stdlib
external imports become foreign classes; stdlib imports are dropped.

Usage: python3 scan_python.py ROOT [PREFIX]
PREFIX (e.g. "myapp") is stripped from ids; empty means ids are full module
names relative to ROOT. Unparseable files are reported on stderr and skipped.
"""

import ast
import sys
from pathlib import Path

SKIP_DIRS = {"__pycache__", ".venv", "venv", "node_modules", ".git", "build", "dist"}


def module_name(path, root):
    rel = path.relative_to(root).with_suffix("")
    parts = list(rel.parts)
    if parts[-1] == "__init__":
        parts = parts[:-1]
    return ".".join(parts)


def source_files(root):
    return sorted(
        p for p in root.rglob("*.py")
        if not SKIP_DIRS.intersection(p.relative_to(root).parts[:-1])
    )


def package_of(mod, is_package):
    return mod if is_package else mod.rpartition(".")[0]


def resolve_relative(mod, is_package, level, target):
    base = package_of(mod, is_package).split(".") if mod else []
    up = level - 1
    if up > len(base):
        return None
    base = base[: len(base) - up] if up else base
    return ".".join([*base, target] if target else base) or None


def longest_module(name, modules):
    parts = name.split(".")
    for n in range(len(parts), 0, -1):
        cand = ".".join(parts[:n])
        if cand in modules:
            return cand
    return None


def is_protocol_base(b):
    return (isinstance(b, ast.Name) and b.id == "Protocol") or (
        isinstance(b, ast.Attribute) and b.attr == "Protocol")


def is_abc_base(b):
    return (isinstance(b, ast.Name) and b.id == "ABC") or (
        isinstance(b, ast.Attribute) and b.attr == "ABC")


def is_abstract_method(fn):
    for d in fn.decorator_list:
        name = d.attr if isinstance(d, ast.Attribute) else getattr(d, "id", None)
        if name == "abstractmethod":
            return True
    return False


def stereotype(tree):
    kind = None
    for node in ast.walk(tree):
        if not isinstance(node, ast.ClassDef):
            continue
        if any(is_protocol_base(b) for b in node.bases):
            return "interface"
        metaclass = any(
            k.arg == "metaclass" and getattr(k.value, "id", getattr(k.value, "attr", None)) == "ABCMeta"
            for k in node.keywords)
        if (any(is_abc_base(b) for b in node.bases) or metaclass or any(
                isinstance(f, (ast.FunctionDef, ast.AsyncFunctionDef)) and is_abstract_method(f)
                for f in node.body)):
            kind = "abstract"
    return kind


class Module:
    def __init__(self, path, root):
        self.path = path
        self.name = module_name(path, root)
        self.is_package = path.name == "__init__.py"
        self.tree = None


def parse(mod):
    try:
        mod.tree = ast.parse(mod.path.read_text(encoding="utf-8"), filename=str(mod.path))
    except (SyntaxError, UnicodeDecodeError, ValueError) as e:
        print(f"scan_python: skipped {mod.path}: {e}", file=sys.stderr)


def imported_names(mod, modules):
    """(project deps, foreign modules, {local name: project module})."""
    deps, foreign, bound = set(), set(), {}
    here = package_of(mod.name, mod.is_package)

    def sibling(full):
        # Lambda-style code runs with its own folder as the import root.
        if not here or full.split(".")[0] in project_tops:
            return None
        target = longest_module(f"{here}.{full}", modules)
        head = f"{here}.{full.split('.')[0]}"
        return target if target and (target == head or target.startswith(head + ".")) else None

    def note(full, local=None):
        target = longest_module(full, modules) or sibling(full)
        if target:
            deps.add(target)
            if local:
                bound[local] = target
        elif full.split(".")[0] not in project_tops:
            foreign.add(full)

    project_tops = {m.split(".")[0] for m in modules}
    for node in ast.walk(mod.tree):
        if isinstance(node, ast.Import):
            for a in node.names:
                note(a.name, a.asname or a.name.split(".")[0])
        elif isinstance(node, ast.ImportFrom):
            if node.level:
                base = resolve_relative(mod.name, mod.is_package, node.level, node.module)
            else:
                base = node.module
            if not base or base == "__future__":
                continue
            for a in node.names:
                sub = f"{base}.{a.name}"
                if a.name != "*" and sub in modules:
                    note(sub, a.asname or a.name)
                elif not node.level and sibling(sub) == f"{here}.{sub}":
                    note(sub, a.asname or a.name)
                else:
                    target = longest_module(base, modules) or (
                        None if node.level else sibling(base))
                    note(base)
                    if target and a.name != "*":
                        bound[a.asname or a.name] = target
    return deps, foreign, bound


def base_modules(tree, bound):
    out = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.ClassDef):
            for b in node.bases:
                root = b
                while isinstance(root, ast.Attribute):
                    root = root.value
                if isinstance(root, ast.Name) and root.id in bound:
                    out.add(bound[root.id])
    return out


def strip(name, prefix):
    if prefix and name.startswith(prefix + "."):
        return name[len(prefix) + 1:]
    return name


def is_stdlib(name):
    return name.split(".")[0] in sys.stdlib_module_names


def edn_str(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


def edn_map(m):
    items = []
    for k, v in m.items():
        if v is True:
            val = "true"
        elif k in ("id", "from", "to", "kind", "stereotype", "lang"):
            val = ":" + v
        else:
            val = edn_str(v)
        items.append(f":{k} {val}")
    return "{" + " ".join(items) + "}"


def scan(root, prefix=""):
    root = Path(root).resolve()
    mods = [Module(p, root) for p in source_files(root)]
    mods = [m for m in mods if m.name]
    for m in mods:
        parse(m)
    mods = [m for m in mods if m.tree is not None]
    names = {m.name for m in mods}
    kinds = {m.name: stereotype(m.tree) for m in mods}

    classes, edges, foreigns = [], [], set()
    for m in mods:
        cid = strip(m.name, prefix)
        c = {"id": cid, "name": cid.rpartition(".")[2], "ns": m.name, "lang": "python"}
        st = kinds[m.name]
        if st:
            c["stereotype"] = st
        classes.append(c)
        deps, foreign, bound = imported_names(m, names)
        for d in sorted(deps - {m.name}):
            edges.append({"from": cid, "to": strip(d, prefix), "kind": "dependency"})
        for b in sorted(base_modules(m.tree, bound) - {m.name}):
            kind = "implements" if kinds.get(b) else "inheritance"
            edges.append({"from": cid, "to": strip(b, prefix), "kind": kind})
        for f in sorted(foreign):
            if not is_stdlib(f):
                foreigns.add(f)
                edges.append({"from": cid, "to": f, "kind": "dependency"})
    for f in sorted(foreigns):
        classes.append({"id": f, "name": f, "ns": f, "foreign": True})
    return classes, edges


def main(argv):
    if len(argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    classes, edges = scan(argv[1], argv[2] if len(argv) > 2 else "")
    out = sys.stdout
    out.write("{:classes [\n")
    for c in classes:
        out.write(" " + edn_map(c) + "\n")
    out.write("]\n :edges [\n")
    for e in edges:
        out.write(" " + edn_map(e) + "\n")
    out.write("]}\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
