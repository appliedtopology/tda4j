#!/usr/bin/env python3
"""List everything in the papers that is still pending, by kind.

    python3 papers/tools/todo-inventory.py            # the whole inventory
    python3 papers/tools/todo-inventory.py --keys     # only the benchmark keys the papers read, with a defined/missing flag

Markers (common/macros.tex): \\TODO* (red: fill in), \\OUTCOME (blue: direction depends on the results), \\VERIFY and
\\PROV (orange: unchecked fact or provenance question). Benchmark cells are the keys the tables read through \\resT,
\\resC, \\resM, \\resA, \\res; a key is `defined` once common/results.tex has a value for it. Bibliography entries with a
`verify` field were written from memory or from project notes and have not been checked against the publication.
"""
import re
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MARKERS = ["TODOtime", "TODOmem", "TODOnum", "TODO", "OUTCOME", "VERIFY", "PROV"]


def balanced(text, start):
    """The text of the {...} group that begins at text[start] == '{', and the index after it."""
    depth = 0
    for i in range(start, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return text[start + 1:i], i + 1
    return text[start + 1:], len(text)


def tex_files():
    skip = {"results.tex", "macros.tex"}
    return sorted(p for p in ROOT.rglob("*.tex") if p.name not in skip and "build" not in p.parts)


def source(path):
    """The file's text with the % comments blanked (the line count is kept)."""
    return "\n".join(re.sub(r"(?<!\\)%.*", "", line) for line in path.read_text().splitlines())


def markers(path):
    text = source(path)
    found = []
    for m in re.finditer(r"\\(" + "|".join(MARKERS) + r")\s*\{", text):
        body, _ = balanced(text, m.end() - 1)
        line = text.count("\n", 0, m.start()) + 1
        found.append((m.group(1), line, " ".join(body.split())))
    return found


def uses_of_keys(path):
    """(key, line) for every table cell and \\res call; \\benchrow cells are expanded over \\benchcases."""
    text = source(path)
    keys = []
    for m in re.finditer(r"\\res([TCMA])\s*\{([^}]*)\}\s*\{([^}]*)\}\s*\{([^}]*)\}", text):
        suffix = {"T": "warm", "C": "cold", "M": "mem", "A": "agree"}[m.group(1)]
        keys.append((f"{m.group(2)}/{m.group(3)}/{m.group(4)}/{suffix}", text.count("\n", 0, m.start()) + 1))
    for m in re.finditer(r"\\res(?:\[[^\]]*\])?\s*\{([^}\\]+/[^}\\]+)\}", text):
        keys.append((m.group(1), text.count("\n", 0, m.start()) + 1))
    # a table: \def\benchcases{a,b,c} \let\benchkind\resX  \benchrow{p}{tool}{name}
    kind = "warm"
    cases = []
    for line_no, line in enumerate(text.splitlines(), 1):
        m = re.search(r"\\def\\benchcases\{([^}]*)\}", line)
        if m:
            cases = [c.strip() for c in m.group(1).split(",") if c.strip()]
        m = re.search(r"\\let\\benchkind\\res([TCMA])", line)
        if m:
            kind = {"T": "warm", "C": "cold", "M": "mem", "A": "agree"}[m.group(1)]
        m = re.search(r"\\benchrow\{([^}]*)\}\{([^}]*)\}", line)
        if m:
            for c in cases:
                keys.append((f"{c}/{m.group(1)}/{m.group(2)}/{kind}", line_no))
    # a sweep: \def\sweepvals{1,2,4} \let\sweepkind\sweepM  \sweeprow{prefix}{name}
    values, kind = [], "warm"
    for line_no, line in enumerate(text.splitlines(), 1):
        m = re.search(r"\\def\\sweepvals\{([^}]*)\}", line)
        if m:
            values = [c.strip() for c in m.group(1).split(",") if c.strip()]
        m = re.search(r"\\let\\sweepkind\\sweep([TM])", line)
        if m:
            kind = {"T": "warm", "M": "mem"}[m.group(1)]
        m = re.search(r"\\sweeprow\{([^}]*)\}", line)
        if m:
            for v in values:
                keys.append((f"{m.group(1)}/{v}/{kind}", line_no))
    return keys


def defined_keys():
    path = ROOT / "common" / "results.tex"
    if not path.exists():
        return set()
    return set(re.findall(r"\\defres\{([^}]*)\}", path.read_text()))


def unchecked_bib():
    path = ROOT / "common" / "refs.bib"
    out = []
    if not path.exists():
        return out
    for entry in re.split(r"\n@", "\n" + path.read_text())[1:]:
        key = re.match(r"\w+\{([^,]+),", entry)
        v = re.search(r"verify\s*=\s*\{([^}]*)\}", entry)
        if key and v:
            out.append((key.group(1), v.group(1)))
    return out


def main(argv):
    only_keys = "--keys" in argv
    defined = defined_keys()
    if not only_keys:
        by_kind = defaultdict(list)
        for path in tex_files():
            for kind, line, body in markers(path):
                by_kind[kind].append((path.relative_to(ROOT), line, body))
        for kind in MARKERS:
            items = by_kind.get(kind, [])
            print(f"\n== {kind}: {len(items)}")
            for path, line, body in items:
                print(f"  {path}:{line}: {body[:150]}")
    seen = defaultdict(list)
    for path in tex_files():
        for key, line in uses_of_keys(path):
            seen[key].append(f"{path.relative_to(ROOT)}:{line}")
    missing = sorted(k for k in seen if k not in defined)
    print(f"\n== benchmark keys read by the papers: {len(seen)} ({len(seen) - len(missing)} defined, {len(missing)} missing)")
    for k in sorted(seen):
        flag = "defined" if k in defined else "MISSING"
        if only_keys or flag == "MISSING":
            print(f"  {flag:8} {k}  <- {seen[k][0]}")
    if not only_keys:
        bib = unchecked_bib()
        print(f"\n== bibliography entries not checked against the publication: {len(bib)}")
        for key, why in bib:
            print(f"  {key}: {why}")


if __name__ == "__main__":
    main(sys.argv[1:])
