package org.appliedtopology.tda4j.plot

/** A standalone page around a plot fragment: the palette's page background in both modes, the fragment centred. */
object Html:
  def page(title: String, body: String, palette: Palette = Palette.default): String =
    s"""<!doctype html>
       |<html lang="en"><head><meta charset="utf-8">
       |<meta name="viewport" content="width=device-width,initial-scale=1">
       |<meta name="color-scheme" content="light dark">
       |<title>${Svg.escape(if title.isEmpty then "TDA4j plot" else title)}</title>
       |<style>
       |html,body{margin:0;background:${palette.light.surface};color:${palette.light.ink};font-family:system-ui,-apple-system,'Segoe UI',sans-serif}
       |@media (prefers-color-scheme: dark){html,body{background:${palette.dark.surface};color:${palette.dark.ink}}}
       |main{padding:16px;display:flex;justify-content:center}
       |main>svg{max-width:100%;height:auto}
       |</style></head>
       |<body><main>
       |$body
       |</main></body></html>
       |""".stripMargin
