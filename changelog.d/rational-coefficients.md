### Added

- `Coefficients.Rationals` and `Coefficients.Rational`: exact rational coefficients (`BigInt` numerator and denominator,
  in lowest terms, a `Field`). `Persistence(..., coefficients = Coefficients.Rationals)` takes them in place of
  `characteristic`, for every engine and both kinds of representative; the facades take `field = Q` (`--field Q`).
  `PersistenceMap` refuses a map between a diagram over the rationals and one over the reals.
