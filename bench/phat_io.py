"""PHAT's files, for bench.py: reading the persistence pairs PHAT writes and turning them into bars.

PHAT (https://bitbucket.org/phat-code/phat) reduces a filtered boundary matrix; it knows nothing of the complex. The matrix
comes from `workers/make_phat.cpp`, which writes beside it `<matrix>.values` (the filtration value of each column, float64) and
`<matrix>.dims` (its dimension, int64). PHAT's pairs file is binary: the number of pairs, then (birth, death) column indices,
int64 each. A column that is in no pair is an essential class.

The values are DIPHA's (half the largest distance of a simplex), so the bars are doubled, as in dipha_io.py.
"""
import struct
from pathlib import Path

import numpy as np


def read_pairs(path):
    """[(birth column, death column)] from PHAT's binary pairs file."""
    with open(path, "rb") as f:
        (count,) = struct.unpack("<q", f.read(8))
        raw = np.fromfile(f, dtype="<i8", count=2 * count)
    if len(raw) != 2 * count:
        raise ValueError(f"{path}: {count} pairs announced, {len(raw) // 2} found")
    return raw.reshape(-1, 2)


def pairs_to_bars(pairs_path, matrix_path, bars_path, max_dim):
    """Writes the bars of degree at most `max_dim` as `dim<TAB>birth<TAB>death` (values doubled; a pair of equal values
    is not a bar; a column in no pair is an essential class) and returns the number of bars per degree."""
    pairs = read_pairs(pairs_path)
    values = np.fromfile(f"{matrix_path}.values", dtype="<f8")
    dims = np.fromfile(f"{matrix_path}.dims", dtype="<i8")
    if len(values) != len(dims):
        raise ValueError(f"{matrix_path}: {len(values)} values for {len(dims)} columns")
    paired = np.zeros(len(dims), dtype=bool)
    paired[pairs.ravel()] = True
    counts = {}
    with open(bars_path, "w") as f:
        for birth, death in pairs:
            dim = int(dims[birth])
            if dim <= max_dim and values[birth] != values[death]:
                f.write(f"{dim}\t{2 * float(values[birth])!r}\t{2 * float(values[death])!r}\n")
                counts[str(dim)] = counts.get(str(dim), 0) + 1
        for column in np.nonzero(~paired & (dims <= max_dim))[0]:
            dim = int(dims[column])
            f.write(f"{dim}\t{2 * float(values[column])!r}\tinf\n")
            counts[str(dim)] = counts.get(str(dim), 0) + 1
    return counts
