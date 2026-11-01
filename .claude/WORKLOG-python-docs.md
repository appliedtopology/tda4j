# Python in the tutorials and the docs (2026-11-01)

Point-in-time snapshot. Rules that came out of it: `rules/python.md` (docs are tests, absolute paths, plot tweaks) and
`rules/docs-and-tutorials.md` (language tabs).

**Ask** (Mikael): the Python package (PR #270) was hardly in the tutorials or docs; "PyPI publishing is still pending".

**Done**
- Python tab after the MATLAB tab on find-a-loop, choosing-a-complex, noise-and-outliers, scaling-up, networks-and-relations,
  comparing-barcodes, images, circular-and-toroidal-coordinates, `vectorization.md` and both tabsets of `plotting.md`; a
  Python section in all-ways-to-call. Each tab is its MATLAB twin with `tda4j.*` calls; every number it quotes equals the
  MATLAB tab's (which `MatlabTabsSpec` asserts), checked by running it. The images tab draws its noise from `java.util.Random(5)`
  through JPype so that the numbers are the Scala script's.
- Not given a Python tab: Truchet tiles, cup products, group cohomology (typed API only).
- Install text: PyPI has no project yet, so `pip install tda4j` would fail. Pages say "not on PyPI yet" and install the release
  wheel; `RELEASE.md` ("PyPI") lists the four files to change when the pending publisher exists (#272 item 5).
- `python/tests/test_docs.py`: runs every ```` ```python ```` fence of `_docs/` and both READMEs, one `ORACLES` entry each. Mutations
  that failed it: cut-off 1.0 to 2.0 (find-a-loop), edge collapse removed (scaling-up), `sublevel` flipped (images), 15 to 16
  landmarks (choosing-a-complex). CI: last step of `python-package` (needs the checkout; skips itself in the wheel job).

**Found on the way**
- After `os.chdir` the JVM keeps its start directory: `Result.save("r.json")` wrote there and `load_result("r.json")` read
  relative to the process directory, so the plotting page's save-and-reload tab failed. Python now makes paths absolute
  (`_jvm.path_in`); `test_results.py` has the regression test (fails without the fix).
- Plot tweaks were not reachable from Python, so the second plotting tabset had no Python tab. `Plot.__getattr__` forwards
  `with_*` to the Java figure's plain-argument overloads. A tweak taking `Optional`/`Palette` values is not reachable.

**Verification**: `python/tests` 110 passed on the sources; the built wheel installed in a clean venv and tested from outside the
checkout 86 passed, 2 skipped (the two that need the checkout); `TDA4J_SCALA_VERSION=3.8.4 sbt tda4j/doc` succeeded (the second
run was served from sbt's disk cache). Not run: MATLAB, Python 3.10, the CI job itself.
