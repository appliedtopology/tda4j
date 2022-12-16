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
  - [x] Create a `FilteredSimplex` class that implements an implicit ordering
  - [ ] Rename type variables in `Simplex.scala` for better understanding
      - [ ] Create an implicit `Ordering[FilteredSimplex]` that orders by filtration value first, by dimension second,
       and finally by lexicographic ordering
      - [x] Explore how to create a `Stream[FilteredSimplex]` from a selection of "maximal" simplices, or an incomplete
      selection of simplices (this will involve figuring out which _additional_ simplices to add)
      - [x] Add Chain (needs to access `Numeric` to get `+ , - , *`). `SortedMap` could work for this, but looking for alternatives.  Want `Chain[C <: Cell,F]` to extend (ie inherit from) `C => F`(so that it contains an apply`(queryCell : C) : F` that returns the coefficient at `queryCell`

- [ ] Write Perisitent Homology Algos
- [ ] Write relevant tests to specify required behaviour
- [ ] Write code to duplicate JavaPlex's computations and analyses of persistent homology

- [ ] Write up Test Suite for Chain class
- [ ] Write up ScalaDocs for Chain
- [ ] implicit conversion from Cell to Chain
- [ ] work on `extends Equals` to implement `canEquals` and `equals`




- [ ] FUTURE NOTE - run Library to check for bottlenecks when done (use of profiler needed)





- [ ] END OF LIBRARY IMPLEMENTATION TASK: Look to do a pull-request for RedBlackTree.scala for merging 2 red-black trees (which are the under the hood algorithms for TreeMap, which SortedMap (which is used by chainMap))

## Ripser on the JRE

- [ ] Sketch out class hierarchy to duplicate Ripser with ability to enlarge for duplicating JavaPlex
- [ ] Write relevant tests to specify required behaviour
- [ ] Write code to duplicate Ripser's computation of Persistent Homology of Vietoris-Rips complexes

## Document

- [ ] Port Henry Adams' tutorials to TDA4j.
