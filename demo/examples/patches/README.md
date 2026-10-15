# Natural image patches at real size: a circle, three circles, a Klein bottle

`patches.scala` runs in the demo jar (`demo/README.md`) and takes about 25 seconds, start to finish, on a 4-core machine
with a 1 GB heap.

```
$ cd <the directory with n50000Dct.csv>
$ java -Xmx2g -jar tda4j-demo-0.6.1.jar
scala> :load /path/to/patches.scala
Betti numbers (degrees 0, 1, 2) in the middle of the range: (1, 5, 0)
Klein bottle, Betti numbers at R * 0.75:  over F_2 (1, 2, 1),  over F_17 (1, 1, 0)
```

and two browser pages open (a barcode; two barcodes side by side).

## What it does

1. Loads 50 000 random 3x3 high-contrast patches of natural images, as unit vectors in R^8 (a DCT basis): the sample behind
   the JavaPlex tutorial's section 6 and Carlsson, Ishkhanov, de Silva, Zomorodian, *On the local behavior of spaces of
   natural images* (IJCV 76, 2008).
2. Density of every patch: `DistanceToMeasure` with 15 neighbours, through `JVPTree` (13 s for 50 000 points on its own).
   The densest 30% (15 000 patches) is the paper's core subset X(15,30), up to the choice of density estimator (96% of the
   rows agree with the shipped file).
3. 50 sequential-maxmin landmarks, the lazy witness complex (`nu = 1`, up to R/4), persistence over F_2: **one component and
   five loops**, the paper's "three circle model" (a circle of linear gradients and two smaller circles of quadratic ones).
   With 50 landmarks this is a second or two.
4. The Klein bottle. **The 50 000-patch sample does not contain one that these tools can see**: the paper needed about
   4 million patches, the densest 10% of them at a much more local density estimate, thirty hand-added points and Z/2
   coefficients only (`.claude/WORKLOG-klein-bottle-patches.md`). A fixed grid of 14 more local cores of the sample found no
   Klein bottle and no torus, and no degree-2 class at all. So the script uses the paper's own *ideal* Klein bottle, the
   polynomials c(ax+by)^2 + d(ax+by) evaluated on the 3x3 grid, as a model of Klein-bottle patches (raw pixel space, not the
   paper's DCT coordinates). Over F_2 its Betti numbers are (1,2,1); over F_17 they are (1,1,0): the Klein bottle has 2-torsion
   in H_1. A flat torus gives (1,2,1) over both (the control in `experiments/ideal.scala`).

## The data

Not in this repository. The files are `data/natural_images/n50000Dct.mat` (and the two core subsets `nk15c30Dct.mat`,
`nk300c30Dct.mat`) of https://github.com/appliedtopology/javaplex; the patches come from Lee, Pedersen and Mumford and van
Hateren's image database (the readme next to the files has the references). Check their terms before putting a copy anywhere.

```
python3 -m venv venv && venv/bin/pip install numpy scipy pandas
venv/bin/python -I experiments/convert.py <javaplex>/data/natural_images <outdir>
```

writes `n50000Dct.csv` (50 000 rows, 8 columns, no header, exact), the two cores, and `knn_n50000.npy` (the 50 000 x 300
neighbour distances, 31 s, 120 MB: only `experiments/gen.py` uses it).

## Experiments

`experiments/` holds what produced the numbers in the worklog (`run.sh` runs one script in the demo jar; set `JAR` and `DATA`).

| script | what it does |
|---|---|
| `calibrate.scala` | the tutorial's two published cases: R = 0.6554 and Betti (1,5,0) for X(15,30); (1,1,0) for X(300,30) |
| `pipeline50k.scala` | the whole pipeline inside tda4j: DTM instead of 1/rho_k to select the core |
| `ideal.scala` | ideal Klein bottle and flat torus, F_2 and F_17, 50/100/150 landmarks, with the pre-stated verdict function |
| `klein_one.scala`, `klein_grid.sh` | the fixed grid of local cores X(k,T) of the sample (one JVM each, three at a time) |
| `units.scala` | what the filtration values of `VietorisRips`, `SparseRips`, `DtmRips` mean |
| `rips_sub.scala`, `sub_sweep.sh` | plain Rips, sparse Rips and DTM-Rips on maxmin landmark subsamples, with a cell budget |
| `gen.py` | the candidate cores, the ideal Klein bottle and the torus as CSV |

## Choices worth knowing

- The scale for a surface is the full range R (`maxFiltrationValue = R`), as in JavaPlex's own flat-Klein-bottle exercise; the
  R/4 of the circle examples sees only noise for a 2-manifold.
- The verdict function (`lib.scala`) was fixed before the experiments: Klein-like means an interval of at least 10% of the
  range with (1,2,1) over F_2 and (1,1,0) over F_17 throughout; torus-like means (1,2,1) over both.
- For these point counts the Vietoris-Rips complex of a few hundred landmarks, through the `Persistence` verb, takes one or two
  seconds and shows both structures; sparse and DTM-weighted complexes only add cost here (worklog, section 5).
- A `java.lang.OutOfMemoryError` ends the REPL session (the REPL's own error printer fails on it). Give big runs a heap.
