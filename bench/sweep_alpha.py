#!/usr/bin/env python3
"""Dimension and point-count sweep for the alpha complex: does TDA4j's alpha hold up against other implementations, and
what do the ambient dimension and the point count cost each backend?

    bench/run.sh sweep-alpha [--dims 5,6,7,8,10,12,15,20,25] [--points 40,60] [--shapes interior,surface]
                             [--grid "11:40,100,300,900"] [--extra 800:11:interior] [--seeds 3] [--timeout 1800]
                             [--jvm-heap 64g] [--py-mem-gb 128] [--count] [--validity "interior:4:25:1000;..."]

`run.sh setup` must have run. The inputs are random points of the unit cube [0,1]^d, the kind the Helix thesis uses (as it
was described to us): n points uniform in the interior, or on the surface (a facet chosen uniformly, one coordinate fixed
to 0 or 1, the others uniform). `--points` and `--dims` give the sweep over d at fixed n; `--grid` the sweep over n at
fixed d (`d:n,n,..;d:n,..`); `--extra n:d:shape` single cells such as 800 points in R^11. Per cell every tool runs on
`--seeds` independent draws: cold, and warm when the cold run took under `--warm-cap` seconds. A tool's series stops at its
first timeout or out-of-memory (a series runs along d, or along n for a grid); a timeout costs `--timeout`.

`--path top|four|plane` runs one of the three sweeps the benchmark is read by: the whole barcode up to degree d - 1 in R^d (the
top degree is where the fast alpha engine's dual union-find needs no reduction), degrees up to 4 in R^d, and the plane with
the point count raised until each tool gives up. `--dim` sets the highest degree directly (a number, or `top`; at most d - 1).

Tools: `tda4j` (the default: Helix without a radius above 4-D), `tda4j-helix`, `tda4j-fastalpha` (Helix's triangulation, then
the dual union-find for the top degree and the cohomology engine for the degrees up to `--dim` below it),
`tda4j-fastalpha-top` (the top degree d - 1 alone, compared with the reference in that degree only), `tda4j-dqp` (the dual quadratic program: it needs no triangulation, so with no radius it builds every
candidate simplex up to dimension 3), `tda4j-cech`, `gudhi` (CGAL's Delaunay triangulation), and the Scala.js twins when
installed. Bowyer-Watson stops at 4-D, the sweep starts at 5. `--py-mem-gb` caps GUDHI's address space, so CGAL's allocation
at high d ends in `oom` and not in the kernel's killer; JVM and Node are not capped.

The reference for a barcode is the first of GUDHI, the Cech complex and the dual-QP that finished on the same draw (and is not
the tool itself). GUDHI is the other implementation, but it stops early; the Cech complex (minimum enclosing balls, no
triangulation) and the dual-QP (a quadratic program per simplex) are different algorithms from the Delaunay walk, and by
the nerve lemma the Cech and alpha complexes have the same barcode in general position, so agreement of three algorithms
to rounding is a check at dimensions where GUDHI cannot run. Nothing from another paper's tables is used here: the
timings of other implementations were measured in other settings.

Degenerate draws, marked † where it changes a distance: a point on a facet of the cube shares a coordinate with the others on
it, and d + 1 points on one facet lie in a hyperplane, which breaks general position (for 40 points it happens in about 9
of 10 draws at d = 5, 4 of 10 at d = 6, and not from d = 8). Points on a sphere are not sampled: they are all cospherical,
and every Delaunay-based tool then triangulates the one degenerate cell its own way.

`--count` also counts the cells (dimension 0 to dim + 1) of the complex TDA4j's stream and GUDHI's simplex tree build on
each draw (the `cells` column and a table in sweep.md), so time can be set against the size of the triangulation.
`--validity shape:d:n:draws;..` runs the Helix failure rate in one JVM per cell (`PaperBenchmarkDriver task=helix-validity`,
its own draws): the clouds whose triangulation fails the fast alpha engine's checks, those where
`requireValidTriangulation` throws, and those where the walk fell back to its slow search; validity.csv and a table in
sweep.md. A rate of 1 in 170 needs on the order of 1000 draws per cell.

Output, in --out (default $WORKDIR/sweeps/alpha-<timestamp>): sweep.csv (one row per run), bars/, logs/, sweep.md (time,
memory, and the distance to the reference barcode). `sweep_alpha.py --summarize DIR` rebuilds the tables.
"""
import argparse
import csv
import datetime
import json
import os
import statistics
import sys
from argparse import Namespace
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import bench  # noqa: E402  (Case, TOOLS, command, run_proc, last_json, available, agreement, read_bars)
import count_cells  # noqa: E402  (count_tda4j, count_gudhi: the size of the complex a draw produces)

TOOL_NAMES = ["tda4j", "tda4j-helix", "tda4j-fastalpha", "tda4j-fastalpha-top", "tda4j-dqp", "tda4j-cech", "gudhi",
              "tda4j-js", "tda4j-js-helix", "tda4j-js-fastalpha", "tda4j-js-dqp"]
TOP_ONLY = ("tda4j-fastalpha-top",)  # the tools that report only degree d - 1
REFERENCES = ["gudhi", "tda4j-cech", "tda4j-dqp"]  # the barcode a row is compared with: the first that finished
COLUMNS = ["shape", "n", "d", "series", "seed", "tool", "status", "reason", "degenerate", "densest_facet", "cold_wall_s",
           "cold_s", "warm_median_s", "rss_mb", "rss_src", "heap_peak_mb", "old_peak_mb", "bars", "cells"]
COUNTED = ("tda4j", "gudhi")  # the tools whose complex is counted per draw: TDA4j's own stream and GUDHI's simplex tree


def points(shape, n, d, seed):
    """n points uniform in the interior of [0,1]^d, or on its surface. Deterministic in (shape, n, d, seed)."""
    rng = np.random.default_rng([20261018, 1 if shape == "surface" else 0, n, d, seed])
    x = rng.random((n, d))
    if shape == "surface":
        facet = rng.integers(0, 2 * d, size=n)  # the 2d facets have the same (d-1)-volume: uniform on the surface
        x[np.arange(n), facet // 2] = (facet % 2).astype(float)
    return x


def densest_facet(x):
    """The most points on one facet. d + 1 of them lie in a hyperplane (a (d-1)-flat): not general position."""
    return max(int((x[:, i] == v).sum()) for i in range(x.shape[1]) for v in (0.0, 1.0))


def is_degenerate(x):
    return densest_facet(x) >= x.shape[1] + 1


def parse_extra(text):
    """`800:11:interior,100:8:surface` -> [(800, 11, 'interior'), (100, 8, 'surface')]."""
    out = []
    for item in [t for t in text.split(",") if t.strip()]:
        n, d, shape = item.split(":")
        assert shape in ("interior", "surface"), f"--extra {item}: the shape is interior or surface"
        out.append((int(n), int(d), shape))
    return out


def parse_grid(text):
    """`11:40,100;14:40` -> [(11, [40, 100]), (14, [40])]: a dimension and the point counts to run in it."""
    out = []
    for group in [g for g in text.split(";") if g.strip()]:
        d, ns = group.split(":")
        out.append((int(d), [int(n) for n in ns.split(",")]))
    return out


def degree_for(dim, d):
    """The highest homological degree of a cell in R^d: `--dim top` is d - 1, the top degree of an alpha complex, and no
    number asks for more than that (an alpha complex of points in R^d has no homology above d - 1)."""
    return d - 1 if dim == "top" else min(int(dim), d - 1)


# `--path`: the three sweeps the alpha benchmark is read by. Each sets the options still at their defaults.
PATHS = {
    # the whole barcode, up to the top degree d - 1: where the fast alpha engine's dual union-find does the top degree
    "top": {"dim": "top", "dims": "3,4,5,6,7,8,9,10,12", "points": "40,60"},
    # degrees up to 4 in R^d: more than the default sweep's 2, less than the whole barcode
    "four": {"dim": "4", "dims": "5,6,7,8,10,12,15", "points": "40,60"},
    # the plane, the point count raised until each tool gives up: the case the dual union-find needs no reduction for
    "plane": {"dim": "top", "dims": "", "points": "", "shapes": "interior",
              "grid": "2:1000,3000,10000,30000,100000,300000,1000000,3000000"},
}


def run_cell(name, kind, opts, shape, n, d, seed, series, args, bench_args, out):
    """One tool on one draw: a cold run, then a warm one if the cold computation was quick. Returns a row."""
    x = points(shape, n, d, seed)
    rel = f"sweepalpha/{shape}_{n}_{d}_{seed}.txt"
    path = Path(args.data) / rel
    if not path.exists():
        np.savetxt(path, x, fmt="%.17g")
    task = "cech" if name == "tda4j-cech" else "alpha"
    case = bench.Case(f"{shape}{d}d_{n}_s{seed}", task, rel, "points", degree_for(args.dim, d))
    bars = out / "bars" / f"{shape}_{n}_{d}_{seed}__{name}.tsv"
    tag = f"{shape}_{n}_{d}_{seed}__{name}"
    caps = {"address_space_mb": args.py_mem_gb * 1024} if kind == "py" and args.py_mem_gb else {}
    data = Path(args.data)
    cold = bench.run_proc(bench.command(kind, name, opts, case, data, args.field, None, 0, 1, bars, bench_args),
                          args.timeout, out / "logs" / (tag + "__cold"), **caps)
    j = bench.last_json(cold.text) if cold.status == "ok" else None
    row = {"shape": shape, "n": n, "d": d, "series": series or "", "seed": seed, "tool": name, "status": cold.status,
           "reason": cold.reason, "degenerate": str(is_degenerate(x)), "densest_facet": densest_facet(x),
           "cold_wall_s": f"{cold.wall:.3f}", "cold_s": f"{j['times_s'][0]:.4f}" if j else "", "warm_median_s": "",
           "rss_mb": f"{cold.rss_mb:.0f}", "rss_src": cold.rss_src,
           "heap_peak_mb": j.get("heap_peak_mb", "") if j else "", "old_peak_mb": j.get("old_peak_mb", "") if j else "",
           "bars": json.dumps(j["bars"]) if j else "", "cells": ""}
    if j and j["times_s"][0] < args.warm_cap and args.trials > 0:
        warm = bench.run_proc(bench.command(kind, name, opts, case, data, args.field, None, 1, args.trials, bars,
                                            bench_args), args.timeout * (args.trials + 1),
                              out / "logs" / (tag + "__warm"), **caps)
        jw = bench.last_json(warm.text) if warm.status == "ok" else None
        if jw:
            row["warm_median_s"] = f"{statistics.median(jw['times_s']):.4f}"
            row["rss_mb"] = f"{max(cold.rss_mb, warm.rss_mb):.0f}"
            for key in ("heap_peak_mb", "old_peak_mb"):
                row[key] = max(float(row[key] or 0), float(jw.get(key, 0))) or ""
        elif warm.status != "ok":
            row["status"], row["reason"] = warm.status, "warm run: " + warm.reason
    if row["status"] == "ok" and name in COUNTED and args.count:
        row["cells"] = cell_counts(name, case, data / rel, args, out)
    return row


def cell_counts(name, case, path, args, out):
    """The cells of dimension 0 to dim + 1 of the complex `name` built on this draw (`count_cells.py`: TDA4j's stream
    iterated and not reduced, or GUDHI's simplex tree), as JSON; empty if the count did not finish."""
    cargs = Namespace(python=args.python, timeout=args.timeout, jvm_heap=args.jvm_heap, jvm_opts=args.jvm_opts,
                      jvm_fixed_heap=args.jvm_fixed_heap, gudhi_python_ok=True, gudhi_mem_gb=args.py_mem_gb)
    count = count_cells.count_gudhi if name == "gudhi" else count_cells.count_tda4j
    r = count(case, path, None, cargs, out)
    return r["cells"] if r and r["status"] == "ok" else ""


def validity_runs(args, out):
    """`--validity shape:d:n:draws;..`: the failure rate of the Helix triangulation, one JVM per cell (driver mode
    `task=helix-validity`). Rows go to validity.csv; the draws are the driver's own, not the sweep's."""
    cp = Path(os.environ["TDA4J_CP"]).read_text().strip()
    rows = []
    for item in [t for t in args.validity.split(";") if t.strip()]:
        shape, d, n, draws = item.split(":")
        cmd = (["java"] + bench.jvm_flags(args.jvm_heap, args.jvm_opts, args.jvm_fixed_heap) +
               ["-cp", cp, "org.appliedtopology.tda4j.PaperBenchmarkDriver", "task=helix-validity",
                f"shape={shape}", f"d={d}", f"n={n}", f"draws={draws}"])
        proc = bench.run_proc(cmd, args.timeout * 4, out / "logs" / f"validity_{shape}_{d}_{n}")
        j = bench.last_json(proc.text) if proc.status == "ok" else None
        rows.append({"shape": shape, "d": d, "n": n, "draws": draws, "status": proc.status,
                     "invalid": j["invalid"] if j else "", "repair_threw": j["repair_threw"] if j else "",
                     "fallback_draws": j["fallback_draws"] if j else "", "seconds": f"{proc.wall:.0f}"})
        print(f"validity {item}: {rows[-1]}", flush=True)
    with open(out / "validity.csv", "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["shape", "d", "n", "draws", "status", "invalid", "repair_threw",
                                          "fallback_draws", "seconds"])
        w.writeheader()
        w.writerows(rows)


def plan(args):
    """The cells in run order: (shape, n, d, series). `series` names what grows along a tool's run: "d", or "n" for a
    grid; it stops at its first timeout. A cell with series None (`--extra`) always runs."""
    cells = []
    shapes = [s for s in args.shapes.split(",") if s]
    for shape in shapes:
        for n in [int(x) for x in args.points.split(",") if x]:
            cells += [(shape, n, d, ("d", shape, n)) for d in [int(x) for x in args.dims.split(",") if x]]
    for shape in shapes:
        for d, ns in parse_grid(args.grid):
            cells += [(shape, n, d, ("n", shape, d)) for n in ns]
    cells += [(shape, n, d, None) for n, d, shape in parse_extra(args.extra)]
    return cells


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--path", default="", choices=[""] + list(PATHS),
                    help="a preset sweep: `top` (degrees up to d - 1 in R^d), `four` (degrees up to 4 in R^d), `plane` "
                         "(R^2, the point count raised until each tool gives up); it sets the options you did not give")
    ap.add_argument("--dims", default="5,6,7,8,10,12,15,20,25", help="the dimensions of the sweep over d")
    ap.add_argument("--points", default="40,60", help="the point counts of the sweep over d")
    ap.add_argument("--shapes", default="interior,surface")
    ap.add_argument("--grid", default="", help="sweeps over n at fixed d: d:n,n,..;d:n,.. (default: none)")
    ap.add_argument("--extra", default="", help="single cells n:d:shape, run on their own, e.g. 800:11:interior")
    ap.add_argument("--seeds", type=int, default=3)
    ap.add_argument("--dim", default="2",
                    help="the highest homological degree: a number, or `top` for d - 1 in R^d (the whole barcode, "
                         "where the fast alpha engine's dual union-find does the top degree)")
    ap.add_argument("--field", type=int, default=17)
    ap.add_argument("--trials", type=int, default=3)
    ap.add_argument("--count", action="store_true",
                    help="also count the cells of each draw's complex for tda4j and gudhi (the `cells` column)")
    ap.add_argument("--validity", default="",
                    help="Helix failure rates, shape:d:n:draws;.. e.g. interior:4:25:1000;interior:6:40:300")
    ap.add_argument("--timeout", type=float, default=1800)
    ap.add_argument("--warm-cap", type=float, default=30,
                    help="repeat a computation (warm-up + trials) only if one cold run took less than this (s)")
    ap.add_argument("--jvm-heap", default="64g", help="-Xmx of the JVM tools")
    ap.add_argument("--jvm-opts", default="")
    ap.add_argument("--jvm-fixed-heap", action="store_true",
                    help="-Xmx = -Xms with the parallel collector instead of the default serial collector with a heap "
                         "that follows the live data")
    ap.add_argument("--node-heap", default="")
    ap.add_argument("--node-opts", default="")
    ap.add_argument("--py-mem-gb", type=int, default=128, help="cap GUDHI's address space (GB); 0: no cap")
    ap.add_argument("--no-js", action="store_true", help="skip the Scala.js build of TDA4j")
    ap.add_argument("--tools", default="", help="comma-separated tool names (default: all available)")
    ap.add_argument("--out", default="")
    ap.add_argument("--data", default="")
    ap.add_argument("--python", default=sys.executable)
    ap.add_argument("--summarize", default="", help="rebuild the tables of an existing sweep directory and stop")
    args = ap.parse_args()
    for key, value in PATHS.get(args.path, {}).items():
        if getattr(args, key) == ap.get_default(key):
            setattr(args, key, value)
    if args.summarize:
        summarize(Path(args.summarize))
        return
    if not args.data:
        ap.error("--data is required")

    out = Path(args.out or Path(os.environ.get("WORKDIR", ".")) / "sweeps" /
               f"alpha-{datetime.datetime.now():%Y%m%d-%H%M%S}")
    (out / "logs").mkdir(parents=True, exist_ok=True)
    (out / "bars").mkdir(parents=True, exist_ok=True)
    (Path(args.data) / "sweepalpha").mkdir(parents=True, exist_ok=True)
    wanted = set(args.tools.split(",")) if args.tools else None
    tools = []
    for name, kind, task, opts in bench.TOOLS:
        if task not in ("alpha", "cech") or name not in TOOL_NAMES or (wanted and name not in wanted):
            continue
        if kind == "tda4j-js" and args.no_js:
            continue
        if any(t[0] == name for t in tools):  # `gudhi` is listed for both the alpha and the cech task: one row, not two
            continue
        opts = dict(opts, pytool=name)
        if not bench.available(kind, opts, args.python):
            print(f"{name} is not installed: left out", flush=True)
            continue
        tools.append((name, kind, opts))
    bench_args = Namespace(jvm_heap=args.jvm_heap, jvm_opts=args.jvm_opts, jvm_fixed_heap=args.jvm_fixed_heap,
                           node_heap=args.node_heap,
                           node_opts=args.node_opts, python=args.python, javaplex_divisions=1000)
    stopped = set()  # (series, tool): timed out or ran out of memory at a smaller cell of the series
    with open(out / "sweep.csv", "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=COLUMNS)
        w.writeheader()
        for shape, n, d, series in plan(args):
            for name, kind, opts in tools:
                if series is not None and (series, name) in stopped:
                    continue
                for seed in range(args.seeds):
                    row = run_cell(name, kind, opts, shape, n, d, seed, series[0] if series else None, args,
                                   bench_args, out)
                    w.writerow(row)
                    f.flush()
                    best = row["warm_median_s"] or row["cold_s"]
                    print(f"{shape:8s} n={n:<4d} d={d:<3d} seed={seed} {name:20s} {row['status']:12s} "
                          f"{(best + ' s') if best else row['reason'][:70]}  rss={row['rss_mb']}MB", flush=True)
                    if row["status"] in ("timeout", "oom"):
                        if series is not None:
                            stopped.add((series, name))
                        break
    if args.validity:
        validity_runs(args, out)
    summarize(out)
    print(f"\nresults in {out}")


def summarize(out, bottleneck=None):
    """sweep.md: the median time, the memory and the distance to the reference barcode: one table per n with d down the
    rows for the sweeps over d, one per d with n down the rows for a grid."""
    with open(out / "sweep.csv") as f:
        rows = list(csv.DictReader(f))
    tools = list(dict.fromkeys(r["tool"] for r in rows))
    cells = list(dict.fromkeys((r["shape"], int(r["n"]), int(r["d"]), r["series"]) for r in rows))
    by = {}
    for r in rows:
        by.setdefault((r["shape"], int(r["n"]), int(r["d"]), r["tool"]), []).append(r)

    def seconds(r):
        return float(r["warm_median_s"] or r["cold_s"]) if (r["warm_median_s"] or r["cold_s"]) else None

    def time_cell(shape, n, d, tool, rs):
        ok = [r for r in rs if r["status"] == "ok" and seconds(r) is not None]
        if not ok:
            return rs[0]["status"] if rs else ""
        text = f"{statistics.median(seconds(r) for r in ok):.3g}"
        return text if len(ok) == len(rs) else f"{text} ({len(ok)}/{len(rs)})"

    def memory_cell(shape, n, d, tool, rs):
        ok = [r for r in rs if r["status"] == "ok"]
        if not ok:
            return ""
        rss = max(float(r["rss_mb"]) for r in ok)
        heaps = [float(r["heap_peak_mb"]) for r in ok if r["heap_peak_mb"] not in ("", None)]
        mark = "≤" if any(r["rss_src"] == "rusage" for r in ok) else ""
        return f"{mark}{rss:.0f}" + (f" ({max(heaps):.0f})" if heaps else "")

    def reference_for(shape, n, d, tool, seed):
        """The first reference other than `tool` that finished on this draw: (name, its barcode) or None."""
        for ref in REFERENCES:
            if ref != tool and any(x["seed"] == seed and x["status"] == "ok" for x in by.get((shape, n, d, ref), [])):
                bars = bench.read_bars(out / "bars" / f"{shape}_{n}_{d}_{seed}__{ref}.tsv")
                if bars is not None:
                    return ref, bars
        return None

    def distance_cell(shape, n, d, tool, rs):
        worst, ref_name, dagger = None, "", False
        for r in rs:
            found = reference_for(shape, n, d, tool, r["seed"]) if r["status"] == "ok" else None
            mine = bench.read_bars(out / "bars" / f"{shape}_{n}_{d}_{r['seed']}__{tool}.tsv") if found else None
            if mine is None:
                continue
            ref, theirs = found
            try:
                only = [d - 1] if tool in TOP_ONLY else None  # a top-degree-only tool is compared in that degree
                dist, _ = bench.agreement(mine, theirs, max(list(mine) + list(theirs) + [0]), bottleneck, only)
            except ImportError:  # the bottleneck distance is GUDHI's: the times and memory tables do not need it
                return "needs gudhi"
            if worst is None or dist > worst:
                worst, ref_name = dist, ref
            dagger = dagger or r["degenerate"] == "True"
        if worst is None:
            return ""
        text = "0" if worst == 0 else f"{worst:.2g}"
        return f"{text}{'†' if dagger and worst > 1e-5 else ''} ({ref_name})"

    def group_of(shape, n, d, series):
        """(title, row label) of a cell."""
        if series == "n":
            return f"{shape} points of the unit cube, d = {d}", f"n = {n}"
        return f"{shape} points of the unit cube, n = {n}", f"d = {d}"

    def cells_cell(shape, n, d, tool, rs):
        """Total cells of the counted complex (median over draws), by dimension of the first draw."""
        counted = [r for r in rs if r.get("cells")]
        if not counted:
            return ""
        totals = [sum(json.loads(r["cells"]).values()) for r in counted]
        return f"{int(statistics.median(totals)):,}"

    lines = ["# Alpha complex: dimension and point-count sweep", "",
             "Each cell is the median over the draws that finished (`ok/draws` if some did not; otherwise the status). "
             "† marks a degenerate draw: d + 1 points on one facet of the cube.", ""]
    for title, cell in [("Time in seconds (warm median; the cold computation if it was too slow to repeat)", time_cell),
                        ("Peak memory in MB: RSS (JVM heap pools' peak in parentheses)", memory_cell),
                        ("Largest bottleneck distance to the reference barcode (in parentheses)", distance_cell)] + \
                       ([("Cells of the complex up to dimension dim + 1 (median over draws; tda4j: its own stream, "
                          "gudhi: the simplex tree)", cells_cell)] if any(r.get("cells") for r in rows) else []):
        lines += [f"## {title}", ""]
        for group in dict.fromkeys(group_of(*c)[0] for c in cells):
            members = [c for c in cells if group_of(*c)[0] == group]
            lines += [f"### {group}", "", "| | " + " | ".join(tools) + " |", "|---|" + "---|" * len(tools)]
            for s, n, d, series in members:
                degenerate = any(r["degenerate"] == "True" for t in tools for r in by.get((s, n, d, t), []))
                label = group_of(s, n, d, series)[1] + ("†" if degenerate else "")
                body = " | ".join(cell(s, n, d, t, by.get((s, n, d, t), [])) for t in tools)
                lines.append(f"| {label} | {body} |")
            lines.append("")
    if (out / "validity.csv").exists():
        with open(out / "validity.csv") as f:
            vrows = list(csv.DictReader(f))
        lines += ["## Helix triangulation failure rate", "",
                  "Clouds whose triangulation fails the fast alpha engine's checks (`invalid`), for which "
                  "`requireValidTriangulation` throws (`repair threw`), and where the walk used its slow fallback.", "",
                  "| shape | d | n | draws | invalid | repair threw | fallback | status |", "|---|---|---|---|---|---|---|---|"]
        lines += [f"| {v['shape']} | {v['d']} | {v['n']} | {v['draws']} | {v['invalid']} | {v['repair_threw']} | "
                  f"{v['fallback_draws']} | {v['status']} |" for v in vrows]
        lines.append("")
    (out / "sweep.md").write_text("\n".join(lines) + "\n")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
