package org.appliedtopology.tda4j
package streams

import org.appliedtopology.tda4j.algebra.{given, *}
import org.appliedtopology.tda4j.cells.{given, *}

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Converting greyscale images and dense voxel grids into `CubicalGridStream`s. Every constructor here reduces to
  * `fromFlatArray`: a dense n-dimensional grid from a flat, row-major array of values plus an explicit `shape`.
  *
  * `sublevel = true` (the default, matching GUDHI/DIPHA/Perseus's own convention) treats pixel intensity directly as
  * filtration value -- ascending intensity = later in the filtration. `sublevel = false` negates every value before
  * handing it to `CubicalGridStream` -- the standard "sublevel of `-f` is superlevel of `f`, reparametrized" trick (see
  * `CubicalStream.scala`'s own doc for why `CubicalGridStream` itself deliberately carries no direction flag). Reported
  * birth/death values under `sublevel = false` are then in NEGATED-intensity units, not raw `0..255` -- documented,
  * expected behavior of this trick, not a bug to "fix" by flipping signs back.
  *
  * No image-I/O dependency is added for voxel (3D+) data -- there is no single standard JDK-readable volumetric format,
  * so voxel constructors here take an already-in-memory array; callers with a specific file format (NRRD, NIfTI, a raw
  * slice stack, ...) are expected to load it into an array upstream, with whatever library that needs, and hand the
  * result to `fromFlatArray`/`fromVoxelGrid3D` directly.
  */
object CubicalImage:

  /** Grayscale value of one ARGB pixel via the standard ITU-R BT.601 luma formula, 0..255. Applied unconditionally (not
    * just to color images) -- for an already-grayscale image (R=G=B for every pixel), this is the identity, so there is
    * no need to special-case `BufferedImage`'s many possible underlying color types.
    */
  private def luma(rgb: Int): Double =
    val r = (rgb >> 16) & 0xff
    val g = (rgb >> 8) & 0xff
    val b = rgb & 0xff
    0.299 * r + 0.587 * g + 0.114 * b

  /** Dense n-dimensional grid from a flat, row-major array of values and an explicit `shape` -- e.g. for `shape =
    * IndexedSeq(n0, n1, n2)`, index `(i0, i1, i2)` reads `flatValues(i0*n1*n2 + i1*n2 + i2)`, the same convention
    * `Array[Array[...]].flatten` produces, so `fromGrayscale2D`/`fromVoxelGrid3D` below can just flatten and delegate
    * here.
    */
  def fromFlatArray(
    shape: IndexedSeq[Int],
    flatValues: IndexedSeq[Double],
    sublevel: Boolean = true
  ): CubicalGridStream =
    require(
      flatValues.length == shape.product,
      s"flatValues has ${flatValues.length} entries, expected ${shape.product} for shape $shape"
    )
    val sign = if sublevel then 1.0 else -1.0
    // Row-major strides: shape.scanRight(1)(_*_) = (n0*n1*...*n_{k-1}, n1*...*n_{k-1}, ..., n_{k-1}, 1); dropping
    // the first entry (the total size, not a per-axis stride) leaves exactly one stride per axis.
    val strides: IndexedSeq[Int] = shape.scanRight(1)(_ * _).tail
    val values: IndexedSeq[Int] => Double = idx =>
      val flat = idx.zip(strides).map { case (i, s) => i * s }.sum
      sign * flatValues(flat)
    CubicalGridStream(shape, values)

  /** `pixels(i)(j)` as a dense 2D grid, shape `(pixels.length, pixels(0).length)`. */
  def fromGrayscale2D(pixels: Array[Array[Double]], sublevel: Boolean = true): CubicalGridStream =
    require(pixels.nonEmpty, "pixels must be non-empty")
    val cols = pixels(0).length
    require(pixels.forall(_.length == cols), "every row of pixels must have the same length")
    fromFlatArray(IndexedSeq(pixels.length, cols), pixels.flatten.toIndexedSeq, sublevel)

  /** `voxels(i)(j)(k)` as a dense 3D grid, shape `(voxels.length, voxels(0).length, voxels(0)(0).length)`. */
  def fromVoxelGrid3D(voxels: Array[Array[Array[Double]]], sublevel: Boolean = true): CubicalGridStream =
    require(voxels.nonEmpty, "voxels must be non-empty")
    require(voxels(0).nonEmpty, "voxels(0) must be non-empty")
    val d1 = voxels(0).length
    val d2 = voxels(0)(0).length
    require(
      voxels.forall(a => a.length == d1 && a.forall(_.length == d2)),
      "every sub-array of voxels must have consistent dimensions"
    )
    fromFlatArray(IndexedSeq(voxels.length, d1, d2), voxels.flatten.flatten.toIndexedSeq, sublevel)

  /** `img.getRGB(x, y)` grayscale (luma) values as a dense grid, shape `(width, height)` -- axis 0 is the image's own
    * x-axis, axis 1 is y.
    */
  def fromBufferedImage(img: BufferedImage, sublevel: Boolean = true): CubicalGridStream =
    val width = img.getWidth
    val height = img.getHeight
    val sign = if sublevel then 1.0 else -1.0
    val values: IndexedSeq[Int] => Double = idx => sign * luma(img.getRGB(idx(0), idx(1)))
    CubicalGridStream(IndexedSeq(width, height), values)

  /** Reads an image file via `javax.imageio.ImageIO` (JDK-builtin, no new dependency) -- PNG/JPEG/BMP/GIF and whatever
    * other formats the running JVM's registered `ImageReader`s support.
    */
  def fromFile(path: String, sublevel: Boolean = true): CubicalGridStream =
    val img = ImageIO.read(new File(path))
    require(img != null, s"could not read an image from $path (unrecognized format, or not an image file)")
    fromBufferedImage(img, sublevel)
