#!/usr/bin/env python3
"""Thread-scaling sweep of the multithreaded Vietoris-Rips tools: how each one's time falls as it gets more threads, on
cases heavy enough for that to matter.

    bench/run.sh sweep-threads [--cases fractal-r,random16] [--threads 1,2,4,8,16,32] [--tools tda4j-parallel-mt,...]
                               [--baselines ripser.cpp] [--field 2] [--warmup 1] [--trials 3] [--timeout 1800]
                               [--no-pin] [--jvm-heap 16g]

`run.sh setup` and `run.sh prepare` must have run. Tools: every `multithreaded` Vietoris-Rips row of bench.py's TOOLS
(`tda4j-parallel-mt` and `tda4j-blocks-mt`, the two block-parallel experiments; `giotto-ph-mt`; `dipha-mt`, whose
threads are MPI ranks), each at every count of `--threads` (default: 1, 2, 4, ... up to the machine's logical CPUs, and
that number itself). The `--baselines` (default ripser.cpp) run once per case, single-threaded, for scale.

Each run is bench.py's: a cold run, then a warm one (--warmup untimed and --trials timed computations) if the cold
computation took under --warm-cap seconds. A tool's counts run from the most threads down, and its series stops at the
first failure other than `oom` (fewer threads only take longer, and an error does not depend on the count; fewer
threads may need less memory). With pinning (the default, when `taskset` exists) a run at t threads is confined to the
logical CPUs 0 .. t - 1, so the JVM's garbage collector and compiler threads, and MPI's ranks, share those t CPUs with
the computation; `--no-pin` lets them use the whole machine (a single-threaded JVM then collects garbage on every core).

Default cases: fractal-r (512 points, 2.2 billion cells to H2) and random16 (50 points in R^16, degrees up to 7). They
are the heaviest Vietoris-Rips cases `tda4j-parallel-mt` can encode: a simplex is one Long key there, and o3_4096's
(C(4096, 5) indices times 234,145 edge blocks) does not fit (#194). `tda4j-blocks-mt` builds its coboundary matrices
explicitly and runs only below --explicit-max-cells (random16, not fractal-r).

Checks: the barcode must not depend on the thread count, so each run's bars are compared with the same tool's at the
fewest threads that finished (exactly), and with the first baseline's (the bottleneck distance, if GUDHI is installed;
ripser.cpp computes in float32).

Output, in --out (default $WORKDIR/sweeps/threads-<timestamp>): sweep.csv (bench.py's run rows plus `threads` and
`pinned`), bars/, logs/, env.json, sweep.md (per case: the time at each count, the speedup over the tool's own single
thread, the barcode checks). `sweep_threads.py --summarize DIR` rebuilds the tables.
"""
import argparse
import csv
import datetime
import json
import os
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import bench  # noqa: E402  (CASES, TOOLS, run_one, case_threshold, skip_reason, read_bars, agreement)

DEFAULT_CASES = "fractal-r,random16"
COLUMNS = bench.RUNS_COLUMNS + ["threads", "pinned"]


def stops_the_series(status):
    """A failure at t threads that ends a tool's series on a case: anything but `oom` (see the module's doc)."""
    return status not in ("ok", "oom")


def thread_counts(text, cpus):
    """The thread counts to run, most first: those of `text` (comma-separated), or 1, 2, 4, ... up to `cpus`, and
    `cpus` itself."""
    if text:
        counts = {int(t) for t in text.split(",") if t.strip()}
    else:
        counts = {1 << k for k in range(cpus.bit_length()) if 1 << k <= cpus} | {cpus}
    return sorted(counts, reverse=True)


def with_threads(opts, t):
    """The tool's options at `t` threads: `threads` for TDA4j and giotto-ph, `ranks` (MPI processes) for DIPHA."""
    return dict(opts, **{"ranks" if "ranks" in opts else "threads": str(t)})


def pinned_mpi_flags():
    """With pinning, Open MPI must not bind its ranks itself (they would leave the taskset). The flags bench.py gives
    Open MPI, plus `--bind-to none`; None to leave MPIEXEC_FLAGS alone (set by the user, or another MPI)."""
    if "MPIEXEC_FLAGS" in os.environ:
        return None
    flags = bench.mpiexec_flags()
    return " ".join(flags + ["--bind-to", "none"]) if "--oversubscribe" in flags else None


class Rows:
    """bench.run_one's row writer, with the sweep's columns added."""

    def __init__(self, w, **extra):
        self.w, self.extra = w, extra

    def writerow(self, row):
        self.w.writerow({**row, **self.extra})


def plan(args, cpus):
    """[(name, kind, opts, counts)]: the baselines at one thread, then the multithreaded tools at every count."""
    wanted = set(args.tools.split(",")) if args.tools else None
    baselines = [b for b in args.baselines.split(",") if b]
    vr = {name: (kind, opts) for name, kind, task, opts in bench.TOOLS if task == "vr"}
    unknown = [b for b in baselines if b not in vr] + [t for t in (wanted or ()) if t not in vr]
    if unknown:
        raise SystemExit(f"not a Vietoris-Rips tool of bench.py: {', '.join(unknown)}")
    counts = thread_counts(args.threads, cpus)
    return ([(b, vr[b][0], dict(vr[b][1]), [1]) for b in baselines] +
            [(name, kind, dict(opts), counts) for name, (kind, opts) in vr.items()
             if opts.get("multithreaded") and (wanted is None or name in wanted)])


def sweep(args, out, cpus, pin):
    data = Path(args.data)
    for sub in ("bars", "logs"):
        (out / sub).mkdir(parents=True, exist_ok=True)
    names = {c.name: c for c in bench.CASES}
    cases = []
    for name in [c for c in args.cases.split(",") if c]:
        if name not in names or names[name].task != "vr":
            raise SystemExit(f"--cases: {name} is not a Vietoris-Rips case of bench.py")
        cases.append(names[name])
    tools = plan(args, cpus)
    cells = bench.cells_reference()
    args.javaplex_divisions = 1000  # bench.command reads it; JavaPlex is not multithreaded
    if pin and any(kind == "dipha" for _, kind, _, _ in tools):
        flags = pinned_mpi_flags()
        if flags is not None:
            os.environ["MPIEXEC_FLAGS"] = flags
    with open(out / "sweep.csv", "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=COLUMNS)
        w.writeheader()
        for case in cases:
            threshold = bench.case_threshold(case, data)
            ambient = bench.ambient_dimension(case, data)
            for name, kind, opts, counts in tools:
                opts.setdefault("pytool", name)
                single = counts == [1] and not opts.get("multithreaded")
                stopped = None  # (status, threads) of the failure that ends this series
                for t in counts:
                    rows = Rows(w, tool=name, threads=t, pinned=pin)

                    def not_run(status, reason):
                        rows.writerow({"case": case.name, "task": case.task, "p": args.field, "mode": "cold",
                                       "status": status, "threshold": threshold, "reason": reason})
                        f.flush()
                        print(f"[{datetime.datetime.now():%H:%M:%S}] {case.name} {name}@{t}: {status} ({reason})",
                              flush=True)

                    if not bench.available(kind, opts, args.python):
                        not_run("missing", "not installed")
                        break
                    why = (bench.skip_reason(opts, case, ambient, threshold, args.field) or
                           bench.explicit_too_large(kind, opts, cells.get(case.name), args.explicit_max_cells))
                    if why:
                        not_run("unsupported", why)
                        break
                    if stopped:
                        not_run(stopped[0], f"not run: {stopped[0]} at {stopped[1]} threads")
                        continue
                    args.pin_cpus = t if pin else 0
                    status = bench.run_one(rows, f, data, out, case, args.field, threshold, f"{name}@{t}", kind,
                                           opts if single else with_threads(opts, t), args)
                    if stops_the_series(status):
                        stopped = (status, t)


def parser():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--cases", default=DEFAULT_CASES, help="comma-separated Vietoris-Rips cases of bench.py")
    ap.add_argument("--threads", default="", help="comma-separated thread counts (default: 1, 2, 4, ... up to the CPUs)")
    ap.add_argument("--tools", default="", help="comma-separated multithreaded tools (default: all available)")
    ap.add_argument("--baselines", default="ripser.cpp", help="single-threaded tools run once per case, for scale")
    ap.add_argument("--field", type=int, default=2)
    ap.add_argument("--warmup", type=int, default=1)
    ap.add_argument("--trials", type=int, default=3)
    ap.add_argument("--timeout", type=float, default=1800, help="seconds per computation")
    ap.add_argument("--warm-cap", type=float, default=60,
                    help="if one cold computation takes longer (s), skip the warm repetitions and reuse it")
    ap.add_argument("--no-pin", action="store_true", help="do not confine a run at t threads to t CPUs")
    ap.add_argument("--explicit-max-cells", type=float, default=1e8,
                    help="the largest complex (cells, from bench/cells-reference.csv) a tool builds explicitly")
    ap.add_argument("--jvm-heap", default="16g")
    ap.add_argument("--jvm-opts", default="")
    ap.add_argument("--node-heap", default="")
    ap.add_argument("--node-opts", default="")
    ap.add_argument("--out", default="")
    ap.add_argument("--data", default="")
    ap.add_argument("--python", default=sys.executable)
    ap.add_argument("--summarize", default="", help="rebuild the tables of an existing sweep directory and stop")
    return ap


def main():
    ap = parser()
    args = ap.parse_args()
    if args.summarize:
        summarize(Path(args.summarize))
        return
    if not args.data:
        ap.error("--data is required")
    cpus = os.cpu_count()
    pin = not args.no_pin and shutil.which("taskset") is not None
    if pin and max(thread_counts(args.threads, cpus)) > cpus:
        ap.error(f"--threads goes above the {cpus} CPUs a run can be pinned to; add --no-pin to oversubscribe")
    out = Path(args.out or Path(os.environ.get("WORKDIR", ".")) / "sweeps" /
               f"threads-{datetime.datetime.now():%Y%m%d-%H%M%S}")
    (out / "bars").mkdir(parents=True, exist_ok=True)
    bench.write_env(out, args)
    sweep(args, out, cpus, pin)
    summarize(out)
    print(f"\nresults in {out}")


def seconds(row):
    """The computation's time in a run row: the warm median, or the cold computation's; None if it did not finish."""
    return float(row["median_s"]) if row.get("status") == "ok" and row.get("median_s") else None


def same_barcode(a, b):
    """None if the barcodes are equal as multisets of (degree, birth, death), else the first degree that differs."""
    for k in sorted(set(a) | set(b)):
        x, y = sorted(a.get(k, [])), sorted(b.get(k, []))
        if len(x) != len(y):
            return f"H{k}: {len(x)} bars against {len(y)}"
        if x != y:
            return "H{}: bar {} against {}".format(k, *next((u, v) for u, v in zip(x, y) if u != v))
    return None


def summarize(out, bottleneck=None):
    """sweep.md: per case, each tool's time at every thread count with the speedup over its own single thread, and the
    barcode checks."""
    with open(out / "sweep.csv") as f:
        rows = list(csv.DictReader(f))
    try:
        with open(out / "env.json") as f:
            env = json.load(f)
    except (OSError, ValueError):
        env = {}
    baselines = [b for b in env.get("args", {}).get("baselines", "").split(",") if b]
    cells = bench.cells_reference()
    cases = {c.name: c for c in bench.CASES}
    by = {}  # (case, p, tool) -> {threads: {mode: row}}
    for r in rows:
        by.setdefault((r["case"], r["p"], r["tool"]), {}).setdefault(int(r["threads"]), {})[r["mode"]] = r
    pinned = {r["pinned"] for r in rows}
    lines = ["# Thread scaling of the multithreaded Vietoris-Rips tools", "",
             f"Machine: {env.get('cpu') or 'unknown CPU'}, {env.get('cores', '?')} logical CPUs. "
             + ("Each run at t threads was pinned to t CPUs (taskset). " if pinned == {"True"} else
                "Runs were not pinned: garbage collection, compilation and MPI could use every CPU. "
                if pinned == {"False"} else "")
             + "Times in seconds: the warm median, or the cold computation when it was too slow to repeat. "
               "In parentheses, the speedup over the tool's own run at 1 thread.", ""]
    for cname, p in dict.fromkeys((r["case"], r["p"]) for r in rows):
        tools = [t for (c, q, t) in by if (c, q) == (cname, p)]
        counts = sorted({t for tool in tools for t in by[(cname, p, tool)]})
        case = cases.get(cname)
        total = cells.get(cname)
        lines += [f"## {cname}, p = {p}" + (f", degrees up to {case.dim}" if case else "")
                  + (f", {int(total):,} cells" if total else ""), "",
                  "| tool | " + " | ".join(str(t) for t in counts) + " | fastest | barcode |",
                  "|---|" + "---|" * (len(counts) + 2)]
        baseline = next((t for t in baselines if t in tools), None)
        reference, base_time = None, None
        if baseline:
            m = by[(cname, p, baseline)].get(1, {})
            base_time = seconds(m.get("warm", {})) or seconds(m.get("cold", {}))
            reference = bench.read_bars(out / "bars" / f"{cname}__p{p}__{baseline}@1.tsv") if base_time else None
        for tool in tools:
            runs = by[(cname, p, tool)]
            time = {t: seconds(m.get("warm", {})) or seconds(m.get("cold", {})) for t, m in runs.items()}
            one = time.get(1)
            cells_text = []
            for t in counts:
                m = runs.get(t)
                if m is None:
                    cells_text.append("")
                elif time[t] is None:
                    cells_text.append((m.get("warm") or m.get("cold"))["status"])
                else:
                    cells_text.append(f"{time[t]:.3g}" + (f" ({one / time[t]:.2g}×)" if one and t != 1 else ""))
            finished = sorted(t for t in counts if time.get(t))
            checks = []
            bars = {t: bench.read_bars(out / "bars" / f"{cname}__p{p}__{tool}@{t}.tsv") for t in finished}
            bars = {t: b for t, b in bars.items() if b is not None}
            if len(bars) > 1:
                first = min(bars)
                diffs = [(t, same_barcode(b, bars[first])) for t, b in bars.items() if t != first]
                bad = [(t, d) for t, d in diffs if d]
                checks.append(f"differs at {bad[0][0]} threads from {first} ({bad[0][1]})" if bad
                              else "same at every count")
            if bars and reference is not None and tool != baseline:
                b = bars[min(bars)]
                try:
                    dist, note = bench.agreement(b, reference, max(list(b) + list(reference) + [0]), bottleneck)
                    checks.append(f"{baseline}: {bench.fmt_distance(dist)}" + (f" ({note})" if note else ""))
                except ImportError:
                    checks.append(f"{baseline}: needs gudhi")
            fastest = ""
            if finished and tool != baseline:
                best = min(finished, key=lambda t: time[t])
                fastest = f"{time[best]:.3g} at {best}" + (f" ({time[best] / base_time:.2g}× {baseline})" if base_time else "")
            label = tool if tool != baseline else f"{tool} (single-threaded)"
            lines.append(f"| {label} | " + " | ".join(cells_text) + f" | {fastest} | " + "; ".join(checks) + " |")
        lines.append("")
    (out / "sweep.md").write_text("\n".join(lines) + "\n")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
