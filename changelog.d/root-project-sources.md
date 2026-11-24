### Build, CI and Documentation

- `sbt test`, `sbt "clean ; test"` and `sbt release` at the root work again. The root project had compiled the library's
  sources a third time, without the libraries and compiler flags of the two platform builds, and failed with "Not found:
  cats" and "Illegal context bound" before it reached a spec; it now has no sources of its own, and `RELEASE.md`'s
  pre-flight and `release.sbt`'s test step run as written.
