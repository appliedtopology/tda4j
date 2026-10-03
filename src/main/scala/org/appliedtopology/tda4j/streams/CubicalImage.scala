package org.appliedtopology.tda4j

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Cubical complexes of images and voxel grids. Every constructor reduces to [[fromFlatArray]]: values in row-major
  * order (last axis fastest) and a `shape`.
  *
  * `sublevel = true` (the default, as in GUDHI, DIPHA and Perseus) filters by the values; `sublevel = false` by the
  * negated values, so bars of a superlevel filtration are reported in negated units. Voxel data of any file format is
  * loaded into an array first.
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
    sublevel: Boolean = true,
    // Threaded straight through to CubicalGridStream's own constructor parameter of the same name -- see
    // its doc there. Safe with every value closure built in this object (all read only immutable captured
    // data), so this is the one place a caller of these convenience constructors can actually reach it.
    parallelFiltrationValue: Boolean = false
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
    CubicalGridStream(shape, values, parallelFiltrationValue)

  /** `pixels(i)(j)` as a dense 2D grid, shape `(pixels.length, pixels(0).length)`. */
  def fromGrayscale2D(
    pixels: Array[Array[Double]],
    sublevel: Boolean = true,
    parallelFiltrationValue: Boolean = false
  ): CubicalGridStream =
    require(pixels.nonEmpty, "pixels must be non-empty")
    val cols = pixels(0).length
    require(pixels.forall(_.length == cols), "every row of pixels must have the same length")
    fromFlatArray(IndexedSeq(pixels.length, cols), pixels.flatten.toIndexedSeq, sublevel, parallelFiltrationValue)

  /** `voxels(i)(j)(k)` as a dense 3D grid, shape `(voxels.length, voxels(0).length, voxels(0)(0).length)`. */
  def fromVoxelGrid3D(
    voxels: Array[Array[Array[Double]]],
    sublevel: Boolean = true,
    parallelFiltrationValue: Boolean = false
  ): CubicalGridStream =
    require(voxels.nonEmpty, "voxels must be non-empty")
    require(voxels(0).nonEmpty, "voxels(0) must be non-empty")
    val d1 = voxels(0).length
    val d2 = voxels(0)(0).length
    require(
      voxels.forall(a => a.length == d1 && a.forall(_.length == d2)),
      "every sub-array of voxels must have consistent dimensions"
    )
    fromFlatArray(
      IndexedSeq(voxels.length, d1, d2),
      voxels.flatten.flatten.toIndexedSeq,
      sublevel,
      parallelFiltrationValue
    )

  /** `img.getRGB(x, y)` grayscale (luma) values as a dense grid, shape `(width, height)` -- axis 0 is the image's own
    * x-axis, axis 1 is y.
    */
  def fromBufferedImage(
    img: BufferedImage,
    sublevel: Boolean = true,
    parallelFiltrationValue: Boolean = false
  ): CubicalGridStream =
    val width = img.getWidth
    val height = img.getHeight
    val sign = if sublevel then 1.0 else -1.0
    // BufferedImage.getRGB is safe for concurrent reads as long as nothing mutates the image concurrently
    // (true here: img is only ever read, by this closure, for the stream's whole lifetime).
    val values: IndexedSeq[Int] => Double = idx => sign * luma(img.getRGB(idx(0), idx(1)))
    CubicalGridStream(IndexedSeq(width, height), values, parallelFiltrationValue)

  /** Reads an image file via `javax.imageio.ImageIO` (JDK-builtin, no new dependency) -- PNG/JPEG/BMP/GIF and whatever
    * other formats the running JVM's registered `ImageReader`s support.
    */
  def fromFile(
    path: String,
    sublevel: Boolean = true,
    parallelFiltrationValue: Boolean = false
  ): CubicalGridStream =
    val img = ImageIO.read(new File(path))
    require(img != null, s"could not read an image from $path (unrecognized format, or not an image file)")
    fromBufferedImage(img, sublevel, parallelFiltrationValue)
