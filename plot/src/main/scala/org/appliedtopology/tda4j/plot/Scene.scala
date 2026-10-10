package org.appliedtopology.tda4j.plot

/** A point in 3-D data coordinates. */
type XYZ = (Double, Double, Double)

/** What a 3-D scene is made of: points, segments and triangles, each with one paint or one per item. */
sealed trait Layer:
  def paints: IndexedSeq[Paint]
  final def paintAt(i: Int): Paint = if paints.size == 1 then paints(0) else paints(i)

object Layer:
  final case class Points(at: IndexedSeq[XYZ], paints: IndexedSeq[Paint], radius: Double = 3.5) extends Layer
  final case class Segments(ends: IndexedSeq[(XYZ, XYZ)], paints: IndexedSeq[Paint], width: Double = 1.2) extends Layer
  final case class Triangles(corners: IndexedSeq[(XYZ, XYZ, XYZ)], paints: IndexedSeq[Paint], opacity: Double = 0.25)
      extends Layer

/** A 3-D plot. In a page (`html`, the live viewer) it is drawn on a canvas you can turn (drag) and zoom (scroll), with
  * no library to load; as an `.svg` file it is one view, from `azimuth`/`elevation` (degrees), drawn back to front.
  */
final case class Scene(
  layers: Vector[Layer] = Vector.empty,
  title: String = "",
  subtitle: String = "",
  width: Int = 640,
  height: Int = 520,
  azimuth: Double = -35,
  elevation: Double = 25,
  palette: Palette = Palette.default
) extends Plot:
  def +(layer: Layer): Scene = copy(layers = layers :+ layer)
  def ++(more: IterableOnce[Layer]): Scene = copy(layers = layers ++ more)

  /** This scene's layers drawn over `under`'s. */
  def over(under: Scene): Scene = copy(layers = under.layers ++ layers)

  private def allPoints: Seq[XYZ] = layers.flatMap {
    case l: Layer.Points    => l.at
    case l: Layer.Segments  => l.ends.flatMap((a, b) => Seq(a, b))
    case l: Layer.Triangles => l.corners.flatMap((a, b, c) => Seq(a, b, c))
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

  // ----- the static view (SVG)

  def svgFile(theme: Theme = Theme.Light): String =
    """<?xml version="1.0" encoding="UTF-8"?>""" + "\n" + staticSvg(theme)

  /** One view as an `<svg>`, primitives sorted back to front (orthographic projection). */
  def staticSvg(theme: Theme = Theme.Light): String =
    def paint(p: Paint) = if theme == Theme.Auto then palette.css(p) else palette.hex(p, theme)
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
    val top = if title.nonEmpty then 40.0 else 12.0
    val scale = 0.46 * math.min(width, height - top)
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
            )}" style="stroke:${paint(l.paintAt(i))}" stroke-width="${l.width}" stroke-linecap="round"/>"""
        }
      case l: Layer.Points =>
        l.at.indices.map { i =>
          val v = view(l.at(i))
          v._3 - 2e-6 -> s"""<circle cx="${sx(v)}" cy="${sy(v)}" r="${l.radius}" style="fill:${paint(
              l.paintAt(i)
            )};stroke:${paint(Paint.Surface)}" stroke-width="1.5"/>"""
        }
    }
    val body = items.sortBy(-_._1).map(_._2).mkString
    val id = s"tda4j3d${Scene.counter.incrementAndGet()}"
    val style = if theme == Theme.Auto then s"<style>${palette.cssVariables(s"#$id")}</style>" else ""
    val heading =
      (if title.nonEmpty then
         s"""<text x="14" y="24" font-size="15" font-weight="600" style="fill:${paint(Paint.Ink)}">${Svg.escape(
             title
           )}</text>"""
       else "") +
        (if subtitle.nonEmpty then
           s"""<text x="14" y="${if title.nonEmpty then 40 else 24}" font-size="12" style="fill:${paint(
               Paint.Secondary
             )}">${Svg.escape(subtitle)}</text>"""
         else "")
    s"""<svg xmlns="http://www.w3.org/2000/svg" id="$id" width="$width" height="$height" viewBox="0 0 $width $height" font-family="system-ui,-apple-system,'Segoe UI',sans-serif">$style<rect width="$width" height="$height" style="fill:${paint(
        Paint.Surface
      )}"/>$heading$body</svg>"""

  // ----- the interactive view (canvas)

  def fragment(theme: Theme = Theme.Auto): String =
    val (c, r) = frame
    def num(v: Double) = f"$v%.5f"
    def pos(p: XYZ) = s"[${num((p._1 - c._1) / r)},${num((p._2 - c._2) / r)},${num((p._3 - c._3) / r)}]"
    def col(p: Paint) = "\"" + (if theme == Theme.Auto then palette.css(p) else palette.hex(p, theme)) + "\""
    val json = layers
      .map {
        case l: Layer.Points =>
          s"""{"kind":"p","r":${l.radius},"items":[${l.at.indices
              .map(i => s"[${pos(l.at(i))},${col(l.paintAt(i))}]")
              .mkString(",")}]}"""
        case l: Layer.Segments =>
          s"""{"kind":"s","w":${l.width},"items":[${l.ends.indices
              .map(i => s"[${pos(l.ends(i)._1)},${pos(l.ends(i)._2)},${col(l.paintAt(i))}]")
              .mkString(",")}]}"""
        case l: Layer.Triangles =>
          s"""{"kind":"t","o":${l.opacity},"items":[${l.corners.indices
              .map { i =>
                val (a, b, d) = l.corners(i)
                s"[${pos(a)},${pos(b)},${pos(d)},${col(l.paintAt(i))}]"
              }
              .mkString(",")}]}"""
      }
      .mkString("[", ",", "]")
    val id = s"tda4j3d${Scene.counter.incrementAndGet()}"
    val vars = if theme == Theme.Auto then palette.cssVariables(s"#$id") else ""
    s"""<div id="$id" style="position:relative;width:${width}px;max-width:100%;font-family:system-ui,-apple-system,'Segoe UI',sans-serif">
       |<style>$vars #$id canvas{display:block;width:100%;touch-action:none;cursor:grab;border-radius:4px}</style>
       |${
        if title.nonEmpty then
          s"""<div style="font:600 15px system-ui;margin:4px 0 2px;color:${col(Paint.Ink).replace("\"", "")}">${Svg
              .escape(title)}</div>"""
        else ""
      }
       |${
        if subtitle.nonEmpty then
          s"""<div style="font:12px system-ui;margin:0 0 6px;color:${col(Paint.Secondary).replace("\"", "")}">${Svg
              .escape(subtitle)}</div>"""
        else ""
      }
       |<canvas width="$width" height="${height - 40}"></canvas>
       |<div style="font:11px system-ui;color:${col(Paint.Muted).replace(
        "\"",
        ""
      )};margin-top:4px">drag to turn, scroll to zoom</div>
       |<script>(function(){
       |const root=document.getElementById('$id'),cv=root.querySelector('canvas'),g=cv.getContext('2d');
       |const layers=$json;
       |const css=getComputedStyle(root);
       |const color=s=>s.startsWith('var(')?css.getPropertyValue(s.slice(4,-1)).trim():s;
       |let az=${math.toRadians(azimuth)},el=${math.toRadians(elevation)},zoom=1;
       |function proj(p){const x1=p[0]*Math.cos(az)-p[1]*Math.sin(az),y1=p[0]*Math.sin(az)+p[1]*Math.cos(az);
       |  return [x1,y1*Math.sin(el)+p[2]*Math.cos(el),y1*Math.cos(el)-p[2]*Math.sin(el)];}
       |function draw(){const dpr=window.devicePixelRatio||1,W=cv.clientWidth,H=W*cv.height/cv.width;
       |  if(cv.width!==Math.round(W*dpr)){cv.width=Math.round(W*dpr);cv.height=Math.round(H*dpr);}
       |  g.setTransform(dpr,0,0,dpr,0,0);g.fillStyle=color(${col(Paint.Surface)});g.fillRect(0,0,W,H);
       |  const s=0.46*Math.min(W,H)*zoom,cx=W/2,cy=H/2,items=[];
       |  for(const L of layers){for(const it of L.items){
       |    if(L.kind==='t'){const a=proj(it[0]),b=proj(it[1]),c=proj(it[2]);items.push([(a[2]+b[2]+c[2])/3,0,L,[a,b,c],it[3]]);}
       |    else if(L.kind==='s'){const a=proj(it[0]),b=proj(it[1]);items.push([(a[2]+b[2])/2-1e-6,1,L,[a,b],it[2]]);}
       |    else{const a=proj(it[0]);items.push([a[2]-2e-6,2,L,[a],it[1]]);}}}
       |  items.sort((u,v)=>v[0]-u[0]);
       |  const X=p=>cx+p[0]*s,Y=p=>cy-p[1]*s,surface=color(${col(Paint.Surface)});
       |  for(const [d,k,L,ps,col] of items){const c=color(col);
       |    if(k===0){g.globalAlpha=L.o;g.fillStyle=c;g.beginPath();g.moveTo(X(ps[0]),Y(ps[0]));g.lineTo(X(ps[1]),Y(ps[1]));g.lineTo(X(ps[2]),Y(ps[2]));g.closePath();g.fill();g.globalAlpha=1;}
       |    else if(k===1){g.strokeStyle=c;g.lineWidth=L.w;g.lineCap='round';g.beginPath();g.moveTo(X(ps[0]),Y(ps[0]));g.lineTo(X(ps[1]),Y(ps[1]));g.stroke();}
       |    else{g.fillStyle=c;g.strokeStyle=surface;g.lineWidth=1.5;g.beginPath();g.arc(X(ps[0]),Y(ps[0]),L.r,0,2*Math.PI);g.fill();g.stroke();}}}
       |let drag=null;
       |cv.addEventListener('pointerdown',e=>{drag=[e.clientX,e.clientY,az,el];cv.setPointerCapture(e.pointerId);cv.style.cursor='grabbing';});
       |cv.addEventListener('pointermove',e=>{if(!drag)return;az=drag[2]+(e.clientX-drag[0])*0.01;el=Math.max(-1.55,Math.min(1.55,drag[3]+(e.clientY-drag[1])*0.01));draw();});
       |cv.addEventListener('pointerup',()=>{drag=null;cv.style.cursor='grab';});
       |cv.addEventListener('wheel',e=>{e.preventDefault();zoom=Math.max(0.2,Math.min(8,zoom*Math.exp(-e.deltaY*0.001)));draw();},{passive:false});
       |matchMedia('(prefers-color-scheme: dark)').addEventListener('change',draw);
       |window.addEventListener('resize',draw);draw();})();</script>
       |</div>""".stripMargin

  def html(theme: Theme = Theme.Auto): String = Html.page(title, fragment(theme), palette)

object Scene:
  // Element ids for the fragments of one page; scratch, no meaning outside this file.
  private[plot] val counter = new java.util.concurrent.atomic.AtomicLong()
