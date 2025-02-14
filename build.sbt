import sbt.Keys.libraryDependencies

ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "2.12.15"
enablePlugins(JavaAppPackaging, AshScriptPlugin)
name    := "reminder-bot"
version := "0.1.1"

maintainer         := "Sologub Matvey <matbeuko@mail.ru>"
dockerBaseImage    := "openjdk:11-jre-slim"
dockerUpdateLatest := true
dockerUsername     := Some("matbeuko")

//cats effect
libraryDependencies += "org.typelevel"     %% "cats-effect" % "3.5.4"
addCompilerPlugin("org.typelevel" % "kind-projector"     % "0.13.2" cross CrossVersion.full)
addCompilerPlugin("com.olegpy"   %% "better-monadic-for" % "0.3.1")

//sttp
libraryDependencies += "com.softwaremill.sttp.client3" %% "core" % "3.10.1"
libraryDependencies += "com.softwaremill.sttp.client3" %% "fs2"  % "3.10.1"
libraryDependencies += "com.softwaremill.sttp.client3" %% "async-http-client-backend-cats" % "3.9.7"

//circe
libraryDependencies += "io.circe"                      %% "circe-generic" % "0.14.9"
libraryDependencies += "io.circe"                      %% "circe-parser"  % "0.14.9"
libraryDependencies += "com.softwaremill.sttp.client3" %% "circe"         % "3.10.1"

libraryDependencies += "com.typesafe.akka" %% "akka-http" % "10.5.0"

//logs
libraryDependencies ++= Seq(
  "org.typelevel" % "log4cats-core_2.12" % "2.7.0",
  "biz.enef"     %% "slogging-slf4j"     % "0.6.1",
  "org.slf4j"     % "slf4j-simple"       % "1.7.+" // or another slf4j implementation
)

//database
lazy val doobieVersion = "1.0.0-RC4"

libraryDependencies ++= Seq(
  "org.tpolecat" %% "doobie-core"     % "1.0.0-RC4",
  "org.tpolecat" %% "doobie-postgres" % doobieVersion, // Postgres driver 42.6.0 + type mappings.
  "org.tpolecat" %% "doobie-specs2" % doobieVersion % "test", // Specs2 support for typechecking statements.
  "org.tpolecat" %% "doobie-scalatest" % doobieVersion % "test" // ScalaTest support for typechecking statements.
)

//config
libraryDependencies += "com.github.pureconfig" %% "pureconfig" % "0.17.8"

//retry
libraryDependencies += "com.github.cb372" %% "cats-retry" % "3.1.3"

//telegram
libraryDependencies += "org.augustjune" %% "canoe" % "0.6.0"

//tests
libraryDependencies += "org.mockito" %% "mockito-scala" % "1.17.37" % Test

fork := true

lazy val root = (project in file("."))
  .settings(
    name := "reminder-bot"
  )
