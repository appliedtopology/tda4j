"""DIPHA's binary files, for bench.py: writing the sparse distance matrix a Vietoris-Rips case is run from, and reading the
persistence diagram DIPHA writes back.

DIPHA (https://github.com/DIPHA/dipha) reads and writes little-endian binary files of 64-bit words that start with the magic
number 8067171840 and a file type. The input here is type 8, a sparse distance matrix: n, the number of connections of each
point, the indices of the connected points, the distances. Both directions of an edge are listed. A distance above the
threshold is simply not an edge, which is how DIPHA is given a threshold. The output is type 2, a persistence diagram: the
number of points, then (dimension, birth, death) triples, an essential class stored with the dimension -d - 1.

DIPHA's filtration value of a Vietoris-Rips simplex is half the largest distance in it (a radius), where every other tool of
the benchmark uses the distance: `diagram_to_bars` doubles the values.
"""
import struct
from pathlib import Path

import numpy as np

MAGIC = 8067171840
SPARSE_DISTANCE_MATRIX = 8
PERSISTENCE_DIAGRAM = 2

# An edge of length exactly the threshold (the enclosing radius is one) stays in whatever the last bit says.
MARGIN = 1e-9


def edges_within(points, threshold):
    """(i, j, d): the pairs i < j of the points at distance at most the threshold, with their distances."""
    from scipy.spatial import cKDTree
    points = np.ascontiguousarray(points, dtype=float)
    pairs = cKDTree(points).query_pairs(threshold * (1 + MARGIN), output_type="ndarray")
    i, j = pairs[:, 0], pairs[:, 1]
    return i, j, np.linalg.norm(points[i] - points[j], axis=1)


def matrix_edges_within(matrix, threshold):
    """The same from a distance matrix, read from its lower triangle: d(i, j) = D[max][min], as ripser.cpp does."""
    matrix = np.asarray(matrix, dtype=float)
    lower_i, lower_j = np.tril_indices(matrix.shape[0], -1)
    d = matrix[lower_i, lower_j]
    keep = d <= threshold * (1 + MARGIN)
    return lower_j[keep], lower_i[keep], d[keep]  # i < j


def write_sparse_distance_matrix(path, n, i, j, d):
    """Writes the graph with the edges (i[k], j[k]) of length d[k], listed from both ends, rows in index order."""
    rows = np.concatenate([i, j]).astype(np.int64)
    cols = np.concatenate([j, i]).astype(np.int64)
    dist = np.concatenate([d, d]).astype(np.float64)
    order = np.lexsort((cols, rows))
    rows, cols, dist = rows[order], cols[order], dist[order]
    counts = np.bincount(rows, minlength=n).astype(np.int64)
    with open(path, "wb") as f:
        f.write(struct.pack("<qqq", MAGIC, SPARSE_DISTANCE_MATRIX, n))
        counts.tofile(f)
        cols.tofile(f)
        dist.tofile(f)


def prepare_input(load, fmt, threshold, dest):
    """Writes the DIPHA input of a Vietoris-Rips case at the threshold, unless it is there already. `load()` returns the
    point cloud (fmt "points") or the distance matrix (fmt "distance"); it is not called when the file exists."""
    dest = Path(dest)
    if dest.exists() and dest.stat().st_size > 0:
        return dest
    data = load()
    dest.parent.mkdir(parents=True, exist_ok=True)
    i, j, d = edges_within(data, threshold) if fmt == "points" else matrix_edges_within(data, threshold)
    tmp = dest.with_suffix(dest.suffix + ".part")
    write_sparse_distance_matrix(tmp, len(data), i, j, d)
    tmp.rename(dest)
    return dest


def read_diagram(path):
    """[(dimension, birth, death)] of a DIPHA diagram, an essential class as (d, birth, inf), values as DIPHA has them."""
    with open(path, "rb") as f:
        magic, kind, count = struct.unpack("<qqq", f.read(24))
        if magic != MAGIC or kind != PERSISTENCE_DIAGRAM:
            raise ValueError(f"{path} is not a DIPHA persistence diagram")
        raw = np.fromfile(f, dtype=np.dtype([("dim", "<i8"), ("birth", "<f8"), ("death", "<f8")]), count=count)
    if len(raw) != count:
        raise ValueError(f"{path}: {count} points announced, {len(raw)} found")
    out = []
    for dim, birth, death in raw:
        out.append((int(-dim - 1), float(birth), float("inf")) if dim < 0 else (int(dim), float(birth), float(death)))
    return out


def diagram_to_bars(diagram_path, bars_path, max_dim):
    """Writes the bars of degree at most `max_dim` as `dim<TAB>birth<TAB>death` with the values doubled (DIPHA works with
    radii, the benchmark with distances) and returns the number of bars per degree."""
    counts = {}
    with open(bars_path, "w") as f:
        for dim, birth, death in read_diagram(diagram_path):
            if dim > max_dim:
                continue
            f.write(f"{dim}\t{2 * birth!r}\t{'inf' if death == float('inf') else repr(2 * death)}\n")
            counts[str(dim)] = counts.get(str(dim), 0) + 1
    return counts
