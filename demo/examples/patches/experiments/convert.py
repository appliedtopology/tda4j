"""Converts JavaPlex's natural-image-patch .mat files to CSV and tabulates k-NN distances for core subsets.

Run:  venv/bin/python -I convert.py <javaplex data/natural_images dir> <output dir>
Output: <name>.csv (no header, 8 columns) for each .mat; knn_n50000.npy: (N, 300) distances to the 1st..300th neighbour
(Euclidean in R^8; the first column is the 1st neighbour, not the point itself).
"""
import sys, time
from pathlib import Path

import numpy as np
import pandas as pd
import scipy.io

src, out = Path(sys.argv[1]), Path(sys.argv[2])
out.mkdir(parents=True, exist_ok=True)

for f in sorted(src.glob("*.mat")):
    m = scipy.io.loadmat(f)
    (key,) = [k for k in m if not k.startswith("__")]
    a = np.asarray(m[key], dtype=np.float64)
    # repr() of a float64 round-trips exactly
    pd.DataFrame(a).to_csv(out / (f.stem + ".csv"), header=False, index=False, float_format="%.17g")
    print(f.name, key, a.shape, flush=True)

X = scipy.io.loadmat(src / "n50000Dct.mat")["n50000Dct"].astype(np.float64)
N, K = X.shape[0], 300
sq = (X * X).sum(1)
knn = np.empty((N, K), dtype=np.float64)
t0 = time.time()
CH = 400
for s in range(0, N, CH):
    blk = X[s : s + CH]
    d2 = sq[s : s + CH, None] + sq[None, :] - 2.0 * blk @ X.T
    np.maximum(d2, 0.0, out=d2)
    idx = np.arange(s, min(s + CH, N))
    d2[idx - s, idx] = np.inf  # the point is not its own neighbour
    part = np.partition(d2, K - 1, axis=1)[:, :K]
    part.sort(axis=1)
    knn[s : s + CH] = np.sqrt(part)
    if (s // CH) % 20 == 0:
        print(f"  knn rows {s}/{N}  {time.time()-t0:.0f}s", flush=True)
np.save(out / "knn_n50000.npy", knn)
print("knn done", knn.shape, f"{time.time()-t0:.0f}s", flush=True)
