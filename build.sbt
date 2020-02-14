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
  "dev.zio"                      %% "zio"                           % ZIOVersion,
  "dev.zio"                      %% "zio-streams"                   % ZIOVersion,
  "com.github.pureconfig"        %% "pureconfig"                    % "0.12.2",
  "dev.zio"                      %% "zio-test"                      % ZIOVersion % "test",
  "dev.zio"                      %% "zio-test-sbt"                  % ZIOVersion % "test",
  "com.softwaremill.sttp.client" %% "async-http-client-backend-zio" % SttpVersion,
  "com.softwaremill.sttp.client" %% "circe"                         % SttpVersion,
  "io.circe"                     %% "circe-core"                    % CirceVersion,
  "io.circe"                     %% "circe-parser"                  % CirceVersion,
  "org.scala-lang.modules"       %% "scala-xml"                     % "1.2.0",
  "org.jsoup"                    % "jsoup"                          % "1.11.2",
  "com.google.cloud"             % "google-cloud-firestore"         % "1.32.3",
)

addCompilerPlugin("org.typelevel" %% "kind-projector"     % "0.11.0" cross CrossVersion.full)
addCompilerPlugin("com.olegpy"    %% "better-monadic-for" % "0.3.1")

addCommandAlias("fix", "all compile:scalafix test:scalafix")

testFrameworks := Seq(new TestFramework("zio.test.sbt.ZTestFramework"))

scalafixDependencies in ThisBuild += "com.nequissimus" %% "sort-imports" % "0.3.2"

assemblyJarName in assembly := "zortal.jar"

mainClass in assembly := Some("net.zortal.telegram.bot.Main")

// logLevel in assembly := Level.Debug

assemblyMergeStrategy in assembly := {
  case PathList("META-INF", xs @ _*) => MergeStrategy.discard
  case "module-info.class"           => MergeStrategy.filterDistinctLines
  case _                             => MergeStrategy.deduplicate
}

enablePlugins(GraalVMNativeImagePlugin)

// graalVMNativeImageOptions ++= Seq(
//   "--report-unsupported-elements-at-runtime",
//   "--allow-incomplete-classpath",
//   "--no-fallback",
// )

// graalVMNativeImageGraalVersion := Some("19.3.1-java8")
