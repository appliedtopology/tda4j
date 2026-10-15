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
