### Added

- `Relative(stream, inSubcomplex)` and `Relative.ofVertices(stream, vertices)`: the pair `(X, A)` as a stream, so that
  `Persistence` and every engine compute relative persistent homology `H(X_t, A_t)`. Its cells are `RelativeCell`s, the
  cells of `X` outside `A` with a boundary that drops the faces in `A`. `A` must be closed under faces (not checked).
