### Added

- The demo REPL runs in a browser tab: a page that needs nothing installed, with the same imports and session as
  `tda4j-demo-<version>.jar` and each plot under the line that drew it. It is published on the docs site under
  `/playground/`; `sbt webSite` in `demo/` builds it for another host. It starts in 20 to 30 seconds and computes
  roughly ten times slower than a Java runtime (`demo/README.md`, "In a web page").
- The demo REPL as a container image, `ghcr.io/appliedtopology/tda4j-demo`, for a machine with Docker and no Java:
  `docker run --rm -p 127.0.0.1:7681:7681 -p 127.0.0.1:8337:8337 ghcr.io/appliedtopology/tda4j-demo` serves the REPL as
  a terminal in a web page on port 7681 and the plots on port 8337 (`demo/README.md`, "In a container").
- `Plot.viewWith(target) { ... }` hands every plot that the block `view()`s to `target` instead of the live viewer,
  for a program that hosts a session and has its own place for plots.
- The live viewer listens on another address than `127.0.0.1` when the system property `tda4j.plot.host` or the
  environment variable `TDA4J_PLOT_HOST` names one (`0.0.0.0` for every address), which a container that publishes the
  viewer's port needs. The default is unchanged: only the machine itself can reach the page.

### Build, CI and Documentation

- A workflow, `demo.yml`, builds the demo's web page and container image, uses both in a headless browser, and
  publishes them; a release runs it, and it can be run by hand for a branch.
