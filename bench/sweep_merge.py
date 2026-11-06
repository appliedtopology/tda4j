#!/usr/bin/env python3
"""One sweep directory from several: a rerun of some tools replaces their rows in an earlier sweep and leaves the rest.

    bench/run.sh sweep-merge OUT DIR[:tool,tool]... [--drop PATTERN] [--allow-mixed]

The sweeps of `sweep-threads`, `sweep-cubical` and `sweep-alpha` each write a directory with a `sweep.csv`, `bars/`,
`logs/` and `sweep.md` (the alpha sweep also a `validity.csv`). This writes the same into OUT, as `bench.py merge` does for
the directories of `run`: every source is `DIR` or `DIR:tools`, `tools` in `--tools`'s notation (comma-separated names or
patterns, `!pattern` leaves tools out: `old:!tda4j*` is every tool but TDA4j's, the cubical sweep's `old:!cripser,!gudhi`
the same there), and for every series the rows come from the LAST source that has it, with its bars and logs. The
tables are rebuilt from the merged rows by the sweep's own summary, and `env.json` has `merged_from`: each source's
directory, date, host and TDA4j commit and what it supplied; `sweep.md` names them and warns where the hosts differ.

A series is the unit a rerun replaces, not a row: a tool's run along one axis stops at its first timeout or out-of-memory,
so rows of an old and a new series would otherwise interleave, a size or a thread count the new run never reached
standing beside the rows of the new commit. The series is a tool's rows on

  - `sweep-threads`: one case in one field, at every thread count (`case`, `p`, `tool`);
  - `sweep-cubical`: one image kind in one dimension, at every size (`dim`, `image`, `engine`);
  - `sweep-alpha`: the sweep over d at one n and shape, or over n at one d and shape, at one radius (`series`, ...), or a
    single `--extra` cell, over its draws.

Rows of tools that appear in a source but not in the later ones stay as they are. A source that only says a tool is
`missing` there does not replace rows another sweep measured. The sources must be sweeps of one kind.

The parts must come from one machine and one protocol for the merged tables to compare: a tool's times against another
tool's. On the final run, `final_run.sh` reruns TDA4j's rows alone (`SWEEPS_BASE`) only where the anchor rows show the
machine as it was. `sweep-alpha` records the radius it gave the tools in `env.json` (`alpha_radius_rule`): sweeps from
before the enclosing radius was given to every tool that takes one (GUDHI got none) do not merge with later ones, unless
`--allow-mixed` says the difference (at most 11% of GUDHI's time on the draws measured, none in the barcode) is accepted.
"""
import argparse
import csv
import json
import os
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import bench  # noqa: E402  (parse_source, tool_selected, read_env)
import sweep_alpha  # noqa: E402
import sweep_cubical  # noqa: E402
import sweep_threads  # noqa: E402


class Kind:
    """What the merge needs to know of one sweep: its columns, the tool a row is of, the series a row belongs to, the
    names (bars and log file stems) a row owns, and the summary that rebuilds the tables."""

    def __init__(self, name, columns, tool, series, tags, tda4j, summarize):
        self.name, self.columns, self.tool, self.series, self.tags = name, columns, tool, series, tags
        self.tda4j, self.summarize = tda4j, summarize


def alpha_series(r):
    if r["series"] in ("d", "n"):  # a sweep over d is at one n, over n at one d
        return (r["series"], r["shape"], r["n"] if r["series"] == "d" else r["d"], r["radius"], r["tool"])
    return ("", r["shape"], r["n"], r["d"], r["radius"], r["tool"])


def alpha_tags(r):
    args = (r["shape"], r["n"], r["d"], r["seed"], r["radius"])
    tags = {f"{sweep_alpha.cell_tag(*args)}__{r['tool']}"}
    if r["tool"] in sweep_alpha.COUNTED:  # the cell counts log under the bench.Case's name
        tags.add(f"{sweep_alpha.case_name(*args)}__{'gudhi' if r['tool'] == 'gudhi' else 'tda4j'}")
    return tags


KINDS = {
    "threads": Kind("threads", sweep_threads.COLUMNS, lambda r: r["tool"], lambda r: (r["case"], r["p"], r["tool"]),
                    lambda r: {f"{r['case']}__p{r['p']}__{r['tool']}@{r['threads']}"},
                    lambda tool: tool.startswith("tda4j"), sweep_threads.summarize),
    "cubical": Kind("cubical", sweep_cubical.COLUMNS, lambda r: r["engine"], lambda r: (r["dim"], r["image"], r["engine"]),
                    lambda r: {f"{r['dim']}d_{r['image']}_{r['n']}_{r['engine']}"},
                    lambda tool: tool not in sweep_cubical.REFERENCES, sweep_cubical.summarize),
    "alpha": Kind("alpha", sweep_alpha.COLUMNS, lambda r: r["tool"], alpha_series, alpha_tags,
                  lambda tool: tool.startswith("tda4j"), sweep_alpha.summarize),
}
VALIDITY_TOOL = "tda4j-helix"  # validity.csv is the failure rate of the Helix triangulation


def kind_of(directory):
    """Which sweep wrote this directory, from the columns of its sweep.csv."""
    try:
        with open(Path(directory) / "sweep.csv", newline="") as f:
            header = set(next(csv.reader(f), []))
    except OSError:
        raise SystemExit(f"{directory} has no sweep.csv: not a sweep directory")
    for name, kind in KINDS.items():
        if header >= set(kind.columns) - {"radius"}:  # a sweep.csv from before the radius sweep has no such column
            return kind
    raise SystemExit(f"{directory}/sweep.csv has columns that match no sweep: {sorted(header)}")


def read_rows(directory):
    with open(Path(directory) / "sweep.csv", newline="") as f:
        return list(csv.DictReader(f))


def copy_files(src, out, wanted):
    """Copies the bars and logs of `src` whose stem is one of `wanted`, or one followed by `__<cold|warm>`."""
    for sub in ("bars", "logs"):
        if not (Path(src) / sub).is_dir():
            continue
        (out / sub).mkdir(parents=True, exist_ok=True)
        for entry in os.scandir(Path(src) / sub):
            stem = entry.name.rsplit(".", 1)[0]
            if stem in wanted or stem.rsplit("__", 1)[0] in wanted:
                shutil.copy2(entry.path, out / sub / entry.name)


def merge(out, sources, drop="", allow_mixed=False):
    """The merged sweep directory `out` from `sources` (see the module's doc). Returns each source's origin record."""
    out = Path(out)
    if (out / "sweep.csv").exists():
        raise SystemExit(f"{out} already holds a sweep: merge writes a new directory")
    parsed = [bench.parse_source(s) for s in sources]
    kinds = {kind_of(src).name: kind_of(src) for src, _ in parsed}
    if len(kinds) != 1:
        raise SystemExit(f"the sources are sweeps of different kinds ({', '.join(sorted(kinds))})")
    kind = next(iter(kinds.values()))

    def selected(tool, tools):
        return bench.tool_selected(tool, tools) and not (drop and bench.tool_selected(tool, drop))

    chosen = {}  # series -> (index of the source, its rows)
    order = {}   # series -> where it first appeared: the merged sweep keeps the earlier sweep's order
    for i, (src, tools) in enumerate(parsed):
        by = {}
        for r in read_rows(src):
            key = kind.series(r)
            order.setdefault(key, len(order))
            if selected(kind.tool(r), tools):
                by.setdefault(key, []).append(r)
        for key, rows in by.items():
            if key in chosen and all(r["status"] == "missing" for r in rows):
                continue
            chosen[key] = (i, rows)
    rules = {i: bench.read_env(parsed[i][0]).get("alpha_radius_rule", "") for i in range(len(parsed))
             if kind.name == "alpha" and any(j == i for j, _ in chosen.values())}
    if len(set(rules.values())) > 1 and not allow_mixed:
        raise SystemExit("these alpha sweeps gave their tools different radii: "
                         + "; ".join(f"{parsed[i][0]}: {rules[i] or 'GUDHI and the named backends none'}"
                                     for i in rules)
                         + ". Rerun the tools of the older one, or --allow-mixed to accept the difference.")
    (out / "bars").mkdir(parents=True, exist_ok=True)
    (out / "logs").mkdir(parents=True, exist_ok=True)
    with open(out / "sweep.csv", "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=kind.columns, extrasaction="ignore", restval="")
        w.writeheader()
        for key in sorted(chosen, key=order.__getitem__):
            w.writerows(chosen[key][1])
    origin, tools_by_source = [], []
    for i, (src, _) in enumerate(parsed):
        env = bench.read_env(src)
        mine = [key for key, (j, _) in chosen.items() if j == i]
        tools = sorted({kind.tool(r) for key in mine for r in chosen[key][1]})
        tools_by_source.append(tools)
        origin.append({"dir": str(src), "date": env.get("date", ""), "host": env.get("host", ""),
                       "tda4j_git": env.get("tda4j_git", ""), "tda4j_js_git": env.get("tda4j_js_git", ""),
                       "tools": tools, "keys": len(mine)})
        copy_files(src, out, {tag for key in mine for r in chosen[key][1] for tag in kind.tags(r)})
    last = max((i for i, tools in enumerate(tools_by_source) if any(kind.tda4j(t) for t in tools)),
               default=len(parsed) - 1)
    env = {**bench.read_env(parsed[last][0]), "merged_from": origin}
    if kind.name == "threads":  # the summary compares with the baselines any part ran, whichever part is the newest
        baselines = [b for src, _ in parsed
                     for b in str(bench.read_env(src).get("args", {}).get("baselines", "")).split(",") if b]
        env["args"] = {**env.get("args", {}), "baselines": ",".join(dict.fromkeys(baselines))}
    if kind.name == "alpha":
        distinct = set(rules.values())
        env["alpha_radius_rule"] = "mixed" if len(distinct) > 1 else next(iter(distinct), "")
        merge_validity(out, parsed, selected)
    with open(out / "env.json", "w") as f:
        json.dump(env, f, indent=1, default=str)
    kind.summarize(out)
    return origin


def merge_validity(out, parsed, selected):
    """validity.csv (the Helix failure rates of the alpha sweep): a (shape, d, n) cell from the last source that has it."""
    rows = {}
    for src, tools in parsed:
        path = Path(src) / "validity.csv"
        if path.exists() and selected(VALIDITY_TOOL, tools):
            with open(path, newline="") as f:
                for r in csv.DictReader(f):
                    rows[(r["shape"], r["d"], r["n"])] = r
    if rows:
        with open(out / "validity.csv", "w", newline="") as f:
            w = csv.DictWriter(f, fieldnames=sweep_alpha.VALIDITY_COLUMNS, extrasaction="ignore", restval="")
            w.writeheader()
            w.writerows(rows.values())


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("out", help="the merged sweep directory (must not hold a sweep.csv)")
    ap.add_argument("sources", nargs="+", metavar="DIR[:tools]",
                    help="sweep directories, the last with a series winning; `:tools` takes only those tools")
    ap.add_argument("--drop", default="", help="tools to leave out of the result, in --tools's notation")
    ap.add_argument("--allow-mixed", action="store_true",
                    help="merge alpha sweeps that gave their tools different radii (see above)")
    args = ap.parse_args()
    origin = merge(args.out, args.sources, args.drop, args.allow_mixed)
    for o in origin:
        print(f"{o['dir']}: {o['keys']} series ({', '.join(o['tools']) or 'none'})")
    print(f"merged sweep in {args.out}")


if __name__ == "__main__":
    main()
