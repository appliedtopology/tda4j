name := "TDA4j"

version := "0.0.1"

libraryDependencies += "org.specs2"       %% "specs2-core" % "5.2.0" % "test"
libraryDependencies += ("org.scala-graph" %% "graph-core"  % "1.13.5").cross(
  CrossVersion.for3Use2_13
)
libraryDependencies += "org.openjdk.jol" % "jol-core" % "0.17"
libraryDependencies += "org.apache.commons" % "commons-numbers-combinatorics" % "1.1"

scalaVersion := "3.2.0"

lazy val root = (project in file("."))
  .enablePlugins(
    SiteScaladocPlugin,
    ParadoxSitePlugin,
    ParadoxMaterialThemePlugin,
    GitHubPagesPlugin
  )
  .settings(
    name := "TDA4j",
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
    // ghpagesNoJekyll := true
    gitHubPagesOrgName := "appliedtopology",
    gitHubPagesRepoName := "tda4j",
    gitHubPagesSiteDir := baseDirectory.value / "target/site"
  )

Compile / doc / scalacOptions := Seq("-diagrams")

mimaPreviousArtifacts := Set.empty