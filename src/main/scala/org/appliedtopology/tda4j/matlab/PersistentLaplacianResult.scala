package org.appliedtopology.tda4j.matlab

import org.appliedtopology.tda4j.{PersistentLaplacian, Simplex}

/** The persistent Laplacian of the Vietoris-Rips complex of a point cloud or a distance matrix
  * (`TDA4j.persistentLaplacianFromPoints`, `persistentLaplacianFromDistanceMatrix`): a symmetric matrix on the
  * simplices of one dimension that have entered by `start()`, whose kernel has the dimension of the persistent Betti
  * number from `start()` to `stop()`.
  */
final class PersistentLaplacianResult private[matlab] (private val laplacian: PersistentLaplacian.Result[Simplex[Int]]):
  /** The dimension of the simplices the matrix is on. */
  def dimension(): Int = laplacian.dimension

  /** The earlier filtration value: the simplices are those that have entered by it. */
  def start(): Double = laplacian.from

  /** The later filtration value: the chains whose boundary is in the complex at `start()` are those of the complex
    * here.
    */
  def stop(): Double = laplacian.to

  /** The number of simplices, the order of the matrix. */
  def size(): Int = laplacian.cells.size

  /** The simplices, one array of vertex numbers for each row and column of the matrix. */
  def cells(): Array[Array[Int]] = laplacian.cells.map(_.toList.toArray).toArray

  /** The matrix, `size()` by `size()`. */
  def matrix(): Array[Array[Double]] = laplacian.matrix.toArray

  /** The eigenvalues, smallest first. */
  def eigenvalues(): Array[Double] = laplacian.eigenvalues.toArray

  /** The dimension of the kernel: the number of classes of this dimension at `start()` that are still there at
    * `stop()`.
    */
  def bettiNumber(): Int = laplacian.bettiNumber

  /** Whether some eigenvalue is not zero. */
  def hasSpectralGap(): Boolean = laplacian.spectralGap.isDefined

  /** The smallest eigenvalue that is not zero, `NaN` if there is none. */
  def spectralGap(): Double = laplacian.spectralGap.getOrElse(Double.NaN)
