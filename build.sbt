ThisBuild / version := "0.1.1-SNAPSHOT"

ThisBuild / scalaVersion := "2.13.18"

lazy val root = (project in file("."))
  .enablePlugins(BuildInfoPlugin)
  .settings(
    name := "shogi-puzzler",
    run / fork := true,
    assembly / mainClass := Some("shogi.puzzler.maintenance.MaintenanceApp"),
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "shogi.puzzler"
  )

libraryDependencies ++= Seq(
  "com.lihaoyi" %% "cask" % "0.11.3",
  "com.lihaoyi" %% "requests" % "0.9.0",
  "com.lihaoyi" %% "ujson" % "4.4.1",
  "com.lihaoyi" %% "scalatags" % "0.13.1",
  "org.mongodb.scala" %% "mongo-scala-driver" % "5.3.1",
  "ch.qos.logback" % "logback-classic" % "1.5.16",
  "org.fusesource.jansi" % "jansi" % "2.4.1",

  "com.typesafe" % "config" % "1.4.5",
  "io.github.wandererxii" %% "scalashogi" % "12.2.1",
  "com.microsoft.playwright" % "playwright" % "1.49.0",

  "org.scalatest" %% "scalatest" % "3.2.17" % Test
)

assembly / assemblyMergeStrategy := {
  case PathList("META-INF", "services", _*) => MergeStrategy.concat
  case PathList("META-INF", xs @ _*) => MergeStrategy.discard
  case x => MergeStrategy.first
}

resolvers ++= Seq(
  "lila-maven" at "https://raw.githubusercontent.com/lichess-org/lila-maven/master"
)