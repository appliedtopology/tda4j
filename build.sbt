name := "TDA4j"
organization := "org.appliedtopology"
scalaVersion := "3.9.0"

versionScheme := Some("semver-spec")

assembly / assemblyJarName := s"${name.value}-${version.value}-assembly.jar"

libraryDependencies += "org.specs2"        %% "specs2-core"                   % "5.5.1" % "test"
libraryDependencies += "org.specs2"        %% "specs2-matcher-extra"          % "5.5.1" % "test"
libraryDependencies += "org.specs2"        %% "specs2-scalacheck"             % "5.5.1" % "test"
libraryDependencies += "org.apache.commons" % "commons-numbers-combinatorics" % "1.1"
libraryDependencies += "org.apache.commons" % "commons-math3"                 % "3.6.1"
libraryDependencies += "com.eatthepath"     % "jvptree"                       % "0.2"
libraryDependencies += "com.dreizak"        % "miniball"                      % "1.0.3"
libraryDependencies +=
  "org.scala-lang.modules"              %% "scala-parallel-collections" % "1.0.4"
libraryDependencies += "org.scalacheck" %% "scalacheck"                 % "1.17.0" % "test"
// CLI argument parsing for the `cli` package -- chosen over decline specifically because it has zero transitive
// dependencies (decline pulls in cats-core, which nothing else in this codebase uses) -- see
// .claude/WORKLOG-cli-executable.md.
libraryDependencies += "org.rogach" %% "scallop" % "6.0.0"

lazy val root = (project in file("."))
  .enablePlugins(
    SiteScaladocPlugin,
    ParadoxSitePlugin,
    ParadoxMaterialThemePlugin,
    GitHubPagesPlugin
  )
  .settings(
    // Compiler options: language features (implicitConversions, adhocExtensions) and warning flags.
    scalacOptions ++= List(
      "-source:future",
      "-language:experimental.modularity",
      "-language:implicitConversions",
      "-language:adhocExtensions",
      "-feature",
      "-deprecation",
      "-unchecked"
    ),
    paradoxGroups := Map("Language" -> Seq("Scala", "Java", "Matlab")),
    Compile / paradoxMaterialTheme :=
      ParadoxMaterialTheme(),
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
    gitHubPagesSiteDir := baseDirectory.value / "target/site",
    // Both settings are needed, not just one: `Compile / mainClass` is what `sbt run` uses; `assembly /
    // mainClass` is what sbt-assembly writes into the fat jar's manifest (`java -jar ... `). Neither is inferred
    // from the other.
    Compile / mainClass := Some("org.appliedtopology.tda4j.cli.TDA4jCLI"),
    assembly / mainClass := Some("org.appliedtopology.tda4j.cli.TDA4jCLI")
  )

// Workaround for XML versioning issues
// See: https://github.com/scala/bug/issues/12632
libraryDependencySchemes ++= Seq(
  "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always
)

Compile / doc / scalacOptions := Seq("-diagrams")

mimaPreviousArtifacts := Set.empty
