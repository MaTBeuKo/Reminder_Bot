import sbt.Keys.libraryDependencies

ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "2.12.15"
enablePlugins(JavaAppPackaging, AshScriptPlugin)
name := "reminder-bot"
version := "0.1.1"

maintainer := "Sologub Matvey <matbeuko@mail.ru>"
dockerBaseImage := "openjdk:11-jre-slim"
dockerUpdateLatest := true
dockerUsername := Some("matbeuko")

libraryDependencies += "org.typelevel" %% "cats-effect" % "3.5.4"
addCompilerPlugin("org.typelevel" % "kind-projector" % "0.13.2" cross CrossVersion.full)
addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1")

libraryDependencies += "com.softwaremill.sttp.client3" %% "core" % "3.10.1"
libraryDependencies += "com.softwaremill.sttp.client3" %% "fs2" % "3.10.1"
libraryDependencies += "com.softwaremill.sttp.client3" %% "async-http-client-backend-cats" % "3.9.7"

libraryDependencies += "io.circe" %% "circe-generic" % "0.14.9"
libraryDependencies += "io.circe" %% "circe-parser" % "0.14.9"
libraryDependencies += "com.softwaremill.sttp.client3" %% "circe" % "3.10.1"
libraryDependencies += "com.typesafe.akka" %% "akka-http" % "10.5.0"

libraryDependencies ++= Seq(
  "biz.enef" %% "slogging-slf4j" % "0.6.1",
  "org.slf4j" % "slf4j-simple" % "1.7.+"  // or another slf4j implementation
)

lazy val doobieVersion = "1.0.0-RC4"
libraryDependencies ++= Seq(

  // Start with this one
  "org.tpolecat" %% "doobie-core"      % "1.0.0-RC4",

  // And add any of these as needed
  "org.tpolecat" %% "doobie-h2"        % doobieVersion,          // H2 driver 1.4.200 + type mappings.
  "org.tpolecat" %% "doobie-hikari"    % doobieVersion,          // HikariCP transactor.
  "org.tpolecat" %% "doobie-postgres"  % doobieVersion,          // Postgres driver 42.6.0 + type mappings.
  "org.tpolecat" %% "doobie-specs2"    % doobieVersion % "test", // Specs2 support for typechecking statements.
  "org.tpolecat" %% "doobie-scalatest" % doobieVersion % "test"  // ScalaTest support for typechecking statements.
)
libraryDependencies += "com.github.pureconfig" %% "pureconfig" % "0.17.8"

libraryDependencies += "com.github.cb372" %% "cats-retry" % "3.1.3"

libraryDependencies += "org.augustjune" %% "canoe" % "0.6.0"

// https://mvnrepository.com/artifact/org.mockito/mockito-scala
libraryDependencies += "org.mockito" %% "mockito-scala" % "1.17.37" % Test
// https://mvnrepository.com/artifact/org.powermock/powermock-api-mockito2
libraryDependencies += "org.powermock" % "powermock-api-mockito2" % "2.0.9" % Test

fork := true

lazy val root = (project in file("."))
  .settings(
    name := "reminder-bot"
  )
