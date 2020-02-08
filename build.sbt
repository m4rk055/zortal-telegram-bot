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

val ZIOVersion    = "1.0.0-RC17"
val Http4sVersion = "0.21.0-RC2"

libraryDependencies ++= Seq(
  "dev.zio" %% "zio"         % ZIOVersion,
  "dev.zio" %% "zio-streams" % ZIOVersion,
  // "dev.zio"                %% "zio-config"          % ZIOVersion,
  "dev.zio"                %% "zio-test"            % ZIOVersion % "test",
  "dev.zio"                %% "zio-test-sbt"        % ZIOVersion % "test",
  "dev.zio"                %% "zio-interop-cats"    % "2.0.0.0-RC10",
  "org.http4s"             %% "http4s-dsl"          % Http4sVersion,
  "org.http4s"             %% "http4s-circe"        % Http4sVersion,
  "org.http4s"             %% "http4s-blaze-client" % Http4sVersion,
  "co.fs2"                 %% "fs2-core"            % "2.2.1",
  "io.circe"               %% "circe-core"          % "0.12.3",
  "org.scala-lang.modules" %% "scala-xml"           % "1.2.0",
  "org.jsoup"              % "jsoup"                % "1.11.2",
)

addCompilerPlugin("org.typelevel" %% "kind-projector"     % "0.11.0" cross CrossVersion.full)
addCompilerPlugin("com.olegpy"    %% "better-monadic-for" % "0.3.1")

testFrameworks := Seq(new TestFramework("zio.test.sbt.ZTestFramework"))

assemblyJarName in assembly := "zortal.jar"
