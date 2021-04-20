Global / useSuperShell := false
Global / onChangedBuildSource := ReloadOnSourceChanges
Global / excludeLintKeys += publishMavenStyle

val scalaVersions = List("2.13.4", "2.12.12")

ThisBuild / version := "0.0.10-SNAPSHOT"
ThisBuild / organization := "com.github.IndiscriminateCoding"
ThisBuild / scalaVersion := scalaVersions.head

ThisBuild / libraryDependencies +=
  compilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1")

val scala212options = Seq(
  "-deprecation",
  "-explaintypes",
  "-feature",
  "-language:higherKinds",
  "-unchecked",
  "-Xcheckinit",
  "-Xlint:unsound-match",
  "-Yno-adapted-args",
  "-Ypartial-unification",
  "-Ywarn-dead-code",
  "-Ywarn-infer-any",
  "-Ywarn-nullary-override",
  "-Ywarn-nullary-unit",
  "-Ywarn-numeric-widen",
  "-Ywarn-value-discard"
)

val scala213options = Seq(
  "-deprecation",
  "-explaintypes",
  "-feature",
  "-language:higherKinds",
  "-unchecked",
  "-Xcheckinit",
  "-Xlint:adapted-args",
  "-Xlint:infer-any",
  "-Xlint:nullary-unit",
  "-Ywarn-dead-code",
  "-Ywarn-numeric-widen",
  "-Ywarn-value-discard"
)

val http4sVersion = "0.21.21"
val jettyVersion = "11.0.2"
val scalatestVersion = "3.2.8"

lazy val jetty4s = (project in file("."))
  .aggregate(common, client, server)
  .settings(
    publish / skip := true,
    crossScalaVersions := Nil
  )

lazy val common = (project in file("common"))
  .settings(
    crossScalaVersions := scalaVersions,
    scalacOptions := {
      if (scalaVersion.value.startsWith("2.12.")) scala212options
      else scala213options
    },
    name := "jetty4s-common",
    libraryDependencies += "org.http4s" %% "http4s-core" % http4sVersion
  )

lazy val client = (project in file("client"))
  .settings(
    crossScalaVersions := scalaVersions,
    scalacOptions := {
      if (scalaVersion.value.startsWith("2.12.")) scala212options
      else scala213options
    },
    name := "jetty4s-client",
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-client" % http4sVersion,
      "org.eclipse.jetty" % "jetty-client" % jettyVersion,

      "org.http4s" %% "http4s-blaze-server" % http4sVersion % Test,
      "org.scalatest" %% "scalatest" % scalatestVersion % Test
    )
  )
  .dependsOn(common)

lazy val server = (project in file("server"))
  .settings(
    crossScalaVersions := scalaVersions,
    scalacOptions := {
      if (scalaVersion.value.startsWith("2.12.")) scala212options
      else scala213options
    },
    name := "jetty4s-server",
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-server" % http4sVersion,
      "org.eclipse.jetty" % "jetty-server" % jettyVersion,
      "org.eclipse.jetty" % "jetty-alpn-server" % jettyVersion,
      "org.eclipse.jetty.http2" % "http2-server" % jettyVersion,

      "ch.qos.logback" % "logback-classic" % "1.2.3" % Test,
      "org.scalatest" %% "scalatest" % scalatestVersion % Test
    )
  )
  .dependsOn(common, client % "test")

// sonatype-related settings
ThisBuild / publishTo := sonatypePublishTo.value
ThisBuild / publishMavenStyle := true
ThisBuild / licenses :=
  Seq("BSD3" -> url("https://raw.githubusercontent.com/IndiscriminateCoding/jetty4s/dev/LICENSE"))
ThisBuild / homepage := Some(url("https://github.com/IndiscriminateCoding/jetty4s"))
ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/IndiscriminateCoding/jetty4s"),
    "scm:git@github.com:IndiscriminateCoding/jetty4s.git"
  )
)
ThisBuild / developers := List(Developer(
  id = "IndiscriminateCoding",
  name = "IndiscriminateCoding",
  email = "28496046+IndiscriminateCoding@users.noreply.github.com",
  url = url("https://github.com/IndiscriminateCoding/")
))

