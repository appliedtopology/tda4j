# Showcase application directions: cubical and simplicial-set support

Research pass, 2026-09-20, in response to: "What would be some good compelling showcase applications of our
cubical and especially our simplicial-set support?" Not a WORKLOG (no debugging/investigation arc here) — this
is a roadmap/ideas document, kept separate so it doesn't get lost in chat scrollback. Not retroactively edited
to match code drift; re-derive from current code state before acting on anything below.

## Framing

Two different kinds of "compelling": things GUDHI/CubicalRipser/Ripser-family tools already do well (credible
demos, but not a reason to reach for tda4j specifically), vs. things that lean on what's actually distinctive
about tda4j's own feature set. The cubical directions below are mostly the former (real, active research areas,
worth demoing, but not novel to tda4j). The simplicial-set directions are the latter and are the more
interesting bet: no mainstream TDA library (GUDHI, Ripser, Dionysus, Eirene, Flagser) has general simplicial-set
(degeneracy-aware) support with `product`/`coproduct`/`quotient`/`identify`, and the one lineage that does
(Kenzo/EAT, effective homology) computes static homology only, in unmaintained Common Lisp, with no
filtration/persistence concept at all. tda4j's combination — hand-buildable small *exact* models plus a real
persistence-capable engine on top — appears to be genuinely rare.

## Cubical complexes: 4 directions

1. **Train-free / weakly-supervised medical image segmentation.** François & Tinarrage, *Train-Free
   Segmentation in MRI with Cubical Persistent Homology* (arXiv:2401.01160, JMIV 2026). Pipeline: threshold to
   find the object, detect a topologically-known subset via cubical persistence, then **extract representative
   cycles from the persistence diagram** to delineate anatomical structure (evaluated on glioblastoma and fetal
   cortical-plate segmentation). Good fit specifically because it needs more than a bar-count histogram — it
   needs `barcodeAt`'s actual representative-cycle reconstruction (already implemented for
   `CellularHomologyContext`/cubical), which CubicalRipser/GUDHI's cubical module don't expose as directly.

2. **Porous-media / materials characterization from micro-CT.** Established line (Moon et al. 2019 *Water
   Resources Research*; Thompson et al. 2023): the **signed Euclidean distance transform** (negative in pore
   space, positive in grain space) as the cubical filtration value, quantifying pore/grain size, shape, and
   connectivity from 3D voxel scans, applied to predicting fluid flow and dissolution in rock/carbonate samples.
   `CubicalImage.fromVoxelGrid3D`/`fromFlatArray` already accept this input shape directly; missing piece is
   just a SEDT-based `topCellValue` loader.

3. **Cosmic web / large-scale-structure topology.** Active area (Oxford MNRAS 2025-2026, including
   neutrino-mass constraints from cosmic-web topology). Density fields from N-body simulations represented as
   cubical complexes on a grid (periodic boundary conditions); Betti-curve evolution across the filtration
   discriminates cosmological models. Good match for tda4j's multi-field coefficient support (F2 vs. rationals
   vs. Double) since orientability/torsion questions occasionally matter here. Also a genuinely large-grid
   stress test — see the capacity sweep below for how far that can realistically go today.

4. **Topologically-regularized image denoising.** *A Cubical Persistent Homology-Based Technique for Image
   Denoising with Topological Feature Preservation* (2024): persistence barcodes distinguish "signal" topology
   from noise-induced short-lived features before filtering. Lighter-weight than the above, self-contained demo
   — only needs 2D grayscale images (`CubicalImage.fromBufferedImage`, already built and PNG-round-trip-tested).

**Honest caveat, now backed by a measured capacity sweep** (`.claude/WORKLOG-cubical-capacity-sweep.md`,
2026-09-20): the chunks engine (`CellularPersistenceInChunksContext[Cube, Double]`) comfortably handles a
single functional-MRI-sized volume (~140k voxels) in well under a second. A modest cosmological grid (128^3 =
2.1M cells) is workable as a batch job (~90s, ~10GB heap) but not interactive. A full-resolution anatomical MRI
(256^3 ≈ 16.7M voxels) or a typical micro-CT porous-media scan (often tens of millions of voxels) are both well
beyond what was measured comfortable even on the faster engine — memory, not wall-clock time, is the actual
binding constraint at that scale (a 12GB heap was already saturated at 7.19M cells). A real showcase at (2)'s or
(3)'s natural scale needs either a cropped/downsampled region of interest, or the union-find/discrete-Morse
style engine (CubicalRipser / Wagner-Chen-Vuçini) CLAUDE.md already names as a valid, unattempted future
direction. (4) and a cropped/downsampled version of (1) remain the most immediately tractable demos without
further scaling work.

## Simplicial sets: 4 directions

1. **Symmetry-reduced / quotient configuration spaces.** Directly matches *Inferring a Cell Structure on the
   Space of Cyclooctane Conformations* (arXiv:2502.20149, 2025): build a cell structure on a molecule's labeled
   conformation space, then take the **quotient by the symmetry group's action** to get the physically
   meaningful unlabeled space (finding it's contractible, vs. the labeled space isn't). This is exactly the
   `identify`/`quotient` machinery shipped in `.claude/WORKLOG-autonomous-session-2026-09-19.md`'s task #3. Same
   pattern in *Quotient Geometry and Persistence-Stable Metrics for Swarm Configurations* (arXiv:2603.18041,
   2026) for unordered/orbit configuration spaces of multi-robot systems. Showcase: build a small labeled
   configuration-space model by hand (or via `product`), quotient by the relevant symmetry group via `identify`,
   get exact Betti numbers/persistence with no point-cloud sampling noise — cross-validating or replacing what
   these papers currently do numerically.

2. **A modern, tested, open successor to Kenzo for classifying spaces / group (co)homology.** tda4j's
   `realProjectiveSpace` fixture is already literally `B(Z/2)`'s reduced-bar model. Kenzo computes
   `H^*(BG; F_p)` for finite/simplicial groups via exactly this construction, with real applications in
   computational group theory (Ellis' HAP package is the modern comparison point, still active). tda4j could
   offer the same for small finite groups (cyclic, dihedral, quaternion) as a Scala/JVM-native, `sbt
   test`-validated alternative — Kenzo itself has no maintained modern reimplementation.

3. **Topological-robotics small-model validation.** Farber's topological-complexity theory (motion planning,
   TC(X)) and sensor-coverage results (Ghrist et al., *Coverage in sensor networks via persistent homology*)
   lean on cohomology-ring structure of specific small spaces: real projective spaces, tori, lens spaces.
   tda4j's exact, hand-built RP^n/torus fixtures plus `product`/`quotient` let a researcher verify a claimed
   cohomology-ring computation or TC(X) bound on a concrete small model cheaply — a reference/pedagogical tool
   rather than a scale play.

4. **Δ-set shape descriptors for digital images, done properly.** Ahmad & Peters, *Delta Complexes in Digital
   Images* (arXiv:1706.04549, 2017), replace pixel-triangulations with delta-sets specifically because far
   fewer cells are needed to capture the same shape — but that work only computes static shape-proximity
   measures, no filtration/persistence. tda4j could extend the idea with an actual filtered Δ-set (via
   `FilteredSimplicialSetStream`) over binary shape masks (segmentation masks, glyph outlines): a compact
   non-degenerate skeleton instead of one cube per pixel, feeding the same persistence machinery at a fraction
   of the cell count `CubicalGridStream` would need for the same region.

**Recommendation from the research pass**: direction (1) is the most compelling and most novel — a live,
2025-era research pattern (symmetry-quotient conformation/configuration spaces) mapping almost one-to-one onto
what was just built, and nothing else in the mainstream TDA tooling landscape can do it. Direction (2) is lower
effort to prototype (the RP² bar-construction pattern already exists) with a clear "why this matters" story
(Kenzo's obsolescence), even though the audience is narrower.

## Sources

- [Train-Free Segmentation in MRI with Cubical Persistent Homology (arXiv:2401.01160)](https://arxiv.org/pdf/2401.01160)
- [A Cubical Persistent Homology-Based Technique for Image Denoising](https://www.researchgate.net/publication/384686277_A_Cubical_Persistent_Homology-Based_Technique_for_Image_Denoising_with_Topological_Feature_Preservation)
- [Statistical Inference Over Persistent Homology Predicts Fluid Flow in Porous Media](https://agupubs.onlinelibrary.wiley.com/doi/10.1029/2019WR025171)
- [Persistent Homology as a Heterogeneity Metric for Predicting Pore Size Change in Dissolving Carbonates](https://agupubs.onlinelibrary.wiley.com/doi/abs/10.1029/2023WR034559)
- [Persistent homology of the cosmic web – I. Hierarchical topology in ΛCDM cosmologies](https://academic.oup.com/mnras/article/507/2/2968/6353532)
- [Revealing the neutrino mass through persistent homology of the cosmic web](https://arxiv.org/pdf/2604.02300)
- [Uncovering the Topology of Time-Varying fMRI Data using Cubical Persistence (NeurIPS 2020)](https://proceedings.neurips.cc/paper/2020/file/4d771504ddcd28037b4199740df767e6-Paper.pdf)
- [Inferring a Cell Structure on the Space of Cyclooctane Conformations (arXiv:2502.20149)](https://arxiv.org/abs/2502.20149)
- [Quotient Geometry and Persistence-Stable Metrics for Swarm Configurations (arXiv:2603.18041)](https://arxiv.org/html/2603.18041)
- [A primer on computational group homology and cohomology (HAP)](https://arxiv.org/pdf/0706.0549)
- [Constructive Algebraic Topology (Kenzo)](https://arxiv.org/html/math/0111243v1)
- [Coverage in sensor networks via persistent homology](https://projecteuclid.org/journals/algebraic-and-geometric-topology/volume-7/issue-1/Coverage-in-sensor-networks-via-persistent-homology/10.2140/agt.2007.7.339.full)
- [Topology of Robot Motion Planning (Farber survey)](https://math.uchicago.edu/~shmuel/AAT-readings/Robotics/Farber%20robotics%20survey.pdf)
- [Delta Complexes in Digital Images. Approximating Image Object Shapes (arXiv:1706.04549)](https://arxiv.org/abs/1706.04549)
