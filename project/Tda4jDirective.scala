// format: off
// Lives in project/ (compiled under sbt's own Scala 2.12 meta-build), like SnipDirective.scala, because it's
// registered directly in build.sbt's own `laikaExtensions +=` -- see that file's own comment for why
// `// format: off` is load-bearing here too (scalafmt's global `runner.dialect = scala3` can't parse this
// file's Scala 2 syntax and will rewrite it into forms the meta-build compiler can't read back).
//
// `@:tda4j` -- opt-in, inline: wraps "tda4j" in the same font as the logo's wordmark, with the "4" in the
// accent color (`brand/DECISIONS.md` has the full logo/color decision record). Deliberately reads
// `--header-font`/`--primary-color`/`--secondary-color` -- the theme's own UI-safe (4.5:1-checked) CSS custom
// properties, NOT the brighter brand-mark hex values `brand/icon.svg` itself uses. The logo mark only needs to
// clear WCAG's 3:1 non-text threshold; this directive renders actual body-sized prose text, which needs the
// stricter 4.5:1 tier -- using the brand-mark gold here would be readable-when-large-and-graphical but not
// reliably readable-as-inline-text. Reading the CSS variables (rather than hardcoding hex) also means this
// re-colors for dark mode for free, the same way every other themed element on the site already does.
import laika.api.bundle.{BlockDirectives, DirectiveRegistry, LinkDirectives, SpanDirectives, TemplateDirectives}
import laika.api.bundle.SpanDirectives.dsl._
import laika.ast.{Options, SpanSequence, Text}

object Tda4jDirective extends DirectiveRegistry {

  override val description: String = "Font/color-matched inline wordmark (@:tda4j)"

  override def blockDirectives: Seq[BlockDirectives.Directive]         = Seq.empty
  override def templateDirectives: Seq[TemplateDirectives.Directive] = Seq.empty
  override def linkDirectives: Seq[LinkDirectives.Directive]         = Seq.empty

  // `@:tda4j` takes no arguments and can never fail -- `attribute(0)` is only here so `eval` has a
  // DirectivePart to build on (its value is discarded); `.optional` is what lets the directive be written
  // bare, with no parentheses at all. Uses `eval` + `.map`, the one directive-construction shape already
  // proven to compile in this Scala-2.12 meta-build context (SnipDirective.scala's own `eval` + `.mapN`),
  // rather than guessing at `create`'s exact "lift a constant, no attributes" incantation.
  lazy val tda4j: SpanDirectives.Directive = SpanDirectives.eval("tda4j") {
    attribute(0).as[String].optional.map { _ =>
      Right(
        SpanSequence(
          Seq(
            Text("tda"),
            Text("4", Options(None, Set("tda4j-accent"))),
            Text("j")
          ),
          Options(None, Set("tda4j-mark"))
        )
      )
    }
  }

  override def spanDirectives: Seq[SpanDirectives.Directive] = Seq(tda4j)
}
