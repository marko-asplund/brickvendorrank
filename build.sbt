name := "legosearchtool"

version := "0.1"

scalaVersion := "2.12.8"

scalacOptions := Seq("-feature", "-encoding", "utf8",
  "-deprecation", "-unchecked", "-Xlint", "-Yrangepos", "-Ypartial-unification", "-explaintypes")

val http4sVersion = "0.20.0-M6"
val circeVersion = "0.11.1"
val specs2Version = "4.1.0"
val logbackVersion = "1.2.3"

libraryDependencies ++= Seq(
  "org.http4s"          %% "http4s-dsl"           % http4sVersion,
  "org.http4s"          %% "http4s-blaze-client"  % http4sVersion,
  "org.http4s"          %% "http4s-circe"         % http4sVersion,
  "io.circe"            %% "circe-generic"        % circeVersion,
  "io.circe"            %% "circe-generic-extras" % circeVersion,
  "io.circe"            %% "circe-literal"        % circeVersion,
  "ch.qos.logback"      %  "logback-classic"      % logbackVersion,
  "org.log4s"           %% "log4s"                % "1.7.0",
  "org.specs2"          %% "specs2-core"          % specs2Version % "test"
)

addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.full)
