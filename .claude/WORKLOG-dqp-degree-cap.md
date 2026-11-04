# Auto: DQP for a low degree cap in high dimension (2026-11-03)

Question (Mikael, 2026-10-28): does DQP need a degree cap to be worth it, and should `Auto` pick it only for capped
computations? Data: the `--path four` sweep (degrees up to 4 in R^d, interior and surface points, n = 40 and 60,
`bench/sweep_alpha.py` on the 32-core server, commit after #285), against the `--path top` rows (cap d - 1) and the
radius sweep's enclosing-radius row. `tda4j` there is the Helix path (no radius); tda4j-helix equals it.

Interior, n = 40 / n = 60, time Helix / DQP (RSS Helix vs DQP, MB):

| d | n = 40 | n = 60 |
|---|---|---|
| 5 | 0.9x (647 vs 674) | 0.56x (731 vs 680) |
| 6 | 1.1x (745 vs 675) | 1.15x (752 vs 747) |
| 7 | 2.3x (749 vs 753) | 1.8x (959 vs 1115) |
| 8 | 3.1x (1003 vs 968) | 3.1x (2262 vs 2068) |
| 10 | 7.6x (2203 vs 2278) | 6.4x (6029 vs 2994) |
| 12 | 27.7x (10508 vs 2362) | Helix timeout, DQP 233 s (8329) |
| 15 | Helix timeout, DQP 66 s (3059) | Helix timeout, DQP 757 s (22005) |

Surface points: within 20% of those at every cell. Cech is flat in d (n = 40: 23 to 38 s, 2 GB; n = 60: 321 to 471 s,
12 to 16 GB): it beats DQP at d = 15 (37.8 s vs 66 s; 471 s vs 757 s) and loses at d = 12.

Cost model: a triangulation's cost does not depend on the cap (uniform points: cells linear in n with a constant
exploding in d); DQP builds the candidates up to dimension cap + 1 only. At the top degree (cap = d - 1) DQP's lead is
1.66x (d = 9, n = 40) and 1.2x (d = 10, n = 60) with 1.0x to 2.5x the memory, so those stay on the triangulation.

Rule (`AlphaShapes.prefersDQPAtEnclosingRadius`, used by `upToEnclosingRadius`, so by the verb and the facades): DQP iff
d >= 8 and `maxDimension <= d - 2`. d = 7 is left out: the lead shrinks with n there (2.3x at 40, 1.8x at 60; a straight
extrapolation crosses 1 near n = 150). Not measured: caps between 4 and d - 2, n >= 100, clustered clouds. Asked of
Mikael: `--dim 8 --dims 10` and `--dim 6 --dims 8` (cap d - 2) and an n-grid (`--grid "8:60,100,200,400;12:60,100,200,400"
--dim 2 --dims "" --points ""`).

Tests: `AlphaDispatchSpec` (the choice at the thresholds, mutation-checked on both, and the verb's bars against Helix's in
8-D at caps 1, 2, 4).
