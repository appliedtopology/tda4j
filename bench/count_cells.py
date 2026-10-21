#!/usr/bin/env python3
"""Counts the cells of the complex every benchmark case reduces: the size of the problem, by dimension.

    count_cells.py --data DATA --out DIR [--cases a,b] [--tasks vr,cubical,alpha] [--timeout 3600] [--threads N]
    count_cells.py --data DATA --out DIR --join RESULTS      add the totals to RESULTS/summary.csv

The unit is the cells of dimension 0 to dim + 1 of the complex a computation to degree `dim` needs, at the case's
threshold: the whole complex, not what an engine touches (no apparent pairs, no clearing, no implicit complex). Where a
count would need more time than `--timeout` the row says so and gives the number of vertices and edges and the bound
"every subset"; no row is an estimate.

  - Vietoris-Rips: every clique of the threshold graph is counted (workers/count_cliques.cpp, compiled here with g++;
    exact, by degeneracy ordering and bitsets). A clique of size k is a (k - 1)-simplex.
  - Cubical: the images are full grids, so the cells of dimension j are the coefficient of x^j in the product over the
    axes of ((n_i + 1) + n_i x); the unit tests enumerate small grids cell by cell against it.
  - Alpha: GUDHI's alpha complex (CGAL) where the ambient dimension allows it (`--gudhi-max-ambient`), and TDA4j's own
    stream (`PaperBenchmarkDriver count=true`), which is the only count at all for the cases a Delaunay triangulation
    cannot reach. Where both ran they are compared; a case with one source says "single source".
  - `--cross-check` (the default when TDA4J_CP is set): the Vietoris-Rips cases small enough are also counted by TDA4j's
    own stream, which must equal the clique count.

Output: `cells.csv` (one row per case: the canonical count, its source, the check), `cells_sources.csv` (one row per
case and source), `cells.md` (the table). Environment: TDA4J_CP as for bench.py; CXX to choose the compiler.

A benchmark run never counts. The count is made once per case, with `--reference`, into `bench/cells-reference.csv` (checked
in, with the SHA-256 of the data file counted), and `bench.py summarize` joins it as the `cells` column.
"""
import argparse
import csv
import datetime
import json
import math
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import bench  # noqa: E402

# The order in which a case's sources are preferred for the canonical count: independent of TDA4j first.
SOURCE_ORDER = ["cliques", "grid", "gudhi", "tda4j"]


def stamp(message):
    print(f"[{datetime.datetime.now():%H:%M:%S}] {message}", flush=True)


# ---------------------------------------------------------------------------------------------------------
# counting
# ---------------------------------------------------------------------------------------------------------
def grid_cells(shape, top_dim):
    """Cells of each dimension 0..min(top_dim, len(shape)) of the cubical complex of a full grid of top cells: the
    coefficient of x^j in the product over the axes of ((n + 1) + n x) (an axis contributes a vertex, n + 1 ways, or an
    edge, n ways)."""
    poly = [1]
    for n in shape:
        grown = [0] * (len(poly) + 1)
        for j, c in enumerate(poly):
            grown[j] += c * (n + 1)
            grown[j + 1] += c * n
        poly = grown
    return {j: poly[j] for j in range(min(top_dim, len(shape)) + 1)}


def every_subset_bound(n, max_size):
    """The cells of a complex on n vertices that has all of them: subsets of size 1..max_size."""
    return sum(math.comb(n, k) for k in range(1, max_size + 1))


def build_counter(build_dir):
    """Compiles workers/count_cliques.cpp into build_dir (again when the source is newer); returns the binary."""
    src, exe = HERE / "workers" / "count_cliques.cpp", Path(build_dir) / "count_cliques"
    Path(build_dir).mkdir(parents=True, exist_ok=True)
    if exe.exists() and exe.stat().st_mtime >= src.stat().st_mtime:
        return exe
    cxx = os.environ.get("CXX", "g++")
    if shutil.which(cxx) is None:
        raise SystemExit(f"no C++ compiler ({cxx}): count_cells.py compiles workers/count_cliques.cpp; set CXX")
    # Without OpenMP the count is the same, only slower.
    for flags in (["-fopenmp"], []):
        r = subprocess.run([cxx, "-O3", "-march=native", *flags, "-o", str(exe), str(src)], capture_output=True, text=True)
        if r.returncode == 0:
            return exe
    raise SystemExit(f"could not compile {src}:\n{r.stderr}")


def by_dimension(counts):
    """{dimension: cells} -> the canonical text of a row: a JSON object with string keys in dimension order."""
    return {str(k): int(v) for k, v in sorted(counts.items())}


def row(case, source, status, counts=None, seconds=0.0, rss_mb=0.0, note="", points="", edges=""):
    counts = by_dimension(counts or {})
    return {"case": case.name, "task": case.task, "source": source, "status": status, "points": points,
            "dim": case.dim, "cells": json.dumps(counts), "total": sum(counts.values()) if counts else "",
            "edges": edges, "seconds": f"{seconds:.2f}", "peak_mb": f"{rss_mb:.0f}", "note": note}


def graph_line(err_path):
    """(n, edges) from the `graph:` line count_cliques prints as soon as it has built the graph, or None."""
    try:
        m = re.search(r"graph: n=(\d+) edges=(\d+)", Path(err_path).read_text())
    except OSError:
        return None
    return (int(m.group(1)), int(m.group(2))) if m else None


def count_cliques(case, path, threshold, counter, args, out):
    log = out / "logs" / f"{case.name}__cliques"
    cmd = [str(counter), str(path), case.format, repr(threshold), str(case.dim + 2)]
    proc = bench.run_proc(cmd, args.timeout, log)
    graph = graph_line(str(log) + ".err")
    if proc.status == "ok":
        j = bench.last_json(proc.text)
        counts = {k: int(c) for k, c in enumerate(j["counts"])}
        return row(case, "cliques", "ok", counts, proc.wall, proc.rss_mb, points=j["n"], edges=j["edges"],
                   note=f"{j['threads']} threads")
    n, edges = graph or ("", "")
    note = proc.reason or proc.status
    if n != "":
        note += f"; at most {every_subset_bound(n, case.dim + 2):.3g} cells (every subset of up to {case.dim + 2} of the {n} vertices)"
    return row(case, "cliques", proc.status, None, proc.wall, proc.rss_mb, note=note, points=n, edges=edges)


def count_cubical(case, path):
    start = time.perf_counter()
    shape = np.load(path, mmap_mode="r").shape
    counts = grid_cells(shape, case.dim + 1)
    return row(case, "grid", "ok", counts, time.perf_counter() - start, points="x".join(map(str, shape)),
               note=f"a full {len(shape)}-dimensional grid of {math.prod(shape)} top cells")


def gudhi_alpha_worker(path, dim, threshold):
    """Run in a process of its own (so a timeout or a memory limit kills it): prints the simplices of GUDHI's alpha complex
    up to dimension dim + 1 by dimension."""
    import gudhi
    points = np.loadtxt(path, ndmin=2)
    square = math.inf if threshold == "None" else float(threshold) ** 2
    tree = gudhi.AlphaComplex(points=points).create_simplex_tree(max_alpha_square=square)
    tree.prune_above_dimension(int(dim) + 1)
    counts = {}
    for simplex, _ in tree.get_simplices():
        counts[len(simplex) - 1] = counts.get(len(simplex) - 1, 0) + 1
    print(json.dumps({"n": len(points), "cells": counts, "gudhi": gudhi.__version__}))


def count_gudhi(case, path, threshold, args, out):
    if not args.gudhi_python_ok:
        return None
    cmd = [args.python, str(Path(__file__).resolve()), "--gudhi-worker", str(path), str(case.dim), repr(threshold)]
    proc = bench.run_proc(cmd, args.timeout, out / "logs" / f"{case.name}__gudhi", address_space_mb=args.gudhi_mem_gb * 1024)
    if proc.status == "ok":
        j = bench.last_json(proc.text)
        return row(case, "gudhi", "ok", {int(k): v for k, v in j["cells"].items()}, proc.wall, proc.rss_mb,
                   points=j["n"], note=f"GUDHI {j['gudhi']} (CGAL)")
    return row(case, "gudhi", proc.status, None, proc.wall, proc.rss_mb, note=proc.reason or proc.status)


def count_tda4j(case, path, threshold, args, out):
    cp = Path(os.environ["TDA4J_CP"]).read_text().strip()
    cmd = (["java"] + bench.jvm_flags(args.jvm_heap, args.jvm_opts) +
           ["-cp", cp, "org.appliedtopology.tda4j.PaperBenchmarkDriver", f"task={case.task}", "count=true",
            f"input={path}", f"format={case.format}", f"dim={case.dim}"] +
           ([f"threshold={threshold!r}"] if threshold is not None else []) + bench.case_extra_args(case, path.parent))
    proc = bench.run_proc(cmd, args.timeout, out / "logs" / f"{case.name}__tda4j")
    if proc.status == "ok":
        j = bench.last_json(proc.text)
        return row(case, "tda4j", "ok", {int(k): v for k, v in j["cells"].items()}, proc.wall, proc.rss_mb,
                   note="TDA4j's own stream, iterated and not reduced")
    return row(case, "tda4j", proc.status, None, proc.wall, proc.rss_mb, note=proc.reason or proc.status)


def counts_of(r):
    return {int(k): v for k, v in json.loads(r["cells"]).items()} if r["status"] == "ok" else None


def compare(first, second):
    """'' when the two ok rows count alike, else which dimensions differ and by how much."""
    a, b = counts_of(first), counts_of(second)
    if a == b:
        return ""
    return "; ".join(f"dim {k}: {first['source']} {a.get(k, 0)} / {second['source']} {b.get(k, 0)}"
                     for k in sorted(set(a) | set(b)) if a.get(k, 0) != b.get(k, 0))


def count_case(case, args, data, out, counter):
    """Every source's row for one case, then the canonical one is picked by `canonical`."""
    path = data / case.file
    if not path.exists():
        return [row(case, "-", "missing", note=f"{path} not found: run bench.py prepare --data {data}")]
    threshold = bench.case_threshold(case, data)
    rows = []
    if case.task == "vr":
        rows.append(count_cliques(case, path, threshold, counter, args, out))
        small = rows[0]["status"] == "ok" and rows[0]["total"] <= args.cross_check_max
        if args.cross_check and small and have_jvm(args):
            rows.append(count_tda4j(case, path, threshold, args, out))
    elif case.task == "cubical":
        rows.append(count_cubical(case, path))
    elif case.task == "alpha":
        ambient = bench.ambient_dimension(case, data)
        if args.gudhi_python_ok and ambient <= args.gudhi_max_ambient:
            rows.append(count_gudhi(case, path, threshold, args, out))
        if have_jvm(args):
            rows.append(count_tda4j(case, path, threshold, args, out))
    elif case.task in ("cech", "sparse", "dtm", "witness", "dowker") and have_jvm(args):
        rows.append(count_tda4j(case, path, threshold, args, out))  # single source: no other tool builds the same complex
    for r in rows:
        r["threshold"] = "" if threshold is None else repr(threshold)
        r["ambient"] = bench.ambient_dimension(case, data) or ""
        if r["points"] == "" and case.task != "cubical":
            r["points"] = line_count(path)
    return rows


def line_count(path):
    """Data lines of a point or matrix file: the number of points."""
    with open(path) as f:
        return sum(1 for line in f if line.strip() and not line.lstrip().startswith("#"))


def have_jvm(args):
    cp = os.environ.get("TDA4J_CP")
    return not args.no_jvm and bool(cp) and Path(cp).exists()


def canonical(rows):
    """(row, check): the first ok row in SOURCE_ORDER and what the other ok rows say about it."""
    ok = sorted((r for r in rows if r["status"] == "ok"), key=lambda r: SOURCE_ORDER.index(r["source"]))
    if not ok:
        return rows[0], "no count"
    chosen, others = ok[0], ok[1:]
    if not others:
        if chosen["source"] in ("cliques", "grid"):
            return chosen, ""
        return chosen, "single source" + (" (TDA4j's own count)" if chosen["source"] == "tda4j" else "")
    notes = []
    for other in others:
        diff = compare(chosen, other)
        notes.append(f"{other['source']} agrees" if not diff else f"{other['source']} DIFFERS ({diff})")
    return chosen, "; ".join(notes)


# ---------------------------------------------------------------------------------------------------------
# output
# ---------------------------------------------------------------------------------------------------------
def write_outputs(out, cases_rows):
    sources = [r for _, rows in cases_rows for r in rows]
    source_fields = ["case", "task", "source", "status", "points", "ambient", "dim", "threshold", "cells", "total", "edges",
                     "seconds", "peak_mb", "note"]
    with open(out / "cells_sources.csv", "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=source_fields, extrasaction="ignore")
        w.writeheader()
        w.writerows(sources)

    chosen = [(case, *canonical(rows)) for case, rows in cases_rows]
    top = max([len(json.loads(r["cells"])) for _, r, _ in chosen if r["status"] == "ok"] + [1])
    dims = [f"dim{k}" for k in range(top)]
    fields = ["case", "task", "points", "ambient", "dim", "threshold", "source", "status", "total"] + dims + ["check", "note"]
    flat = []
    for case, r, check in chosen:
        counts = json.loads(r["cells"]) if r["status"] == "ok" else {}
        flat.append({**{k: r.get(k, "") for k in fields[:9]}, **{f"dim{k}": counts.get(str(k), "") for k in range(top)},
                     "check": check, "note": r["note"]})
    with open(out / "cells.csv", "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        w.writerows(flat)

    def thousands(v):
        return f"{int(v):,}" if v not in ("", None) else ""

    lines = ["# Cells of the benchmark cases", "",
             "Cells of dimension 0 to `dim` + 1 of the complex each case reduces, at its threshold: the whole complex, not what "
             "an engine touches. `source`: `cliques` (every clique of the threshold graph counted), `grid` (a full cubical grid), "
             "`gudhi` (GUDHI's alpha complex), `tda4j` (TDA4j's stream iterated). `check` says what the other sources found.", "",
             "| case | task | points | ambient | threshold | " + " | ".join(f"dim {k}" for k in range(top)) +
             " | total | source | check |",
             "|---|---|---|---|---|" + "---|" * top + "---|---|---|"]
    for r in flat:
        thr = f"{float(r['threshold']):.4g}" if r["threshold"] else ""
        remark = r["check"] if r["status"] == "ok" else f"{r['status']}: {r['note']}"
        lines.append(f"| {r['case']} | {r['task']} | {r['points']} | {r['ambient']} | {thr} | " +
                     " | ".join(thousands(r[d]) for d in dims) + f" | {thousands(r['total'])} | {r['source']} | {remark} |")
    lines += ["", "Every source's own row, with time and peak memory: `cells_sources.csv`."]
    (out / "cells.md").write_text("\n".join(lines) + "\n")
    return flat


def update_reference(path, flat, hashes):
    """Merges the ok rows of `flat` into the reference file: a row for a case replaces that case's old one, a failed count
    never replaces a good one, the file stays in the order of bench.CASES. Each row carries the SHA-256 of the data file it
    was counted from, so a regenerated or edited input is noticed. Returns the number of rows written."""
    kept = {}
    if Path(path).exists():
        with open(path) as f:
            kept = {r["case"]: r for r in csv.DictReader(f)}
    for r in flat:
        if r["status"] == "ok":
            kept[r["case"]] = {**r, "data_sha256": hashes.get(r["case"], "")}
    top = max([int(k[3:]) + 1 for r in kept.values() for k in r if re.fullmatch(r"dim\d+", k) and r[k] not in ("", None)] + [1])
    fields = ["case", "task", "points", "ambient", "dim", "threshold", "source", "total"] + [f"dim{k}" for k in range(top)] + \
             ["check", "data_sha256"]
    order = {c.name: i for i, c in enumerate(bench.CASES)}
    with open(path, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fields, extrasaction="ignore", restval="")
        w.writeheader()
        w.writerows(sorted(kept.values(), key=lambda r: order.get(r["case"], len(order))))
    return len(kept)


def verify_reference(path, data):
    """[(case, what)] for every reference row whose data file is missing or no longer has the SHA-256 it was counted from."""
    problems = []
    with open(path) as f:
        for r in csv.DictReader(f):
            case = next((c for c in bench.CASES if c.name == r["case"]), None)
            file = data / case.file if case else None
            if case is None:
                problems.append((r["case"], "no such case in bench.py"))
            elif not file.exists():
                problems.append((r["case"], f"{file} not found"))
            elif r["data_sha256"] and bench.sha256_of(file) != r["data_sha256"]:
                problems.append((r["case"], f"{file.name} is not the file that was counted (SHA-256 differs)"))
    return problems


def join_summary(results, flat):
    """RESULTS/summary.csv with the total number of cells of each row's case added: `summary_with_cells.csv`."""
    totals = {r["case"]: r["total"] for r in flat}
    with open(Path(results) / "summary.csv") as f:
        rows = list(csv.DictReader(f))
    fields = list(rows[0].keys()) + ["cells_total"]
    dest = Path(results) / "summary_with_cells.csv"
    with open(dest, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        for r in rows:
            w.writerow({**r, "cells_total": totals.get(r["case"], "")})
    return dest


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "--gudhi-worker":
        gudhi_alpha_worker(*sys.argv[2:5])
        return
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--data", required=True, help="the directory `bench.py prepare` filled")
    ap.add_argument("--out", required=True, help="where cells.md, cells.csv and cells_sources.csv go")
    ap.add_argument("--cases", default="", help="comma-separated case names (default: all)")
    ap.add_argument("--tasks", default="", help="comma-separated: vr,cubical,alpha (default: all)")
    ap.add_argument("--timeout", type=float, default=3600, help="seconds per count")
    ap.add_argument("--threads", type=int, default=0, help="threads of the clique counter (default: all cores)")
    ap.add_argument("--python", default=sys.executable)
    ap.add_argument("--gudhi-max-ambient", type=int, default=5,
                    help="GUDHI counts the alpha complex only up to this ambient dimension (CGAL's triangulation "
                         "does not reach further)")
    ap.add_argument("--gudhi-mem-gb", type=int, default=64)
    ap.add_argument("--no-gudhi", action="store_true")
    ap.add_argument("--no-jvm", action="store_true", help="no TDA4j count at all")
    ap.add_argument("--cross-check", action=argparse.BooleanOptionalAction, default=True,
                    help="also count the small Vietoris-Rips cases with TDA4j's stream (default: on)")
    ap.add_argument("--cross-check-max", type=float, default=3e5,
                    help="the largest Vietoris-Rips count that is cross-checked (TDA4j's stream yields about 8,000 cells/s)")
    ap.add_argument("--jvm-heap", default="8g")
    ap.add_argument("--jvm-opts", default="")
    ap.add_argument("--reference", action="store_true",
                    help="write the counts into the checked-in reference file (bench/cells-reference.csv), which "
                         "bench.py summarize joins; a failed count never replaces a good one")
    ap.add_argument("--reference-file", default=str(bench.CELLS_REFERENCE))
    ap.add_argument("--verify", action="store_true",
                    help="only check that the data files are the ones the reference was counted from (SHA-256); exit 1 if not")
    ap.add_argument("--join", default="", help="a bench.py results directory: add each case's total to its summary.csv")
    args = ap.parse_args()
    data, out = Path(args.data), Path(args.out)
    if args.verify:
        problems = verify_reference(args.reference_file, data)
        for case, what in problems:
            print(f"{case}: {what}")
        print("the data files match the reference" if not problems else f"{len(problems)} of the reference's cases do not match")
        sys.exit(1 if problems else 0)
    (out / "logs").mkdir(parents=True, exist_ok=True)
    if args.threads:
        os.environ["OMP_NUM_THREADS"] = str(args.threads)
    args.gudhi_python_ok = (not args.no_gudhi and subprocess.run([args.python, "-c", "import gudhi"], capture_output=True).returncode == 0)
    wanted = {c for c in args.cases.split(",") if c}
    tasks = {t for t in args.tasks.split(",") if t}
    cases = [c for c in bench.CASES if (not wanted or c.name in wanted) and (not tasks or c.task in tasks)]
    counter = build_counter(out) if any(c.task == "vr" for c in cases) else None
    cases_rows = []
    for case in cases:
        stamp(f"{case.name} ({case.task})")
        rows = count_case(case, args, data, out, counter)
        cases_rows.append((case, rows))
        for r in rows:
            stamp(f"    {r['source']:8s} {r['status']:8s} total={r['total']} {r['seconds']}s {r['note'][:100]}")
        write_outputs(out, cases_rows)  # after every case: a killed run keeps what it counted
    flat = write_outputs(out, cases_rows)
    if args.reference:
        hashes = {c.name: bench.sha256_of(data / c.file) for c, _ in cases_rows if (data / c.file).exists()}
        stamp(f"{update_reference(args.reference_file, flat, hashes)} cases in {args.reference_file}")
    if args.join:
        stamp(f"wrote {join_summary(args.join, flat)}")
    print((out / "cells.md").read_text())


if __name__ == "__main__":
    main()
