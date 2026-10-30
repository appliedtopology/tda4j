### Added

- A Python package, `tda4j` (`pip install tda4j`; Python 3.10 or newer and Java 21 or newer): the MATLAB and Java interface
  with NumPy in and out, so `tda4j.persistence(points, max_dimension=1)` gives the bars, a representative and the
  creating and destroying cell of each, distances and vectorizations, plots, relative persistence, maps between barcodes,
  landmarks, Takens embeddings, ToMATo and circular coordinates. The wheel carries the fat jar; options are keyword
  arguments in snake_case, and Java exceptions are Python ones. It lives in `python/`; CI installs the wheel on Python 3.10
  and 3.13 and tests it, and the release attaches the wheel and publishes it to PyPI once the project is set up there
  (`RELEASE.md`).
