import sbt.{Def, _}
import Keys._

object Settings {
  lazy val settings = Seq(
    organization := "org.appliedtopology",
    version := "0.0.1" + sys.props.getOrElse("buildNumber", default="0-SNAPSHOT"),
    scalaVersion := "2.13.8",
    publishMavenStyle := true,
    Test / publishArtifact := false
  )

  lazy val testSettings = Seq(
    Test / fork := false,
    Test / parallelExecution := false
  )

  lazy val itSettings = Defaults.itSettings ++ Seq(
    IntegrationTest / logBuffered := false,
    IntegrationTest / fork := true
  )

/*
  lazy val tda4jSettings = Seq(
    assemblyJarName in assembly := "tda4j-" + version.value + ".jar",
    test in assembly := {},
    target in assembly := file(baseDirectory.value + "/../bin/"),
    assemblyOption in assembly := (assemblyOption in assembly).value.copy(
      includeScala = false,
      includeDependency=true),
    assemblyMergeStrategy in assembly := {
      case PathList("META-INF", xs@_*) => MergeStrategy.discard
      case n if n.startsWith("reference.conf") => MergeStrategy.concat
      case _ => MergeStrategy.first
    }
  )
*/


  lazy val sonatypeSettings = Seq(
    // used as `artifactId`
    name := "tda4j",

    // used as `groupId`
    organization := "org.appliedtopology.org",

    // open source licenses that apply to the project
    licenses := Seq("MIT" -> url("https://opensource.org/licenses/MIT")),

    description := "Topological Data Analysis for the JRE.",

    homepage := Some(url("https://appliedtopology.github.io/tda4j")),
    scmInfo := Some(
      ScmInfo(
        url("https://github.com/appliedtopology/tda4j"),
        "scm:git@github.com:appliedtopology/tda4j.git"
      )
    ),
    developers := List(
      Developer(id="michiexile", name="Mikael Vejdemo-Johansson",
        email="mikael@appliedtopology.org", url=url("https://mikael.johanssons.org"))
    ),

    // publish to the sonatype repository
    //publishTo := sonatypePublishToBundle.value
  )
}
