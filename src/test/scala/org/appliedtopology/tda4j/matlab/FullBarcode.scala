package org.appliedtopology.tda4j.matlab

/** Test-only: `TDA4j`'s `computeFrom*` entry points with the default persistence threshold switched OFF
  * (`minPersistence=0`), so the pre-existing specs -- which assert on complete, hand-derived or cross-engine-compared
  * barcodes -- keep testing exactly what they always did. `TDA4j` itself now hides bars with persistence below 1% of
  * the connectivity scale by default; that behaviour has its own specs (`PersistenceThresholdSpec`), which call `TDA4j`
  * directly.
  */
private[tda4j] object FullBarcode:
  private val off = Array("minPersistence", "0")

  def computeFromPoints(points: Array[Array[Double]]): PersistenceResult = TDA4j.computeFromPoints(points, off)
  def computeFromPoints(points: Array[Array[Double]], options: Array[String]): PersistenceResult =
    TDA4j.computeFromPoints(points, options ++ off)

  def computeFromDistanceMatrix(distances: Array[Array[Double]]): PersistenceResult =
    TDA4j.computeFromDistanceMatrix(distances, off)
  def computeFromDistanceMatrix(distances: Array[Array[Double]], options: Array[String]): PersistenceResult =
    TDA4j.computeFromDistanceMatrix(distances, options ++ off)

  def computeFromRelation(relation: Array[Array[Double]]): PersistenceResult = TDA4j.computeFromRelation(relation, off)
  def computeFromRelation(relation: Array[Array[Double]], options: Array[String]): PersistenceResult =
    TDA4j.computeFromRelation(relation, options ++ off)

  def computeFromPointsAndLandmarks(points: Array[Array[Double]], landmarks: Array[Int]): PersistenceResult =
    TDA4j.computeFromPointsAndLandmarks(points, landmarks, off)
  def computeFromPointsAndLandmarks(
    points: Array[Array[Double]],
    landmarks: Array[Int],
    options: Array[String]
  ): PersistenceResult = TDA4j.computeFromPointsAndLandmarks(points, landmarks, options ++ off)

  def computeFromDistanceMatrixAndLandmarks(distances: Array[Array[Double]], landmarks: Array[Int]): PersistenceResult =
    TDA4j.computeFromDistanceMatrixAndLandmarks(distances, landmarks, off)
  def computeFromDistanceMatrixAndLandmarks(
    distances: Array[Array[Double]],
    landmarks: Array[Int],
    options: Array[String]
  ): PersistenceResult = TDA4j.computeFromDistanceMatrixAndLandmarks(distances, landmarks, options ++ off)

  def computeFromCubicalImage(shape: Array[Int], flatValues: Array[Double]): PersistenceResult =
    TDA4j.computeFromCubicalImage(shape, flatValues, off)
  def computeFromCubicalImage(
    shape: Array[Int],
    flatValues: Array[Double],
    options: Array[String]
  ): PersistenceResult = TDA4j.computeFromCubicalImage(shape, flatValues, options ++ off)

  def computeFromImage(pixels: Array[Array[Double]]): PersistenceResult = TDA4j.computeFromImage(pixels, off)
  def computeFromImage(pixels: Array[Array[Double]], options: Array[String]): PersistenceResult =
    TDA4j.computeFromImage(pixels, options ++ off)
