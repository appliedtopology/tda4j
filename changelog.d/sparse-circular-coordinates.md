### Added

- Perea's sparse circular coordinates: the loop is found on the nerve of the balls around a few landmarks, and every
  point gets an angle by a partition of unity over those balls, so circular and toroidal coordinates scale to many
  points (20,000 points from 50 landmarks in a few seconds). `numLandmarks` on `CircularCoordinates.h1Bars`, `compute`
  and `computeToroidal` chooses maxmin landmarks; `CircularCoordinates.sparse(diagram, relation)` and `sparseToroidal`
  take the Dowker diagram of your own landmarks or cover. MATLAB `h1Bars`, `circularCoordinates` and
  `toroidalCoordinates` take `numLandmarks` as one more argument, Python `num_landmarks`, JavaScript the option
  `numLandmarks`.
