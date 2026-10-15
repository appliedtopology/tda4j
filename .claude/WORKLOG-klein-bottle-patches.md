# Natural image patches at real size, and the Klein bottle (started 2026-10-15)

Ask (project lead, before sleeping): a "real sized" final tutorial example, the Klein bottle of image patches, from the
`.mat` files in `appliedtopology/javaplex` (`data/natural_images/`); find how to run it with witness, DTM or sparse Rips in
a demo-able time (5 minutes at the outside); "I'd be interested to see how sparse rips and dtm cope"; document choices.
Method: advisor consulted before and during; this file is written as the work goes (see "Log" at the end).

## What the data is (checked, not recalled)

- `n50000Dct.mat`: 50 000 x 8; `nk300c30Dct.mat`, `nk15c30Dct.mat`: 15 000 x 8. Every row has Euclidean norm 1: 3x3
  high-contrast patches in a DCT basis, on S^7 (Lee-Pedersen-Mumford sample of van Hateren's images). scipy reads all three
  (v5 files, no HDF5 needed); `klein/convert.py` writes CSVs (no header, `%.17g`: round-trips) and an exact 50 000 x 300
  neighbour-distance table (31 s with a chunked matrix product; numpy only, no scipy).
- The two cores are subsets OF the 50 000 sample, and are exactly its densest 30% (15 000 points) by 1/rho_k with rho_k
  the distance to the k-th neighbour, the point itself NOT counted: k = 300 reproduces `nk300c30Dct` on 14 999 of 15 000
  rows, k = 15 reproduces `nk15c30Dct` on 14 999 (one duplicate row in each file). Neighbour k-1 with the point counted
  (the DTM convention in `DistanceToMeasure`, self-inclusive) gives 14 990 / 14 830: close, not the same.

## What the Klein bottle is, per the paper (Carlsson-Ishkhanov-de Silva-Zomorodian, IJCV 76, 2008, section 6-7)

Read from the PDF (redwood.berkeley.edu/wp-content/uploads/2018/08/carlsson-ijcv08.pdf), not from memory:

- X(15,30) on **5 x 10^4** patches is the three-circle model (Betti_1 = 5); X(300,30) is the primary circle. These are the
  two shipped files, and the JavaPlex tutorial uses exactly them (section 6 there; it never computes a Klein bottle).
- The Klein bottle is NOT obtained from a 5 x 10^4 sample. They go to the full M (about **4 x 10^6** patches), take
  X(100,10) (k = 100 there corresponds to k = 1.25 at 5 x 10^4: below any usable noise level on the sample we have), add
  a hand-built set Q of **30 points** (patches with purely quadratic gradients that the density cut misses), and run
  witness persistence on **random 10 000-point subsets** of X(100,10) union Q, repeated "many times". Result: two
  essential H_1 and one H_2 over **Z/2 only**; they note the torus has the same Z/2 numbers and argue for the Klein
  bottle by (a) the generated "ideal" Klein bottle K (the family c(ax+by)^2 + d(ax+by), (a,b), (c,d) on S^1, evaluated at
  the 9 grid points, mean-subtracted and normalised) and a special filtration of K union (X(100,10) union Q) that gives
  K's own barcode, (b) the model: S^1 x S^1 / (theta, phi) ~ (theta + pi, 2 pi - phi).

So: the supplied sample very probably yields the circle and the three-circle model at real size, and does not yield the
Klein bottle without the larger set and the Q augmentation. This worklog tests that claim on the sample rather than
asserting it (below), and offers the ideal Klein bottle K as the faithful stand-in.

## Pass criterion for "this is a Klein bottle" (stated before looking)

One filtration interval, at least 10% of the range, on which the Betti vector over F_2 is (1,2,1) AND the same interval,
over an odd prime (17, the library default), is (1,1,0). Torus: (1,2,1) over both. Anything else is reported as what it
is. No parameter is tuned until it "looks like" the answer.

## Log

### 1. Calibration against the tutorial (done, 10 s wall for both)

Lazy witness, 50 sequential-maxmin landmarks (first landmark = point 0), nu = 1, maxFiltrationValue = R/4, top degree 2
(JavaPlex's `max_dimension = 3`), Z/2, the shipped 15 000-point cores through `klein/calibrate.scala` in the demo jar.
Betti plateaus (share of the range [0, R/4]):

| file | R | plateau | share | tutorial says |
|---|---|---|---|---|
| `nk15c30Dct` | 0.6554 | (1,5,0) | 78% | R = 0.6554, Betti_0 = 1, Betti_1 = 5 |
| `nk300c30Dct` | 0.5919 | (1,1,0) | 87% | Betti_0 = Betti_1 = 1 |

Same R to four digits and the same Betti numbers: the parameters translate. Compute per file: maxmin 0.5-0.6 s, stream
0.1-0.3 s, persistence 1.1-1.4 s. A short (1,1,1) / (1,3,0) run precedes each plateau (4-5% of the range): the usual
early noise, not a finding.

### 2. The whole pipeline inside tda4j on the 50 000-point sample (done, 77 s wall for both cores, 6 GB heap)

`klein/pipeline50k.scala`: CSV (50 000 x 8) -> `DistanceToMeasure(ms, JVPTree(ms), k, q = 2)` -> densest 15 000 by DTM ->
50 maxmin landmarks -> lazy witness (nu = 1, R/4) -> persistence over F_2.

| k | DTM time | rows in common with the shipped core | Betti plateau | share |
|---|---|---|---|---|
| 15 | 12.8 s | 14 397 / 15 000 | (1,5,0) | 60% (a (1,6,0) run before it) |
| 300 | 53.6 s | 14 447 / 15 000 | (1,1,0) | 91% |

The DTM core (a smoothed density: the mean of squared distances to the k nearest, self included) is not the paper's
1/rho_k core (96% overlap) but gives the same topology, and it removes the Python step: this is the tda4j-only demo
pipeline. Witness stage 0.2 s (landmarks) + 0.1-1.0 s (stream) + 1.6-1.9 s (persistence). `JVPTree` is valid for
Euclidean distances (not for an explicit matrix); the brute-force default would be O(n^2) here.

### 3. The ideal Klein bottle and the torus control (5000 points each; witness, nu = 1, maxFiltrationValue = R)

First attempt used R/3 (copied from the 1-D examples) and showed only noise for BOTH shapes (hundreds of short loops, no
plateau) -- a wrong-scale artefact, not a result. JavaPlex's own recipe for the flat Klein bottle (tutorial exercise 22:
1000 points, 50 sequential-maxmin landmarks, nu = 1, `max_filtration_value = R`) was adopted: a 2-manifold needs the full
range. The criterion and the verdict function were NOT changed.

`klein/ideal.scala`, F_2 and F_17 from one stream; widths are shares of [0, R]:

| input | landmarks | R | verdict | interval (F_2 = (1,2,1)) | F_17 over it | persistence F_2 / F_17 |
|---|---|---|---|---|---|---|
| ideal Klein | 50 | 0.5620 | KLEIN-LIKE | [0.0773, 0.4187] (61%) | (1,1,0) | 1.5 s / 0.6 s |
| ideal Klein | 100 | 0.3802 | KLEIN-LIKE | [0.1834, 0.3802] (52%) | (1,1,0) | 1.7 s / 1.2 s |
| ideal Klein | 150 | 0.3166 | KLEIN-LIKE | [0.1837, 0.3166] (42%) | (1,1,0) | 7.9 s / 2.0 s |
| flat torus | 50 | 0.6913 | TORUS-LIKE | [0.0881, 0.5617] (69%) | (1,2,1) | 1.4 s / 1.3 s |
| flat torus | 100 | 0.4710 | TORUS-LIKE | [0.1307, 0.4710] (72%) | (1,2,1) | 1.9 s / 0.6 s |
| flat torus | 150 | 0.3888 | TORUS-LIKE | [0.1614, 0.3888] (58%) | (1,2,1) | 5.6 s / 4.3 s |

The criterion separates the two on every setting. The "ideal Klein bottle" is the paper's family (section 7) evaluated on
the 3x3 grid, mean-subtracted and normalised, in raw pixel space with the Euclidean norm (9 coordinates), NOT the paper's
D-norm DCT coordinates: a model of Klein-bottle patches, parametrised uniformly in (theta, phi), not on the surface.
Timings are from runs that shared the machine with another job: indicative, not the final timing.

### 4. Is there a Klein bottle in the 50 000-point sample? A fixed grid of local cores (no)

Cores X(k,T): the densest T% of the sample by 1/rho_k (k-th neighbour, self not counted), k in {1,2,3,5,8}, T in {5,10,20}%
(2 500 / 5 000 / 10 000 points). Fixed before looking: 100 maxmin landmarks, nu = 1, maxFiltrationValue = R, F_2 and F_17
from one stream, the criterion above. One JVM per core (`klein/klein_grid.sh`). Nothing was tuned afterwards.

**14 of the 15 cores: no interval of >= 10% with Betti (1,2,1) over F_2, so neither Klein-like nor torus-like. The 15th
(k = 1, T = 20%, 10 000 points, the one whose density ranking starts with exact duplicates, rho_1 = 0) has no verdict: its
F_2 persistence took 410.6 s and the F_17 run did not finish inside the 900 s limit of the grid driver. k = 1 is
degenerate anyway.** What the cores do show:

- k = 8, T = 20%: (1,5,0) over 83% of [0, R] (R = 0.4776), identical over F_2 and F_17: the three-circle model, now at a
  much more local density estimate than the shipped k = 15.
- k = 5, T = 10%: (1,5,0) for 43% after a stretch with (2,4,0) -> (1,5,0) -> (1,4,0): same model, noisier.
- k = 3 and k = 2 at T = 5%: fragmented (Betti_0 up to 15, Betti_1 of 3-5 in short runs): too sparse to say anything.
- No core has any H_2 on a plateau.

Reading: on this 5 x 10^4 sample the local cores give the three-circle model or noise, never a surface. That is what the
paper warns about ("reducing k below 15 seems unreasonable due to noise issues") and why they went to 4 x 10^6 patches. Not
shown by this experiment, and not claimed: that the Klein bottle is absent from the full space M.

Parameter caveat: 100 landmarks, nu = 1, maxFiltrationValue = R for every core; a core with a genuine Klein bottle but
needing more landmarks would be missed. The ideal Klein bottle needed only 50-150 landmarks at R (section 3).

### 5. Sparse Rips and DTM-Rips, and plain Rips as the baseline (maxmin landmark subsamples of the real core)

Method (`experiments/rips_sub.scala`, `sub_sweep.sh`): `LandmarkSelector.maxmin` of the 15 000-point core (or the 5 000-point
ideal Klein bottle) down to n landmarks; `vr` = `Persistence(points, maxDimension = top, maxFiltrationValue = cap)` (Auto:
packed Ripser); `sparse` = `SparseRips(eps)` on the landmarks, reduced by the generic cohomology engine (Ripser refuses it);
`dtm` = `DtmRips` with DTM(k = 15, q = 2) weights computed on ALL the points (JVPTree, 0.6-0.9 s) and restricted to the landmarks.
Cell budget 5 000 000; 240 s timeout per case; one JVM per case, 6 GB heap; the machine was otherwise idle for these.

**Units** (`experiments/units.scala`, 200 points): `VietorisRips` and `SparseRips` values are DISTANCES (an edge's value is
its length; `SparseRips(eps = 0.02)` equals plain Rips on all 19 900 edges; at eps = 0.9 it keeps 3 137 of them). `DtmRips`
(p = 1) values are `d(i,j) + w_i + w_j` for an edge and `2 w_i` for a vertex, so its scale sits higher by about twice the
weights (about 0.12 each here). Caps below are in those units.

| input (top degree) | landmarks | method | cells | time | Betti plateau (F_2) |
|---|---|---|---|---|---|
| three-circle core (1), cap 1.2 | 400 | plain Rips | - | 1.2 s | (1,5) on [0.642, 1.119] (40%) |
| | 400 | sparse eps 0.5 | 863 962 | 48 s count + 103 s reduce = 163 s wall | (1,5) on [0.642, 1.119], bars identical to plain |
| | 400 | DTM-Rips k 15, cap 1.5 | 464 415 | 20 s + 46 s = 76 s wall | (1,5) on [1.136, 1.5] (24%; the cap cuts it) |
| | 800 | plain Rips | - | 2.5 s | (1,5) on [0.516, 1.038] (44%) |
| | 800 | sparse eps 0.5 | not counted | TIMEOUT at 240 s (stream 0.2 s) | - |
| | 800 | DTM-Rips k 15 | 4 040 821 | 215 s just to count; TIMEOUT | - |
| ideal Klein (2), cap 0.9 | 300 | plain Rips | - | 1.0 s | (1,2,1) on [0.623, 0.9] (31%) |
| | 300 | sparse eps 0.5 | 118 992 | 38 s + 71 s = 117 s wall | same plateau and bars |
| | 300 | DTM-Rips k 15, cap 1.2 | 204 801 | 33 s + 96 s = 136 s wall | (1,2,1) on [0.96, 1.101] (12%); 4 loops at the cap |

Reading, with what each number supports:
- **Plain Rips through the verb is the winner at these sizes**: 1-2.5 s, and it shows both structures (five loops; the Klein
  bottle's (1,2,1)). Nothing in this table needed a sparser or weighted complex.
- **Sparse Rips at eps = 0.5 gives the same bars and costs 60-100x more**: the complex is not small for 8-dimensional data
  (864 k cells at 400 landmarks), and it cannot use the packed Ripser engine, so every cell is materialised. Separately,
  the O(n^2) build (issue #64) measured earlier on the full core: 48-51 s for 15 000 points (`nk15c30Dct`, `nk300c30Dct`).
  One eps was tried; section 6 below varies it.
- **DTM-Rips** is the noise-robust choice in principle (the weights come from all 15 000 points), but it is the heaviest here:
  4.0 M cells at 800 landmarks. It does not need to be: on a core that is already the densest 30% the weights add little.
- The REPL dies on `OutOfMemoryError`: the first run of this experiment (2 000 points, eps 0.5, cap 1.2, 6 GB) ended with the
  REPL's own error printer throwing a `NullPointerException` (`fansi.Str` of a null stack-frame string), taking the session
  down. Checked: `RuntimeException()` with no message, an NPE, `1/0` and a plain `Exception` are all printed and the session
  continues. So it is out-of-memory specific. Start big examples with a stated `-Xmx`.

### 6. The committed demo script, timed alone (`demo/examples/patches/patches.scala`)

`:load patches.scala` in the demo jar, from the directory with `n50000Dct.csv`: raw 50 000 patches -> DTM(k = 15) via JVPTree ->
densest 30% -> 50 landmarks -> lazy witness -> persistence over F_2 -> `view()`; then the ideal Klein bottle (5 000 points,
100 landmarks, witness at R) over F_2 and F_17 with a side-by-side `view()`. Output: Betti (1,5,0) in the middle of the range;
Klein bottle (1,2,1) over F_2 and (1,1,0) over F_17.

| -Xmx | wall, JVM start to exit |
|---|---|
| 4g | 28.5 s (a finishing background job may have shared the machine) |
| 2g | 24.1 s |
| 1g | 27.4 s |

4 cores, Linux, Java 21, nothing else running for the 2g and 1g runs. Correct output at every heap, 1 GB included.

### 5b. Second and third sweeps: a different eps, larger sizes, all 15 000 points

(`subsweep-sub2/sub3`; 6 GB heap, 240 s timeout, idle machine; top degree 1, F_2, cap in distance units.)

| input | method | result |
|---|---|---|
| 400 landmarks, cap 1.2 | sparse eps **0.9** | 863 962 cells (the SAME count as eps 0.5), bars identical to plain Rips; 39 s count + 65 s reduce = 111 s |
| 800 landmarks | sparse eps 0.9 | TIMEOUT at 240 s |
| 2 000 landmarks | sparse eps 0.9 | `OutOfMemoryError` after a 0.9 s stream |
| 2 000 landmarks | plain Rips | 28.1 s, (1,5) on [0.462, 1.038] (48%) |
| 4 000 landmarks | plain Rips | 219.8 s, (1,5) on [0.387, 1.038] (54%) |
| all 15 000 points, cap 0.5 | plain Rips (verb, packed Ripser) | **88.8 s**, (1,5) on [0.325, 0.5] (35% of the cap): the five loops of the real core with no landmarks at all |
| all 15 000 points, cap 1.2 | plain Rips | `OutOfMemoryError` |
| all 15 000 points, cap 0.5 | sparse eps 0.9 and eps 0.5 | stream built in 39.4 s / 33.5 s, then `OutOfMemoryError` in the enumeration |

- **Sparse Rips on a maxmin subsample prunes nothing**: eps 0.5 and 0.9 give the same 863 962 cells and the same 822 bars as plain
  Rips on the same 400 landmarks. A greedy-permutation subsample is already a net, so there is nothing left for the sparsification
  to remove below the cap. The construction is meant for the DENSE cloud; there, in this checkout, it did not fit 6 GB (above).
  Only a 6 GB heap and the cap/eps values above were tried; more memory might let the full-size sparse case finish (not tried).
- **Plain Rips grows steeply with the landmark count** (1.2 s, 2.5 s, 28 s, 220 s at 400, 800, 2 000, 4 000 landmarks), but a
  few hundred landmarks already show the five loops.
- Artefact corrected: the first plain-Rips run on all 15 000 points (`sub2`) died of memory because the probe script allocated
  a cached 15 000 x 15 000 distance matrix (1.8 GB) before choosing the method, not because of Rips. Fixed (`rips_sub.scala`),
  rerun as `sub3`; the 1.2-cap out-of-memory above is from the fixed script.
- Not tried: top degree 2 on the real core (the three-circle model has no H_2), eps between 0.5 and 0.9 or above 0.9,
  `Engine.Ripser` on the sparse stream (the stream refuses it), more memory.
