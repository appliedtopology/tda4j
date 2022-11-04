# TODO Items and Project Planning for TDA4j

## Project Startup

- [x] Set up basic project
- [X] Set up GitHub
- [X] Set up continuous integration testing
- [X] Set up documentation building framework
- [X] Example code `org.appliedtopology.tda4j.Simplex`
- [X] Example test code `org.appliedtopology.tda4j.SimplexSpec`

## JavaPlex in Scala

- [ ] Sketch out class hierarchy to duplicate JavaPlex with particular attention to simple calling interfaces
  - [ ] Create a `FilteredSimplex` class that implements an implicit ordering
      - [ ] Create an implicit `Ordering[FilteredSimplex]` that orders by filtration value first, by dimension second,
       and finally by lexicographic ordering
  - [ ] Explore how to create a `Stream[FilteredSimplex]` from a selection of "maximal" simplices, or an incomplete
  selection of simplices (this will involve figuring out which _additional_ simplices to add)
- [ ] Write relevant tests to specify required behaviour
- [ ] Write code to duplicate JavaPlex's computations and analyses of persistent homology

## Ripser on the JRE

- [ ] Sketch out class hierarchy to duplicate Ripser with ability to enlarge for duplicating JavaPlex
- [ ] Write relevant tests to specify required behaviour
- [ ] Write code to duplicate Ripser's computation of Persistent Homology of Vietoris-Rips complexes

## Document

- [ ] Port Henry Adams' tutorials to TDA4j.
