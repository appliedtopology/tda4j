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
