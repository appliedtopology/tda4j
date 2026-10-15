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
