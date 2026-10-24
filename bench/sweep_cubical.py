#!/usr/bin/env python3
"""Size sweep for TDA4j's image engines: which one should `Persistence(Image(...))` pick by default?

    bench/run.sh sweep-cubical [--dims 2,3] [--images random,blob] [--timeout 1800] [--jvm-heap 16g]

`run.sh setup` must have run (it builds the TDA4j classpath, the TDA4j npm package and the Python venv). For each image
dimension, image kind and size, every engine runs in its own JVM, one warm-up plus `--trials` timed computations.
CubicalRipser and GUDHI run alongside for scale, and so does the Scala.js build of TDA4j (`js-fastcubical`,
`js-cohomology-cycles`: the same engines on Node.js) when `setup` installed it; `--no-js` leaves it out.

Engines:
  - fastcubical: union-find on the image and its dual grid; in 3-D and up, grid cohomology for the middle degrees.
    This is what `Engine.Auto` picks today.
  - cohomology: the general cohomology engine (cocycles native, cycles by the involution; both are timed).
  - chunks: homology by clearing and compression (2-D only by default; slow in 3-D).
  - js-fastcubical, js-cohomology-cycles: the first two on the Scala.js build, in Node.js (`--node-heap` sets its heap).

A series stops growing once one size times out. Output, in --out (default $WORKDIR/sweeps/cubical-<timestamp>):
  sweep.csv (one row per run), sweep.md (median seconds, µs per pixel, and the ratio fast / cohomology).
"""
import argparse
import csv
import datetime
import json
import os
import statistics
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import bench  # noqa: E402  (run_proc, last_json, blob)

SIZES = {2: [128, 256, 512, 1024, 2048, 4096], 3: [16, 24, 32, 48, 64, 96, 128], 4: [6, 8, 10, 12, 16]}
ENGINES = [  # (label, engine, reps, dims, largest pixel count or 0 for no cap); JS_ENGINES: the same on Scala.js
    ("fastcubical", "fastcubical", "cycles", {2, 3, 4}, 0),
    ("cohomology-cycles", "cohomology", "cycles", {2, 3, 4}, 0),
    ("cohomology-cocycles", "cohomology", "cocycles", {2, 3, 4}, 0),
    ("chunks", "chunks", "cycles", {2}, 512 * 512),  # already 78 s at 256²: a reference point, not a contender
]
JS_ENGINES = [
    ("js-fastcubical", "fastcubical", "cycles", {2, 3, 4}, 0),
    ("js-cohomology-cycles", "cohomology", "cycles", {2, 3, 4}, 0),
]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dims", default="2,3")
    ap.add_argument("--images", default="random,blob")
    ap.add_argument("--trials", type=int, default=3)
    ap.add_argument("--timeout", type=float, default=1800)
    ap.add_argument("--jvm-heap", default="16g")
    ap.add_argument("--jvm-fixed-heap", action="store_true",
                    help="-Xmx = -Xms with the parallel collector instead of the default serial collector with a heap "
                         "that follows the live data (peak RSS then shows what a run allocated, not what it needs)")
    ap.add_argument("--node-heap", default="", help="heap of the js-* engines, in the JVM's notation (default: --jvm-heap)")
    ap.add_argument("--no-js", action="store_true", help="skip the Scala.js build of TDA4j")
    ap.add_argument("--out", default="")
    ap.add_argument("--data", required=True)
    ap.add_argument("--python", default=sys.executable)
    ap.add_argument("--no-reference", action="store_true", help="skip CubicalRipser and GUDHI")
    ap.add_argument("--max-pixels", type=int, default=0, help="skip sizes with more pixels (0: no cap)")
    ap.add_argument("--engines", default="", help="comma-separated labels (default: all; see ENGINES, plus cripser,gudhi)")
    ap.add_argument("--warm-cap", type=float, default=60,
                    help="repeat a computation (warm-up + trials) only if one cold run took less than this (s)")
    args = ap.parse_args()

    out = Path(args.out or Path(os.environ.get("WORKDIR", ".")) / "sweeps" /
               f"cubical-{datetime.datetime.now():%Y%m%d-%H%M%S}")
    (out / "logs").mkdir(parents=True, exist_ok=True)
    data = Path(args.data) / "sweep"
    data.mkdir(parents=True, exist_ok=True)
    cp = Path(os.environ["TDA4J_CP"]).read_text().strip()
    js = not args.no_js and bench.available("tda4j-js", {}, args.python)
    if not args.no_js and not js:
        print("the Scala.js build is not installed (bench/run.sh setup tda4j-js): js-* engines are left out", flush=True)
    rng = np.random.default_rng(20261005)
    rows = []
    with open(out / "sweep.csv", "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["dim", "image", "n", "pixels", "engine", "status", "median_s", "times_s", "us_per_pixel",
                    "maxrss_mb", "bars", "rss_src", "heap_peak_mb", "reason"])
        for d in [int(x) for x in args.dims.split(",")]:
            for image in args.images.split(","):
                stopped = set()
                for n in SIZES[d]:
                    if args.max_pixels and n ** d > args.max_pixels:
                        continue
                    shape = (n,) * d
                    path = data / f"{image}{d}d_{n}.npy"
                    if not path.exists():
                        np.save(path, rng.random(shape) if image == "random" else bench.blob(shape, rng))
                    wanted = set(args.engines.split(",")) if args.engines else None

                    def command(label, warmup, trials):
                        for lab, engine, reps, dims, cap in JS_ENGINES:
                            if lab == label:
                                return [os.environ.get("NODE", "node"),
                                        f"--max-old-space-size={bench.node_heap_mb(args.node_heap or args.jvm_heap)}",
                                        str(bench.HERE / "workers" / "tda4j_js_worker.mjs"), "task=cubical",
                                        f"input={path}", f"dim={d - 1}", "p=2", f"engine={engine}", f"reps={reps}",
                                        f"warmup={warmup}", f"trials={trials}"]
                        for lab, engine, reps, dims, cap in ENGINES:
                            if lab == label:
                                return (["java"] + bench.jvm_flags(args.jvm_heap, fixed_heap=args.jvm_fixed_heap) +
                                        ["-cp", cp, "org.appliedtopology.tda4j.PaperBenchmarkDriver", "task=cubical",
                                         f"input={path}", f"dim={d - 1}", "p=2", f"engine={engine}", f"reps={reps}",
                                         f"warmup={warmup}", f"trials={trials}"])
                        return [args.python, str(bench.HERE / "workers" / "py_worker.py"), f"tool={label}",
                                "task=cubical", f"input={path}", f"dim={d - 1}", "p=2", f"warmup={warmup}",
                                f"trials={trials}"]

                    labels = [lab for lab, _, _, dims, cap in ENGINES if d in dims and (not cap or n ** d <= cap)]
                    if js:
                        labels += [lab for lab, _, _, dims, cap in JS_ENGINES if d in dims and (not cap or n ** d <= cap)]
                    if not args.no_reference:
                        labels += ["cripser", "gudhi"]
                    for label in labels:
                        if label in stopped or (wanted and label not in wanted):
                            continue
                        tag = f"{d}d_{image}_{n}_{label}"
                        # Cold first: one computation, bounded by --timeout. Repeat only when that was quick enough
                        # for warm-up to matter; past --warm-cap the cold time is the measurement.
                        cold = bench.run_proc(command(label, 0, 1), args.timeout, out / "logs" / (tag + "__cold"))
                        status, wall, rss, reason, rss_src = cold.status, cold.wall, cold.rss_mb, cold.reason, cold.rss_src
                        j = bench.last_json(cold.text) if status == "ok" else None
                        if j and j["times_s"][0] < args.warm_cap and args.trials > 0:
                            warm = bench.run_proc(command(label, 1, args.trials), args.timeout * (args.trials + 1),
                                                  out / "logs" / (tag + "__warm"))
                            j2 = bench.last_json(warm.text) if warm.status == "ok" else None
                            if j2:
                                j, rss = j2, max(rss, warm.rss_mb)
                        med = statistics.median(j["times_s"]) if j else None
                        upp = med / (n ** d) * 1e6 if med else None
                        w.writerow([d, image, n, n ** d, label, status, med, json.dumps(j["times_s"]) if j else "",
                                    upp, f"{rss:.0f}", json.dumps(j["bars"]) if j else "", rss_src,
                                    j.get("heap_peak_mb", "") if j else "", reason])
                        f.flush()
                        rows.append((d, image, n, label, status, med, upp, rss))
                        print(f"{tag:40s} {status:9s} " + (f"{med:9.3f}s {upp:8.2f}us/px {rss:7.0f}MB" if med
                              else f"wall={wall:.0f}s"), flush=True)
                        if status != "ok":
                            stopped.add(label)
    write_table(out, rows)
    print(f"\nresults in {out}")


def write_table(out, rows):
    lines = ["# Image engines: size sweep", "", "Median seconds (µs per pixel). `fast/coh` = fastcubical ÷ "
             "cohomology-cycles: above 1, the general engine is faster. `js/jvm` = js-fastcubical ÷ fastcubical.", ""]
    labels = [e[0] for e in ENGINES] + ["cripser", "gudhi"] + [e[0] for e in JS_ENGINES]
    lines.append("| dim | image | n | " + " | ".join(labels) + " | fast/coh | js/jvm |")
    lines.append("|---" * (len(labels) + 5) + "|")
    keys = sorted({(r[0], r[1], r[2]) for r in rows})
    for d, image, n in keys:
        cells, by = [], {}
        for r in rows:
            if (r[0], r[1], r[2]) == (d, image, n):
                by[r[3]] = r
        for lab in labels:
            r = by.get(lab)
            cells.append("" if r is None else (f"{r[5]:.3g} ({r[6]:.3g})" if r[5] else r[4]))
        f, c, j = by.get("fastcubical"), by.get("cohomology-cycles"), by.get("js-fastcubical")
        ratio = f"{f[5] / c[5]:.2f}" if f and c and f[5] and c[5] else ""
        js_ratio = f"{j[5] / f[5]:.2f}" if j and f and j[5] and f[5] else ""
        lines.append(f"| {d} | {image} | {n} | " + " | ".join(cells) + f" | {ratio} | {js_ratio} |")
    (out / "sweep.md").write_text("\n".join(lines) + "\n")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
