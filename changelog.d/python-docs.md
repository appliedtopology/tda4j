### Fixed

- Python: a relative path means the directory Python is in. After `os.chdir` (a notebook does it) `result.save("r.json")` and
  `plot.save("p.svg")` wrote into the directory the JVM had started in, and `tda4j.load_result("r.json")` then did not find the
  file. The package now makes the path absolute before it reaches Java.

### Build, CI and Documentation

- The tutorials show their code in a Python tab beside the Scala and MATLAB ones (finding a loop, choosing a complex, noise and
  outliers, scaling up, networks and relations, comparing barcodes, images, circular and toroidal coordinates), and so do the
  vectorization and plotting pages. "All the ways to call TDA4j" has a Python section. The installation, Python and
  troubleshooting pages say how to install the wheel while the package is not on PyPI, and what the usual start-up errors mean.
  `python/tests/test_docs.py` runs every Python example in the docs and checks the numbers around it.
