"""Times C++ Ripser (through ripser.py) on a point cloud written by BlockParallelReduction's `dump` method.

    python3 -I .claude/scripts/ripser-cpp-timing.py <points.csv> <maxDim> <threshold> [trials] [p]

Prints the median wall-clock time of `ripser(distance matrix, maxdim, thresh, coeff)` (the distance matrix is computed
once, outside the timing) and the number of bars of positive length.
"""
import sys
import time

import numpy as np
from ripser import ripser
from scipy.spatial.distance import pdist, squareform

points = np.loadtxt(sys.argv[1], delimiter=",")
max_dim = int(sys.argv[2])
threshold = float(sys.argv[3])
trials = int(sys.argv[4]) if len(sys.argv) > 4 else 5
p = int(sys.argv[5]) if len(sys.argv) > 5 else 17
distances = squareform(pdist(points))
times = []
for _ in range(trials):
    start = time.perf_counter()
    result = ripser(distances, maxdim=max_dim, thresh=threshold, coeff=p, distance_matrix=True)
    times.append(time.perf_counter() - start)
bars = sum(int(np.sum(d[:, 1] > d[:, 0])) for d in result["dgms"])
print(f"ripser.py n={len(points)} maxDim={max_dim} threshold={threshold} p={p}: bars {bars}, "
      f"median {sorted(times)[trials // 2] * 1000:.1f} ms")
