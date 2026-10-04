package org.appliedtopology.tda4j

import org.scalacheck.Gen

import scala.reflect.ClassTag

// Shared ScalaCheck generators. Deliberately in a file of their own, not inside any one spec: specs across several
// packages import `streams.matrixGen`, and it once vanished when a spec file was overwritten.

/** A point cloud as an `Array` of `size` points, each of `dimension` coordinates drawn from `g`. */
def matrixGen[T: ClassTag](g: Gen[T], dimension: Gen[Int], size: Gen[Int]): Gen[Array[Array[T]]] =
  for
    dim <- dimension
    sz <- size
    values <- Gen.listOfN(dim * sz, g)
  yield values.toArray.grouped(dim).toArray
