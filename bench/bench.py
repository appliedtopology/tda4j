#!/usr/bin/env python3
"""Cross-platform persistent-homology benchmark: TDA4j against the other libraries at the frontier.

    bench.py prepare  --data DATA                        download the benchmark data, generate the synthetic sets
    bench.py run      --data DATA --out RESULTS [...]    time every (case, tool, field), check the barcodes agree
    bench.py summarize RESULTS                           tables: per case, and TDA4j's slowdown

Normally driven by bench/run.sh, which builds the tools and sets the environment variables read here
(TDA4J_CP, RIPSER_BIN, RIPSER_COEFF_BIN, JULIA, RIPSERER_PROJECT, JAVAPLEX_JAR, JAVAPLEX_BUILD). A tool
whose variable is unset, or whose Python package is missing, is skipped and listed as `missing`.

Method (see .claude/PLAN-paper.md, section 3.0):
  - Every run is its own process, killed at --timeout; peak RSS comes from wait4.
  - "cold": one process doing one computation, timed from launch to exit (startup, JIT, reading input).
  - "warm": one process doing --warmup untimed and --trials timed computations; we report the median of
    the timed ones (for ripser.cpp, which has no in-process loop, the median of --trials cold runs).
  - Agreement: every barcode is compared, per degree, with the reference tool's (ripser.cpp for
    Vietoris-Rips, GUDHI for cubical and alpha): bottleneck distance on finite bars, and essential bars
    matched by birth. ripser.cpp computes in float32, so agreement is up to a tolerance, not exact.
  - Distance matrices are symmetrized (ripser.cpp's lower triangle wins) so every tool reads the same input.
  - When a case has no threshold, every tool gets the enclosing radius explicitly (ripser.cpp's default).
"""
import argparse
import csv
import datetime
import json
import math
import os
import platform
import shutil
import signal
import statistics
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
RIPSER_BENCH = "https://raw.githubusercontent.com/Ripser/ripser-benchmark/master"
ROADMAP = "https://raw.githubusercontent.com/n-otter/PH-roadmap/master/data_sets"

# ---------------------------------------------------------------------------------------------------------
# Cases. The Vietoris-Rips ones are the Ripser paper's benchmark (ripser-benchmark's Dockerfile, same flags
# minus --ratio, which only filters printed output), plus the sphere3 size ladder.
# ---------------------------------------------------------------------------------------------------------
CASES = [
    # name, task, file, format, dim, threshold
    ("sphere3_48", "vr", "sphere3_48.txt", "points", 2, None),
    ("sphere3_96", "vr", "sphere3_96.txt", "points", 2, None),
    ("sphere3_192", "vr", "sphere_3_192_points.dat", "points", 2, None),
    ("dragon2000", "vr", "dragon_2000.txt", "points", 1, None),
    ("random16", "vr", "random_point_cloud_50_16_.txt", "points", 7, None),
    ("fractal-r", "vr", "fractal-r.sym.txt", "distance", 2, None),
    ("o3_1024", "vr", "o3_1024.txt", "points", 3, 1.8),
    ("o3_4096", "vr", "o3_4096.txt", "points", 3, 1.4),
    ("clifford50000", "vr", "clifford_torus_50000.points.txt", "points", 2, 0.15),
    # cubical: sublevel filtration, T-construction (top cells carry the values)
    ("random2d_512", "cubical", "random2d_512.npy", "npy", 1, None),
    ("blob2d_512", "cubical", "blob2d_512.npy", "npy", 1, None),
    ("random2d_1024", "cubical", "random2d_1024.npy", "npy", 1, None),
    ("blob2d_1024", "cubical", "blob2d_1024.npy", "npy", 1, None),
    ("random2d_2048", "cubical", "random2d_2048.npy", "npy", 1, None),
    ("blob2d_2048", "cubical", "blob2d_2048.npy", "npy", 1, None),
    ("random3d_32", "cubical", "random3d_32.npy", "npy", 2, None),
    ("random3d_64", "cubical", "random3d_64.npy", "npy", 2, None),
    ("blob3d_64", "cubical", "blob3d_64.npy", "npy", 2, None),
    ("random3d_128", "cubical", "random3d_128.npy", "npy", 2, None),
    # alpha: filtration values are circumradii (GUDHI's squared radii are square-rooted)
    ("unif2d_1000", "alpha", "unif2d_1000.txt", "points", 1, None),
    ("unif2d_10000", "alpha", "unif2d_10000.txt", "points", 1, None),
    ("unif3d_1000", "alpha", "unif3d_1000.txt", "points", 2, None),
    ("unif3d_5000", "alpha", "unif3d_5000.txt", "points", 2, None),
    ("dragon2000_alpha", "alpha", "dragon_2000.txt", "points", 2, None),
    ("sphere3_192_alpha", "alpha", "sphere_3_192_points.dat", "points", 2, None),
]

# ---------------------------------------------------------------------------------------------------------
# Tools: (name, kind, task, options). `multithreaded` rows are reported but never count as the
# single-threaded baseline. Reference tool per task is used for agreement.
# ---------------------------------------------------------------------------------------------------------
TOOLS = [
    ("tda4j", "tda4j", "vr", {"engine": "auto", "reps": "cycles"}),
    ("tda4j-cocycles", "tda4j", "vr", {"engine": "auto", "reps": "cocycles"}),
    ("ripser.cpp", "ripser", "vr", {}),
    ("ripser.py", "py", "vr", {}),
    ("giotto-ph-1t", "py", "vr", {"pytool": "giotto-ph", "threads": "1"}),
    ("giotto-ph-mt", "py", "vr", {"pytool": "giotto-ph", "threads": "ALL", "multithreaded": True}),
    ("gudhi", "py", "vr", {}),
    ("gudhi-collapse", "py", "vr", {"pytool": "gudhi", "variant": "collapse"}),
    ("ripserer", "julia", "vr", {"variant": "cohomology"}),
    ("ripserer-involuted", "julia", "vr", {"variant": "involuted"}),
    ("javaplex", "javaplex", "vr", {}),
    ("tda4j", "tda4j", "cubical", {"engine": "auto", "reps": "cycles"}),
    ("tda4j-cohomology", "tda4j", "cubical", {"engine": "cohomology", "reps": "cocycles"}),
    ("gudhi", "py", "cubical", {}),
    ("cripser", "py", "cubical", {}),
    ("tda4j", "tda4j", "alpha", {"engine": "auto", "reps": "cycles"}),
    ("tda4j-fastalpha", "tda4j", "alpha", {"engine": "fastalpha", "reps": "cycles"}),
    ("gudhi", "py", "alpha", {}),
]
REFERENCE = {"vr": "ripser.cpp", "cubical": "gudhi", "alpha": "gudhi"}


# ---------------------------------------------------------------------------------------------------------
# prepare
# ---------------------------------------------------------------------------------------------------------
def fetch(url, dest):
    if dest.exists() and dest.stat().st_size > 0:
        return
    print(f"  fetching {url}", flush=True)
    tmp = dest.with_suffix(dest.suffix + ".part")
    with urllib.request.urlopen(url, timeout=300) as r, open(tmp, "wb") as f:
        shutil.copyfileobj(r, f)
    tmp.rename(dest)


def blob(shape, rng):
    grids = np.meshgrid(*[np.arange(n) for n in shape], indexing="ij")
    r2 = sum(((g - (n - 1) / 2) / (n / 4)) ** 2 for g, n in zip(grids, shape))
    return np.exp(-r2 / 2) + 0.05 * rng.random(shape)


def prepare(data: Path):
    data.mkdir(parents=True, exist_ok=True)
    for f in ["sphere_3_192_points.dat", "o3_1024.txt", "o3_4096.txt", "clifford_torus_50000.points.txt"]:
        fetch(f"{RIPSER_BENCH}/{f}", data / f)
    fetch(f"{ROADMAP}/roadmap_datasets_point_cloud/random_point_cloud_50_16_.txt", data / "random_point_cloud_50_16_.txt")
    fetch(f"{ROADMAP}/roadmap_datasets_point_cloud/dragon_vrip.ply.txt_2000_.txt", data / "dragon_2000.txt")
    fetch(f"{ROADMAP}/roadmap_datasets_distmat/fractal_9_5_2_random_edge_list.txt_0.19795_distmat.txt",
          data / "fractal-r.txt")

    sphere = np.loadtxt(data / "sphere_3_192_points.dat")
    np.savetxt(data / "sphere3_48.txt", sphere[:48], fmt="%.17g")
    np.savetxt(data / "sphere3_96.txt", sphere[:96], fmt="%.17g")

    # The fractal-r matrix is not symmetric (2,135 of 130,816 pairs differ by up to 1e-5). ripser.cpp reads its
    # lower triangle; give every tool that same symmetric matrix.
    d = np.loadtxt(data / "fractal-r.txt")
    low = np.tril(d, -1)
    np.savetxt(data / "fractal-r.sym.txt", low + low.T, fmt="%.17g")

    rng = np.random.default_rng(20261004)
    for n in [512, 1024, 2048]:
        np.save(data / f"random2d_{n}.npy", rng.random((n, n)))
        np.save(data / f"blob2d_{n}.npy", blob((n, n), rng))
    for n in [32, 64, 128]:
        np.save(data / f"random3d_{n}.npy", rng.random((n, n, n)))
    np.save(data / "blob3d_64.npy", blob((64, 64, 64), rng))
    for n in [1000, 10000]:
        np.savetxt(data / f"unif2d_{n}.txt", rng.random((n, 2)), fmt="%.17g")
    for n in [1000, 5000]:
        np.savetxt(data / f"unif3d_{n}.txt", rng.random((n, 3)), fmt="%.17g")
    with open(data / "MANIFEST.json", "w") as f:
        json.dump({p.name: p.stat().st_size for p in sorted(data.iterdir()) if p.is_file()}, f, indent=1)
    print(f"data ready in {data}")


# ---------------------------------------------------------------------------------------------------------
# running one process
# ---------------------------------------------------------------------------------------------------------
def run_proc(cmd, timeout, log_prefix):
    """Runs cmd in its own session; returns (status, wall_s, maxrss_mb, stdout). status: ok|timeout|error|unsupported."""
    out_path, err_path = Path(str(log_prefix) + ".out"), Path(str(log_prefix) + ".err")
    with open(out_path, "w") as out, open(err_path, "w") as err:
        start = time.perf_counter()
        proc = subprocess.Popen(cmd, stdout=out, stderr=err, start_new_session=True)
        status, rusage = None, None
        while True:
            pid, st, ru = os.wait4(proc.pid, os.WNOHANG)
            if pid != 0:
                status, rusage = st, ru
                break
            if time.perf_counter() - start > timeout:
                os.killpg(proc.pid, signal.SIGKILL)
                _, status, rusage = os.wait4(proc.pid, 0)
                wall = time.perf_counter() - start
                return "timeout", wall, rusage.ru_maxrss / 1024, ""
            time.sleep(0.02)
        wall = time.perf_counter() - start
    code = os.waitstatus_to_exitcode(status)
    text = out_path.read_text()
    if code == 3:
        return "unsupported", wall, rusage.ru_maxrss / 1024, text
    return ("ok" if code == 0 else f"error({code})"), wall, rusage.ru_maxrss / 1024, text


def last_json(text):
    for line in reversed(text.splitlines()):
        line = line.strip()
        if line.startswith("{"):
            try:
                return json.loads(line)
            except json.JSONDecodeError:
                pass
    return None


def parse_ripser_output(text, out_path):
    """ripser.cpp prints `persistence intervals in dim k:` then ` [b,d)` / ` [b, )` lines."""
    dim, rows = None, []
    for line in text.splitlines():
        if line.startswith("persistence intervals in dim"):
            dim = int(line.split()[-1].rstrip(":"))
        elif line.strip().startswith("[") and dim is not None:
            b, d = line.strip()[1:-1].split(",")
            rows.append((dim, float(b), math.inf if d.strip() == "" else float(d)))
    with open(out_path, "w") as f:
        for k, b, d in rows:
            f.write(f"{k}\t{b!r}\t{'inf' if math.isinf(d) else repr(d)}\n")
    counts = {}
    for k, _, _ in rows:
        counts[str(k)] = counts.get(str(k), 0) + 1
    return counts


# ---------------------------------------------------------------------------------------------------------
# commands per tool kind
# ---------------------------------------------------------------------------------------------------------
def available(kind, opts, python):
    env = os.environ
    if kind == "tda4j":
        return bool(env.get("TDA4J_CP")) and Path(env["TDA4J_CP"]).exists()
    if kind == "ripser":
        return bool(env.get("RIPSER_BIN"))
    if kind == "julia":
        return bool(env.get("JULIA")) and bool(env.get("RIPSERER_PROJECT"))
    if kind == "javaplex":
        return bool(env.get("JAVAPLEX_JAR")) and bool(env.get("JAVAPLEX_BUILD"))
    if kind == "py":
        mod = {"ripser.py": "ripser", "giotto-ph": "gph", "gudhi": "gudhi", "cripser": "cripser"}[opts["pytool"]]
        r = subprocess.run([python, "-c", f"import {mod}"], capture_output=True)
        return r.returncode == 0
    return False


def command(kind, name, opts, case, data, p, threshold, warmup, trials, out, args):
    cname, task, fname, fmt, dim, _ = case
    common = [f"task={task}", f"input={data / fname}", f"dim={dim}", f"p={p}", f"warmup={warmup}",
              f"trials={trials}", f"out={out}"]
    if fmt in ("points", "distance"):
        common.append(f"format={fmt}")
    if threshold is not None:
        common.append(f"threshold={threshold!r}")
    if kind == "tda4j":
        cp = Path(os.environ["TDA4J_CP"]).read_text().strip()
        return (["java", f"-Xmx{args.jvm_heap}"] + args.jvm_opts.split() +
                ["-cp", cp, "org.appliedtopology.tda4j.PaperBenchmarkDriver",
                 f"engine={opts['engine']}", f"reps={opts['reps']}"] + common)
    if kind == "py":
        extra = [f"tool={opts['pytool']}"]
        if "variant" in opts:
            extra.append(f"variant={opts['variant']}")
        if "threads" in opts:
            extra.append(f"threads={os.cpu_count() if opts['threads'] == 'ALL' else opts['threads']}")
        return [args.python, str(HERE / "workers" / "py_worker.py")] + extra + common
    if kind == "julia":
        return ([os.environ["JULIA"], f"--project={os.environ['RIPSERER_PROJECT']}",
                 str(HERE / "workers" / "ripserer_worker.jl"), f"variant={opts['variant']}"] + common)
    if kind == "javaplex":
        cp = os.environ["JAVAPLEX_JAR"] + os.pathsep + os.environ["JAVAPLEX_BUILD"]
        return ["java", f"-Xmx{args.jvm_heap}", "-cp", cp, "JavaPlexWorker",
                f"divisions={args.javaplex_divisions}"] + common
    if kind == "ripser":
        binary = os.environ["RIPSER_BIN"] if p == 2 else os.environ.get("RIPSER_COEFF_BIN", "")
        if not binary:
            return None
        cmd = [binary, str(data / fname), "--dim", str(dim),
               "--format", "point-cloud" if fmt == "points" else "distance"]
        if threshold is not None:
            cmd += ["--threshold", repr(threshold)]
        if p != 2:
            cmd += ["--modulus", str(p)]
        return cmd
    raise ValueError(kind)


def enclosing_radius(case, data):
    _, task, fname, fmt, _, threshold = case
    if threshold is not None or task != "vr":
        return threshold
    if fmt == "distance":
        d = np.loadtxt(data / fname)
    else:
        x = np.loadtxt(data / fname)
        g = x @ x.T
        sq = np.diag(g)
        d = np.sqrt(np.maximum(sq[:, None] + sq[None, :] - 2 * g, 0))
    return float(np.min(np.max(d, axis=1)))


# ---------------------------------------------------------------------------------------------------------
# agreement
# ---------------------------------------------------------------------------------------------------------
def read_bars(path):
    bars = {}
    if not Path(path).exists():
        return None
    for line in open(path):
        k, b, d = line.split("\t")
        bars.setdefault(int(k), []).append((float(b), math.inf if d.strip() == "inf" else float(d)))
    return bars


def agreement(a, b, max_dim):
    """Largest discrepancy over degrees 0..max_dim: bottleneck distance on finite bars, essential births
    matched in sorted order (a count mismatch is reported as inf)."""
    import gudhi
    worst = 0.0
    for k in range(max_dim + 1):
        fa = [(x, y) for x, y in a.get(k, []) if math.isfinite(y) and y > x]
        fb = [(x, y) for x, y in b.get(k, []) if math.isfinite(y) and y > x]
        worst = max(worst, gudhi.bottleneck_distance(np.array(fa).reshape(-1, 2), np.array(fb).reshape(-1, 2)))
        ea = sorted(x for x, y in a.get(k, []) if math.isinf(y))
        eb = sorted(x for x, y in b.get(k, []) if math.isinf(y))
        if len(ea) != len(eb):
            return math.inf
        if ea:
            worst = max(worst, max(abs(x - y) for x, y in zip(ea, eb)))
    return worst


# ---------------------------------------------------------------------------------------------------------
# run
# ---------------------------------------------------------------------------------------------------------
def run(args):
    data = Path(args.data)
    out = Path(args.out)
    (out / "bars").mkdir(parents=True, exist_ok=True)
    (out / "logs").mkdir(parents=True, exist_ok=True)
    write_env(out, args)
    cases = [c for c in CASES if not args.cases or c[0] in args.cases.split(",")]
    tasks = set(args.tasks.split(",")) if args.tasks else None
    wanted = set(args.tools.split(",")) if args.tools else None
    fields = [int(p) for p in args.fields.split(",")]
    runs_csv = out / "runs.csv"
    new = not runs_csv.exists()
    with open(runs_csv, "a", newline="") as f:
        w = csv.writer(f)
        if new:
            w.writerow(["case", "task", "p", "tool", "mode", "status", "wall_s", "times_s", "median_s",
                        "maxrss_mb", "bars", "threshold", "multithreaded", "info"])
        for case in cases:
            cname, task = case[0], case[1]
            if tasks and task not in tasks:
                continue
            threshold = enclosing_radius(case, data)
            for p in fields:
                for name, kind, ttask, opts in TOOLS:
                    if ttask != task or (wanted and name not in wanted):
                        continue
                    opts = dict(opts)
                    opts.setdefault("pytool", name)
                    if not available(kind, opts, args.python):
                        w.writerow([cname, task, p, name, "-", "missing", "", "", "", "", "", threshold, "", ""])
                        f.flush()
                        continue
                    run_one(w, f, data, out, case, p, threshold, name, kind, opts, args)


def run_one(w, f, data, out, case, p, threshold, name, kind, opts, args):
    cname, task = case[0], case[1]
    tag = f"{cname}__p{p}__{name}"
    bars_path = out / "bars" / f"{tag}.tsv"
    mt = bool(opts.get("multithreaded"))
    print(f"[{datetime.datetime.now():%H:%M:%S}] {tag}", flush=True)

    def record(mode, status, wall, rss, info, times=None, bars=None):
        med = statistics.median(times) if times else ""
        w.writerow([cname, task, p, name, mode, status, f"{wall:.4f}",
                    json.dumps(times) if times else "", med if med == "" else f"{med:.6f}",
                    f"{rss:.1f}", json.dumps(bars) if bars else "", threshold, mt, info])
        f.flush()
        print(f"    {mode:4s} {status:10s} wall={wall:.2f}s median={med if med == '' else f'{med:.3f}s'} "
              f"rss={rss:.0f}MB {info}", flush=True)

    if kind == "ripser":
        cmd = command(kind, name, opts, case, data, p, threshold, 0, 1, bars_path, args)
        if cmd is None:
            record("cold", "missing", 0, 0, "no coefficient build for p != 2")
            return
        walls, rss_max = [], 0.0
        for t in range(max(1, args.trials)):
            status, wall, rss, text = run_proc(cmd, args.timeout, out / "logs" / f"{tag}__{t}")
            rss_max = max(rss_max, rss)
            if status != "ok":
                record("cold", status, wall, rss, "")
                return
            if t == 0:
                counts = parse_ripser_output(text, bars_path)
                record("cold", status, wall, rss, "", [wall], counts)
            walls.append(wall)
        record("warm", "ok", sum(walls), rss_max, "median of cold runs", walls, counts)
        return

    cmd = command(kind, name, opts, case, data, p, threshold, 0, 1, bars_path, args)
    status, wall, rss, text = run_proc(cmd, args.timeout, out / "logs" / f"{tag}__cold")
    j = last_json(text) if status == "ok" else None
    record("cold", status, wall, rss, "", j["times_s"] if j else None, j["bars"] if j else None)
    if status != "ok" or args.trials <= 0:
        return
    cmd = command(kind, name, opts, case, data, p, threshold, args.warmup, args.trials, bars_path, args)
    status, wall, rss, text = run_proc(cmd, args.timeout * (args.warmup + args.trials), out / "logs" / f"{tag}__warm")
    j = last_json(text) if status == "ok" else None
    record("warm", status, wall, rss, "", j["times_s"] if j else None, j["bars"] if j else None)


def write_env(out, args):
    def cmd_out(cmd):
        try:
            r = subprocess.run(cmd, capture_output=True, text=True, timeout=60)
            return (r.stdout + r.stderr).strip()
        except Exception as e:
            return f"unavailable: {e}"
    cpu = ""
    if Path("/proc/cpuinfo").exists():
        cpu = next((l.split(":", 1)[1].strip() for l in open("/proc/cpuinfo") if l.startswith("model name")), "")
    env = {
        "date": datetime.datetime.now().isoformat(),
        "host": platform.node(), "platform": platform.platform(), "cpu": cpu, "cores": os.cpu_count(),
        "mem_total": next((l.split(":")[1].strip() for l in open("/proc/meminfo")), "") if Path("/proc/meminfo").exists() else "",
        "java": cmd_out(["java", "-version"]),
        "python_packages": cmd_out([args.python, "-m", "pip", "freeze"]),
        "julia": cmd_out([os.environ["JULIA"], "--version"]) if os.environ.get("JULIA") else "",
        "ripser_git": cmd_out(["git", "-C", str(Path(os.environ.get("RIPSER_BIN", ".")).parent), "rev-parse", "HEAD"]),
        "tda4j_git": cmd_out(["git", "-C", str(HERE.parent), "rev-parse", "HEAD"]),
        "args": vars(args),
    }
    with open(out / "env.json", "w") as f:
        json.dump(env, f, indent=1, default=str)


# ---------------------------------------------------------------------------------------------------------
# summarize
# ---------------------------------------------------------------------------------------------------------
def summarize(out: Path):
    rows = list(csv.DictReader(open(out / "runs.csv")))
    case_dim = {c[0]: c[4] for c in CASES}
    try:
        divisions = json.load(open(out / "env.json"))["args"]["javaplex_divisions"]
    except Exception:
        divisions = 1000
    by = {}
    for r in rows:
        by.setdefault((r["case"], r["p"], r["tool"]), {})[r["mode"]] = r
    summary = []
    for (cname, p, tool), modes in sorted(by.items()):
        cold, warm = modes.get("cold", {}), modes.get("warm", {})
        task = (cold or warm or next(iter(modes.values())))["task"]
        ref = REFERENCE[task]
        mine = read_bars(out / "bars" / f"{cname}__p{p}__{tool}.tsv") if cold.get("status") == "ok" else None
        theirs = read_bars(out / "bars" / f"{cname}__p{p}__{ref}.tsv")
        dist = agreement(mine, theirs, case_dim[cname]) if (mine is not None and theirs is not None) else None
        thr = cold.get("threshold") or ""
        scale = float(thr) if thr not in ("", "None") else 1.0
        tol = 1e-5 * max(1.0, scale)
        if tool == "javaplex" and thr not in ("", "None"):
            tol = max(tol, float(thr) / float(divisions))  # JavaPlex snaps values to threshold/divisions
        summary.append({
            "case": cname, "task": task, "p": p, "tool": tool,
            "status": warm.get("status") or cold.get("status") or next(iter(modes.values()))["status"],
            "cold_s": cold.get("wall_s", ""), "warm_median_s": warm.get("median_s", ""),
            "maxrss_mb": max([float(m["maxrss_mb"]) for m in modes.values() if m.get("maxrss_mb")] or [0]),
            "bars": cold.get("bars", ""), "multithreaded": cold.get("multithreaded", ""),
            "vs_reference": "" if dist is None else f"{dist:.3g}",
            "agrees": "" if dist is None else str(dist <= tol),
        })
    with open(out / "summary.csv", "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(summary[0].keys()))
        w.writeheader()
        w.writerows(summary)

    # TDA4j's slowdown: its warm median against the fastest other single-threaded tool, and against the
    # reference; geometric means over the cases where both finished.
    lines = ["# TDA4j cross-platform benchmark", "", f"Results: `{out}`. Environment: `env.json`.", ""]
    lines += ["| case | p | tool | status | cold s | warm s | peak MB | bars | Δ vs reference | agrees |",
              "|---|---|---|---|---|---|---|---|---|---|"]
    for s in summary:
        lines.append(f"| {s['case']} | {s['p']} | {s['tool']} | {s['status']} | {s['cold_s']} | "
                     f"{s['warm_median_s']} | {s['maxrss_mb']:.0f} | {s['bars']} | {s['vs_reference']} | {s['agrees']} |")
    lines += ["", "## TDA4j slowdown (warm median ÷ other tool's warm median; cold in parentheses)", "",
              "| case | p | TDA4j variant | best other single-threaded | × best | × reference |", "|---|---|---|---|---|---|"]
    ratios_best, ratios_ref = {}, {}
    groups = {}
    for s in summary:
        groups.setdefault((s["case"], s["p"]), []).append(s)
    for (cname, p), ss in sorted(groups.items()):
        ok = [s for s in ss if s["status"] == "ok" and s["warm_median_s"] not in ("", None)]
        others = [s for s in ok if not s["tool"].startswith("tda4j") and s["multithreaded"] != "True"]
        if not others:
            continue
        best = min(others, key=lambda s: float(s["warm_median_s"]))
        ref = next((s for s in ok if s["tool"] == REFERENCE[ss[0]["task"]]), None)
        for t in [s for s in ok if s["tool"].startswith("tda4j")]:
            rb = float(t["warm_median_s"]) / float(best["warm_median_s"])
            rr = float(t["warm_median_s"]) / float(ref["warm_median_s"]) if ref else None
            cb = float(t["cold_s"]) / float(best["cold_s"]) if t["cold_s"] and best["cold_s"] else None
            ratios_best.setdefault((t["tool"], p), []).append(rb)
            if rr:
                ratios_ref.setdefault((t["tool"], p), []).append(rr)
            cold = f" ({cb:.2f})" if cb else ""
            ref_text = "" if rr is None else f"{rr:.2f}"
            lines.append(f"| {cname} | {p} | {t['tool']} | {best['tool']} | {rb:.2f}{cold} | {ref_text} |")
    lines += ["", "Geometric means:", ""]
    for key, vals in sorted(ratios_best.items()):
        gm = math.exp(sum(math.log(v) for v in vals) / len(vals))
        ref_vals = ratios_ref.get(key, [])
        gr = math.exp(sum(math.log(v) for v in ref_vals) / len(ref_vals)) if ref_vals else float("nan")
        lines.append(f"- {key[0]}, p={key[1]}: {gm:.2f}x the best other tool, {gr:.2f}x the reference "
                     f"({len(vals)} cases)")
    (out / "summary.md").write_text("\n".join(lines) + "\n")
    print("\n".join(lines))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    p1 = sub.add_parser("prepare")
    p1.add_argument("--data", required=True)
    p2 = sub.add_parser("run")
    p2.add_argument("--data", required=True)
    p2.add_argument("--out", required=True)
    p2.add_argument("--cases", default="", help="comma-separated case names (default: all)")
    p2.add_argument("--tasks", default="", help="comma-separated: vr,cubical,alpha (default: all)")
    p2.add_argument("--tools", default="", help="comma-separated tool names (default: all available)")
    p2.add_argument("--fields", default="2", help="comma-separated primes, e.g. 2,17")
    p2.add_argument("--warmup", type=int, default=2)
    p2.add_argument("--trials", type=int, default=5)
    p2.add_argument("--timeout", type=float, default=1800, help="seconds per computation")
    p2.add_argument("--jvm-heap", default="16g")
    p2.add_argument("--jvm-opts", default="")
    p2.add_argument("--javaplex-divisions", type=int, default=1000)
    p2.add_argument("--python", default=sys.executable)
    p3 = sub.add_parser("summarize")
    p3.add_argument("out")
    args = ap.parse_args()
    if args.cmd == "prepare":
        prepare(Path(args.data))
    elif args.cmd == "run":
        run(args)
        summarize(Path(args.out))
    else:
        summarize(Path(args.out))


if __name__ == "__main__":
    main()
