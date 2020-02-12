name := "zortal bot"
version := "0.0.1"

scalaVersion := "2.13.1"

scalacOptions ++= Seq(
  "-feature",
  "-deprecation",
  "-unchecked",
  "-language:postfixOps",
  "-language:higherKinds",
  "-Ywarn-unused:_,imports",
  "-Ywarn-unused:imports",
)

val ZIOVersion   = "1.0.0-RC17"
val SttpVersion  = "2.0.0-RC9"
val CirceVersion = "0.12.3"

libraryDependencies ++= Seq(
  "dev.zio" %% "zio"         % ZIOVersion,
  "dev.zio" %% "zio-streams" % ZIOVersion,
  // "dev.zio"                %% "zio-config"          % ZIOVersion,
  "dev.zio"                      %% "zio-test"                      % ZIOVersion % "test",
  "dev.zio"                      %% "zio-test-sbt"                  % ZIOVersion % "test",
  "com.softwaremill.sttp.client" %% "async-http-client-backend-zio" % SttpVersion,
  "com.softwaremill.sttp.client" %% "circe"                         % SttpVersion,
  "io.circe"                     %% "circe-core"                    % CirceVersion,
  "io.circe"                     %% "circe-parser"                  % CirceVersion,
  "org.scala-lang.modules"       %% "scala-xml"                     % "1.2.0",
  "org.jsoup"                    % "jsoup"                          % "1.11.2",
)

addCompilerPlugin("org.typelevel" %% "kind-projector"     % "0.11.0" cross CrossVersion.full)
addCompilerPlugin("com.olegpy"    %% "better-monadic-for" % "0.3.1")

testFrameworks := Seq(new TestFramework("zio.test.sbt.ZTestFramework"))

assemblyJarName in assembly := "zortal.jar"

assemblyMergeStrategy in assembly := {
  case PathList("META-INF", "MANIFEST.MF") => MergeStrategy.discard
  case _                                   => MergeStrategy.first
}

enablePlugins(GraalVMNativeImagePlugin)

// graalVMNativeImageOptions ++= Seq(
//   "--report-unsupported-elements-at-runtime",
//   "--allow-incomplete-classpath",
//   "--no-fallback",
// )

// graalVMNativeImageGraalVersion := Some("19.3.1-java8")
