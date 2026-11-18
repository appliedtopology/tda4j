// A self-contained REPL jar for demos and teaching: `java -jar tda4j-demo-<version>.jar` opens a Scala 3 prompt with the
// library on its classpath and the imports already done. The only thing a participant needs is a Java runtime.
//
// This is a SEPARATE sbt build on purpose (its own `project/`, not a subproject of the root): the Scala compiler
// and the REPL never become a dependency of the library, and the root build's CI, release, MiMa and coverage runs do
// not see it. It depends on a version of the library, never on the root's sources:
//
//   (cd .. && sbt publishLocal)     # the checkout's own version.sbt, into ~/.ivy2/local
//   sbt assembly                    # demo/target/out/.../tda4j-demo-<version>.jar
//
//   TDA4J_VERSION=0.6.1 sbt assembly    # the same, from a RELEASE on Maven Central
//
// Details: README.md next to this file.

ThisBuild / scalaVersion := "3.9.0"

name := "tda4j-demo"
organization := "org.appliedtopology"

// The library version to bundle: TDA4J_VERSION, else the checkout's version.sbt.
val tda4jVersion: String = sys.env.getOrElse(
  "TDA4J_VERSION", {
    val versionSbt = file("../version.sbt")
    """"([^"]+)"""".r
      .findFirstMatchIn(IO.read(versionSbt))
      .map(_.group(1))
      .getOrElse(sys.error(s"no version found in $versionSbt; set TDA4J_VERSION"))
  }
)

version := tda4jVersion

// No sbt disk cache in this build. With it, `assembly` for one TDA4J_VERSION returned the jar an earlier run had built for
// another one: under the earlier version's name, with the earlier library inside. (`WORKLOG-demo-assembly-cache.md`.)
Global / cacheStores := Nil

libraryDependencies += "org.appliedtopology" %% "tda4j"       % tda4jVersion
libraryDependencies += "org.scala-lang"      %% "scala3-repl" % scalaVersion.value

Compile / mainClass := Some("org.appliedtopology.tda4j.demo.DemoRepl")
assembly / mainClass := Some("org.appliedtopology.tda4j.demo.DemoRepl")
assembly / assemblyJarName := s"tda4j-demo-${tda4jVersion}.jar"

// JDK 24+ asks before a library (jline's terminal provider) calls native code; this answers for the jar.
assembly / packageOptions += Package.ManifestAttributes("Enable-Native-Access" -> "ALL-UNNAMED")

assembly / assemblyMergeStrategy := {
  case PathList("META-INF", "versions", _, "module-info.class") => MergeStrategy.discard
  case "module-info.class"                                      => MergeStrategy.discard
  // Several jars register providers under the same service name (jline's terminals, for one).
  case PathList("META-INF", "services", _*) => MergeStrategy.concat
  // The REPL carries its own copy of pprint's `CollectionName` (to print collections); pprint itself, which comes in
  // with cask, has another. Either serves both: they answer the same question about a collection's name.
  case PathList("scala", "collection", "internal", "pprint", _*) => MergeStrategy.first
  case other                                                     => (assembly / assemblyMergeStrategy).value(other)
}

// The same REPL as a web page (README.md, "In a web page"): `sbt webSite` fills demo/target/web with the page, the
// classes the page's Java runtime cannot run as they are, rewritten, and the assembly in two jars. The page is
// published to the docs site, a git branch, and git keeps every version of every file it is given: one 73 MB jar
// per publish would add 73 MB to that branch's history each time. So the page's jars are cut where the changes are:
//
//   - `tda4j-demo-project.jar`: everything under `org/appliedtopology/tda4j/` (the library and the demo's own
//     classes), which changes with every publish: about 6 MB;
//   - `tda4j-demo-libs.jar`: everything else (the Scala compiler, cats, the viewer's web server, ...), which changes
//     only when a dependency does: about 62 MB, written the same bytes every time, so that git stores it once.
//
// Together they hold exactly what the assembly holds (`splitAssembly` checks it). The rewriting program is in the
// assembly itself (`WebPatch`) and reads the jar it runs from, so it runs from the jar just built.
lazy val webSite = taskKey[File](
  "The demo REPL as a static web site: the page, the jars cut where they change, the classes rewritten for the page's Java runtime"
)

// The part of the assembly that is this repository's own: the library and the demo's classes.
val projectPrefix = "org/appliedtopology/tda4j/"

/** Cuts the assembly `whole` into `project` (the entries under [[projectPrefix]], with the assembly's manifest) and
  * `libs` (the rest, with a manifest that names nothing that changes between builds), and checks that every file of the
  * assembly is in exactly one of them, byte for byte. Both jars are written the same way every time: entries sorted by
  * name, one fixed time, the same compression.
  */
def splitAssembly(whole: File, project: File, libs: File): Unit = {
  import java.io.{ByteArrayOutputStream, FileOutputStream}
  import java.time.LocalDateTime
  import java.util.jar.{Attributes, Manifest}
  import java.util.zip.{ZipEntry, ZipFile, ZipOutputStream}
  import scala.jdk.CollectionConverters.*

  val zip = new ZipFile(whole)
  try {
    val entries = zip.entries.asScala.toVector.sortBy(_.getName)
    val manifestName = "META-INF/MANIFEST.MF"
    // `org/` and `org/appliedtopology/` are the folders of both halves.
    def inProject(name: String) =
      name.startsWith(projectPrefix) || (name.endsWith("/") && projectPrefix.startsWith(name))
    def inLibs(name: String) = !name.startsWith(projectPrefix)

    val libsManifest = new Manifest()
    libsManifest.getMainAttributes.put(Attributes.Name.MANIFEST_VERSION, "1.0")
    val projectManifest = new Manifest(zip.getInputStream(zip.getEntry(manifestName)))
    // The page needs the version (`WebRepl.version`) and not the way into the REPL's terminal.
    projectManifest.getMainAttributes.remove(Attributes.Name.MAIN_CLASS)
    def bytes(m: Manifest) = { val b = new ByteArrayOutputStream; m.write(b); b.toByteArray }

    def write(target: File, manifest: Manifest, keep: String => Boolean): Unit = {
      val out = new ZipOutputStream(new FileOutputStream(target))
      def put(name: String, content: Array[Byte]): Unit = {
        val entry = new ZipEntry(name)
        // One time for every entry, in nobody's time zone. Not 1980-01-01 00:00:00: the zip classes take that for "before
        // 1980" and add an extended timestamp field with a UTC time, which differs from one time zone to the next.
        entry.setTimeLocal(LocalDateTime.of(2000, 1, 1, 0, 0, 0))
        out.putNextEntry(entry)
        out.write(content)
        out.closeEntry()
      }
      try {
        put(manifestName, bytes(manifest))
        entries.filter(e => e.getName != manifestName && keep(e.getName)).foreach { e =>
          put(e.getName, if (e.isDirectory) Array.emptyByteArray else zip.getInputStream(e).readAllBytes())
        }
      } finally out.close()
    }
    IO.createDirectory(project.getParentFile)
    write(project, projectManifest, inProject)
    write(libs, libsManifest, inLibs)

    // Every file of the assembly, once, with the same bytes.
    def files(jar: File): Map[String, Long] = {
      val z = new ZipFile(jar)
      try
        z.entries.asScala
          .filterNot(e => e.isDirectory || e.getName == manifestName)
          .map(e => e.getName -> e.getCrc)
          .toMap
      finally z.close()
    }
    val (inProjectJar, inLibsJar) = (files(project), files(libs))
    val both = inProjectJar.keySet.intersect(inLibsJar.keySet)
    if (both.nonEmpty) sys.error(s"the page's two jars both hold ${both.take(3).mkString(", ")}")
    val expected =
      entries.filterNot(e => e.isDirectory || e.getName == manifestName).map(e => e.getName -> e.getCrc).toMap
    if (expected != inProjectJar ++ inLibsJar)
      sys.error(s"the page's two jars do not hold exactly what ${whole.getName} holds")
  } finally zip.close()
}

webSite := Def.uncached {
  val _ = assembly.value
  val jar = (assembly / assemblyOutputPath).value
  val site = baseDirectory.value / "target" / "web"
  IO.delete(site)
  IO.copyDirectory(baseDirectory.value / "web", site)
  splitAssembly(jar, site / "tda4j-demo-project.jar", site / "tda4j-demo-libs.jar")
  val java = (file(sys.props("java.home")) / "bin" / "java").getPath
  val patch = site / "tda4j-demo-web-patch.jar"
  val command = Seq(java, "-cp", jar.getPath, "org.appliedtopology.tda4j.demo.WebPatch", patch.getPath)
  val exit = scala.sys.process.Process(command).!
  if (exit != 0) sys.error(s"WebPatch failed (exit $exit): the web page cannot start without its jars")
  streams.value.log.info(s"web site in $site; try it with: node ${site / "serve.mjs"}")
  site
}
