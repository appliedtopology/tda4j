package org.appliedtopology.tda4j

import scala.scalajs.js.typedarray.Uint8ClampedArray

/** What [[CubicalImage]] reads images with on Scala.js: the pixel data a browser's canvas hands out. The JVM build has
  * a trait of the same name (`src/main/scalajvm`) that reads image files with `javax.imageio`.
  */
// Restricted: the platform half of `CubicalImage`; its members are public there.
private[tda4j] trait CubicalImagePlatform:

  /** Grayscale (luma) values of RGBA pixel data as a dense grid, shape `(width, height)` -- axis 0 is the image's own
    * x-axis, axis 1 is y. `rgba` is what `canvasContext.getImageData(0, 0, width, height).data` returns: four bytes per
    * pixel, row by row from the top.
    */
  def fromImageData(
    rgba: Uint8ClampedArray,
    width: Int,
    height: Int,
    sublevel: Boolean = true,
    parallelFiltrationValue: Boolean = false
  ): CubicalGridStream =
    require(
      rgba.length == 4 * width * height,
      s"expected ${4 * width * height} bytes of pixel data, got ${rgba.length}"
    )
    val sign = if sublevel then 1.0 else -1.0
    val values: IndexedSeq[Int] => Double = idx =>
      val at = 4 * (idx(1) * width + idx(0))
      sign * CubicalImage.luma((rgba(at).toInt << 16) | (rgba(at + 1).toInt << 8) | rgba(at + 2).toInt)
    CubicalGridStream(IndexedSeq(width, height), values, parallelFiltrationValue)

  /** Image files are not read on Scala.js: it throws. Decode the image where it is available (in a browser: draw it on
    * a canvas and pass `getImageData(...).data` to [[fromImageData]]) or pass the pixels to
    * `CubicalImage.fromGrayscale2D`.
    */
  def fromFile(
    path: String,
    sublevel: Boolean = true,
    parallelFiltrationValue: Boolean = false
  ): CubicalGridStream =
    throw new UnsupportedOperationException(
      s"cannot read the image file $path on Scala.js: decode the image yourself and pass its pixels to " +
        "CubicalImage.fromGrayscale2D, or in a browser pass canvas pixel data to CubicalImage.fromImageData"
    )
