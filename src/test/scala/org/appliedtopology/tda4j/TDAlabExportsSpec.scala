package org.appliedtopology.tda4j

import org.specs2.mutable.Specification

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/** Guards `TDAlab`'s generated re-export block (`package.scala`, between the `BEGIN`/`END generated re-exports`
  * markers): every public top-level class/trait/object/type/enum of the root package and of the `sset` add-on must be
  * re-exported, so `import tdalab.{*, given}` stays the only import a lab user needs. Same scan as
  * `.claude/scripts/tdalab-exports.py`; when this fails, run that script.
  *
  * Also pins the property that makes re-exporting safe: a user who imports BOTH the package and `tdalab.*` gets no
  * ambiguity for re-exported objects/classes/types (it only arises for defs, which are never re-exported).
  */
class TDAlabExportsSpec extends Specification:
  private val root = Paths.get("src/main/scala/org/appliedtopology/tda4j")
  private val excluded = Set("TDAlab", "SimplexOps", "SimplexInstances", "CubeInstances")
  private val decl =
    """^(?:(?:sealed|final|case|abstract|open|opaque|transparent|infix)\s+)*(?:class|trait|object|type|enum)\s+([^\s\[\(:=]+)""".r

  private def scalaFiles: Seq[Path] =
    Files.walk(root).iterator.asScala.filter(_.toString.endsWith(".scala")).toSeq

  private def publicNames(packageLines: Seq[String]): Set[String] =
    scalaFiles.flatMap { f =>
      val lines = Files.readAllLines(f).asScala.toSeq
      if lines.take(30).filter(_.startsWith("package ")) != packageLines then Nil
      else lines.flatMap(l => decl.findFirstMatchIn(l).map(_.group(1)))
    }.toSet -- excluded

  private val exported: Set[String] =
    val text = Files.readString(root.resolve("package.scala"))
    val block =
      text.substring(text.indexOf("// BEGIN generated re-exports"), text.indexOf("// END generated re-exports"))
    """(?m)^\s{4}([^\s,]+),?\s*$""".r.findAllMatchIn(block).map(_.group(1)).toSet

  private val addons = Seq("groups") // TEMP: Seq("sset") once groups and the simplicial-set files move there
  private def addonNames =
    addons.flatMap(a => publicNames(Seq("package org.appliedtopology.tda4j", s"package $a"))).toSet

  "TDAlab's re-export block" should {
    "cover every public top-level type of the root package" in {
      (publicNames(Seq("package org.appliedtopology.tda4j")) + "∆" -- exported) must beEqualTo(Set.empty[String])
    }
    "cover every public top-level type of the add-on packages" in {
      (addonNames -- exported) must beEqualTo(Set.empty[String])
    }
    "contain nothing that no longer exists" in {
      val all = publicNames(Seq("package org.appliedtopology.tda4j")) ++ addonNames + "∆"
      (exported -- all) must beEqualTo(Set.empty[String])
    }
  }
