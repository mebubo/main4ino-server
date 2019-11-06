val ProjectVersion = "0.1.0-SNAPSHOT"

val ScalaVersion = "2.12.8"


lazy val root = (project in file("."))
  .settings(
    organization := "org.mauritania",
    name := "main4ino-server",
    version := ProjectVersion,
    scalaVersion := ScalaVersion,
    scalacOptions ++= Seq("-Ypartial-unification"),
    libraryDependencies ++= Dependencies.Dependencies,
    parallelExecution in Test := false,

    coverageMinimum := 95,
    coverageFailOnMinimum := true

  )



