# WORKLOG: language tabsets in tutorial pages (2026-10-02)

Request: show MATLAB code next to the Scala wherever `matlab.TDA4j` supports the task, as switchable tabs.
- scaladoc bundles no Bootstrap (checked the built site), so the tabs are ~25 lines of CSS + JS in `_layouts/main.html`.
- Markup (the blank lines matter -- they stop the strict markdown parser swallowing the fence as HTML text):
  ```
  <div class="tabset">
  <div class="tab" data-lang="Scala">

  ```scala sc:nocompile
  ...
  ```

  </div>
  <div class="tab" data-lang="MATLAB">

  ```matlab
  ...
  ```

  </div>
  </div>
  ```
  The script builds the button bar from `data-lang`, remembers the choice in `localStorage` (shared by every tabset and page), and only
  hides inactive tabs once it has run (`.tabset.js-ready`), so with JS off every language is shown.
- Verified in headless Chromium (bar built, switching hides/shows). Verified that a deliberate type error in a compiled `scala` fence
  INSIDE a tab div still fails `sbt doc` at page:line -- the snippet compiler does see fences inside HTML blocks.
- MATLAB has no highlighter in scaladoc's bundled highlight.js; the fence renders plain. Not worth adding one.
- No MATLAB/Octave here: every MATLAB tab is mirrored by a spec calling `matlab.TDA4j` directly (not the FullBarcode shim) with the same
  arguments and option strings, asserting the numbers the tab quotes. MATLAB syntax and Java marshalling remain unverified.

## MATLAB tabs added (find-a-loop, choosing-a-complex, noise-and-outliers, images, circular-and-toroidal, comparing-barcodes, networks, scaling-up)
Each page's "whole script" section is now a Scala | MATLAB tabset; `tutorial/MatlabTabsSpec` makes the same facade calls and asserts
the quoted numbers (16 examples; the toroidal one costs ~30 s because the facade cannot cap the complex).
Where the facade differs from the Scala tab (each stated on its page, none silent):
- `compute*` hides bars <= 1% of the enclosing radius; `toArrayUnfiltered()` is the full barcode (1544), `minPersistence` sets a cut.
- Java indices count from 0: `cycleVertices(k)` takes (row of the bar in `toArray()`) - 1; `toroidalCoordinates` classes likewise (`int32([0 1])`).
- `engine=naive` is passed explicitly where the page quotes a representative: the default engine returns a DIFFERENT (longer, 121-edge)
  representative of the same loop, and different cycle compositions on the outlier page.
- Distances/landscapes/images use the COMPLETE barcode: bottleneck agrees with the Scala tab, Wasserstein does not (0.045 vs 0.040,
  0.830 vs 0.826) because the many tiny bars add up in a sum.
- `computeFromImage(..., 'sublevel','false')` reports NEGATED values (documented facade behaviour), so bright features have negative births.
- `h1Bars`/`toroidalCoordinates` have no `maxFiltrationValue`; first four torus bars are unchanged by the cap, so the page numbers hold.
- DTM weights themselves are not exposed (only `dtmK`/`dtmP`), so the weights comparison on the outlier page has no MATLAB counterpart.
- The image page regenerates the Scala image in MATLAB from `java.util.Random(int64(5))` in the same row-major order.
No tabs: telling-spaces-apart and persistent-group-cohomology (no MATLAB entry point for simplicial sets or groups);
all-ways-to-call (already organised by language).
Unverified: MATLAB syntax and Java<->MATLAB marshalling (e.g. whether `int[][]` from `cycleVertices` arrives as a matrix; the tab wraps it in `double(...)`).
