package org.appliedtopology.tda4j.matlab

import org.appliedtopology.tda4j.{PersistentLaplacian, Simplex}

/** The persistent Laplacian of the Vietoris-Rips complex of a point cloud or a distance matrix
  * (`TDA4j.persistentLaplacianFromPoints`, `persistentLaplacianFromDistanceMatrix`): a symmetric matrix on the
  * simplices of one dimension that have entered by `start()`, whose kernel has the dimension of the persistent Betti
  * number from `start()` to `stop()`. Or the persistent Laplacian of a simplicial map between two such complexes
  * (`persistentLaplacianOfMapFromPoints`, `...FromDistanceMatrices`): then the simplices are those of the target that
  * the map hits, `start()` is the scale of the source and `stop()` that of the target, and the kernel has the dimension
  * of the rank of the map on homology.
  */
final class PersistentLaplacianResult private[matlab] (
  private val laplacian: PersistentLaplacian.Spectrum[Simplex[Int]],
  private val startValue: Double,
  private val stopValue: Double
):
  /** The dimension of the simplices the matrix is on. */
  def dimension(): Int = laplacian.dimension

  /** The earlier filtration value: the simplices are those that have entered by it. */
  def start(): Double = startValue

  /** The later filtration value: the chains whose boundary is in the complex at `start()` are those of the complex
    * here.
    */
  def stop(): Double = stopValue

  /** The weight of each simplex of `cells()`: 1, but for a map that sends several simplices of the source onto one, the
    * number of them. The matrix is on the simplices scaled to length one (a chain of weight `w` has the length
    * `1 / sqrt(w)`); `kernel()` is in the coefficients of the chains.
    */
  def weights(): Array[Double] = laplacian.weights.toArray

  /** The number of simplices, the order of the matrix. */
  def size(): Int = laplacian.cells.size

  /** The simplices, one array of vertex numbers for each row and column of the matrix. */
  def cells(): Array[Array[Int]] = laplacian.cells.map(_.toList.toArray).toArray

  /** The matrix, `size()` by `size()`. */
  def matrix(): Array[Array[Double]] = laplacian.matrix.toArray

  /** The eigenvalues, smallest first. */
  def eigenvalues(): Array[Double] = laplacian.eigenvalues.toArray

  /** An orthonormal basis of the kernel, `bettiNumber()` rows of `size()` entries, one entry for each simplex of
    * `cells()` (the persistent harmonic chains; see `PersistentLaplacian.Result.kernel`).
    */
  def kernel(): Array[Array[Double]] =
    val position = laplacian.cells.zipWithIndex.toMap
    laplacian.kernel.map { chain =>
      val row = new Array[Double](laplacian.cells.size)
      for (cell, x) <- chain.terms do row(position(cell)) = x
      row
    }.toArray

  /** The dimension of the kernel: the number of classes of this dimension at `start()` that are still there at
    * `stop()`.
    */
  def bettiNumber(): Int = laplacian.bettiNumber

  /** Whether some eigenvalue is not zero. */
  def hasSpectralGap(): Boolean = laplacian.spectralGap.isDefined

  /** The smallest eigenvalue that is not zero, `NaN` if there is none. */
  def spectralGap(): Double = laplacian.spectralGap.getOrElse(Double.NaN)
