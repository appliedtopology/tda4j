---
paths:
  - "python/**"
---

# The Python package

`python/` is the `tda4j` package on PyPI: a thin JPype layer over `org.appliedtopology.tda4j.matlab.TDA4j` (the MATLAB/Java
facade), NumPy in and out. Derivation and what was tried: `WORKLOG-more-inputs.md` (section "Python package"). User page:
`_docs/user-guide/python.md`; release: `RELEASE.md` ("PyPI").

- **It is the facade, not the Scala API.** Every function is one facade call; no logic of its own beyond converting
  arrays and options. A new capability goes into `matlab.TDA4j` first (the 1:1 mirror rule in CLAUDE.md), then gets its
  Python function. `tests/test_facade_coverage.py` fails, naming the method, when a public facade method (or result-class
  method) is neither called by `python/src/tda4j` nor listed in its `NOT_WRAPPED` with a reason; the bytecode-public
  internals of Scala (`private[matlab]`) are filtered by `INTERNAL` there.
- **Options are `**options`**, snake_case, turned into the facade's flat camelCase `String[]` by `_jvm.options_in`
  (bool `true`/`false`, numpy numbers through `int`/`float`, `inf` as `Infinity`, `None` skipped). Do not copy the option
  list into Python signatures: a new facade option then works with no Python change, and the docstrings name only the
  common ones. A mistyped option is the facade's own `ValueError` (it lists the accepted names, lowercased).
- **Java exceptions become Python ones** in `_jvm._translate` (`IllegalArgumentException` is `ValueError`, index errors
  `IndexError`, `UnsupportedOperationException` `NotImplementedError`, the rest `Tda4jError`). Public functions and result
  methods carry `@translating`; a new result class is wrapped with `translating(Class)`.
- **Result dataclasses holding arrays use `eq=False`**: `==` on NumPy fields raises. Compare fields.
- **The jar**: `$TDA4J_JAR`, else `python/src/tda4j/jars/*-assembly.jar` (git-ignored, copied by
  `scripts/bundle-jar.sh`). The wheel is pure Python (`py3-none-any`, about 30 MB with the jar). `start_jvm(args)` must run before
  the first call to choose the heap; JPype cannot restart a JVM in one process.
- **Test the wheel, not the checkout**: `test.yml`'s `python-package` job installs the wheel into a clean venv (3.13 and 3.10)
  and runs `python/tests` from a copy outside the repository. Pure-source tests (`test_release_scripts.py`) skip themselves
  there. Locally: `TDA4J_JAR=... PYTHONPATH=python/src pytest python/tests`.
- **Tests state the oracle by hand**: a unit square's bars, `d∘d = 0` and "a face precedes its coface" for the boundary
  matrix, a cycle's boundary summing to zero, Takens windows by index arithmetic, an Euler curve counted by hand. They were
  mutation-checked (boundary rows/cols swapped, Takens delay/stride swapped, column-major points, `True` written for a
  boolean, an empty death cell): keep a test only if its plausible wrong answer fails it.
- **Known traps**: row order of `bars` is not part of the answer (compare after sorting); `cells()` order is the engine's
  (grouped by dimension), not globally sorted by filtration value; an image's birth cell in degree 0 is a corner vertex of
  the lowest pixel; a symmetric grid (a torus on a lattice) has ties that circular coordinates refuse (`ValueError`), so
  perturb the test data.
- **Python 3.10 floor** (tested 3.10, 3.11, 3.13). The version in `pyproject.toml` is a placeholder; `release.yml` writes the
  release's PEP 440 version (`scripts/set_version.py`). PyPI publishing needs the one-time setup in `RELEASE.md` and the
  repository variable `PUBLISH_TO_PYPI`.
