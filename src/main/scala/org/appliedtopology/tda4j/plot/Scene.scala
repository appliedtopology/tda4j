package org.appliedtopology.tda4j.plot

import org.appliedtopology.tda4j.*

/** A point in 3-D data coordinates. */
type XYZ = (Double, Double, Double)

/** What a 3-D scene is made of: points, segments and triangles, each with one paint or one per item; balls around
  * points (translucent discs, as a sphere looks in an orthographic view) and halos (discs of a size in pixels, to ring
  * a point).
  */
sealed trait Layer:
  def paints: IndexedSeq[Paint]
  final def paintAt(i: Int): Paint = if paints.size == 1 then paints(0) else paints(i)

object Layer:
  final case class Points(at: IndexedSeq[XYZ], paints: IndexedSeq[Paint], radius: Double = 3.5) extends Layer
  final case class Segments(
    ends: IndexedSeq[(XYZ, XYZ)],
    paints: IndexedSeq[Paint],
    width: Double = 1.2,
    opacity: Double = 1.0
  ) extends Layer
  final case class Triangles(corners: IndexedSeq[(XYZ, XYZ, XYZ)], paints: IndexedSeq[Paint], opacity: Double = 0.25)
      extends Layer

  /** Balls of `radius` (in data units) around `centers`: what the overlaps of a Vietoris-Rips or Cech complex record.
    * An orthographic view of a ball is a disc of the same radius, so a ball is drawn as a translucent disc; the scene's
    * frame comes from the centres, so the picture stays put as the balls grow (an item of an interactive page can
    * follow a control with [[Behavior.Radius]]).
    */
  final case class Balls(centers: IndexedSeq[XYZ], radius: Double, paints: IndexedSeq[Paint], opacity: Double = 0.09)
      extends Layer

  /** Translucent discs of `radius` pixels at `at`: a ring around a point (the halo of a chosen item, see
    * [[Panel.Spatial.selecting]]).
    */
  final case class Halos(at: IndexedSeq[XYZ], paints: IndexedSeq[Paint], radius: Double = 8.5, opacity: Double = 0.3)
      extends Layer

/** A 3-D plot. In a page (`html`, the live viewer) it is drawn on a canvas you can turn (drag) and zoom (scroll), with
  * no library to load; as an `.svg` file it is one view, from `azimuth`/`elevation` (degrees), drawn back to front.
  *
  * With `turns = false` the view stays as `azimuth` and `elevation` give it and a drag pans instead; the scroll wheel
  * zooms about the pointer. A flat picture (points in the plane, seen from above: `azimuth = 0`, `elevation = 90`) is
  * drawn this way when it has too many items for an SVG, which a canvas draws in a fraction of the time and bytes.
  */
final case class Scene(
  layers: Vector[Layer] = Vector.empty,
  title: String = "",
  subtitle: String = "",
  width: Int = 640,
  height: Int = 520,
  azimuth: Double = -35,
  elevation: Double = 25,
  palette: Palette = Palette.default,
  style: Style = Style.default,
  degrees: Map[Int, DegreeStyle] = Map.empty,
  elements: Elements = Elements(),
  zoom: Double = 1.0,
  turns: Boolean = true
) extends Plot:
  def +(layer: Layer): Scene = copy(layers = layers :+ layer)
  def ++(more: IterableOnce[Layer]): Scene = copy(layers = layers ++ more)

  /** This scene with the change `tweak` makes. Only the tweaks that need no axes apply: a palette, a [[Style]], a
    * [[Size]], the [[Camera]], the title and subtitle of [[Labels]] and the colors of `Scale.degree`.
    */
  def +(tweak: Tweak): Scene = tweak.onScene(this)

  // The tweaks that apply to a scene, as methods to chain (as on [[Figure]]); the plain-argument ones are for MATLAB.

  /** The title and subtitle: only what is given changes. A 3-D scene has no axes, legend or color bar to label. */
  def withLabels(title: Optional[String] = Optional.empty, subtitle: Optional[String] = Optional.empty): Scene =
    this + Labels(title = title, subtitle = subtitle)

  /** The size in pixels; give one to keep the other. */
  def withSize(width: Optional[Int] = Optional.empty, height: Optional[Int] = Optional.empty): Scene =
    this + Size(width, height)

  /** The color of homological degree `k` (and its dark-mode color). See [[Scale.degree]]. */
  def withDegree(
    k: Int,
    color: Optional[String] = Optional.empty,
    darkColor: Optional[String] = Optional.empty
  ): Scene =
    this + Scale.degree(k, color = color, darkColor = darkColor)

  /** Where the camera looks from (`azimuth` and `elevation`, in degrees) and how close (`zoom`). See [[Camera]]. */
  def withCamera(
    azimuth: Optional[Double] = Optional.empty,
    elevation: Optional[Double] = Optional.empty,
    zoom: Optional[Double] = Optional.empty
  ): Scene = this + Camera(azimuth, elevation, zoom)

  /** The camera by plain numbers, for MATLAB and Java: `withCamera(-35, 25, 1)` is the default view. */
  def withCamera(azimuth: Double, elevation: Double, zoom: Double): Scene = this + Camera(azimuth, elevation, zoom)

  /** This palette. */
  def withPalette(palette: Palette): Scene = this + palette

  /** This style, in place of the scene's. */
  def withStyle(style: Style): Scene = this + style

  /** The heading. */
  def withTitle(text: String): Scene = withLabels(title = text)

  /** The line under the heading. */
  def withSubtitle(text: String): Scene = withLabels(subtitle = text)

  /** The size in pixels. */
  def withSize(width: Int, height: Int): Scene = this + Size(width, height)

  /** The look of the title, the subtitle or the hint (`TextRole.Note`): the text a scene has. See [[Text]]. */
  def withText(
    role: TextRole,
    size: Optional[Double] = Optional.empty,
    weight: Optional[Weight] = Optional.empty,
    italic: Optional[Boolean] = Optional.empty,
    align: Optional[Anchor] = Optional.empty,
    font: Optional[String] = Optional.empty,
    color: Optional[String] = Optional.empty,
    paint: Optional[Paint] = Optional.empty
  ): Scene = this + Text(role, size, weight, italic, align, Optional.empty, font, color, paint)

  /** The markers of the points of a scene: `size`, `opacity` and the edge (`edgeWidth`, `edgeColor`, `edgeVisible`); a
    * scene's points are round and filled. See [[Geom.points]].
    */
  def withPoints(
    size: Optional[Double] = Optional.empty,
    opacity: Optional[Double] = Optional.empty,
    edgeWidth: Optional[Double] = Optional.empty,
    edgeColor: Optional[String] = Optional.empty,
    edgeVisible: Optional[Boolean] = Optional.empty
  ): Scene = this + Geom.points(
    size = size,
    opacity = opacity,
    edgeWidth = edgeWidth,
    edgeColor = edgeColor,
    edgeVisible = edgeVisible
  )

  /** One change written as text, `element.property=value`: `withSetting("title.size=18")`. See [[Settings]]; a scene
    * has only the title, subtitle and note.
    */
  def withSetting(spec: String): Scene = this + Settings.parse(spec)

  /** The palette called `name`: `brand`, `reference`, `print`, `grayscale`, `okabe-ito` or `clear`. */
  def withPalette(name: String): Scene = withPalette(Palette.named(name))

  /** The style called `name`: `default`, `slides`, `print` or `clear`. */
  def withStyle(name: String): Scene = withStyle(Style.named(name))

  /** The color of homological degree `degree`, as a CSS color. */
  def withDegreeColor(degree: Int, color: String): Scene = withDegree(degree, color = color)

  /** A factor on every text size. */
  def withTextScale(factor: Double): Scene = withStyle(style.copy(textScale = factor))

  /** A factor on the size of markers and the width of lines. */
  def withMarkScale(factor: Double): Scene = withStyle(style.copy(markScale = factor))

  /** Without a background. */
  def withTransparentBackground(): Scene = withStyle(style.copy(transparent = true))

  /** With its palette's surface as the background in a saved `.svg` too, where it has none unless asked. */
  def withOpaqueBackground(): Scene = withStyle(style.copy(transparent = false))

  // One line, so that a REPL echoing the value of `scene.view()` does not print every point.
  override def toString: String = Plot.describe("Scene", title, s"${layers.size} layers")

  /** This scene's layers drawn over `under`'s. */
  def over(under: Scene): Scene = copy(layers = under.layers ++ layers)

  // the centres of balls and halos count, their extent does not: a ball that grows leaves the picture where it was
  private def allPoints: Seq[XYZ] = layers.flatMap {
    case l: Layer.Points    => l.at
    case l: Layer.Segments  => l.ends.flatMap((a, b) => Seq(a, b))
    case l: Layer.Triangles => l.corners.flatMap((a, b, c) => Seq(a, b, c))
    case l: Layer.Balls     => l.centers
    case l: Layer.Halos     => l.at
  }

  /** The centre and radius that fit the scene into a unit ball. */
  private def frame: (XYZ, Double) =
    val ps = allPoints
    if ps.isEmpty then ((0.0, 0.0, 0.0), 1.0)
    else
      def mid(f: XYZ => Double) = (ps.map(f).min + ps.map(f).max) / 2
      val c = (mid(_._1), mid(_._2), mid(_._3))
      val r = ps.map(p => math.sqrt(math.pow(p._1 - c._1, 2) + math.pow(p._2 - c._2, 2) + math.pow(p._3 - c._3, 2))).max
      (c, if r > 0 then r else 1.0)

  /** A scene's point as it is drawn: round and filled, with a ring of 1.5 pixels in the surface color, then whatever
    * the scene's marker changes (its size, opacity and edge).
    */
  private def sceneMarker(radius: Double, own: Paint): MarkerFace =
    MarkerStyle.resolve(
      Shape.Circle,
      radius,
      own,
      Seq(MarkerStyle(edge = LineStyle(width = Some(1.5))), elements.marker)
    )

  // ----- the static view (SVG)

  def svgFile(theme: Theme = Theme.Light): String =
    """<?xml version="1.0" encoding="UTF-8"?>""" + "\n" + staticSvg(theme, saved = true)

  /** One view as an `<svg>`, primitives sorted back to front (orthographic projection). With `saved`, the view of a
    * file, which draws no background unless the style asks for one (see [[Style.transparent]]).
    */
  def staticSvg(theme: Theme = Theme.Light, saved: Boolean = false): String =
    val pal = palette.withDegrees(degrees)
    val (ts, ink) = (style.textScale, style.markScale)
    def paint(p: Paint) = if theme == Theme.Auto then pal.css(p) else pal.hex(p, theme)
    val (c, r) = frame
    val az = math.toRadians(azimuth)
    val el = math.toRadians(elevation)
    def view(p: XYZ): (Double, Double, Double) =
      val (x, y, z) = ((p._1 - c._1) / r, (p._2 - c._2) / r, (p._3 - c._3) / r)
      val x1 = x * math.cos(az) - y * math.sin(az)
      val y1 = x * math.sin(az) + y * math.cos(az)
      val y2 = y1 * math.cos(el) - z * math.sin(el)
      val z2 = y1 * math.sin(el) + z * math.cos(el)
      (x1, z2, y2) // screen x, screen y (up), depth (away from the viewer)
    val top = if title.nonEmpty then 16.0 + 24.0 * ts * (elements.face(TextRole.Title).size / 15.0) else 12.0
    val scale = 0.46 * math.min(width, height - top) * zoom
    val (cx, cy) = (width / 2.0, top + (height - top) / 2.0)
    def sx(v: (Double, Double, Double)) = f"${cx + v._1 * scale}%.2f"
    def sy(v: (Double, Double, Double)) = f"${cy - v._2 * scale}%.2f"
    val items = layers.flatMap {
      case l: Layer.Triangles =>
        l.corners.indices.map { i =>
          val (a, b, d) = l.corners(i)
          val (va, vb, vd) = (view(a), view(b), view(d))
          val depth = (va._3 + vb._3 + vd._3) / 3
          depth -> s"""<polygon points="${sx(va)},${sy(va)} ${sx(vb)},${sy(vb)} ${sx(vd)},${sy(
              vd
            )}" style="fill:${paint(l.paintAt(i))};fill-opacity:${l.opacity}"/>"""
        }
      case l: Layer.Segments =>
        l.ends.indices.map { i =>
          val (a, b) = l.ends(i)
          val (va, vb) = (view(a), view(b))
          (va._3 + vb._3) / 2 - 1e-6 -> s"""<line x1="${sx(va)}" y1="${sy(va)}" x2="${sx(vb)}" y2="${sy(
              vb
            )}" style="stroke:${paint(l.paintAt(i))}${
              if l.opacity == 1.0 then ""
              else s";stroke-opacity:${l.opacity}"
            }" stroke-width="${l.width * ink}" stroke-linecap="round"/>"""
        }
      case l: Layer.Balls =>
        l.centers.indices.map { i =>
          val v = view(l.centers(i))
          v._3 + 1e-6 -> s"""<circle cx="${sx(v)}" cy="${sy(
              v
            )}" r="${f"${l.radius / r * scale}%.2f"}" style="fill:${paint(
              l.paintAt(i)
            )};fill-opacity:${l.opacity}"/>"""
        }
      case l: Layer.Halos =>
        l.at.indices.map { i =>
          val v = view(l.at(i))
          v._3 - 3e-6 -> s"""<circle cx="${sx(v)}" cy="${sy(v)}" r="${l.radius * ink}" style="fill:${paint(
              l.paintAt(i)
            )};fill-opacity:${l.opacity}"/>"""
        }
      case l: Layer.Points =>
        l.at.indices.map { i =>
          val v = view(l.at(i))
          val face = sceneMarker(l.radius, l.paintAt(i))
          val attrs =
            MarkerStyle.attributes(face, paint(l.paintAt(i)), paint(face.edge.paint), _.toString.stripSuffix(".0"))
          v._3 - 2e-6 -> s"""<circle cx="${sx(v)}" cy="${sy(v)}" r="${face.size * ink}" $attrs/>"""
        }
    }
    val body = items.sortBy(-_._1).map(_._2).mkString
    val id = s"tda4j3d${Scene.counter.incrementAndGet()}"
    val css = if theme == Theme.Auto then s"<style>${pal.cssVariables(s"#$id")}</style>" else ""
    def num(v: Double) = if v == v.toLong then v.toLong.toString else f"$v%.2f"
    // the title and the subtitle, as the style and the text roles have them
    val (titleFace, subtitleFace) = (elements.face(TextRole.Title), elements.face(TextRole.Subtitle))
    val (titlePx, subtitlePx) = (titleFace.size * ts, subtitleFace.size * ts)
    val titleBase = 9.0 + titlePx
    def at(f: TextFace): (Double, String) = f.align match
      case Anchor.Start  => (14.0, "")
      case Anchor.Middle => (width / 2.0, """ text-anchor="middle"""")
      case Anchor.End    => (width - 14.0, """ text-anchor="end"""")
    def extras(f: TextFace): String =
      f.weight.fold("")(wt => s""" font-weight="${wt.css}"""") + (if f.italic then """ font-style="italic"""" else "") +
        f.font.fold("")(ff => s""" font-family="${Svg.escape(ff)}"""")
    val heading =
      (if title.nonEmpty then
         val (tx, anchor) = at(titleFace)
         s"""<text x="${num(tx)}" y="${num(titleBase)}"$anchor font-size="${num(titlePx)}"${extras(
             titleFace
           )} style="fill:${paint(
             titleFace.paint
           )}">${Svg.escape(title)}</text>"""
       else "")
      +
        (if subtitle.nonEmpty then
           val (sx, anchor) = at(subtitleFace)
           val sy =
             if title.nonEmpty then
               titleBase + 16.0 * ts + 0.25 * (titlePx - 15.0 * ts) + 0.75 * (subtitlePx - 12.0 * ts)
             else titleBase + (subtitlePx - 12.0 * ts)
           s"""<text x="${num(sx)}" y="${num(sy)}"$anchor font-size="${num(subtitlePx)}"${extras(
               subtitleFace
             )} style="fill:${paint(
               subtitleFace.paint
             )}">${Svg.escape(subtitle)}</text>"""
         else "")
    val background =
      if !style.hasBackground(saved) then ""
      else s"""<rect width="$width" height="$height" style="fill:${paint(Paint.Surface)}"/>"""
    s"""<svg xmlns="http://www.w3.org/2000/svg" id="$id" width="$width" height="$height" viewBox="0 0 $width $height" font-family="${Svg
        .escape(style.font)}">$css$background$heading$body</svg>"""

  // ----- the interactive view (canvas)

  def fragment(theme: Theme = Theme.Auto): String = build(theme, None, None)

  /** This scene as one panel of an [[Interactive]] page: the items whose reaction is [[Behavior.Shown]] come and go as
    * the controls move, balls follow a [[Behavior.Radius]], items appear while a key is chosen ([[Behavior.Selected]])
    * and choose one when the pointer is on them ([[Behavior.Selects]]. The canvas listens for the page's `tda4j:change`
    * events.
    */
  private[plot] def fragmentReacting(theme: Theme, reactions: Map[Int, IndexedSeq[Reaction]], id: String): String =
    build(theme, Some(reactions), Some(id))

  private def build(theme: Theme, reacting: Option[Map[Int, IndexedSeq[Reaction]]], named: Option[String]): String =
    val pal = palette.withDegrees(degrees)
    val (ts, ink) = (style.textScale, style.markScale)
    def px(v: Double) = if v == v.toLong then v.toLong.toString else f"$v%.2f"
    // a text's size, weight, slant, font and alignment as CSS, then its color
    def css(role: TextRole, rest: String): String =
      val f = elements.face(role)
      val weight = f.weight.fold("")(wt => s"font-weight:${wt.css};")
      val slant = if f.italic then "font-style:italic;" else ""
      val family = f.font.fold("")(ff => s"font-family:${Svg.escape(ff)};")
      val align = f.align match
        case Anchor.Start  => ""
        case Anchor.Middle => "text-align:center;"
        case Anchor.End    => "text-align:right;"
      s"font-size:${px(f.size * ts)}px;$weight$slant$family$align$rest"
    val (c, r) = frame
    def col(p: Paint) = "\"" + (if theme == Theme.Auto then pal.css(p) else pal.hex(p, theme)) + "\""
    // The positions are written once, as a flat list of x, y, z (in the unit ball); a layer's items are numbers into it.
    // A cell that two layers share, or a point that many cells share, costs a number, not three.
    val places = scala.collection.mutable.LinkedHashMap.empty[XYZ, Int]
    def at(p: XYZ): Int = places.getOrElseUpdate(p, places.size)
    def short(v: Double): String =
      val t = f"$v%.5f".reverse.dropWhile(_ == '0').reverse
      if t.endsWith(".") then t.dropRight(1) else if t == "-0" then "0" else t
    // the paints of a layer: a table of CSS colors and which entry each item takes (one number when all do)
    def paintsOf(l: Layer, n: Int): String =
      val table = (0 until n).map(i => col(l.paintAt(i))).distinct
      val which =
        if l.paints.size == 1 || table.size == 1 then "0"
        else (0 until n).map(i => table.indexOf(col(l.paintAt(i)))).mkString("[", ",", "]")
      s""""pal":[${(if table.isEmpty then Seq(col(l.paintAt(0))) else table).mkString(",")}],"c":$which"""
    // what the controls and selections ask of the items of a layer, for a panel of an interactive page
    def reacts(index: Int, layer: Layer): String =
      reacting.flatMap(_.get(index)).fold("") { rs =>
        val n = Interactive.itemCount(layer)
        val per = (0 until n).map(i => Interactive.reactionOf(rs, i).behaviors)
        val radius = per.headOption.flatMap(_.collectFirst { case Behavior.Radius(ctl, factor) => (ctl, factor) })
        val rest = per.map(_.filter {
          case _: Behavior.Radius | _: Behavior.Custom | _: Behavior.Selects | _: Behavior.Follows => false
          case _                                                                                   => true
        })
        val picks = per.map(_.collect { case Behavior.Selects(sel, key) => (sel, key) })
        def number(v: Double) =
          if v.isPosInfinity then "Infinity" else if v.isNegInfinity then "-Infinity" else v.toString
        val shownOnly = rest.forall(_ match
          case Seq(_: Behavior.Shown) => true
          case _                      => false)
        val controls = rest.flatMap(_.collect { case Behavior.Shown(ctl, _, _) => ctl }).distinct
        val shown =
          if n > 0 && shownOnly && controls.size == 1 then
            val sh = rest.map { case Seq(Behavior.Shown(_, from, until)) => (from, until); case _ => (0.0, 0.0) }
            val untils =
              if sh.forall(_._2.isPosInfinity) then "null" else sh.map(x => number(x._2)).mkString("[", ",", "]")
            s""","sh":{"c":"${controls.head}","f":${sh.map(x => number(x._1)).mkString("[", ",", "]")},"u":$untils}"""
          else if rest.forall(_.isEmpty) then ""
          else
            val entries = rest.map { bs =>
              if bs.isEmpty then "null"
              else
                bs.map {
                  case Behavior.Shown(ctl, from, until) => s"""["v","$ctl",${number(from)},${number(until)}]"""
                  case Behavior.Selected(sel, key)      => s"""["w","$sel",$key]"""
                  case _                                => "null"
                }.mkString("[", ",", "]")
            }
            s""","ia":${entries.mkString("[", ",", "]")}"""
        val ball = radius.fold("")((ctl, factor) => s""","ra":["$ctl",${short(factor / r)}]""")
        val chooses =
          if picks.forall(_.isEmpty) then ""
          else
            val entries = picks.map(ps =>
              if ps.isEmpty then "null" else ps.map((sel, key) => s"""["$sel",$key]""").mkString("[", ",", "]")
            )
            s""","pk":${entries.mkString("[", ",", "]")}"""
        shown + ball + chooses
      }
    val layerJson = layers.zipWithIndex.map { (layer, li) =>
      val n = Interactive.itemCount(layer)
      val body = layer match
        case l: Layer.Points =>
          val face = sceneMarker(l.radius, l.paints.headOption.getOrElse(Paint.Ink))
          val edge = if face.edge.visible then face.edge.width else 0.0
          s""""k":"p","r":${short(face.size * ink)},"op":${face.opacity},"ew":$edge,"ec":${col(
              face.edge.paint
            )},"i":${l.at.map(at).mkString("[", ",", "]")}"""
        case l: Layer.Segments =>
          s""""k":"s","w":${short(l.width * ink)},"op":${l.opacity},"i":${l.ends
              .flatMap((a, b) => Seq(at(a), at(b)))
              .mkString("[", ",", "]")}"""
        case l: Layer.Triangles =>
          s""""k":"t","op":${l.opacity},"i":${l.corners
              .flatMap((a, b, d) => Seq(at(a), at(b), at(d)))
              .mkString("[", ",", "]")}"""
        case l: Layer.Balls =>
          s""""k":"b","rad":${short(l.radius / r)},"op":${l.opacity},"i":${l.centers.map(at).mkString("[", ",", "]")}"""
        case l: Layer.Halos =>
          s""""k":"h","r":${short(l.radius * ink)},"op":${l.opacity},"i":${l.at.map(at).mkString("[", ",", "]")}"""
      s"""{$body,${paintsOf(layer, n)}${reacts(li, layer)}}"""
    }
    // `keys` is a Set: flatMap over it would collapse equal coordinates, so go through a Vector
    val positions = places.keys.toVector.flatMap(p =>
      Seq(short((p._1 - c._1) / r), short((p._2 - c._2) / r), short((p._3 - c._3) / r))
    )
    val json = layerJson.mkString("[", ",", "]")
    // the script of a panel of an interactive page also asks which items the controls and selections leave out
    val hook =
      if reacting.isEmpty then ""
      else
        """
          |const ia=root.closest('.tda4j-ia'),vals={};
          |function hid(L,k){
          |  if(L.sh){const v=vals[L.sh.c];if(v!==undefined&&!(v>=L.sh.f[k]&&v<(L.sh.u?L.sh.u[k]:Infinity)))return true;}
          |  const a=L.ia&&L.ia[k];
          |  if(a)for(const e of a){const v=vals[e[1]];
          |    if(e[0]==='v'){if(v!==undefined&&!(v>=e[2]&&v<e[3]))return true;}
          |    else if(!v||v.indexOf(e[2])<0)return true;}
          |  return false;}
          |if(ia)ia.addEventListener('tda4j:change',e=>{vals[e.detail.name]=e.detail.value;draw();});""".stripMargin
    val id = named.getOrElse(s"tda4j3d${Scene.counter.incrementAndGet()}")
    val vars = if theme == Theme.Auto then pal.cssVariables(s"#$id") else ""
    s"""<div id="$id" style="position:relative;width:${width}px;max-width:100%;font-family:${Svg.escape(style.font)}">
       |<style>$vars #$id canvas{display:block;width:100%;touch-action:none;cursor:grab;border-radius:4px}</style>
       |${
        if title.nonEmpty then
          s"""<div style="${css(
              TextRole.Title,
              s"margin:4px 0 2px;color:${col(elements.face(TextRole.Title).paint).replace("\"", "")}"
            )}">${Svg.escape(title)}</div>"""
        else ""
      }
       |${
        if subtitle.nonEmpty then
          s"""<div style="${css(
              TextRole.Subtitle,
              s"margin:0 0 6px;color:${col(elements.face(TextRole.Subtitle).paint).replace("\"", "")}"
            )}">${Svg.escape(subtitle)}</div>"""
        else ""
      }
       |<canvas width="$width" height="${height - 40}"></canvas>
       |<div style="${css(
        TextRole.Note,
        s"color:${col(elements.face(TextRole.Note).paint).replace("\"", "")};margin-top:4px"
      )}">${if turns then "drag to turn, scroll to zoom" else "drag to pan, scroll to zoom"}</div>
       |<script>(function(){
       |const root=document.getElementById('$id'),cv=root.querySelector('canvas'),g=cv.getContext('2d');
       |const P=${positions.mkString("[", ",", "]")},layers=$json;$hook
       |const css=getComputedStyle(root),np=P.length/3,Q=new Float64Array(3*np),resolved=new Map();
       |const color=s=>{if(!s.startsWith('var('))return s;let c=resolved.get(s);
       |  if(c===undefined){c=css.getPropertyValue(s.slice(4,-1)).trim();resolved.set(s,c);}return c;};
       |let az=${math.toRadians(azimuth)},el=${math.toRadians(elevation)},zoom=${Svg.number(zoom)};
       |let pan=[0,0];const turns=$turns,ends=L=>L.k==='s'?2:L.k==='t'?3:1,count=L=>L.i.length/ends(L),tint=(L,k)=>L.pal[typeof L.c==='number'?L.c:L.c[k]];
       |function project(){const ca=Math.cos(az),sa=Math.sin(az),ce=Math.cos(el),se=Math.sin(el);
       |  for(let i=0;i<np;i++){const x=P[3*i],y=P[3*i+1],z=P[3*i+2],x1=x*ca-y*sa,y1=x*sa+y*ca;
       |    Q[3*i]=x1;Q[3*i+1]=y1*se+z*ce;Q[3*i+2]=y1*ce-z*se;}}
       |let last=[],sc=1,ox=0,oy=0;
       |const X=i=>ox+Q[3*i]*sc,Y=i=>oy-Q[3*i+1]*sc;
       |// the painting order depends on the view alone, not on the sliders or the zoom: it is sorted when the view turns
       |const off=[];let total=0;layers.forEach(L=>{off.push(total);total+=count(L);});
       |const LI=new Uint16Array(total),KK=new Int32Array(total),dep=new Float64Array(total);let order=null;
       |layers.forEach((L,li)=>{const n=count(L);for(let k=0;k<n;k++){LI[off[li]+k]=li;KK[off[li]+k]=k;}});
       |function sortAll(){project();
       |  layers.forEach((L,li)=>{const n=count(L),o=off[li];
       |    for(let k=0;k<n;k++){let d;
       |      if(L.k==='t')d=(Q[3*L.i[3*k]+2]+Q[3*L.i[3*k+1]+2]+Q[3*L.i[3*k+2]+2])/3;
       |      else if(L.k==='s')d=(Q[3*L.i[2*k]+2]+Q[3*L.i[2*k+1]+2])/2-1e-6;
       |      else d=Q[3*L.i[k]+2]+(L.k==='p'?-2e-6:L.k==='b'?1e-6:-3e-6);
       |      dep[o+k]=d;}});
       |  order=new Int32Array(total);for(let i=0;i<total;i++)order[i]=i;order.sort((u,v)=>dep[v]-dep[u]);}
       |function draw(){const dpr=window.devicePixelRatio||1,W=cv.clientWidth,H=W*cv.height/cv.width;
       |  if(cv.width!==Math.round(W*dpr)){cv.width=Math.round(W*dpr);cv.height=Math.round(H*dpr);}
       |  g.setTransform(dpr,0,0,dpr,0,0);g.fillStyle=color(${col(Paint.Surface)});g.fillRect(0,0,W,H);
       |  if(!order)sortAll();sc=0.46*Math.min(W,H)*zoom;ox=W/2+pan[0];oy=H/2+pan[1];last=[];
       |  for(let q=0;q<total;q++){const gi=order[q],li=LI[gi],k=KK[gi],L=layers[li];
       |    ${
        if reacting.isEmpty then "" else "if(hid(L,k))continue;"
      }
       |    if(L.pk&&L.pk[k])last.push([dep[gi],li,k]);
       |    const c=color(tint(L,k));
       |    if(L.k==='t'){g.globalAlpha=L.op;g.fillStyle=c;g.beginPath();g.moveTo(X(L.i[3*k]),Y(L.i[3*k]));g.lineTo(X(L.i[3*k+1]),Y(L.i[3*k+1]));g.lineTo(X(L.i[3*k+2]),Y(L.i[3*k+2]));g.closePath();g.fill();g.globalAlpha=1;}
       |    else if(L.k==='s'){g.globalAlpha=L.op;g.strokeStyle=c;g.lineWidth=L.w;g.lineCap='round';g.beginPath();g.moveTo(X(L.i[2*k]),Y(L.i[2*k]));g.lineTo(X(L.i[2*k+1]),Y(L.i[2*k+1]));g.stroke();g.globalAlpha=1;}
       |    else if(L.k==='p'){g.globalAlpha=L.op;g.fillStyle=c;g.beginPath();g.arc(X(L.i[k]),Y(L.i[k]),L.r,0,2*Math.PI);g.fill();if(L.ew>0){g.strokeStyle=color(L.ec);g.lineWidth=L.ew;g.stroke();}g.globalAlpha=1;}
       |    else{const rad=L.k==='b'?radiusOf(L)*sc:L.r;g.globalAlpha=L.op;g.fillStyle=c;g.beginPath();g.arc(X(L.i[k]),Y(L.i[k]),rad,0,2*Math.PI);g.fill();g.globalAlpha=1;}}}
       |function radiusOf(L){if(L.ra){const v=vals[L.ra[0]];if(v!==undefined)return v*L.ra[1];}return L.rad;}
       |// what the pointer is over: the nearest drawn item of a layer that chooses, within 4 pixels of its shape
       |const segDist=(px,py,ax,ay,bx,by)=>{const dx=bx-ax,dy=by-ay,l=dx*dx+dy*dy,t=l?Math.max(0,Math.min(1,((px-ax)*dx+(py-ay)*dy)/l)):0;return Math.hypot(px-ax-t*dx,py-ay-t*dy);};
       |function reach(L,k,x,y){
       |  if(L.k==='p')return Math.max(0,Math.hypot(x-X(L.i[k]),y-Y(L.i[k]))-L.r);
       |  if(L.k==='s')return Math.max(0,segDist(x,y,X(L.i[2*k]),Y(L.i[2*k]),X(L.i[2*k+1]),Y(L.i[2*k+1]))-L.w/2);
       |  if(L.k!=='t')return Infinity;
       |  const a=L.i[3*k],b=L.i[3*k+1],c=L.i[3*k+2],ax=X(a),ay=Y(a),bx=X(b),by=Y(b),cx=X(c),cy=Y(c);
       |  const s1=(x-bx)*(ay-by)-(ax-bx)*(y-by),s2=(x-cx)*(by-cy)-(bx-cx)*(y-cy),s3=(x-ax)*(cy-ay)-(cx-ax)*(y-ay);
       |  if(!((s1<0||s2<0||s3<0)&&(s1>0||s2>0||s3>0)))return 0;
       |  return Math.min(segDist(x,y,ax,ay,bx,by),segDist(x,y,bx,by,cx,cy),segDist(x,y,cx,cy,ax,ay));}
       |const choosers=new Set();layers.forEach(L=>{if(L.pk)L.pk.forEach(p=>{if(p)p.forEach(q=>choosers.add(q[0]));});});
       |function pickAt(clientX,clientY){const r=cv.getBoundingClientRect(),x=(clientX-r.left)*cv.clientWidth/r.width,y=(clientY-r.top)*cv.clientWidth/r.width;
       |  let best=null,bd=Infinity,bz=Infinity;
       |  for(const it of last){const L=layers[it[1]];if(!L.pk||!L.pk[it[2]])continue;
       |    const d=reach(L,it[2],x,y);if(d>4)continue;
       |    if(d<bd||(d===bd&&it[0]<bz)){bd=d;bz=it[0];best=L.pk[it[2]];}}
       |  return best;}
       |const keysFor=(pk,n)=>pk?pk.filter(q=>q[0]===n).map(q=>q[1]):[];
       |const link=()=>ia&&ia.tda4j;
       |let frame=0,xy=null;
       |function hoverAt(e){if(!choosers.size||!link())return;xy=[e.clientX,e.clientY];
       |  if(frame)return;
       |  frame=requestAnimationFrame(()=>{frame=0;const pk=pickAt(xy[0],xy[1]);
       |    choosers.forEach(n=>link().hover(n,keysFor(pk,n)));cv.style.cursor=pk?'pointer':'grab';});}
       |function leave(){if(frame){cancelAnimationFrame(frame);frame=0;}
       |  if(link())choosers.forEach(n=>link().hover(n,[]));cv.style.cursor='grab';}
       |let drag=null,moved=0;
       |cv.addEventListener('pointerdown',e=>{drag=[e.clientX,e.clientY,az,el,pan[0],pan[1]];moved=0;cv.setPointerCapture(e.pointerId);cv.style.cursor='grabbing';});
       |cv.addEventListener('pointermove',e=>{if(!drag){hoverAt(e);return;}
       |  moved=Math.max(moved,Math.hypot(e.clientX-drag[0],e.clientY-drag[1]));
       |  if(moved<4)return;
       |  if(turns){az=drag[2]+(e.clientX-drag[0])*0.01;el=Math.max(-1.55,Math.min(1.55,drag[3]+(e.clientY-drag[1])*0.01));order=null;}
       |  else pan=[drag[4]+(e.clientX-drag[0])*cv.clientWidth/cv.getBoundingClientRect().width,drag[5]+(e.clientY-drag[1])*cv.clientWidth/cv.getBoundingClientRect().width];
       |  draw();});
       |cv.addEventListener('pointerup',e=>{const was=drag;drag=null;cv.style.cursor='grab';
       |  if(was&&moved<4&&choosers.size&&link()){const pk=pickAt(e.clientX,e.clientY);choosers.forEach(n=>link().click(n,keysFor(pk,n),e.shiftKey));}});
       |cv.addEventListener('pointerleave',leave);
       |cv.addEventListener('wheel',e=>{e.preventDefault();const z=Math.max(0.2,Math.min(turns?8:200,zoom*Math.exp(-e.deltaY*0.001)));
       |  if(!turns){const r=cv.getBoundingClientRect(),k=cv.clientWidth/r.width,x=(e.clientX-r.left)*k-cv.clientWidth/2,y=(e.clientY-r.top)*k-cv.clientHeight/2;
       |    pan=[x-(x-pan[0])*z/zoom,y-(y-pan[1])*z/zoom];}
       |  zoom=z;draw();},{passive:false});
       |matchMedia('(prefers-color-scheme: dark)').addEventListener('change',()=>{resolved.clear();draw();});
       |window.addEventListener('resize',draw);draw();})();</script>
       |</div>""".stripMargin

  def html(theme: Theme = Theme.Auto): String = Html.page(title, fragment(theme), palette)

object Scene:
  // Element ids for the fragments of one page; scratch, no meaning outside this file.
  private[plot] val counter = new java.util.concurrent.atomic.AtomicLong()
