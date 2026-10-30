"""RedZeD's input file, for bench.py: the lower triangle of a Vietoris-Rips case's distance matrix.

RedZeD (https://github.com/nkershaw01/RedZeD; Kapulkin and Kershaw, arXiv:2606.06310) reads a distance matrix from a binary
file. With `--lower` the file is raw little-endian float32 values and nothing else: the n(n - 1) / 2 entries below the diagonal,
row by row (d(1, 0), d(2, 0), d(2, 1), d(3, 0), ...); n is worked out from the file's size. That is the layout of
`D[np.tril_indices(n, -1)]`, and it is the half of the matrix the other tools read too (ripser.cpp takes the lower triangle of
a distance matrix).

The file is written once per case, before the run and untimed, as DIPHA's input is: it does not depend on the threshold. The
distances are computed from the points exactly (differences of coordinates, not the Gram-matrix identity, which loses digits
for close points), a block of rows at a time, so that a case of 50,000 points never holds its n x n matrix in memory.
"""
from pathlib import Path

import numpy as np

# Values (not bytes) of one block of coordinate differences: 2^25 doubles are 256 MB.
BLOCK_ELEMENTS = 1 << 25


def lower_rows(rows, n, block):
    """The lower triangle of an n x n matrix, row by row, as flat arrays covering `block` rows at a time.
    `rows(start, stop)` returns the matrix rows start .. stop - 1 restricted to the columns 0 .. stop - 2."""
    start = 1
    while start < n:
        stop = min(n, start + block)
        part = rows(start, stop)
        yield np.concatenate([part[k, :start + k] for k in range(stop - start)])
        start = stop


def point_rows(points, block_elements=BLOCK_ELEMENTS):
    """The lower triangle of the Euclidean distance matrix of `points`, in flat blocks."""
    x = np.ascontiguousarray(points, dtype=float)
    n, d = x.shape

    def rows(start, stop):
        return np.sqrt(((x[start:stop, None, :] - x[None, :stop - 1, :]) ** 2).sum(axis=-1))

    return lower_rows(rows, n, max(1, block_elements // max(1, n * d)))


def matrix_rows(matrix, block_elements=BLOCK_ELEMENTS):
    """The lower triangle of a distance matrix, in flat blocks. Only the entries below the diagonal are read:
    d(i, j) = D[max][min], as ripser.cpp reads it."""
    m = np.asarray(matrix, dtype=float)
    n = m.shape[0]
    return lower_rows(lambda start, stop: m[start:stop, :stop - 1], n, max(1, block_elements // max(1, n)))


def prepare_input(load, fmt, dest, block_elements=BLOCK_ELEMENTS):
    """Writes the RedZeD input of a Vietoris-Rips case unless it is there already. `load()` returns the point cloud (fmt
    "points") or the distance matrix (fmt "distance"); it is not called when the file exists. The path."""
    dest = Path(dest)
    if dest.exists() and dest.stat().st_size > 0:
        return dest
    data = load()
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(dest.suffix + ".part")
    with open(tmp, "wb") as f:
        for block in (point_rows if fmt == "points" else matrix_rows)(data, block_elements):
            block.astype("<f4").tofile(f)
    tmp.rename(dest)
    return dest
