name := "TDA4j"
organization := "org.appliedtopology"
version := "0.1.0-alpha"
scalaVersion := "3.5.0"

versionScheme := Some("semver-spec")

pomIncludeRepository := { _ => false }
publishTo := sonatypePublishToBundle.value
sonatypeCredentialHost := "s01.oss.sonatype.org"

libraryDependencies += "org.specs2"       %% "specs2-core"          % "5.5.1" % "test"
libraryDependencies += "org.specs2"       %% "specs2-matcher-extra" % "5.5.1" % "test"
libraryDependencies += "org.specs2"       %% "specs2-scalacheck"    % "5.5.1" % "test"
libraryDependencies += ("org.scala-graph" %% "graph-core"           % "1.13.5").cross(
  CrossVersion.for3Use2_13
)
libraryDependencies += "org.openjdk.jol"    % "jol-core"                      % "0.17"
libraryDependencies += "org.apache.commons" % "commons-numbers-combinatorics" % "1.1"
libraryDependencies += "org.apache.commons" % "commons-math3"                 % "3.6.1"
libraryDependencies +=
  "org.scala-lang.modules"                             %% "scala-parallel-collections" % "1.0.4"
libraryDependencies += "org.scalacheck"                %% "scalacheck"                 % "1.17.0" % "test"
libraryDependencies += "org.scalaz"                    %% "scalaz-core"                % "7.3.6"
libraryDependencies += "org.scalaz"                    %% "scalaz-scalacheck-binding"  % "7.4.0-M14"
libraryDependencies += "org.bitbucket.inkytonik.kiama" %% "kiama"                      % "2.5.1"
libraryDependencies += "org.bitbucket.inkytonik.kiama" %% "kiama-extras"               % "2.5.1"

lazy val root = (project in file("."))
  .enablePlugins(
    SiteScaladocPlugin,
    ParadoxSitePlugin,
    ParadoxMaterialThemePlugin,
    GitHubPagesPlugin
  )
  .settings(
    // these options make 3.5.0 use the given resolution algorithms planned for 3.7.x.
    scalacOptions ++= List("-source:future", "-language:experimental.modularity"),
    Compile / paradoxMaterialTheme := {
      ParadoxMaterialTheme()
    },
    Compile / paradoxProperties ++= Map(
      "project.url" -> "https://appliedtopology.github.io/tda4j",
      "github.base_url" -> s"https://github.com/appliedtopology/tda4j/tree/${version.value}",
      "scaladoc.base_url" -> s"latest/api",
      "scaladoc.tda4j.base_url" -> s"latest/api"
    ),
    Compile / paradoxMaterialTheme ~= {
      _.withoutSearch()
    },
    Compile / paradoxMaterialTheme ~= {
      _.withColor("indigo", "blue")
    },
    Compile / paradoxMaterialTheme ~= {
      _.withCopyright("MIT License © Mikael Vejdemo-Johansson, Daniel Hope")
    },
    Compile / paradoxMaterialTheme ~= {
      _.withRepository(uri("https://github.com/appliedtopology/tda4j"))
    },
    gitHubPagesOrgName := "appliedtopology",
    gitHubPagesRepoName := "tda4j",
    gitHubPagesSiteDir := baseDirectory.value / "target/site"
  )

// Workaround for XML versioning issues
// See: https://github.com/scala/bug/issues/12632
libraryDependencySchemes ++= Seq(
  "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always
)

Compile / doc / scalacOptions := Seq("-diagrams")

mimaPreviousArtifacts := Set.empty
