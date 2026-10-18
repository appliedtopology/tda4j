#!/usr/bin/env python3
"""Runs one (tool, task, input) computation for the cross-platform benchmark, in its own process.

Reads the input once, runs `warmup` untimed and `trials` timed computations, prints one JSON line
with the timings, and writes the last barcode as `dim<TAB>birth<TAB>death` (`inf` for essential bars).

    py_worker.py tool=gudhi task=vr input=x.txt format=points dim=2 [threshold=1.8] [p=2]
                 [variant=...] [threads=1] [warmup=2] [trials=5] [out=bars.tsv]

Tools and what they support (anything else exits with status 3, "unsupported"):
  ripser.py  vr                      ripser.ripser
  giotto-ph  vr                      gph.ripser_parallel, threads=N (variant ignored)
  gudhi      vr, cubical, alpha      RipsComplex (variant=collapse: edge collapse first),
                                     CubicalComplex(top_dimensional_cells=...) = T-construction,
                                     AlphaComplex (filtration values are squared radii: we take sqrt)
  cripser    cubical                 tcripser = T-construction, F_2 only
  dionysus   vr                      Dionysus 2 (float32): fill_rips, then variant=cohomology (default; the fast one),
                                     homology (the clearing reduction) or cocycles (cohomology, and a representative
                                     cocycle is read for every bar)
  oat        vr                      OpenAppliedTopology (oat_python, Rust): a U-match decomposition of the
                                     Vietoris-Rips boundary matrix, over the rationals whatever p is (it takes no
                                     modulus); variant=cycles also returns a cycle representative for every bar
"""
import json
import math
import sys
import time

import numpy as np

UNSUPPORTED = 3


def parse_args(argv):
    opts = {}
    for a in argv:
        k, _, v = a.partition("=")
        opts[k] = v
    return opts


def load(opts):
    task = opts["task"]
    path = opts["input"]
    if task == "cubical":
        return np.load(path)
    data = np.loadtxt(path, delimiter=None if "," not in open(path).readline() else ",", ndmin=2)
    return data


def finite_or_inf(x):
    return math.inf if (x is None or not math.isfinite(x) or x > 1e300) else float(x)


# ---------------------------------------------------------------------------------------------------------
# Each adapter returns a function () -> list of (dim, birth, death).
# ---------------------------------------------------------------------------------------------------------

def ripser_py(opts, data):
    if opts["task"] != "vr":
        sys.exit(UNSUPPORTED)
    from ripser import ripser
    dim, p = int(opts["dim"]), int(opts.get("p", 2))
    thresh = float(opts["threshold"]) if "threshold" in opts else math.inf
    dist = opts.get("format", "points") == "distance"

    def run():
        dgms = ripser(data, maxdim=dim, thresh=thresh, coeff=p, distance_matrix=dist)["dgms"]
        return [(k, float(b), finite_or_inf(d)) for k, dg in enumerate(dgms) for b, d in dg]
    return run


def giotto_ph(opts, data):
    if opts["task"] != "vr":
        sys.exit(UNSUPPORTED)
    from gph import ripser_parallel
    dim, p = int(opts["dim"]), int(opts.get("p", 2))
    thresh = float(opts["threshold"]) if "threshold" in opts else math.inf
    metric = "precomputed" if opts.get("format", "points") == "distance" else "euclidean"
    threads = int(opts.get("threads", 1))

    def run():
        dgms = ripser_parallel(data, maxdim=dim, thresh=thresh, coeff=p, metric=metric, n_threads=threads)["dgms"]
        return [(k, float(b), finite_or_inf(d)) for k, dg in enumerate(dgms) for b, d in dg]
    return run


def gudhi_tool(opts, data):
    import gudhi
    task, dim, p = opts["task"], int(opts["dim"]), int(opts.get("p", 2))

    def intervals(st, transform=lambda x: x):
        out = []
        for k in range(dim + 1):
            for b, d in st.persistence_intervals_in_dimension(k):
                out.append((k, transform(float(b)), finite_or_inf(transform(float(d)) if math.isfinite(d) else d)))
        return out

    if task == "vr":
        thresh = float(opts["threshold"]) if "threshold" in opts else math.inf
        collapse = opts.get("variant") == "collapse"
        if opts.get("format", "points") == "distance":
            make = lambda: gudhi.RipsComplex(distance_matrix=data, max_edge_length=thresh)
        else:
            make = lambda: gudhi.RipsComplex(points=data, max_edge_length=thresh)

        def run():
            if collapse:
                st = make().create_simplex_tree(max_dimension=1)
                st.collapse_edges(-1)
                st.expansion(dim + 1)
            else:
                st = make().create_simplex_tree(max_dimension=dim + 1)
            st.compute_persistence(homology_coeff_field=p, min_persistence=0)
            return intervals(st)
        return run
    if task == "cubical":
        def run():
            cc = gudhi.CubicalComplex(top_dimensional_cells=data)
            cc.compute_persistence(homology_coeff_field=p, min_persistence=0)
            out = []
            for k in range(dim + 1):
                for b, d in cc.persistence_intervals_in_dimension(k):
                    out.append((k, float(b), finite_or_inf(d)))
            return out
        return run
    if task == "alpha":
        # A threshold is the largest radius, as in TDA4j's truncated complex: bars alive at it are essential. The tree
        # keeps simplices up to dimension dim + 1 only (all a barcode up to degree dim needs). The Delaunay
        # triangulation is built whole whatever the radius, which is why GUDHI is not expected to finish in 10-D.
        max_square = float(opts["threshold"]) ** 2 if "threshold" in opts else math.inf

        def run():
            st = gudhi.AlphaComplex(points=data).create_simplex_tree(max_alpha_square=max_square)
            st.prune_above_dimension(dim + 1)
            st.compute_persistence(homology_coeff_field=p, min_persistence=0)
            return intervals(st, transform=lambda x: math.sqrt(max(x, 0.0)) if math.isfinite(x) else x)
        return run
    sys.exit(UNSUPPORTED)


def cripser_tool(opts, data):
    if opts["task"] != "cubical" or int(opts.get("p", 2)) != 2:
        sys.exit(UNSUPPORTED)
    dim = int(opts["dim"])
    arr = np.ascontiguousarray(data, dtype=np.float64)
    # T-construction (top cells carry the values), like TDA4j's and GUDHI's top_dimensional_cells. Recent
    # cripser takes filtration="T"; older releases ship a separate tcripser module.
    try:
        import cripser
        cripser.computePH(np.zeros((2, 2)), maxdim=0, filtration="T")
        compute = lambda a: cripser.computePH(a, maxdim=dim, filtration="T")
    except (TypeError, ImportError):
        import tcripser
        compute = lambda a: tcripser.computePH(a, maxdim=dim)

    def run():
        res = compute(arr)
        return [(int(r[0]), float(r[1]), finite_or_inf(r[2])) for r in res if int(r[0]) <= dim]
    return run


def dionysus_tool(opts, data):
    if opts["task"] != "vr":
        # fill_freudenthal is a lower-star filtration of the Freudenthal triangulation, not the T-construction of the
        # cubical benchmark; the wheel has no alpha complex (that needs CGAL).
        sys.exit(UNSUPPORTED)
    import dionysus
    dim, p = int(opts["dim"]), int(opts.get("p", 2))
    variant = opts.get("variant", "cohomology")
    # Dionysus keeps the edges of length at most r in float32; the margin is the float32 rounding of the threshold
    # (the enclosing radius is an edge length, and rounding it down would drop that very edge).
    r = float(opts["threshold"]) * (1 + 1e-6) if "threshold" in opts else math.inf
    if opts.get("format", "points") == "distance":
        # fill_rips reads a 1-D array as the condensed (upper-triangle, row-major) distance matrix.
        inputs = np.ascontiguousarray(data[np.triu_indices(data.shape[0], 1)], dtype=np.float64)
    else:
        inputs = np.ascontiguousarray(data, dtype=np.float64)

    def run():
        filtration = dionysus.fill_rips(inputs, dim + 1, r)
        if variant == "homology":
            pers = dionysus.homology_persistence(filtration, p, "clearing")
        else:
            pers = dionysus.cohomology_persistence(filtration, p, variant == "cocycles")
        # The last diagram is the one of degree dim + 1, which the (dim + 1)-skeleton cannot get right.
        dgms = dionysus.init_diagrams(pers, filtration)[:dim + 1]
        if variant == "cocycles":
            cocycles = [pers.cocycle(pt.data) for dg in dgms for pt in dg]  # read, as a caller of the cocycles would
        return [(k, float(pt.birth), finite_or_inf(pt.death)) for k, dg in enumerate(dgms) for pt in dg]
    return run


def oat_tool(opts, data):
    if opts["task"] != "vr":
        sys.exit(UNSUPPORTED)  # no cubical or alpha complexes (it has Vietoris-Rips and Dowker)
    import oat_python as oat
    dim = int(opts["dim"])
    cycles = opts.get("variant") == "cycles"
    # Edges strictly above this are left out, and the enclosing radius is an edge length: a relative margin keeps that
    # edge in whatever the last bits of the neighbour search (scikit-learn's, from points) say.
    limit = float(opts["threshold"]) * (1 + 1e-6) if "threshold" in opts else math.inf
    distance = opts.get("format", "points") == "distance"
    inputs = np.ascontiguousarray(data, dtype=np.float64)

    def run():
        if distance:
            sparse = oat.dissimilarity.sparse_matrix_for_dense(dissimilarity_matrix=inputs, max_dissimilarity=limit)
        else:
            sparse = oat.dissimilarity.sparse_matrix_for_points(points=inputs, max_dissimilarity=limit)
        decomposition = oat.core.vietoris_rips.BoundaryMatrixDecomposition(
            dissimilarity_matrix=sparse, max_homology_dimension=dim)
        df = decomposition.persistent_homology_dataframe(
            return_cycle_representatives=cycles, return_bounding_chains=False)
        return [(int(k), float(b), finite_or_inf(d))
                for k, b, d in zip(df["dimension"], df["birth_filtration"], df["death_filtration"]) if int(k) <= dim]
    return run


TOOLS = {"ripser.py": ripser_py, "giotto-ph": giotto_ph, "gudhi": gudhi_tool, "cripser": cripser_tool,
         "dionysus": dionysus_tool, "oat": oat_tool}
DISTRIBUTION = {"ripser.py": "ripser", "giotto-ph": "giotto-ph", "gudhi": "gudhi", "cripser": "cripser",
                "dionysus": "dionysus", "oat": "oat_python"}


def version_of(tool):
    try:
        from importlib.metadata import version
        return version(DISTRIBUTION[tool])
    except Exception:
        return "unknown"


def main():
    opts = parse_args(sys.argv[1:])
    tool = opts["tool"]
    if tool not in TOOLS:
        print(f"unknown tool {tool}", file=sys.stderr)
        sys.exit(2)
    t0 = time.perf_counter()
    data = load(opts)
    run = TOOLS[tool](opts, data)
    load_s = time.perf_counter() - t0
    bars = []
    for _ in range(int(opts.get("warmup", 2))):
        bars = run()
    times = []
    for _ in range(int(opts.get("trials", 5))):
        t = time.perf_counter()
        bars = run()
        times.append(time.perf_counter() - t)
    if "out" in opts:
        with open(opts["out"], "w") as f:
            for k, b, d in bars:
                f.write(f"{k}\t{b!r}\t{'inf' if math.isinf(d) else repr(d)}\n")
    counts = {}
    for k, _, _ in bars:
        counts[str(k)] = counts.get(str(k), 0) + 1
    print(json.dumps({"tool": tool, "task": opts["task"], "load_s": load_s, "times_s": times, "bars": counts,
                      "version": version_of(tool)}))


if __name__ == "__main__":
    main()
