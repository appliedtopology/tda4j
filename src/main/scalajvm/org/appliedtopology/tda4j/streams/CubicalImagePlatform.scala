package org.appliedtopology.tda4j

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** What [[CubicalImage]] reads images with on the JVM: `javax.imageio`. The Scala.js build has a trait of the same name
  * (`src/main/scalajs`) with the browser's pixel data in its place.
  */
// Restricted: the platform half of `CubicalImage`; its members are public there.
private[tda4j] trait CubicalImagePlatform:

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
    val values: IndexedSeq[Int] => Double = idx => sign * CubicalImage.luma(img.getRGB(idx(0), idx(1)))
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
