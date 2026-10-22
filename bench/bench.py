#!/usr/bin/env python3
"""Cross-platform persistent-homology benchmark: TDA4j against the other libraries at the frontier.

    bench.py prepare  --data DATA                        download the benchmark data, generate the synthetic sets
    bench.py run      --data DATA --out RESULTS [...]    time every (case, tool, field), check the barcodes agree
    bench.py summarize RESULTS                           tables: per case, and TDA4j's slowdown

Normally driven by bench/run.sh, which builds the tools and sets the environment variables read here
(TDA4J_CP, TDA4J_JS, NODE, RIPSER_BIN, RIPSER_COEFF_BIN, JULIA, RIPSERER_PROJECT, JAVAPLEX_JAR, JAVAPLEX_BUILD). A tool
whose variable is unset, or whose Python package is missing, is skipped and listed as `missing`.

The `tda4j-js*` tools are the Scala.js build, packaged as the npm package and run on Node.js
(workers/tda4j_js_worker.mjs): the same sources as `tda4j*`, so the pair of rows is the cost of the platform.

Method (see .claude/PLAN-paper.md, section 3.0):
  - Every run is its own process, killed at --timeout. Peak RSS is the kernel's VmHWM of that process, sampled from
    /proc while it runs (wait4's ru_maxrss would include the harness's own RSS: the kernel folds the parent's
    high-water mark into the child at exec). The JVM rows also report the peak use of the heap pools.
  - A run that does not finish is `timeout`, `limit` (the tool's own index range), `oom`, `unsupported` or
    `error(code)`, with the last line of its stderr as the reason; a timeout at one field is not rerun at the next.
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
import hashlib
import json
import math
import os
import platform
import re
import shutil
import signal
import statistics
import subprocess
import sys
import time
import urllib.request
from pathlib import Path
from typing import NamedTuple, Optional

import numpy as np

HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))
import dipha_io  # noqa: E402
import phat_io  # noqa: E402
RIPSER_BENCH = "https://raw.githubusercontent.com/Ripser/ripser-benchmark/master"
ROADMAP = "https://raw.githubusercontent.com/n-otter/PH-roadmap/master/data_sets"
# The cyclooctane conformations (6040 points in R^24; Martin, Thompson, Coutsias and Watson 2010), as JavaPlex's MATLAB
# tutorial ships them. Pinned to a commit and to the file's SHA-256: a changed file would change the benchmark.
JAVAPLEX_TUTORIAL = ("https://github.com/appliedtopology/javaplex/raw/05a288a4a99f27242d8f4b24e98d4cce749fb71a/"
                     "src/matlab/for_distribution/tutorial_examples")
CYCLOOCTANE_SHA256 = "6725fa217e770fe8a65c20d82c347cc5cfa17d557488b38a1b554d90566f79cc"

# ---------------------------------------------------------------------------------------------------------
# Cases. The Vietoris-Rips ones are the Ripser paper's benchmark (ripser-benchmark's Dockerfile, same flags
# minus --ratio, which only filters printed output), plus the sphere3 size ladder.
# ---------------------------------------------------------------------------------------------------------
class Case(NamedTuple):
    name: str
    task: str
    file: str
    format: str  # points | distance | npy
    dim: int  # the highest homological degree computed
    threshold: Optional[float] = None  # the largest filtration value; None: the enclosing radius (VR) or no limit
    radius_k: Optional[int] = None  # alpha: a radius at which a point has about this many neighbours within 2r
    degenerate: bool = False  # not in general position: the reference is not ground truth for the barcode
    params: tuple = ()  # (key, value) pairs every tool is given as key=value: eps, k, L, nu, ...


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
    # Cyclooctane (R^24, 6040 conformations): Ripser to the cutoff 0.5, the same data and degrees as the alpha case below
    # at the radius 0.25 (two balls of radius r meet at distance 2r).
    ("cyclooctane", "vr", "cyclooctane.txt", "points", 2, 0.5),
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
    # The two point sets below are not in general position (the 192 points of sphere_3_192_points.dat lie on the unit sphere of
    # R^3, three coordinates each, and are cospherical; the dragon is a scan),
    # so each tool triangulates the degenerate cells its own way and GUDHI is no ground truth: kept, flagged, and
    # paired with a jittered copy (`_gp`, general position) that all tools read instead.
    Case("dragon2000_alpha", "alpha", "dragon_2000.txt", "points", 2, degenerate=True),
    Case("sphere3_192_alpha", "alpha", "sphere_3_192_points.dat", "points", 2, degenerate=True),
    Case("dragon2000_alpha_gp", "alpha", "dragon_2000.gp.txt", "points", 2),
    Case("sphere3_192_alpha_gp", "alpha", "sphere_3_192.gp.txt", "points", 2),
    # High ambient dimension, the regime the dual-QP and Helix backends are for (issues #131, #133). unif5d_500 is the
    # Helix crossover table's 5-D case (CGAL still finishes it). unif10d_1000 takes its parameters from the DQP paper's
    # random example (Carlsson and Carlsson, Sci. Rep. 14:19824, section 4.2): 1000 points uniform in [-1,1]^10, radius
    # 1.0, up to 3-simplices; a full Delaunay triangulation is out of reach there (GUDHI and Helix are expected to time
    # out, which is a result). o3_1024_alpha: the Ripser benchmark's 3x3 orthogonal matrices in R^9, with a radius at
    # which a point has about 25 neighbours within 2r.
    Case("unif5d_500", "alpha", "unif5d_500.txt", "points", 2),
    Case("unif10d_1000", "alpha", "unif10d_1000.txt", "points", 2, threshold=1.0),
    Case("o3_1024_alpha", "alpha", "o3_1024.txt", "points", 2, radius_k=25),
    # Cyclooctane as the DQP paper uses it: Alpha(S, 0.25) up to 3-simplices. A real data set of 24 coordinates, for which
    # a full Delaunay triangulation is out of reach. The bars alive at the radius are the Betti numbers there (the
    # summary's `betti` column, per field).
    Case("cyclooctane_alpha", "alpha", "cyclooctane.txt", "points", 2, threshold=0.25),

    # --- the rarer complexes -------------------------------------------------------------------------------------
    # cech: Cech radii. The reference is GUDHI's Delaunay-Cech complex (same persistent homology, other values per
    # simplex and another number of cells). A radius bounds the work: Cech without one is every subset of dim + 2 points.
    Case("cech_unif3d_80", "cech", "unif3d_80.txt", "points", 2),
    Case("cech_unif3d_1000", "cech", "unif3d_1000.txt", "points", 2, threshold=0.08),
    Case("cech_unif5d_500", "cech", "unif5d_500.txt", "points", 2, threshold=0.2),
    # dtm: the DTM-weighted Rips complex of Anai et al. (p = 1), k neighbours counting the point itself. The circle with
    # outliers is the reason the filtration exists: the outliers' bars should not be there.
    Case("dtm_circle_outliers", "dtm", "circle_outliers_600.txt", "points", 1, threshold=0.6, params=(("k", 10),)),
    Case("dtm_unif3d_1000", "dtm", "unif3d_1000.txt", "points", 2, threshold=0.15, params=(("k", 10),)),
    # witness: landmarks are chosen once by maxmin (bench.py) and given to every tool as point indices.
    Case("witness_unif3d_1000_L50", "witness", "unif3d_1000.txt", "points", 2, params=(("L", 50),)),
    Case("witness_unif3d_5000_L100", "witness", "unif3d_5000.txt", "points", 2, params=(("L", 100),)),
    # sparse: Sheehy's sparse Rips (TDA4j: Cavanna-Jahanseir-Sheehy) against exact Rips. The reference is ripser.cpp, so
    # the agreement column is the approximation error (bottleneck distance), not a check that two tools agree.
    Case("sparse_sphere3_96", "sparse", "sphere3_96.txt", "points", 2, params=(("eps", 0.3),)),
    Case("sparse_o3_1024", "sparse", "o3_1024.txt", "points", 2, threshold=1.8, params=(("eps", 0.3),)),
    Case("sparse_unif3d_1000", "sparse", "unif3d_1000.txt", "points", 2, threshold=0.25, params=(("eps", 0.3),)),
    # dowker: a relation matrix, rows are the vertices of the complex and columns the witnesses (made by `prepare`).
    Case("dowker_300x100", "dowker", "relation_300x100.txt", "distance", 1, threshold=0.35),
    Case("dowker_60x200", "dowker", "relation_60x200.txt", "distance", 2, threshold=0.5),
]
CASES = [c if isinstance(c, Case) else Case(*c) for c in CASES]

# One reference count of the cells of each case's complex, made once by count_cells.py --reference and checked in: a
# benchmark run never counts. `summarize` joins it as the `cells` column (blank for a case without a count).
CELLS_REFERENCE = HERE / "cells-reference.csv"


def cells_reference():
    """{case: total cells} from CELLS_REFERENCE; empty when there is no such file."""
    try:
        with open(CELLS_REFERENCE) as f:
            return {r["case"]: r["total"] for r in csv.DictReader(f)}
    except OSError:
        return {}

# ---------------------------------------------------------------------------------------------------------
# Tools: (name, kind, task, options). `multithreaded` rows are reported but never count as the
# single-threaded baseline. Reference tool per task is used for agreement.
# ---------------------------------------------------------------------------------------------------------
TOOLS = [
    ("tda4j", "tda4j", "vr", {"engine": "auto", "reps": "cycles"}),
    ("tda4j-cocycles", "tda4j", "vr", {"engine": "auto", "reps": "cocycles"}),
    # The block-parallel experiment (#194): bars only, no representatives, implicit coboundaries; bench/sweep_threads.py
    # runs the `multithreaded` rows at 1, 2, 4, ... threads.
    ("tda4j-parallel-1t", "tda4j", "vr", {"engine": "parallel", "reps": "none", "threads": "1"}),
    ("tda4j-parallel-mt", "tda4j", "vr", {"engine": "parallel", "reps": "none", "threads": "ALL", "multithreaded": True}),
    ("ripser.cpp", "ripser", "vr", {}),
    # DIPHA (Bauer, Kerber and Reininghaus; MPI): Z/2 only, always the dualized algorithm (as in the Ripser paper's
    # Dockerfile). Its input is a sparse distance matrix with the threshold in it, made before the run and not timed
    # (dipha_io.py); `dipha-mt` is the same under mpiexec with a rank per core, and its memory is the sum over the ranks.
    ("dipha", "dipha", "vr", {"only_p": 2}),
    ("dipha-mt", "dipha", "vr", {"only_p": 2, "multithreaded": True, "ranks": "ALL"}),
    # PHAT (Bauer, Kerber, Reininghaus and Wagner) reduces a prebuilt filtered boundary matrix, over Z/2: it builds no complex.
    # The matrix is DIPHA's Vietoris-Rips complex written beforehand by make_phat (not timed), so a `kernel` row times the
    # reduction alone (reading the matrix, reducing it, writing the pairs) and is never the baseline another tool is measured
    # against. `phat` is its default (twist, bit-tree pivot columns); `phat-dual` is the same on the dual (cohomology).
    ("phat", "phat", "vr", {"only_p": 2, "kernel": True}),
    ("phat-dual", "phat", "vr", {"only_p": 2, "kernel": True, "dualize": True}),
    ("ripser.py", "py", "vr", {}),
    ("giotto-ph-1t", "py", "vr", {"pytool": "giotto-ph", "threads": "1"}),
    ("giotto-ph-mt", "py", "vr", {"pytool": "giotto-ph", "threads": "ALL", "multithreaded": True}),
    ("gudhi", "py", "vr", {}),
    ("gudhi-collapse", "py", "vr", {"pytool": "gudhi", "variant": "collapse"}),
    # Dionysus 2 (float32): its cohomology algorithm is the fast one and the default row; `-homology` is the clearing
    # reduction; `-cocycles` also reads a representative cocycle for every bar. A `slower_than` variant is not run on a
    # case where the named tool timed out or ran out of memory: that would only spend the timeout again.
    ("dionysus", "py", "vr", {"variant": "cohomology"}),
    ("dionysus-homology", "py", "vr", {"pytool": "dionysus", "variant": "homology", "slower_than": "dionysus"}),
    ("dionysus-cocycles", "py", "vr", {"pytool": "dionysus", "variant": "cocycles", "slower_than": "dionysus"}),
    # OAT (OpenAppliedTopology, Rust) computes over the rationals, whatever the field of the run: it takes no modulus.
    # `oat-cycles` also returns a cycle representative for every bar.
    ("oat", "py", "vr", {"char0": True}),
    ("oat-cycles", "py", "vr", {"pytool": "oat", "variant": "cycles", "char0": True, "slower_than": "oat"}),
    ("ripserer", "julia", "vr", {"variant": "cohomology"}),
    ("ripserer-involuted", "julia", "vr", {"variant": "involuted"}),
    # Eirene (Henselman-Petrusek; Julia): Z/2 only. `eirene` is `record="none"`, the barcode, as the Ripser paper's Dockerfile
    # runs it; `eirene-cycles` is `record="cyclerep"` and also reads a representative cycle for every bar.
    ("eirene", "julia", "vr", {"julia_tool": "eirene", "variant": "bars", "only_p": 2}),
    ("eirene-cycles", "julia", "vr",
     {"julia_tool": "eirene", "variant": "cycles", "only_p": 2, "slower_than": "eirene"}),
    ("javaplex", "javaplex", "vr", {}),
    ("tda4j", "tda4j", "cubical", {"engine": "auto", "reps": "cycles"}),
    ("tda4j-cohomology", "tda4j", "cubical", {"engine": "cohomology", "reps": "cocycles"}),
    ("gudhi", "py", "cubical", {}),
    ("cripser", "py", "cubical", {}),
    ("tda4j", "tda4j", "alpha", {"engine": "auto", "reps": "cycles"}),
    # fast-alpha runs on the whole triangulation, so it takes no radius; Bowyer-Watson refuses an affine span above
    # 4; DQP is only run with a radius (untruncated it is about 100 times slower than Helix in 2-D).
    ("tda4j-fastalpha", "tda4j", "alpha", {"engine": "fastalpha", "reps": "cycles", "no_radius": True}),
    # The default above is Bowyer-Watson up to 4 dimensions, Helix above and DQP for a small radius; these name each.
    ("tda4j-bw", "tda4j", "alpha", {"engine": "auto", "reps": "cycles", "backend": "bowyer-watson", "max_ambient": 4}),
    ("tda4j-helix", "tda4j", "alpha", {"engine": "auto", "reps": "cycles", "backend": "helix"}),
    ("tda4j-dqp", "tda4j", "alpha", {"engine": "auto", "reps": "cycles", "backend": "dqp", "needs_radius": True}),
    ("gudhi", "py", "alpha", {}),
    # The Cech complex (minimum enclosing balls, no triangulation) has the barcode of the alpha complex in general
    # position (nerve lemma): a check of the alpha code by another algorithm. Its own task, so no case runs it by itself;
    # sweep_alpha.py does.
    ("tda4j-cech", "tda4j", "cech", {"engine": "auto", "reps": "cycles"}),
    ("gudhi", "py", "cech", {}),
    # The edge-collapsed Rips complex (Boissonnat-Pritam; GUDHI's `collapse_edges` is the other implementation, above).
    ("tda4j-collapse", "tda4j", "vr", {"engine": "auto", "reps": "cycles", "collapse": "true"}),
    # DTM-Rips; GUDHI's DTMRipsComplex (needs scipy).
    ("tda4j", "tda4j", "dtm", {"engine": "auto", "reps": "cycles"}),
    ("gudhi", "py", "dtm", {}),
    # Witness complexes: TDA4j's lazy (flag; nu = 2) and general variants; GUDHI's weak and strong witness complexes.
    # Whether either of GUDHI's is one of ours is unverified (issue #171), so the agreement column may be nonzero.
    ("tda4j", "tda4j", "witness", {"engine": "auto", "reps": "cycles"}),
    ("tda4j-general", "tda4j", "witness", {"engine": "auto", "reps": "cycles", "wvariant": "general"}),
    ("gudhi", "py", "witness", {}),
    ("gudhi-strong", "py", "witness", {"pytool": "gudhi", "variant": "strong"}),
    # Sparse Rips: two different constructions with incomparable epsilons (TDA4j: Cavanna-Jahanseir-Sheehy 2015; GUDHI:
    # Buchet-Chazal-Oudot-Sheehy). ripser.cpp is the exact barcode both approximate.
    ("tda4j", "tda4j", "sparse", {"engine": "auto", "reps": "cycles"}),
    ("gudhi", "py", "sparse", {}),
    ("ripser.cpp", "ripser", "sparse", {}),
    # Dowker: TDA4j on the rows and on the columns (`.dual`, the same barcode without zero-length bars), and the
    # SimplexTree a user would fill by hand (a Python baseline, see py_worker.dowker_baseline).
    ("tda4j", "tda4j", "dowker", {"engine": "auto", "reps": "cycles"}),
    ("tda4j-dual", "tda4j", "dowker", {"engine": "auto", "reps": "cycles", "dual": "true"}),
    ("gudhi", "py", "dowker", {}),
    ("gudhi-dual", "py", "dowker", {"pytool": "gudhi", "variant": "dual"}),
    # Blaser and Brun's `dowker_homology` (exact nerve; Python on GUDHI) and Yoon's Dowker persistence (Julia on a
    # modified Eirene; degrees 0 and 1 over Z/2 only). `run.sh setup dowker` clones both at pinned commits.
    ("dowker-nerves", "py", "dowker", {}),
    ("dowker-yoon", "julia", "dowker", {"julia_tool": "dowker_yoon", "variant": "bars", "only_p": 2, "max_dim": 1}),
]
# The Scala.js build of TDA4j (the npm package, on Node.js) beside each JVM variant above. A tool named `tda4j-js...`
# is the JavaScript twin of `tda4j...`: summarize pairs them.
TOOLS += [
    ("tda4j-js", "tda4j-js", "vr", {"engine": "auto", "reps": "cycles"}),
    ("tda4j-js-cocycles", "tda4j-js", "vr", {"engine": "auto", "reps": "cocycles"}),
    ("tda4j-js", "tda4j-js", "cubical", {"engine": "auto", "reps": "cycles"}),
    ("tda4j-js-cohomology", "tda4j-js", "cubical", {"engine": "cohomology", "reps": "cocycles"}),
    ("tda4j-js", "tda4j-js", "alpha", {"engine": "auto", "reps": "cycles"}),
    ("tda4j-js-fastalpha", "tda4j-js", "alpha", {"engine": "fastalpha", "reps": "cycles", "no_radius": True}),
    ("tda4j-js-bw", "tda4j-js", "alpha",
     {"engine": "auto", "reps": "cycles", "backend": "bowyer-watson", "max_ambient": 4}),
    ("tda4j-js-helix", "tda4j-js", "alpha", {"engine": "auto", "reps": "cycles", "backend": "helix"}),
    ("tda4j-js-dqp", "tda4j-js", "alpha",
     {"engine": "auto", "reps": "cycles", "backend": "dqp", "needs_radius": True}),
]
REFERENCE = {"vr": "ripser.cpp", "cubical": "gudhi", "alpha": "gudhi", "cech": "gudhi", "dtm": "gudhi",
             "witness": "gudhi", "sparse": "ripser.cpp", "dowker": "gudhi"}
CHARACTERISTIC_ZERO = {name for name, _, _, opts in TOOLS if opts.get("char0")}
KERNEL_TOOLS = {name for name, _, _, opts in TOOLS if opts.get("kernel")}


def jvm_twin(name):
    """`tda4j-js-cocycles` -> `tda4j-cocycles`, `tda4j-js` -> `tda4j`; None for a tool that is not a JavaScript twin."""
    if name == "tda4j-js":
        return "tda4j"
    return "tda4j-" + name[len("tda4j-js-"):] if name.startswith("tda4j-js-") else None


def node_heap_mb(spec):
    """`--max-old-space-size` in MB from a JVM-style heap size (`16g`, `512m`, `1024`)."""
    spec = str(spec).strip().lower()
    scale = {"k": 1 / 1024, "m": 1, "g": 1024, "t": 1024 * 1024}.get(spec[-1:], None)
    return int(float(spec[:-1] if scale else spec) * (scale or 1))


def jvm_flags(heap, extra=""):
    """The options of every JVM process of the benchmark, here and in sweep_cubical.py: one collector and a fixed heap.
    A JVM's RSS follows the collector's policy (a parallel collector shows its total allocation until the first
    collection), so the two drivers must agree; the drivers also report the heap pools' peak use."""
    return [f"-Xmx{heap}", f"-Xms{heap}", "-XX:+UseParallelGC"] + extra.split()


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


def jitter(points, rng, relative=1e-3):
    """Gaussian noise of `relative` times the bounding-box diagonal: far above the 1e-10 radius at which Helix calls
    points cospherical and far below the sampling distance, so cospherical and coplanar clusters are broken."""
    scale = relative * float(np.linalg.norm(points.max(axis=0) - points.min(axis=0)))
    return points + scale * rng.normal(size=points.shape)


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def cyclooctane_points(data: Path):
    """data/cyclooctane.txt (6040 rows of 24 numbers) from JavaPlex's `pointsCycloOctane.mat`, checked against its SHA-256."""
    mat = data / "pointsCycloOctane.mat"
    fetch(f"{JAVAPLEX_TUTORIAL}/pointsCycloOctane.mat", mat)
    found = sha256_of(mat)
    if found != CYCLOOCTANE_SHA256:
        raise SystemExit(f"{mat}: SHA-256 {found}, expected {CYCLOOCTANE_SHA256}; delete it and look at what changed")
    try:
        import scipy.io
    except ImportError:
        raise SystemExit("reading the .mat file needs scipy: `bench/run.sh setup python` installs it")
    x = np.asarray(scipy.io.loadmat(str(mat))["pointsCycloOctane"], dtype=float)
    assert x.shape == (6040, 24), f"{mat}: expected 6040 points in R^24, got {x.shape}"
    np.savetxt(data / "cyclooctane.txt", x, fmt="%.17g")


def prepare_rare(data: Path):
    """The inputs of the rarer complexes, from their own seeded generator (every earlier file stays byte-identical)."""
    rng = np.random.default_rng(20261021)
    np.savetxt(data / "unif3d_80.txt", rng.random((80, 3)), fmt="%.17g")
    angle = rng.uniform(0, 2 * np.pi, 500)
    circle = np.c_[np.cos(angle), np.sin(angle)] + rng.normal(0, 0.05, (500, 2))
    np.savetxt(data / "circle_outliers_600.txt", np.r_[circle, rng.uniform(-2, 2, (100, 2))], fmt="%.17g")
    for rows, cols in [(300, 100), (60, 200)]:
        a, b = rng.random((rows, 3)), rng.random((cols, 3))
        np.savetxt(data / f"relation_{rows}x{cols}.txt", np.linalg.norm(a[:, None] - b[None], axis=2), fmt="%.17g")


def prepare(data: Path):
    data.mkdir(parents=True, exist_ok=True)
    for f in ["sphere_3_192_points.dat", "o3_1024.txt", "o3_4096.txt", "clifford_torus_50000.points.txt"]:
        fetch(f"{RIPSER_BENCH}/{f}", data / f)
    fetch(f"{ROADMAP}/roadmap_datasets_point_cloud/random_point_cloud_50_16_.txt", data / "random_point_cloud_50_16_.txt")
    fetch(f"{ROADMAP}/roadmap_datasets_point_cloud/dragon_vrip.ply.txt_2000_.txt", data / "dragon_2000.txt")
    fetch(f"{ROADMAP}/roadmap_datasets_distmat/fractal_9_5_2_random_edge_list.txt_0.19795_distmat.txt",
          data / "fractal-r.txt")

    cyclooctane_points(data)

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

    # Added after the sets above, from their own generator, so the earlier files stay byte-identical.
    rng = np.random.default_rng(20261018)
    np.savetxt(data / "unif5d_500.txt", rng.random((500, 5)), fmt="%.17g")
    np.savetxt(data / "unif10d_1000.txt", rng.uniform(-1, 1, (1000, 10)), fmt="%.17g")  # the DQP paper's example
    # The two degenerate alpha inputs, moved into general position: the same file for every tool.
    np.savetxt(data / "sphere_3_192.gp.txt", jitter(np.loadtxt(data / "sphere_3_192_points.dat"), rng), fmt="%.17g")
    np.savetxt(data / "dragon_2000.gp.txt", jitter(np.loadtxt(data / "dragon_2000.txt"), rng), fmt="%.17g")
    prepare_rare(data)
    with open(data / "MANIFEST.json", "w") as f:
        json.dump({p.name: p.stat().st_size for p in sorted(data.iterdir()) if p.is_file()}, f, indent=1)
    print(f"data ready in {data}")


# ---------------------------------------------------------------------------------------------------------
# running one process
# ---------------------------------------------------------------------------------------------------------
class Proc(NamedTuple):
    """One process: `status` is ok | timeout | unsupported | limit | oom | error(code); `reason` the stderr line that
    explains a status other than ok; `rss_src` is `proc` (VmHWM sampled while it ran) or `rusage` (ru_maxrss, which
    includes the harness's own RSS at spawn time: an upper bound, for a process too short to be sampled)."""
    status: str
    wall: float
    rss_mb: float
    text: str
    reason: str = ""
    rss_src: str = "proc"


# What a tool prints when it hits a limit of its own. Ripser packs the coefficient into the same 64-bit field as the
# simplex index (num_coefficient_bits = 8 in every USE_COEFFICIENTS build, whatever the prime), which leaves a simplex
# index of at most 2^55 - 1; a larger one is a std::overflow_error that nothing catches (SIGABRT, exit -6).
FAILURES = [
    ("limit", re.compile(r"larger than maximum index")),
    ("oom", re.compile(r"bad_alloc|MemoryError|OutOfMemoryError|heap out of memory|Cannot allocate memory")),
]


def classify(code, err_text):
    """(status, reason) of a process that exited with `code` after printing `err_text` to stderr."""
    lines = [l.strip() for l in err_text.splitlines() if l.strip()]
    if code == 0:
        return "ok", ""
    for status, pattern in FAILURES:
        hit = next((l for l in lines if pattern.search(l)), None)
        if hit:
            return status, hit[:300]
    reason = lines[-1][:300] if lines else ""
    if code == 3:
        return "unsupported", reason
    if code == -9 and not reason:
        reason = "killed by SIGKILL, not by the harness: probably the kernel's out-of-memory killer"
    return f"error({code})", reason


def vm_hwm_mb(pid):
    """The kernel's peak-RSS high-water mark of process `pid` (VmHWM), or None where /proc has none: elsewhere than
    Linux, or a zombie."""
    try:
        with open(f"/proc/{pid}/status") as f:
            for line in f:
                if line.startswith("VmHWM:"):
                    return int(line.split()[1]) / 1024
    except (OSError, ValueError):
        pass
    return None


def tree_hwm_mb(pid):
    """VmHWM summed over the process and all its descendants: the memory of a job of several processes (MPI ranks).
    The ranks' peaks need not coincide, so this bounds the total from above."""
    total, todo = 0.0, [pid]
    while todo:
        current = todo.pop()
        total += vm_hwm_mb(current) or 0.0
        try:
            for task in os.listdir(f"/proc/{current}/task"):
                with open(f"/proc/{current}/task/{task}/children") as f:
                    todo += [int(c) for c in f.read().split()]
        except OSError:
            pass
    return total or None


def run_proc(cmd, timeout, log_prefix, address_space_mb=None, tree=False):
    """Runs cmd in its own session (killed as a group at `timeout`); returns a `Proc`. `address_space_mb` caps the
    process's virtual memory (RLIMIT_AS), so a runaway C++ allocation ends in `std::bad_alloc` (status `oom`) and not in
    the kernel's out-of-memory killer; not for a JVM or Node, which reserve far more address space than they use."""
    out_path, err_path = Path(str(log_prefix) + ".out"), Path(str(log_prefix) + ".err")
    limit = None
    if address_space_mb:
        import resource
        cap = int(address_space_mb) * 1024 * 1024
        limit = lambda: resource.setrlimit(resource.RLIMIT_AS, (cap, cap))
    with open(out_path, "w") as out, open(err_path, "w") as err:
        start = time.perf_counter()
        # Popen returns once the child has exec'd, so /proc/<pid>/status describes the new program, not the harness.
        proc = subprocess.Popen(cmd, stdout=out, stderr=err, start_new_session=True, preexec_fn=limit)
        peak = 0.0
        status, rusage, timed_out = None, None, False
        while True:
            hwm = tree_hwm_mb(proc.pid) if tree else vm_hwm_mb(proc.pid)
            if hwm:
                peak = max(peak, hwm)
            pid, st, ru = os.wait4(proc.pid, os.WNOHANG)
            if pid != 0:
                status, rusage = st, ru
                break
            if time.perf_counter() - start > timeout:
                os.killpg(proc.pid, signal.SIGKILL)
                _, status, rusage = os.wait4(proc.pid, 0)
                timed_out = True
                break
            time.sleep(0.01)
        wall = time.perf_counter() - start
    proc.returncode = os.waitstatus_to_exitcode(status)  # reaped by wait4: Popen must not warn about a running child
    rss, src = (peak, "proc") if peak > 0 else (rusage.ru_maxrss / 1024, "rusage")
    if timed_out:
        return Proc("timeout", wall, rss, "", f"killed after {timeout:g} s", src)
    code = os.waitstatus_to_exitcode(status)
    err_text = err_path.read_text(errors="replace")[-65536:]
    kind, reason = classify(code, err_text)
    return Proc(kind, wall, rss, out_path.read_text(), reason, src)


def last_json(text):
    for line in reversed(text.splitlines()):
        line = line.strip()
        if line.startswith("{"):
            try:
                return json.loads(line)
            except json.JSONDecodeError:
                pass
    return None


# Tools that are run as a binary, once per trial, and print or write their result for the harness to read.
BINARY_KINDS = ("ripser", "dipha", "phat")


def parse_binary_output(kind, proc, bars_path, case, matrix=None):
    """Writes the bars of a finished binary tool to `bars_path` (`dim<TAB>birth<TAB>death`); returns the count per degree.
    `matrix` is the boundary matrix PHAT reduced."""
    if kind == "ripser":
        return parse_ripser_output(proc.text, bars_path)
    if kind == "dipha":
        return dipha_io.diagram_to_bars(Path(bars_path).with_suffix(".diagram"), bars_path, case.dim)
    if kind == "phat":
        return phat_io.pairs_to_bars(Path(bars_path).with_suffix(".pairs"), matrix, bars_path, case.dim)
    raise ValueError(kind)


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
    if kind == "tda4j-js":
        return bool(env.get("TDA4J_JS")) and Path(env["TDA4J_JS"]).exists() and shutil.which(env.get("NODE", "node")) is not None
    if kind == "ripser":
        return bool(env.get("RIPSER_BIN"))
    if kind == "julia":
        return bool(env.get("JULIA")) and bool(env.get(JULIA_TOOLS[opts.get("julia_tool", "ripserer")][0]))
    if kind == "phat":
        return bool(env.get("PHAT_BIN")) and bool(env.get("MAKE_PHAT_BIN"))
    if kind == "dipha":
        return bool(env.get("DIPHA_BIN")) and (not opts.get("ranks") or shutil.which(env.get("MPIEXEC", "mpiexec")) is not None)
    if kind == "javaplex":
        return bool(env.get("JAVAPLEX_JAR")) and bool(env.get("JAVAPLEX_BUILD"))
    if kind == "py":
        mod = {"ripser.py": "ripser", "giotto-ph": "gph", "gudhi": "gudhi", "cripser": "cripser",
               "dionysus": "dionysus", "oat": "oat_python"}.get(opts["pytool"])
        if mod is None:  # dowker-nerves: a clone, not an installed package
            return bool(env.get("DOWKER_HOMOLOGY_PATH")) and Path(env["DOWKER_HOMOLOGY_PATH"]).exists()
        r = subprocess.run([python, "-c", f"import {mod}"], capture_output=True)
        return r.returncode == 0
    return False


def command(kind, name, opts, case, data, p, threshold, warmup, trials, out, args):
    task, fname, fmt, dim = case.task, case.file, case.format, case.dim
    common = [f"task={task}", f"input={data / fname}", f"dim={dim}", f"p={p}", f"warmup={warmup}",
              f"trials={trials}", f"out={out}"]
    if fmt in ("points", "distance"):
        common.append(f"format={fmt}")
    if threshold is not None:
        common.append(f"threshold={threshold!r}")
    common += case_extra_args(case, data)
    if kind == "tda4j":
        cp = Path(os.environ["TDA4J_CP"]).read_text().strip()
        threads = [] if "threads" not in opts else [os.cpu_count() if opts["threads"] == "ALL" else int(opts["threads"])]
        # The parallel experiment's sorts run on the common fork-join pool: give it the same number of threads.
        pool = [f"-Djava.util.concurrent.ForkJoinPool.common.parallelism={t}" for t in threads]
        return (["java"] + jvm_flags(args.jvm_heap, args.jvm_opts) + pool +
                ["-cp", cp, "org.appliedtopology.tda4j.PaperBenchmarkDriver",
                 f"engine={opts['engine']}", f"reps={opts['reps']}"] +
                ([f"backend={opts['backend']}"] if "backend" in opts else []) +
                [f"threads={t}" for t in threads] +
                [f"{k}={opts[k]}" for k in ("collapse", "wvariant", "dual", "nu") if k in opts] + common)
    if kind == "tda4j-js":
        heap = args.node_heap or args.jvm_heap
        return ([os.environ.get("NODE", "node"), f"--max-old-space-size={node_heap_mb(heap)}"] + args.node_opts.split() +
                [str(HERE / "workers" / "tda4j_js_worker.mjs"), f"engine={opts['engine']}", f"reps={opts['reps']}"] +
                ([f"backend={opts['backend']}"] if "backend" in opts else []) + common)
    if kind == "py":
        extra = [f"tool={opts['pytool']}"]
        if "variant" in opts:
            extra.append(f"variant={opts['variant']}")
            if opts["variant"] == "dual":
                extra.append("dual=true")
        if "threads" in opts:
            extra.append(f"threads={os.cpu_count() if opts['threads'] == 'ALL' else opts['threads']}")
        return [args.python, str(HERE / "workers" / "py_worker.py")] + extra + common
    if kind == "julia":
        project_variable, worker = JULIA_TOOLS[opts.get("julia_tool", "ripserer")]
        return ([os.environ["JULIA"], f"--project={os.environ[project_variable]}",
                 str(HERE / "workers" / worker), f"variant={opts['variant']}"] + common)
    if kind == "javaplex":
        cp = os.environ["JAVAPLEX_JAR"] + os.pathsep + os.environ["JAVAPLEX_BUILD"]
        return (["java"] + jvm_flags(args.jvm_heap, args.jvm_opts) + ["-cp", cp, "JavaPlexWorker",
                f"divisions={args.javaplex_divisions}"] + common)
    if kind == "dipha":
        if threshold is None:
            raise ValueError("dipha needs a threshold: its input is the sparse distance matrix under it")
        source = data / "dipha" / f"{case.name}.t{threshold!r}.dipha"
        dipha_io.prepare_input(lambda: np.loadtxt(data / fname, ndmin=2), fmt, threshold, source)
        cmd = [os.environ["DIPHA_BIN"], "--dual", "--upper_dim", str(dim + 1), str(source), str(Path(out).with_suffix(".diagram"))]
        if opts.get("ranks"):
            ranks = os.cpu_count() if opts["ranks"] == "ALL" else int(opts["ranks"])
            cmd = [os.environ.get("MPIEXEC", "mpiexec"), "-n", str(ranks)] + mpiexec_flags() + cmd
        return cmd
    if kind == "phat":
        matrix = phat_matrix(case, data, threshold, fmt, fname, dim)
        cmd = [os.environ["PHAT_BIN"], "--binary"] + (["--dualize"] if opts.get("dualize") else [])
        return cmd + [str(matrix), str(Path(out).with_suffix(".pairs"))]
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


def maxmin_landmarks(points, count):
    """Indices of `count` landmarks by maxmin (farthest-point) selection starting at point 0: the same list for every tool."""
    x = np.asarray(points, dtype=float)
    chosen = [0]
    nearest = np.linalg.norm(x - x[0], axis=1)
    while len(chosen) < min(count, len(x)):
        nxt = int(np.argmax(nearest))
        chosen.append(nxt)
        nearest = np.minimum(nearest, np.linalg.norm(x - x[nxt], axis=1))
    return chosen


def case_extra_args(case, data):
    """The key=value arguments a case adds for every tool: its `params`, and for a witness case the landmark file (made
    on first use, beside the data, so that all tools read the same landmarks)."""
    args = [f"{k}={v}" for k, v in case.params]
    if case.task == "witness":
        count = int(dict(case.params)["L"])
        path = data / "landmarks" / f"{case.name}.txt"
        if not path.exists():
            path.parent.mkdir(parents=True, exist_ok=True)
            idx = maxmin_landmarks(np.loadtxt(data / case.file, ndmin=2), count)
            path.write_text("".join(f"{i}\n" for i in idx))
        args.append(f"landmarks={path}")
    return args


def phat_matrix(case, data, threshold, fmt, fname, dim):
    """The filtered boundary matrix of a case for PHAT, made by make_phat from DIPHA's input unless it is there already
    (untimed: the complex is built beforehand). Its path."""
    if threshold is None:
        raise ValueError("phat needs a threshold: its input is the complex under it")
    source = data / "dipha" / f"{case.name}.t{threshold!r}.dipha"
    dipha_io.prepare_input(lambda: np.loadtxt(data / fname, ndmin=2), fmt, threshold, source)
    matrix = data / "phat" / f"{case.name}.t{threshold!r}.phat"
    if not (matrix.exists() and Path(f"{matrix}.dims").exists()):
        matrix.parent.mkdir(parents=True, exist_ok=True)
        for stale in (matrix, Path(f"{matrix}.values"), Path(f"{matrix}.dims")):
            stale.unlink(missing_ok=True)
        subprocess.run([os.environ["MAKE_PHAT_BIN"], str(dim + 1), str(source), str(matrix)], check=True)
    return matrix


# The Julia tools: the environment variable holding the project each needs, and the worker script.
JULIA_TOOLS = {"ripserer": ("RIPSERER_PROJECT", "ripserer_worker.jl"), "eirene": ("EIRENE_PROJECT", "eirene_worker.jl"),
               "dowker_yoon": ("DOWKER_YOON_PROJECT", "dowker_yoon_worker.jl")}


def mpiexec_flags():
    """Options for mpiexec: MPIEXEC_FLAGS when set, else for Open MPI the two a benchmark needs (a rank per hardware
    thread is more ranks than slots, and a container runs as root); other MPIs get none."""
    if "MPIEXEC_FLAGS" in os.environ:
        return os.environ["MPIEXEC_FLAGS"].split()
    try:
        version = subprocess.run([os.environ.get("MPIEXEC", "mpiexec"), "--version"], capture_output=True, text=True).stdout
    except OSError:
        return []
    if "Open MPI" not in version and "OpenRTE" not in version:  # `mpiexec --version` says OpenRTE for Open MPI 4
        return []
    return ["--oversubscribe"] + (["--allow-run-as-root"] if os.geteuid() == 0 else [])


def pairwise_distances(x, rows=None):
    """Euclidean distances from the points `rows` (default: all) to all points of `x`."""
    g = x @ x.T if rows is None else x[rows] @ x.T
    sq = np.einsum("ij,ij->i", x, x)
    sq_rows = sq if rows is None else sq[rows]
    return np.sqrt(np.maximum(sq_rows[:, None] + sq[None, :] - 2 * g, 0))


def alpha_radius(points, k, sample=200, seed=0):
    """A radius at which a point has about k neighbours within 2r (the statistic that decides between the dual-QP and a
    triangulation, `AlphaShapes.prefersDQP`): half the median distance from a sample of points to their k-th nearest
    neighbour. The scale of the data is not known when a case is written; its density is what the cost depends on."""
    x = np.asarray(points, dtype=float)
    rng = np.random.default_rng(seed)
    rows = rng.choice(len(x), size=min(sample, len(x)), replace=False)
    d = np.partition(pairwise_distances(x, rows), k, axis=1)[:, k]  # column 0 of the sorted row is the point itself
    return float(f"{np.median(d) / 2:.6g}")


def case_threshold(case, data):
    """The largest filtration value every tool gets: the case's own, a radius from its neighbour count, or for
    Vietoris-Rips without one the enclosing radius (ripser.cpp's default); None for no limit."""
    if case.threshold is not None:
        return case.threshold
    if case.radius_k:
        return alpha_radius(np.loadtxt(data / case.file, ndmin=2), case.radius_k)
    if case.task not in ("vr", "sparse"):
        return None
    if case.format == "distance":
        d = np.loadtxt(data / case.file)
    else:
        d = pairwise_distances(np.loadtxt(data / case.file))
    return float(np.min(np.max(d, axis=1)))


def ambient_dimension(case, data):
    """The number of coordinates of a point file's first row; 0 for any other input."""
    if case.format != "points":
        return 0
    with open(data / case.file) as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#"):
                return len([x for x in re.split(r"[,\s]+", line) if x])
    return 0


def explicit_too_large(kind, opts, cells, cap):
    """Why a tool that writes out the whole boundary or coboundary matrix (the phat rows, `explicit` rows) is not run on
    a complex of `cells` cells (bench/cells-reference.csv; None or "" when the case has no count), or None."""
    if (kind == "phat" or opts.get("explicit")) and cells and int(cells) > cap:
        return f"the explicit boundary matrix would have {int(cells):,} cells (above --explicit-max-cells {cap:,.0f})"
    return None


def pinned(cmd, args):
    """`cmd` confined by taskset to the logical CPUs 0 .. n - 1 when `args.pin_cpus` is n (the thread sweep), else as is."""
    n = getattr(args, "pin_cpus", 0)
    return ["taskset", "-c", f"0-{n - 1}"] + cmd if n and cmd else cmd


def skip_reason(opts, case, ambient, threshold, p=None):
    """Why a tool is not run on a case at all (recorded as `unsupported`), or None."""
    if opts.get("only_p") and p is not None and p != opts["only_p"]:
        return f"Z/{opts['only_p']} coefficients only"
    if opts.get("max_dim") is not None and case.dim > opts["max_dim"]:
        return f"degrees up to {opts['max_dim']} only"
    if opts.get("max_ambient") and ambient > opts["max_ambient"]:
        return f"ambient dimension {ambient} is above the backend's limit of {opts['max_ambient']}"
    if opts.get("needs_radius") and threshold is None:
        return "run with a radius only: untruncated it builds every pair of points (about 100 times slower than Helix)"
    if opts.get("no_radius") and case.task == "alpha" and threshold is not None:
        return "takes no radius: it needs the whole triangulation"
    return None


# ---------------------------------------------------------------------------------------------------------
# agreement
# ---------------------------------------------------------------------------------------------------------
def read_bars(path):
    bars = {}
    if not Path(path).exists():
        return None
    with open(path) as f:
        for line in f:
            k, b, d = line.split("\t")
            bars.setdefault(int(k), []).append((float(b), math.inf if d.strip() == "inf" else float(d)))
    return bars


def agreement(a, b, max_dim, bottleneck=None):
    """(distance, note): the largest discrepancy over degrees 0..max_dim, as the bottleneck distance on finite bars
    and essential births matched in sorted order. A different number of essential bars in some degree is distance inf,
    and the note says which degree and how many. Distances below 1e-12 are 0 (the bottleneck routine returns
    denormals for equal diagrams). `bottleneck` defaults to GUDHI's."""
    if bottleneck is None:
        import gudhi
        bottleneck = gudhi.bottleneck_distance
    worst = 0.0
    for k in range(max_dim + 1):
        fa = [(x, y) for x, y in a.get(k, []) if math.isfinite(y) and y > x]
        fb = [(x, y) for x, y in b.get(k, []) if math.isfinite(y) and y > x]
        worst = max(worst, bottleneck(np.array(fa).reshape(-1, 2), np.array(fb).reshape(-1, 2)))
        ea = sorted(x for x, y in a.get(k, []) if math.isinf(y))
        eb = sorted(x for x, y in b.get(k, []) if math.isinf(y))
        if len(ea) != len(eb):
            return math.inf, f"H{k} has {len(ea)} essential bars against the reference's {len(eb)}"
        if ea:
            worst = max(worst, max(abs(x - y) for x, y in zip(ea, eb)))
    return (0.0 if worst < 1e-12 else worst), ""


def tolerance(threshold, tool, divisions):
    """The distance below which two barcodes agree. ripser.cpp computes in float32; JavaPlex snaps filtration values
    to a grid of threshold / divisions."""
    scale = float(threshold) if threshold not in ("", "None", None) else 1.0
    tol = 1e-5 * max(1.0, scale)
    if tool == "javaplex" and threshold not in ("", "None", None):
        tol = max(tol, float(threshold) / float(divisions))
    return tol


def essential_counts(bars, max_dim):
    """Essential bars per degree 0..max_dim: the Betti numbers, in that field, of a complex cut at its threshold."""
    return tuple(sum(1 for b, d in bars.get(k, []) if math.isinf(d)) for k in range(max_dim + 1))


def significant_counts(bars, tol):
    """Bars per degree that are essential or longer than `tol`: what two tools can be compared on. A tool that keeps
    the near-zero bars (length below the agreement tolerance) would otherwise look as if it disagreed."""
    counts = {}
    for k, ivs in bars.items():
        n = sum(1 for b, d in ivs if math.isinf(d) or d - b > tol)
        if n:
            counts[str(k)] = n
    return counts


# ---------------------------------------------------------------------------------------------------------
# run
# ---------------------------------------------------------------------------------------------------------
RUNS_COLUMNS = ["case", "task", "p", "tool", "mode", "status", "wall_s", "times_s", "median_s", "maxrss_mb", "bars",
                "threshold", "multithreaded", "info", "reason", "rss_src", "heap_peak_mb", "old_peak_mb"]


def run(args):
    data = Path(args.data)
    out = Path(args.out)
    (out / "bars").mkdir(parents=True, exist_ok=True)
    (out / "logs").mkdir(parents=True, exist_ok=True)
    write_env(out, args)
    cases = [c for c in CASES if not args.cases or c.name in args.cases.split(",")]
    tasks = set(args.tasks.split(",")) if args.tasks else None
    wanted = set(args.tools.split(",")) if args.tools else None
    fields = [int(p) for p in args.fields.split(",")]
    runs_csv = out / "runs.csv"
    new = not runs_csv.exists()
    cells = cells_reference()
    timed_out = {}  # (case, tool) -> the first field at which it timed out
    gave_up = {}  # (case, tool) -> "timeout" or "oom", the first time it did
    with open(runs_csv, "a", newline="") as f:
        w = csv.DictWriter(f, fieldnames=RUNS_COLUMNS)
        if new:
            w.writeheader()
        for case in cases:
            if tasks and case.task not in tasks:
                continue
            threshold = case_threshold(case, data)
            ambient = ambient_dimension(case, data)
            for p in fields:
                for name, kind, ttask, opts in TOOLS:
                    if ttask != case.task or (wanted and name not in wanted):
                        continue
                    opts = dict(opts)
                    opts.setdefault("pytool", name)

                    def not_run(status, reason, mode="cold"):
                        w.writerow({"case": case.name, "task": case.task, "p": p, "tool": name, "mode": mode,
                                    "status": status, "threshold": threshold, "reason": reason})
                        f.flush()
                        print(f"[{datetime.datetime.now():%H:%M:%S}] {case.name}__p{p}__{name}: {status} ({reason})",
                              flush=True)

                    if not available(kind, opts, args.python):
                        w.writerow({"case": case.name, "task": case.task, "p": p, "tool": name, "mode": "-",
                                    "status": "missing", "threshold": threshold})
                        f.flush()
                        continue
                    why = skip_reason(opts, case, ambient, threshold, p)
                    if why:
                        not_run("unsupported", why)
                        continue
                    too_large = explicit_too_large(kind, opts, cells.get(case.name),
                                                   getattr(args, "explicit_max_cells", 1e8))
                    if too_large:
                        not_run("unsupported", too_large)
                        continue
                    earlier = timed_out.get((case.name, name))
                    if earlier is not None and not args.rerun_timeouts:
                        # The time of a computation does not depend on the field (p = 17 over p = 2 was 0.9 to 1.2 for
                        # every tool of the first run), and a timeout costs --timeout seconds.
                        not_run("timeout", f"not run: the same computation timed out at p={earlier}")
                        continue
                    faster = opts.get("slower_than")
                    if faster and (case.name, faster) in gave_up and not args.rerun_timeouts:
                        outcome = gave_up[(case.name, faster)]
                        not_run(outcome, f"not run: {faster}, the faster variant, "
                                         f"{'timed out' if outcome == 'timeout' else 'ran out of memory'} on this case")
                        continue
                    status = run_one(w, f, data, out, case, p, threshold, name, kind, opts, args)
                    if status == "timeout":
                        timed_out.setdefault((case.name, name), p)
                    if status in ("timeout", "oom"):
                        gave_up.setdefault((case.name, name), status)


def run_one(w, f, data, out, case, p, threshold, name, kind, opts, args):
    """Runs one (case, field, tool): cold, then warm. Returns "timeout" if a run timed out, else the last status."""
    cname, task = case.name, case.task
    tag = f"{cname}__p{p}__{name}"
    bars_path = out / "bars" / f"{tag}.tsv"
    mt = bool(opts.get("multithreaded"))
    print(f"[{datetime.datetime.now():%H:%M:%S}] {tag}", flush=True)

    def record(mode, proc, info="", times=None, bars=None, wall=None, rss=None, j=None):
        med = statistics.median(times) if times else ""
        wall = proc.wall if wall is None else wall
        rss = proc.rss_mb if rss is None else rss
        j = j or {}
        w.writerow({"case": cname, "task": task, "p": p, "tool": name, "mode": mode, "status": proc.status,
                    "wall_s": f"{wall:.4f}", "times_s": json.dumps(times) if times else "",
                    "median_s": med if med == "" else f"{med:.6f}", "maxrss_mb": f"{rss:.1f}",
                    "bars": json.dumps(bars) if bars else "", "threshold": threshold, "multithreaded": mt,
                    "info": info, "reason": proc.reason, "rss_src": proc.rss_src,
                    "heap_peak_mb": j.get("heap_peak_mb", ""), "old_peak_mb": j.get("old_peak_mb", "")})
        f.flush()
        print(f"    {mode:4s} {proc.status:10s} wall={wall:.2f}s median={med if med == '' else f'{med:.3f}s'} "
              f"rss={rss:.0f}MB {info} {proc.reason}".rstrip(), flush=True)

    if kind in BINARY_KINDS:
        cmd = pinned(command(kind, name, opts, case, data, p, threshold, 0, 1, bars_path, args), args)
        if cmd is None:
            record("cold", Proc("missing", 0, 0, "", "no coefficient build for p != 2"))
            return "missing"
        walls, rss_max, counts, last = [], 0.0, None, None
        for t in range(max(1, args.trials)):
            if walls and walls[0] > args.warm_cap:
                break  # one long run is enough; see --warm-cap
            for result in (".diagram", ".pairs"):
                Path(bars_path).with_suffix(result).unlink(missing_ok=True)  # DIPHA does not overwrite cleanly
            last = run_proc(cmd, args.timeout, out / "logs" / f"{tag}__{t}", tree=bool(opts.get("ranks")))
            rss_max = max(rss_max, last.rss_mb)
            if last.status != "ok":
                record("cold", last)
                return last.status
            if t == 0:
                try:
                    counts = parse_binary_output(kind, last, bars_path, case, cmd[-2] if kind == "phat" else None)
                except (OSError, ValueError) as e:
                    record("cold", Proc("error(output)", last.wall, last.rss_mb, "", f"cannot read the result: {e}"))
                    return "error(output)"
                record("cold", last, times=[last.wall], bars=counts)
            walls.append(last.wall)
        record("warm", last, "median of cold runs", walls, counts, wall=sum(walls), rss=rss_max)
        return "ok"

    cmd = pinned(command(kind, name, opts, case, data, p, threshold, 0, 1, bars_path, args), args)
    cold = run_proc(cmd, args.timeout, out / "logs" / f"{tag}__cold")
    j = last_json(cold.text) if cold.status == "ok" else None
    record("cold", cold, times=j["times_s"] if j else None, bars=j["bars"] if j else None, j=j)
    if cold.status != "ok" or args.trials <= 0:
        return cold.status
    if j and j["times_s"] and j["times_s"][0] > args.warm_cap:
        # Warm-up is irrelevant at this scale, and warmup+trials repetitions could cost hours: reuse the cold run.
        record("warm", cold, f"cold computation > {args.warm_cap:g}s: warm = cold", j["times_s"], j["bars"], j=j)
        return "ok"
    cmd = pinned(command(kind, name, opts, case, data, p, threshold, args.warmup, args.trials, bars_path, args), args)
    warm = run_proc(cmd, args.timeout * (args.warmup + args.trials), out / "logs" / f"{tag}__warm")
    j = last_json(warm.text) if warm.status == "ok" else None
    record("warm", warm, times=j["times_s"] if j else None, bars=j["bars"] if j else None, j=j)
    return warm.status


def js_package_version():
    """The version in the npm package's package.json (the package TDA4J_JS points into), or ''."""
    main = os.environ.get("TDA4J_JS")
    manifest = Path(main).parent / "package.json" if main else None
    return json.load(open(manifest)).get("version", "") if manifest and manifest.exists() else ""


def classes_directory():
    """TDA4j's compiled classes: the first entry of the classpath file TDA4J_CP names, or None."""
    cp = os.environ.get("TDA4J_CP")
    try:
        first = Path(cp).read_text().strip().split(os.pathsep)[0] if cp else ""
    except OSError:
        return None
    return Path(first) if first else None


def revision(classes, default_repo=HERE.parent):
    """Which TDA4j a run measured. The checkout is the one the classes were built in (the directory above their
    `target`), else `default_repo`, the harness's own. `tda4j_git` is its HEAD; `tda4j_dirty` the tracked files that
    differ from it (untracked ones are not named); `tda4j_classes_stale` whether a JVM source is newer than the newest
    class, which a checkout made after `setup` leaves true (None when there are no classes to compare)."""
    def git(repo, *args):
        try:
            r = subprocess.run(["git", "-C", str(repo), *args], capture_output=True, text=True, timeout=60)
        except Exception:
            return None
        return r.stdout.strip() if r.returncode == 0 else None

    repo = next((p.parent for p in (classes.resolve() if classes else Path()).parents if p.name == "target"),
                default_repo)
    stale = None
    if classes is not None and classes.is_dir():
        built = max((Path(d, f).stat().st_mtime for d, _, fs in os.walk(classes) for f in fs), default=None)
        sources = (git(repo, "ls-files", "--", "src/main/scala", "src/main/scalajvm") or "").splitlines()
        edited = max((Path(repo, s).stat().st_mtime for s in sources if Path(repo, s).exists()), default=None)
        stale = None if built is None or edited is None else edited > built
    return {"tda4j_git": git(repo, "rev-parse", "HEAD") or "unavailable",
            "tda4j_dirty": (git(repo, "diff", "--name-only", "HEAD") or "").splitlines(),
            "tda4j_classes_stale": stale}


def revision_text(env):
    """The line the summaries carry: the commit, and what keeps it from being the whole story. Empty where the run
    recorded no commit."""
    head = env.get("tda4j_git") or ""
    if not re.fullmatch(r"[0-9a-f]{7,40}", head):
        return ""
    text = f"TDA4j `{head[:12]}`"
    dirty = env.get("tda4j_dirty") or []
    if dirty:
        named = ", ".join(dirty[:5]) + (", ..." if len(dirty) > 5 else "")
        text += f" with {len(dirty)} changed file{'' if len(dirty) == 1 else 's'} ({named})"
    if env.get("tda4j_classes_stale"):
        text += "; the classes the JVM rows ran are older than the sources"
    return text + "."


def read_env(out):
    """The run's env.json, or {} where it is missing or unreadable."""
    try:
        with open(Path(out) / "env.json") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


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
        "node": cmd_out([os.environ.get("NODE", "node"), "--version"]),
        "tda4j_js_package": js_package_version(),
        "python_packages": cmd_out([args.python, "-m", "pip", "freeze"]),
        "julia": cmd_out([os.environ["JULIA"], "--version"]) if os.environ.get("JULIA") else "",
        "ripser_git": cmd_out(["git", "-C", str(Path(os.environ.get("RIPSER_BIN", ".")).parent), "rev-parse", "HEAD"]),
        **revision(classes_directory()),
        "jvm_flags": jvm_flags(args.jvm_heap, args.jvm_opts),
        "args": vars(args),
    }
    with open(out / "env.json", "w") as f:
        json.dump(env, f, indent=1, default=str)


# ---------------------------------------------------------------------------------------------------------
# summarize
# ---------------------------------------------------------------------------------------------------------
def geomean(values):
    return math.exp(sum(math.log(v) for v in values) / len(values))


def fmt_distance(dist):
    return "" if dist is None else ("0" if dist == 0 else f"{dist:.3g}")


def summarize(out: Path, bottleneck=None):
    with open(out / "runs.csv") as f:
        rows = list(csv.DictReader(f))
    cases = {c.name: c for c in CASES}
    try:
        with open(out / "env.json") as f:
            divisions = json.load(f)["args"]["javaplex_divisions"]
    except Exception:
        divisions = 1000
    by = {}
    for r in rows:
        by.setdefault((r["case"], r["p"], r["tool"]), {})[r["mode"]] = r
    summary = []
    cells = cells_reference()
    for (cname, p, tool), modes in sorted(by.items()):
        cold, warm = modes.get("cold", {}), modes.get("warm", {})
        first = cold or warm or next(iter(modes.values()))
        task = first["task"]
        ref = REFERENCE[task]
        mine = read_bars(out / "bars" / f"{cname}__p{p}__{tool}.tsv") if cold.get("status") == "ok" else None
        theirs = read_bars(out / "bars" / f"{cname}__p{p}__{ref}.tsv")
        dist, note = None, ""
        if mine is not None and theirs is not None:
            max_dim = cases[cname].dim if cname in cases else max(list(mine) + list(theirs) + [0])
            dist, note = agreement(mine, theirs, max_dim, bottleneck)
        thr = cold.get("threshold") or ""
        tol = tolerance(thr, tool, divisions)
        if tool in CHARACTERISTIC_ZERO and dist is not None and dist > tol:
            # Its barcode is over the rationals, the reference's over F_p: the two differ where there is p-torsion.
            note = f"{note + '; ' if note else ''}computed over the rationals, the reference over F_{p}"
        measured = [m for m in modes.values() if m.get("maxrss_mb")]
        heaps = [float(m["heap_peak_mb"]) for m in modes.values() if m.get("heap_peak_mb")]
        olds = [float(m["old_peak_mb"]) for m in modes.values() if m.get("old_peak_mb")]
        summary.append({
            "case": cname, "task": task, "p": p, "tool": tool, "cells": cells.get(cname, ""),
            "status": warm.get("status") or cold.get("status") or first["status"],
            "cold_s": cold.get("wall_s", ""), "warm_median_s": warm.get("median_s", ""),
            "maxrss_mb": max([float(m["maxrss_mb"]) for m in measured] or [0]),
            "bars": cold.get("bars", ""), "multithreaded": cold.get("multithreaded", ""),
            "vs_reference": fmt_distance(dist),
            "agrees": "" if dist is None else str(dist <= tol),
            "bars_sig": json.dumps(significant_counts(mine, tol)) if mine is not None else "",
            "reason": warm.get("reason") or cold.get("reason") or "",
            "note": note,
            "rss_src": "rusage" if any(m.get("rss_src") == "rusage" for m in measured) else ("proc" if measured else ""),
            "heap_peak_mb": f"{max(heaps):.0f}" if heaps else "",
            "old_peak_mb": f"{max(olds):.0f}" if olds else "",
            "degenerate": str(cname in cases and cases[cname].degenerate),
            "betti": ",".join(map(str, essential_counts(mine, cases[cname].dim))) if (mine is not None and cname in cases) else "",
        })
    if not summary:
        print(f"no runs in {out / 'runs.csv'}")
        return
    with open(out / "summary.csv", "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(summary[0].keys()))
        w.writeheader()
        w.writerows(summary)

    rev = revision_text(read_env(out))
    lines = ["# TDA4j cross-platform benchmark", "", f"Results: `{out}`. Environment: `env.json`." + (f" {rev}" if rev else ""), "",
             "Status: `ok`; `timeout`; `limit` (the tool's own index range); `oom`; `unsupported`; `error(code)`; "
             "`missing` (not installed). Peak MB is the kernel's peak RSS of the process (`≤` where it could only be "
             "read from `ru_maxrss`, an upper bound); heap MB is the JVM's peak use of its heap pools. "
             "† the point set is degenerate (not in general position), so the reference is not ground truth.", ""]
    kernels = sorted({s["tool"] for s in summary if s["tool"] in KERNEL_TOOLS and s["status"] != "missing"})
    if kernels:
        lines[-1:] = ["", f"{', '.join(kernels)} time the reduction of a boundary matrix built beforehand: the complex is not "
                      "timed, so these rows are never the baseline the other tools are measured against.", ""]
    zero = sorted({s["tool"] for s in summary if s["tool"] in CHARACTERISTIC_ZERO and s["status"] != "missing"})
    if zero:
        lines[-1:] = ["", f"{', '.join(zero)} compute over the rationals whatever the p of the row: a difference from the "
                      "reference (over F_p) can be p-torsion, and is noted under \"Agreement notes\".", ""]
    lines += ["| case | p | tool | status | cold s | warm s | peak MB | heap MB | bars (> tol) | Δ vs reference | agrees | cells |",
              "|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for s in summary:
        rss = f"{'≤' if s['rss_src'] == 'rusage' else ''}{s['maxrss_mb']:.0f}" if s["rss_src"] else ""
        agrees = s["agrees"] + ("†" if s["degenerate"] == "True" and s["agrees"] == "False" else "")
        size = f"{int(s['cells']):,}" if s["cells"] else ""
        lines.append(f"| {s['case']} | {s['p']} | {s['tool']} | {s['status']} | {s['cold_s']} | {s['warm_median_s']} | "
                     f"{rss} | {s['heap_peak_mb']} | {s['bars_sig'] or s['bars']} | {s['vs_reference']} | {agrees} | {size} |")

    failed = [s for s in summary if s["status"] not in ("ok", "missing")]
    if failed:
        lines += ["", "## Failures and limits", "", "| case | p | tool | status | reason |", "|---|---|---|---|---|"]
        lines += [f"| {s['case']} | {s['p']} | {s['tool']} | {s['status']} | {s['reason'].replace('|', '/')} |"
                  for s in failed]
    missing = sorted({s["tool"] for s in summary if s["status"] == "missing"})
    if missing:
        lines += ["", f"Not installed: {', '.join(missing)}."]
    noted = [s for s in summary if s["note"]]
    if noted:
        lines += ["", "## Agreement notes", ""]
        lines += [f"- {s['case']}, p={s['p']}, {s['tool']}: {s['note']}" for s in noted]

    # TDA4j's slowdown: its warm median against the reference (the fixed baseline) and against the fastest other
    # single-threaded tool (which changes from row to row). A row whose reference did not finish is marked † and
    # leaves both means: its "best other" is a different tool, and the row is not comparable with the others.
    lines += ["", "## TDA4j slowdown (warm median ÷ other tool's warm median; cold in parentheses)", "",
              "† the reference did not finish: the best other tool is a different, slower one, and the row is not "
              "counted in the means.", "",
              "| case | p | TDA4j variant | best other single-threaded | × best | × reference |", "|---|---|---|---|---|---|"]
    groups = {}
    for s in summary:
        groups.setdefault((s["case"], s["p"]), []).append(s)
    ratios_best, ratios_ref = {}, {}
    attempted, finished = {}, {}
    for (cname, p), ss in sorted(groups.items()):
        task = ss[0]["task"]
        for t in ss:
            if t["tool"].startswith("tda4j") and t["status"] != "missing":
                attempted.setdefault((task, t["tool"], p), set()).add(cname)
                if t["status"] == "ok":
                    finished.setdefault((task, t["tool"], p), set()).add(cname)
        ok = [s for s in ss if s["status"] == "ok" and s["warm_median_s"] not in ("", None)]
        others = [s for s in ok if not s["tool"].startswith("tda4j") and s["multithreaded"] != "True"
                  and s["tool"] not in KERNEL_TOOLS]
        if not others:
            continue
        best = min(others, key=lambda s: float(s["warm_median_s"]))
        ref = next((s for s in ok if s["tool"] == REFERENCE[task]), None)
        for t in [s for s in ok if s["tool"].startswith("tda4j")]:
            rb = float(t["warm_median_s"]) / float(best["warm_median_s"])
            rr = float(t["warm_median_s"]) / float(ref["warm_median_s"]) if ref else None
            cb = float(t["cold_s"]) / float(best["cold_s"]) if t["cold_s"] and best["cold_s"] else None
            if ref:
                ratios_best.setdefault((task, t["tool"], p), []).append(rb)
                ratios_ref.setdefault((task, t["tool"], p), []).append(rr)
            cold = f" ({cb:.2f})" if cb else ""
            lines.append(f"| {cname} | {p} | {t['tool']} | {best['tool']}{'' if ref else ' †'} | {rb:.2f}{cold} | "
                         f"{'' if rr is None else f'{rr:.2f}'} |")
    lines += ["", "Means per task, over the cases where the variant and the reference both finished "
              "(finished = the variant's own successes among the cases it ran):", ""]
    for key in sorted(ratios_ref):
        task, tool, p = key
        vals, best_vals = ratios_ref[key], ratios_best[key]
        ordered = sorted(vals)
        lines.append(f"- {task}, {tool}, p={p}: {geomean(vals):.2f}x the reference (median {statistics.median(ordered):.2f}, "
                     f"range {ordered[0]:.2f} to {ordered[-1]:.2f}) and {geomean(best_vals):.2f}x the best other tool, "
                     f"over {len(vals)} cases; finished {len(finished.get(key, ()))} of {len(attempted.get(key, ()))}")
    lines += js_against_jvm(summary)
    (out / "summary.md").write_text("\n".join(lines) + "\n")
    print("\n".join(lines))


def js_against_jvm(summary):
    """The Scala.js build beside the JVM build of the same variant: warm and cold time ratios (JS ÷ JVM) and memory,
    per case and as geometric means per task. Memory is compared two ways: RSS to RSS, and the JS RSS to the JVM's
    peak heap use (a JVM's RSS follows its collector). Empty when no `tda4j-js*` tool ran."""
    index = {(s["case"], s["p"], s["tool"]): s for s in summary if s["status"] == "ok"}
    rows, warm_ratios, cold_ratios = [], {}, {}
    for (cname, p, tool), js in sorted(index.items()):
        jvm_name = jvm_twin(tool)
        jvm = index.get((cname, p, jvm_name)) if jvm_name else None
        if jvm is None:
            continue
        task = js["task"]

        def ratio(key):
            a, b = js.get(key, ""), jvm.get(key, "")
            return float(a) / float(b) if a not in ("", None) and b not in ("", None) and float(b) > 0 else None

        warm, cold = ratio("warm_median_s"), ratio("cold_s")
        mem = float(js["maxrss_mb"]) / float(jvm["maxrss_mb"]) if float(jvm["maxrss_mb"]) > 0 else None
        heap = float(js["maxrss_mb"]) / float(jvm["heap_peak_mb"]) if jvm.get("heap_peak_mb") else None
        if warm:
            warm_ratios.setdefault((task, tool, p), []).append(warm)
        if cold:
            cold_ratios.setdefault((task, tool, p), []).append(cold)
        fmt = lambda v: "" if v is None else f"{v:.2f}"
        rows.append(f"| {cname} | {p} | {tool} | {jvm_name} | {fmt(warm)} | {fmt(cold)} | {fmt(mem)} | {fmt(heap)} | "
                    f"{js['agrees']} |")
    if not rows:
        return []
    lines = ["", "## Scala.js against the JVM (JS ÷ JVM; above 1, the JVM is faster)", "",
             "| case | p | JS tool | JVM twin | warm × | cold × | peak RSS × | RSS ÷ JVM heap peak | JS agrees with reference |",
             "|---|---|---|---|---|---|---|---|---|"] + rows + ["", "Geometric means of the warm ratio (cold in parentheses):", ""]
    for key, vals in sorted(warm_ratios.items()):
        cold = cold_ratios.get(key, [])
        gc = f" ({geomean(cold):.2f})" if cold else ""
        lines.append(f"- {key[0]}, {key[1]}, p={key[2]}: {geomean(vals):.2f}x{gc} over {len(vals)} cases")
    return lines


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
    p2.add_argument("--warm-cap", type=float, default=60,
                    help="if one cold computation takes longer (s), skip the warm repetitions and reuse it")
    p2.add_argument("--jvm-heap", default="16g")
    p2.add_argument("--jvm-opts", default="", help="more JVM options, after -Xmx/-Xms/-XX:+UseParallelGC")
    p2.add_argument("--rerun-timeouts", action="store_true",
                    help="rerun a computation at every field even after it timed out at an earlier one")
    p2.add_argument("--node-heap", default="",
                    help="--max-old-space-size for the tda4j-js tools, in the JVM's notation (default: --jvm-heap)")
    p2.add_argument("--node-opts", default="", help="more options for node, e.g. --stack-size=4000")
    p2.add_argument("--javaplex-divisions", type=int, default=1000)
    p2.add_argument("--explicit-max-cells", type=float, default=1e8,
                    help="the largest complex (cells, from bench/cells-reference.csv) that is written out as a boundary "
                         "matrix for the phat rows")
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
