#!/usr/bin/env python3
"""Dimension sweep for the alpha complex: what do the ambient dimension and the point count cost each backend?

    bench/run.sh sweep-alpha [--dims 5,6,7,8,10,12,15,20,25] [--points 40,60] [--shapes interior,surface,sphere]
                             [--spheres "11:40,100,300,900;14:40,75,125;17:40,60,75"] [--seeds 3] [--timeout 1800]
                             [--jvm-heap 64g] [--py-mem-gb 128] [--extra 800:11:interior]

`run.sh setup` must have run. The workloads are the Helix thesis's and paper's, as they were described to us (neither
was read):
  - `interior`, `surface`: n points drawn uniformly at random in the interior of the unit cube [0,1]^d, or on its
    surface (a facet chosen uniformly, one coordinate fixed to 0 or 1, the others uniform). n = 40 and 60 over a range
    of dimensions (`--points`, `--dims`); the heaviest case is 800 points in R^11 (`--extra 800:11:interior`).
  - `sphere`: Tadasets' `dsphere(n, d)`, n points on the d-sphere in R^(d+1) (standard normal vectors, normalized),
    for d = 11, 14, 17 with n from 40 up to 900, 125 and 75 (`--spheres`, d:n,n,..;d:n,..). Helix's serial times for
    the 40-point ones were reported as 0.18, 1.58 and 7.87 minutes: the time table has them in a column of their own
    (`THESIS_HELIX_SECONDS`; their machine is not ours).
Per cell every tool runs on `--seeds` independent draws: cold, and warm when the cold run took under `--warm-cap`
seconds. A tool's series stops at its first timeout or out-of-memory: along d for the cubes (the cost grows with d over
this range) and along n for the spheres; a timeout costs `--timeout`.

Tools: `tda4j` (the default: Helix without a radius above 4-D), `tda4j-helix`, `tda4j-fastalpha` (Helix's
triangulation, then the dual union-find), `tda4j-dqp` (the dual quadratic program: it needs no triangulation, so with
no radius it builds every candidate simplex up to dimension 3), `gudhi` (CGAL's Delaunay triangulation), and the
Scala.js twins when installed. Bowyer-Watson stops at 4-D, the sweep starts at 5. `--py-mem-gb` caps GUDHI's address
space, so CGAL's allocation at high d ends in `oom` and not in the kernel's killer; JVM and Node are not capped.

Degenerate draws, marked † where it changes a distance: a point on a facet of the cube shares a coordinate with the others
on it, and d + 1 points on one facet lie in a hyperplane, which breaks general position (for 40 points it happens in
about 9 of 10 draws at d = 5, 4 of 10 at d = 6, and not from d = 8). Points on a sphere are all cospherical (up to
rounding), so Helix treats them as one cluster, DQP keeps the simplex they span, and CGAL, which is exact on the rounded
doubles, triangulates by the rounding: the three barcodes need not agree and GUDHI is no ground truth.

Output, in --out (default $WORKDIR/sweeps/alpha-<timestamp>): sweep.csv (one row per run), bars/, logs/, sweep.md
(time, memory, and the distance to the reference barcode: GUDHI's, else the dual-QP's).
`sweep_alpha.py --summarize DIR` rebuilds the tables.
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

TOOL_NAMES = ["tda4j", "tda4j-helix", "tda4j-fastalpha", "tda4j-dqp", "gudhi",
              "tda4j-js", "tda4j-js-helix", "tda4j-js-fastalpha", "tda4j-js-dqp"]
REFERENCES = ["gudhi", "tda4j-dqp"]  # the barcode a row is compared with: the first that finished on the same draw
DEFAULT_SPHERES = "11:40,100,300,900;14:40,75,125;17:40,60,75"
# Helix's serial time on 40 points of the d-sphere, as reported to us: 0.18, 1.58 and 7.87 minutes. Another machine and
# another implementation; the point of the column is the order of magnitude and the slope in d.
THESIS_HELIX_SECONDS = {("sphere", 40, 11): 0.18 * 60, ("sphere", 40, 14): 1.58 * 60, ("sphere", 40, 17): 7.87 * 60}
COLUMNS = ["shape", "n", "d", "ambient", "seed", "tool", "status", "reason", "degenerate", "densest_facet",
           "cold_wall_s", "cold_s", "warm_median_s", "rss_mb", "rss_src", "heap_peak_mb", "old_peak_mb", "bars"]


def ambient_of(shape, d):
    """The number of coordinates: d for the cubes, d + 1 for the d-sphere (Tadasets' convention)."""
    return d + 1 if shape == "sphere" else d


def points(shape, n, d, seed):
    """The draw. Deterministic in (shape, n, d, seed)."""
    if shape == "sphere":
        return sphere_points(n, d, seed)
    return cube_points(shape, n, d, seed)


def cube_points(shape, n, d, seed):
    """n points uniform in the interior of [0,1]^d, or on its surface."""
    rng = np.random.default_rng([20261018, 1 if shape == "surface" else 0, n, d, seed])
    x = rng.random((n, d))
    if shape == "surface":
        facet = rng.integers(0, 2 * d, size=n)  # the 2d facets have the same (d-1)-volume: uniform on the surface
        x[np.arange(n), facet // 2] = (facet % 2).astype(float)
    return x


def sphere_points(n, d, seed):
    """n points on the d-sphere in R^(d+1), the way Tadasets' `dsphere` does it (version 0.2.2, no noise, no
    embedding): standard normal vectors, normalized."""
    x = np.random.default_rng([20261018, 2, n, d, seed]).standard_normal((n, d + 1))
    return x / np.sqrt((x ** 2).sum(axis=1))[:, None]


def densest_facet(x):
    """The most points on one facet. d + 1 of them lie in a hyperplane (a (d-1)-flat): not general position."""
    return max(int((x[:, i] == v).sum()) for i in range(x.shape[1]) for v in (0.0, 1.0))


def is_degenerate(shape, x):
    """Cospherical (a sphere) or d + 1 points in one hyperplane (the cube's surface): not in general position."""
    if shape == "sphere":
        return True
    return densest_facet(x) >= x.shape[1] + 1


def parse_extra(text):
    """`800:11:interior,100:8:surface` -> [(800, 11, 'interior'), (100, 8, 'surface')]."""
    out = []
    for item in [t for t in text.split(",") if t.strip()]:
        n, d, shape = item.split(":")
        assert shape in ("interior", "surface", "sphere"), f"--extra {item}: interior, surface or sphere"
        out.append((int(n), int(d), shape))
    return out


def parse_spheres(text):
    """`11:40,100;14:40` -> [(11, [40, 100]), (14, [40])]: the sphere dimension and its point counts."""
    out = []
    for group in [g for g in text.split(";") if g.strip()]:
        d, ns = group.split(":")
        out.append((int(d), [int(n) for n in ns.split(",")]))
    return out


def run_cell(name, kind, opts, shape, n, d, seed, args, bench_args, out):
    """One tool on one draw: a cold run, then a warm one if the cold computation was quick. Returns a row."""
    x = points(shape, n, d, seed)
    rel = f"sweepalpha/{shape}_{n}_{d}_{seed}.txt"
    path = Path(args.data) / rel
    if not path.exists():
        np.savetxt(path, x, fmt="%.17g")
    case = bench.Case(f"{shape}{d}d_{n}_s{seed}", "alpha", rel, "points", args.dim)
    bars = out / "bars" / f"{shape}_{n}_{d}_{seed}__{name}.tsv"
    tag = f"{shape}_{n}_{d}_{seed}__{name}"
    caps = {"address_space_mb": args.py_mem_gb * 1024} if kind == "py" and args.py_mem_gb else {}
    data = Path(args.data)
    cold = bench.run_proc(bench.command(kind, name, opts, case, data, args.field, None, 0, 1, bars, bench_args),
                          args.timeout, out / "logs" / (tag + "__cold"), **caps)
    j = bench.last_json(cold.text) if cold.status == "ok" else None
    row = {"shape": shape, "n": n, "d": d, "ambient": ambient_of(shape, d), "seed": seed, "tool": name,
           "status": cold.status, "reason": cold.reason, "degenerate": str(is_degenerate(shape, x)),
           "densest_facet": "" if shape == "sphere" else densest_facet(x), "cold_wall_s": f"{cold.wall:.3f}",
           "cold_s": f"{j['times_s'][0]:.4f}" if j else "", "warm_median_s": "", "rss_mb": f"{cold.rss_mb:.0f}",
           "rss_src": cold.rss_src, "heap_peak_mb": j.get("heap_peak_mb", "") if j else "",
           "old_peak_mb": j.get("old_peak_mb", "") if j else "", "bars": json.dumps(j["bars"]) if j else ""}
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
    return row


def plan(args):
    """The cells in run order: (shape, n, d, series). A tool stops at its first timeout within a series; a cell with
    series None (`--extra`) always runs."""
    cells = []
    for shape in [s for s in args.shapes.split(",") if s in ("interior", "surface")]:
        for n in [int(x) for x in args.points.split(",")]:
            cells += [(shape, n, d, (shape, "n", n)) for d in [int(x) for x in args.dims.split(",")]]
    if "sphere" in args.shapes.split(","):
        for d, ns in parse_spheres(args.spheres):
            cells += [("sphere", n, d, ("sphere", "d", d)) for n in ns]
    cells += [(shape, n, d, None) for n, d, shape in parse_extra(args.extra)]
    return cells


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dims", default="5,6,7,8,10,12,15,20,25", help="the cube's dimensions")
    ap.add_argument("--points", default="40,60", help="the cube's point counts")
    ap.add_argument("--shapes", default="interior,surface,sphere")
    ap.add_argument("--spheres", default=DEFAULT_SPHERES, help="sphere dimension:point counts, separated by ;")
    ap.add_argument("--extra", default="", help="single cells n:d:shape, run on their own, e.g. 800:11:interior")
    ap.add_argument("--seeds", type=int, default=3)
    ap.add_argument("--dim", type=int, default=2, help="the highest homological degree")
    ap.add_argument("--field", type=int, default=17)
    ap.add_argument("--trials", type=int, default=3)
    ap.add_argument("--timeout", type=float, default=1800)
    ap.add_argument("--warm-cap", type=float, default=30,
                    help="repeat a computation (warm-up + trials) only if one cold run took less than this (s)")
    ap.add_argument("--jvm-heap", default="64g", help="-Xmx and -Xms of the JVM tools")
    ap.add_argument("--jvm-opts", default="")
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
        if task != "alpha" or name not in TOOL_NAMES or (wanted and name not in wanted):
            continue
        if kind == "tda4j-js" and args.no_js:
            continue
        opts = dict(opts, pytool=name)
        if not bench.available(kind, opts, args.python):
            print(f"{name} is not installed: left out", flush=True)
            continue
        tools.append((name, kind, opts))
    bench_args = Namespace(jvm_heap=args.jvm_heap, jvm_opts=args.jvm_opts, node_heap=args.node_heap,
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
                    row = run_cell(name, kind, opts, shape, n, d, seed, args, bench_args, out)
                    w.writerow(row)
                    f.flush()
                    best = row["warm_median_s"] or row["cold_s"]
                    print(f"{shape:8s} n={n:<4d} d={d:<3d} seed={seed} {name:20s} {row['status']:12s} "
                          f"{(best + ' s') if best else row['reason'][:70]}  rss={row['rss_mb']}MB", flush=True)
                    if row["status"] in ("timeout", "oom"):
                        if series is not None:
                            stopped.add((series, name))
                        break
    summarize(out)
    print(f"\nresults in {out}")


def summarize(out, bottleneck=None):
    """sweep.md: the median time, the memory and the distance to the reference barcode, by dimension for the cubes
    (one table per n) and by point count for the spheres (one table per d)."""
    with open(out / "sweep.csv") as f:
        rows = list(csv.DictReader(f))
    tools = list(dict.fromkeys(r["tool"] for r in rows))
    cells = list(dict.fromkeys((r["shape"], int(r["n"]), int(r["d"])) for r in rows))
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
                dist, _ = bench.agreement(mine, theirs, max(list(mine) + list(theirs) + [0]), bottleneck)
            except ImportError:  # the bottleneck distance is GUDHI's: the times and memory tables do not need it
                return "needs gudhi"
            if worst is None or dist > worst:
                worst, ref_name = dist, ref
            dagger = dagger or r["degenerate"] == "True"
        if worst is None:
            return ""
        text = "0" if worst == 0 else f"{worst:.2g}"
        return f"{text}{'†' if dagger and worst > 1e-5 else ''} ({ref_name})"

    def group_of(shape, n, d):
        """(title, row label) of a cell: the spheres are tabled by d with n down the rows, the cubes by n with d."""
        if shape == "sphere":
            return f"sphere S^{d} in R^{d + 1}", f"n = {n}"
        return f"{shape} points of the unit cube, n = {n}", f"d = {d}"

    lines = ["# Alpha complex: dimension and point-count sweep", "",
             "Each cell is the median over the draws that finished (`ok/draws` if some did not; otherwise the status). "
             "† marks a degenerate draw: d + 1 points on one facet of the cube, or points on a sphere.", ""]
    for title, cell, anchors in [("Time in seconds (warm median; the cold computation if it was too slow to repeat)",
                                  time_cell, True),
                                 ("Peak memory in MB: RSS (JVM heap pools' peak in parentheses)", memory_cell, False),
                                 ("Largest bottleneck distance to the reference barcode (in parentheses)",
                                  distance_cell, False)]:
        lines += [f"## {title}", ""]
        for group in dict.fromkeys(group_of(*c)[0] for c in cells):
            members = [c for c in cells if group_of(*c)[0] == group]
            show = anchors and any((s, n, d) in THESIS_HELIX_SECONDS for s, n, d in members)
            header = "| | " + " | ".join(tools) + (" | Helix, thesis (serial) |" if show else " |")
            lines += [f"### {group}", "", header, "|---|" + "---|" * (len(tools) + (1 if show else 0))]
            for s, n, d in members:
                degenerate = any(r["degenerate"] == "True" for t in tools for r in by.get((s, n, d, t), []))
                label = group_of(s, n, d)[1] + ("†" if degenerate else "")
                body = " | ".join(cell(s, n, d, t, by.get((s, n, d, t), [])) for t in tools)
                anchor = f" | {THESIS_HELIX_SECONDS[(s, n, d)]:.3g}" if show and (s, n, d) in THESIS_HELIX_SECONDS else (
                    " | " if show else "")
                lines.append(f"| {label} | {body}{anchor} |")
            lines.append("")
    (out / "sweep.md").write_text("\n".join(lines) + "\n")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
