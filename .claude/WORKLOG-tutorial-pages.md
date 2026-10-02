# WORKLOG: tutorial pages (2026-10-02)

Plan (advisor-reviewed): compute first, write second; shared seeded data in `_docs/tutorials/data/` (generator + provenance spec in
`tutorial/TutorialData*.scala`); each page has a spec that runs the same code and asserts every number the prose quotes; narrative
fences are `scala sc:nocompile` (continuations share values), each page ends with a "whole script" fence the doc build compiles;
public API only; TDAlab is the style (exports `barcode`, `cells`, `groups`, `alpha`, `LevelwiseSimplexStream` added for this).
- Tutorials use `diagramAt`/`diagramWithGeneratorsAt` (plain tuples), not `PersistenceBar` (ADT endpoints are clumsy), and avoid
  `barcodeAt(f)` at intermediate f.
- Page 1 find-a-loop, page 2 choosing-a-complex (alpha == Cech exactly: 325 vs 36,050 simplices), page 3 noise-and-outliers.
- **Dataset changed for page 3**: first dataset (25 clutter points INSIDE the ring) did not show DTM helping (margin 2x); inner
  clutter is dense enough that DTM does not see it as outliers. Sparse uniform background noise over a 4x4 square does (VR margin 7x,
  DTM(k=8,p=1) margin 170x). The claim "runner-up loop is made of outliers" was checked against its representative cycle (8 points,
  5 outliers; the real loop 66 points, 2 outliers) before being written.
