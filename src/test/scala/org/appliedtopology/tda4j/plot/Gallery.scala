package org.appliedtopology.tda4j.plot

import org.appliedtopology.tda4j.*

import scala.util.Random

/** Writes one file per kind of plot into a directory, for looking at them (`sbt "plot/Test/runMain
  * org.appliedtopology.tda4j.plot.Gallery out/"`): `.svg` files in the light and dark themes, `.html` for 3-D.
  */
object Gallery:
  def noisyCircle(
    n: Int,
    noise: Double,
    seed: Long,
    cx: Double = 0,
    cy: Double = 0,
    r: Double = 1
  ): Array[Array[Double]] =
    val rng = Random(seed)
    Array.tabulate(n) { i =>
      val t = 2 * math.Pi * i / n
      Array(cx + r * math.cos(t) + noise * rng.nextGaussian(), cy + r * math.sin(t) + noise * rng.nextGaussian())
    }

  def main(args: Array[String]): Unit =
    val dir = args.headOption.getOrElse("target/plot-gallery")
    def out(name: String) = s"$dir/$name"

    val circle = noisyCircle(40, 0.08, 1)
    val d = Persistence(circle, maxDimension = 1)
    import d.given
    Plot.barcode(d).save(out("barcode.svg"))
    Plot.barcode(d).save(out("barcode-dark.svg"), Some(Theme.Dark))
    Plot.diagram(d).save(out("diagram.svg"))
    Plot.diagram(d).save(out("diagram-dark.svg"), Some(Theme.Dark))
    Plot.side(Plot.barcode(d, longest = Some(8)), Plot.diagram(d)).save(out("side.svg"))

    val two = noisyCircle(30, 0.06, 2) ++ noisyCircle(24, 0.05, 3, cx = 2.6, r = 0.7)
    val d2 = Persistence(two, maxDimension = 1)
    Plot.persistenceImage(d2, 1, resolution = 24, sigma = 0.08).save(out("persistence-image.svg"))
    Plot.landscape(d2, 0, levels = 3).save(out("landscape.svg"))
    Plot.barcode(d2).save(out("barcode-two-circles.svg"))

    Plot.complex(circle, 0.45).save(out("complex-vr.svg"))
    Plot.complex(circle, 0.25, Cech).save(out("complex-cech.svg"))
    Plot.complex(circle, 0.25, AlphaShapes).save(out("complex-alpha.svg"))

    val loop = Persistence(circle, maxDimension = 1, engine = Persistence.Engine.Chunks)
    import loop.given
    val cycle = loop.dim(1).longest.get.representative
    Plot.cycle(circle, cycle).over(Plot.complex(circle, loop.dim(1).longest.get.birth)).save(out("cycle.svg"))
    val cocycles = Persistence(circle, maxDimension = 1, representatives = Representatives.Cocycles)
    import cocycles.given
    val h1 = cocycles.dim(1).longest.get
    Plot.cocycle(circle, h1.representative).save(out("cocycle-at-death.svg"))
    Plot.cocycle(circle, h1.representative, at = h1.birth).over(Plot.complex(circle, h1.birth)).save(out("cocycle.svg"))

    val ms = EuclideanMetricSpace(circle)
    val cc = CircularCoordinates.compute(ms, 0.6, 0)
    Plot.circularCoordinates(circle, cc.theta).save(out("circular.svg"))

    val (h, w) = (24, 32)
    val img = Image(
      for i <- 0 until h; j <- 0 until w
      yield
        val (y, x) = (i - h / 2.0, j - w / 2.0)
        val ring = math.abs(math.sqrt(x * x / 1.6 + y * y) - 7)
        math.min(ring, 4.0) + 0.3 * math.sin(i * 0.9 + j * 0.7)
      ,
      IndexedSeq(h, w)
    )
    Plot.image(img).save(out("image.svg"))
    val di = Persistence(img)
    import di.given
    Plot.cubicalChain(img, di.dim(1).longest.get.representative, "Image loop").save(out("image-cycle.svg"))

    val sphere = Array.tabulate(60) { i =>
      val z = -1 + 2.0 * (i + 0.5) / 60
      val r = math.sqrt(1 - z * z)
      Array(r * math.cos(2.4 * i), r * math.sin(2.4 * i), z)
    }
    val scene = Plot.complex3D(sphere, 0.55, VietorisRips).copy(title = "Vietoris-Rips complex of a sphere at 0.55")
    scene.save(out("sphere.html"))
    scene.save(out("sphere.svg"))
    val ds = Persistence(sphere, maxDimension = 2)
    import ds.given
    ds.dim(2)
      .longest
      .foreach(b => Plot.cycle3D(sphere, b.representative, "H2 representative").save(out("sphere-cycle.svg")))
    // ----- customized: the tweaks of the grammar, one file each
    val cloud = Random(7)
    val scattered = Array.fill(70)(Array(cloud.nextDouble() * 4, cloud.nextDouble() * 4))
    val rough = Persistence(scattered, maxDimension = 1)
    val loops = rough.dim(1).bars
    Plot.diagram(loops).save(out("custom-diagram-linear.svg"))
    (Plot.diagram(loops) + Scale.xy(Transform.Log10) + Labels(title = "Loops, log-log", x = "birth", y = "death") +
      Mark.Diagonal(offset = 0.0, paint = Paint.Muted, dashed = true)).save(out("custom-diagram-loglog.svg"))
    (Plot.barcode(d) + Style.slides + Size(960, 540) + Labels(
      title = "For a slide",
      subtitle = "bigger text and marks"
    ))
      .save(out("custom-barcode-slides.svg"))
    (Plot.diagram(d) + Style(legend = LegendPosition.Bottom) + Labels(legend = "Homology") +
      Scale
        .degree(0, label = "components") + Scale.degree(1, color = "#c2185b", label = "loops", shape = Shape.Diamond))
      .save(out("custom-diagram-degrees.svg"))
    val image1 = Plot.persistenceImage(d2, 1, resolution = 24, sigma = 0.08)
    (image1 + Scale.color(stops = ColorScale.viridis) + Labels(color = "density")).save(out("custom-image-viridis.svg"))
    (image1 + Palette.reference + Style(transparent = true)).save(out("custom-image-reference.svg"))
    val before = Plot.barcode(Persistence(noisyCircle(30, 0.03, 4), maxDimension = 1)).copy(title = "tighter")
    val after = Plot.barcode(Persistence(noisyCircle(30, 0.2, 5), maxDimension = 1)).copy(title = "noisier")
    (Plot.side(before, after).shared(y = false) + Style(grid = false)).save(out("custom-board-shared.svg"))
    (Plot.diagram(loops) + Mark.Diagonal(offset = 0.4, paint = Paint.Muted, dashed = true) +
      Mark.Rule.vertical(0.6)).save(out("custom-diagram-threshold.svg"))
    (scene + Style.slides + Palette.reference + Labels(subtitle = "slides style, reference palette"))
      .save(out("custom-sphere.svg"))
    println(s"wrote the gallery to $dir")
