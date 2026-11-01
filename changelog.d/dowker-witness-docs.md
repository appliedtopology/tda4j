### Build, CI and Documentation

- The Dowker pages no longer say that the Dowker complex of landmark-to-witness distances is the witness complex with
  `nu = 0`. It is the Čech complex of the landmarks with the balls' common point required to be a data point: inside
  every witness complex at every scale, but none of them (the lazy `Witness(nu = 0)` is its flag complex, with the same
  edges). Build it with `Dowker(WitnessGeometry(metricSpace, landmarks).D)`.
