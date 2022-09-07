import Dependencies._
import Settings._

lazy val tda4j = (project in file(".")).
  settings(Settings.settings: _*).
  settings(Settings.sonatypeSettings: _*).
  //settings(Settings.tda4jSettings: _*).
  configs(Test)

