name := "TDA4j"
organization := "org.appliedtopology"
scalaVersion := "3.9.0"

versionScheme := Some("semver-spec")

assembly / assemblyJarName := s"${name.value}-${version.value}-assembly.jar"

libraryDependencies += "org.specs2"       %% "specs2-core"          % "5.5.1" % "test"
libraryDependencies += "org.specs2"       %% "specs2-matcher-extra" % "5.5.1" % "test"
libraryDependencies += "org.specs2"       %% "specs2-scalacheck"    % "5.5.1" % "test"
libraryDependencies += ("org.scala-graph" %% "graph-core"           % "1.13.5").cross(
  CrossVersion.for3Use2_13
)
libraryDependencies += "org.apache.commons" % "commons-numbers-combinatorics" % "1.1"
libraryDependencies += "org.apache.commons" % "commons-math3"                 % "3.6.1"
libraryDependencies += "com.eatthepath"     % "jvptree"                       % "0.2"
libraryDependencies += "com.dreizak"        % "miniball"                      % "1.0.3"
libraryDependencies +=
  "org.scala-lang.modules"                             %% "scala-parallel-collections" % "1.0.4"
libraryDependencies += "org.scalacheck"                %% "scalacheck"                 % "1.17.0" % "test"
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
    // implicitConversions: specs2's own matcher/prop DSL (asResultToProp, matcherIsValueCheck, typedValueCheck)
    // is implicit-conversion-based by design -- these fire on essentially every spec file that uses the DSL
    // idiomatically, not on anything project-specific or risky, alongside this project's own deliberate
    // Simplex -> Chain conversion (TDAContext, package.scala). adhocExtensions: SimplicialHomologyContext/
    // CellularHomologyContext are genuinely, permanently subclassed across files by design (TDAContext in
    // package.scala, CubicalHomologyContext in streams/CubicalStream.scala -- see CLAUDE.md's "Persistent
    // homology"/"Cubical complexes" sections), not accidental one-offs; enabled project-wide rather than
    // per-file imports or marking individual classes `open` (a real API-surface decision left to the project
    // lead, not made unilaterally here) given how many call sites this otherwise touches.
    // -feature/-deprecation/-unchecked made permanent (not just a one-off cleanup pass) so a future deprecated-API
    // use or unchecked type test shows up in ordinary `sbt compile`/`sbt test` output going forward, rather than
    // needing these flags re-enabled by hand to notice -- these only print warnings here (no -Xfatal-warnings),
    // so they can't newly fail CI on their own.
    scalacOptions ++= List(
      "-source:future",
      "-language:experimental.modularity",
      "-language:implicitConversions",
      "-language:adhocExtensions",
      "-feature",
      "-deprecation",
      "-unchecked"
    ),
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
    gitHubPagesSiteDir := baseDirectory.value / "target/site"
  )

// Workaround for XML versioning issues
// See: https://github.com/scala/bug/issues/12632
libraryDependencySchemes ++= Seq(
  "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always
)

Compile / doc / scalacOptions := Seq("-diagrams")

mimaPreviousArtifacts := Set.empty
