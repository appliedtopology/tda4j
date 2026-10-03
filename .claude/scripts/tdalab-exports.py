#!/usr/bin/env python3
"""Regenerates TDAlab's re-export block in src/main/scala/org/appliedtopology/tda4j/package.scala.

TDAlab re-exports every public top-level class/trait/object/type/enum of the root package and of the `sset` add-on,
so `import tdalab.{*, given}` is the only import a TDAlab user needs. Only those symbol kinds: Scala 3.9 does not
report an ambiguity when a user imports BOTH `org.appliedtopology.tda4j.*` and `tdalab.*` for re-exported
objects/classes/types, but it does for re-exported defs and extension methods (spike in
.claude/WORKLOG-package-flatten.md) -- so top-level defs/extensions are never re-exported.

`TDAlabExportsSpec` runs the same scan and fails when the block is stale. Usage (from the repo root):
    python3 .claude/scripts/tdalab-exports.py
"""
import pathlib, re

ROOT = pathlib.Path("src/main/scala/org/appliedtopology/tda4j")
PKG = "org.appliedtopology.tda4j"
ADDONS = ["sset"]  # add-on packages TDAlab also re-exports
EXCLUDE = {"TDAlab", "Lab", "CubicalLab", "SimplexOps", "SimplexInstances", "CubeInstances"}  # the facade itself; companion mixins
DECL = re.compile(
    r"^(?:(?:sealed|final|case|abstract|open|opaque|transparent|infix)\s+)*(?:class|trait|object|type|enum)\s+([^\s\[\(:=]+)"
)
EXTRA_TERMS = ["∆"]  # val aliases also re-exported (a val forwarder is not ambiguous; a def forwarder is)
BEGIN, END = "  // BEGIN generated re-exports", "  // END generated re-exports"


def public_names(package_lines):
    names = set()
    for f in sorted(ROOT.rglob("*.scala")):
        text = f.read_text().split("\n")
        if [l for l in text[:30] if l.startswith("package ")] != package_lines:
            continue
        for line in text:
            m = DECL.match(line)
            if m:
                names.add(m.group(1))
    return sorted(names - EXCLUDE)


def block():
    out = [BEGIN, "  // regenerate: python3 .claude/scripts/tdalab-exports.py (checked by TDAlabExportsSpec)"]
    for pkg, lines in [(PKG, [f"package {PKG}"])] + [
        (f"{PKG}.{a}", [f"package {PKG}", f"package {a}"]) for a in ADDONS
    ]:
        names = sorted(public_names(lines) + (EXTRA_TERMS if pkg == PKG else []))
        if names:
            out.append(f"  export {pkg}.{{")
            out.append(",\n".join(f"    {n}" for n in names))
            out.append("  }")
    out.append(END)
    return "\n".join(out)


if __name__ == "__main__":
    p = ROOT / "package.scala"
    s = p.read_text()
    if BEGIN in s:
        s = s[: s.index(BEGIN)] + block() + s[s.index(END) + len(END) :]
    else:
        anchor = "  export cats.implicits.toShow"
        s = s.replace(anchor, block() + "\n\n" + anchor)
    p.write_text(s)
    print("wrote", p)
