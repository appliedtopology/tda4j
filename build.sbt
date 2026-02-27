   ThisBuild / scalaVersion := "3.6.4" // Matches your scala.build version
   ThisBuild / scalacOptions ++= Seq("-language:experimental.modularity") // Compiler options

   lazy val root = (project in file("."))
     .settings(
       name := "TDA4s",
       libraryDependencies ++= Seq(
         "org.scalacheck" %% "scalacheck" % "1.18.1",          // ScalaCheck for property-based testing
         "org.scalatest" %% "scalatest" % "3.2.19",           // ScalaTest framework
         "org.scalatestplus" %% "scalacheck-1-18" % "3.2.19.0" // ScalaTest-ScalaCheck integration
       ),
       Test / fork := true,
       Test / testFrameworks += new TestFramework("org.scalatest.tools.Framework")
     )