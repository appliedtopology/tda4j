// format: off
// sbt's own meta-build (this `project/` directory) compiles under sbt's bundled Scala 2.12 (confirmed from the
// `sbt scalafmtSbt` build log: "compiling ... to .../scala-2.12/sbt-1.0/classes"), not this repo's Scala 3.9 --
// but .scalafmt.conf's `runner.dialect = scala3` (needed for src/main and src/test) also reached this file
// before this directive was added, via `scalafmtSbt`/`scalafmtSbtCheck`. A `fileOverride` glob keyed on
// `**/project/*.scala` with `runner.dialect = scala212` was tried first and did NOT take effect for that
// specific task (`scalafmtSbtCheck` still flagged this file even with the override in place) -- verified the
// hazard directly: running `scalafmtSbt` with the global scala3 dialect and no override rewrote this file's
// brace/`if (...) ... else { ... }` syntax into `then`/indentation-based new syntax, which the Scala 2.12 meta-
// build compiler cannot parse at all, breaking `sbt` itself (had to `git checkout` this file back to recover).
// `// format: off` (this directive), unlike the fileOverride attempt, verifiably fixes BOTH problems at once --
// `scalafmtSbtCheck` passes cleanly and `scalafmtSbt` leaves the file untouched -- so don't replace it with a
// fileOverride-based approach without first confirming that actually takes effect for the Sbt-config task.
import cats.syntax.all.*
import laika.api.bundle.{BlockDirectives, DirectiveRegistry, LinkDirectives, SpanDirectives, TemplateDirectives}
import laika.api.bundle.BlockDirectives.dsl.*
import laika.ast.{Block, CodeBlock, Span, TemplateSpan, Text}
import laika.parse.code.languages.{JavaSyntax, ScalaSyntax}

import java.io.File
import scala.io.Source

/** Laika equivalent of Paradox's `@@snip`: pulls a tagged region out of a real source file so the docs stay
  * anchored to code the test suite actually compiles and exercises, instead of a hand-copied, driftable example.
  *
  * Usage: `@:snip(/src/main/scala/.../File.scala, tag-name)`, where the source file brackets the region with two
  * occurrences of a `// #tag-name` marker line (the same convention Paradox's own `@@snip` used in this repo).
  */
class SnipDirective(baseDir: File) extends DirectiveRegistry {

  override val description: String = "Tagged source-file snippet inclusion (@:snip)"

  override def spanDirectives: Seq[SpanDirectives.Directive]         = Seq.empty
  override def templateDirectives: Seq[TemplateDirectives.Directive] = Seq.empty
  override def linkDirectives: Seq[LinkDirectives.Directive]         = Seq.empty

  private def extractSnippet(path: String, tag: String): Either[String, String] = {
    val file   = new File(baseDir, path.stripPrefix("/"))
    val marker = s"#$tag"
    if (!file.exists())
      Left(s"snip: source file not found: ${file.getPath}")
    else {
      val lines      = Source.fromFile(file).getLines().toVector
      val markerLine = lines.indexWhere(_.contains(marker))
      val endLine    = lines.indexWhere(_.contains(marker), markerLine + 1)
      if (markerLine < 0 || endLine < 0)
        Left(s"snip: tag '$tag' not found twice in ${file.getPath}")
      else {
        val body        = lines.slice(markerLine + 1, endLine)
        val indentLevels = body.filter(_.trim.nonEmpty).map(_.takeWhile(_ == ' ').length)
        val minIndent   = if (indentLevels.isEmpty) 0 else indentLevels.min
        Right(body.map(l => if (l.length >= minIndent) l.drop(minIndent) else l).mkString("\n"))
      }
    }
  }

  private def languageOf(path: String): String =
    path.reverse.takeWhile(_ != '.').reverse match {
      case "scala" => "scala"
      case "java"  => "java"
      case other   => other
    }

  // A CodeBlock built here from plain Text is NOT picked up by `laika.config.SyntaxHighlighting`'s own pass
  // (observed empirically: a ```scala fence highlights, this directive's output didn't, until tokenized here) --
  // so tokenize the extracted text directly using the same per-language parser the built-in highlighter uses.
  private def highlight(language: String, text: String): Seq[Span] = {
    val highlighter = language match {
      case "scala" => Some(ScalaSyntax)
      case "java"  => Some(JavaSyntax)
      case _       => None
    }
    highlighter.flatMap(_.rootParser.parse(text).toOption).getOrElse(Seq(Text(text)))
  }

  // `eval`, not `create`: a missing file or renamed tag must fail the build, not silently render an error
  // string as a code block on the published page -- that would make CI green on broken docs.
  lazy val snip = BlockDirectives.eval("snip") {
    (attribute(0).as[String].widen, attribute(1).as[String].widen).mapN { (path, tag) =>
      extractSnippet(path, tag).map { text =>
        val language = languageOf(path)
        CodeBlock(language, highlight(language, text))
      }
    }
  }

  override def blockDirectives: Seq[BlockDirectives.Directive] = Seq(snip)
}
