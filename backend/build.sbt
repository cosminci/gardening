ThisBuild / scalaVersion := "3.8.4"
ThisBuild / organization := "com.cosminci.gardening"
ThisBuild / version      := sys.env.getOrElse("GARDENING_APP_VERSION", "0.0.0-dev")

val tapirV   = "1.13.31"
val apispecV = "0.11.10"
val munitV   = "1.3.6"

lazy val root = (project in file("."))
  .enablePlugins(JavaAppPackaging, JlinkPlugin)
  .settings(
    name                := "gardening-backend",
    Compile / mainClass := Some("gardening.app.Main"),
    // Bundle a minimal jlink runtime (only the modules the app needs) so the image ships a ~60MB
    // JRE instead of a full 190MB one. Ignore jdeps' missing-optional-dependency errors that a
    // classpath (non-modular) app on Netty otherwise trips.
    jlinkIgnoreMissingDependency := JlinkIgnore.everything,
    jlinkOptions ++= Seq("--no-header-files", "--no-man-pages", "--strip-debug", "--compress=zip-6"),
    scalacOptions ++= Seq(
      "-encoding",
      "utf8",
      "-feature",
      "-deprecation",
      "-unchecked",
      "-Werror",
      "-Wunused:all",
      "-Wvalue-discard",
      "-java-output-version",
      "25"
    ),
    semanticdbEnabled := true,
    semanticdbVersion := scalafixSemanticdb.revision,
    wartremoverErrors ++= Seq(
      Wart.Null,
      Wart.Return,
      Wart.Var,
      Wart.AsInstanceOf,
      Wart.IsInstanceOf,
      Wart.OptionPartial,
      Wart.TryPartial,
      Wart.EitherProjectionPartial
    ),
    coverageFailOnMinimum      := true,
    coverageMinimumStmtTotal   := 100,
    coverageMinimumBranchTotal := 100,
    coverageExcludedPackages := List(
      "gardening\\.app\\..*" // composition root and build-time OpenAPI writer; exercised by the packaged runtime, not unit tests
    ).mkString(";"),
    Test / fork := true,
    // tapir pulls Netty's `netty-all` aggregate, which drags in codecs this app never uses (and
    // whose jdeps analysis breaks jlink on a missing optional aalto module). Dropping them fixes
    // the jlink build and trims the runtime jars.
    excludeDependencies ++= Seq(
      "io.netty" % "netty-codec-xml",
      "io.netty" % "netty-codec-mqtt",
      "io.netty" % "netty-codec-redis",
      "io.netty" % "netty-codec-smtp",
      "io.netty" % "netty-codec-stomp",
      "io.netty" % "netty-codec-memcache",
      "io.netty" % "netty-codec-haproxy",
      "io.netty" % "netty-codec-protobuf",
      "io.netty" % "netty-codec-marshalling",
      "io.netty" % "netty-transport-rxtx",
      "io.netty" % "netty-transport-sctp",
      "io.netty" % "netty-transport-udt",
      "io.netty" % "netty-handler-ssl-ocsp"
    ),
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.tapir"   %% "tapir-core"              % tapirV,
      "com.softwaremill.sttp.tapir"   %% "tapir-netty-server-sync" % tapirV,
      "com.softwaremill.sttp.tapir"   %% "tapir-json-circe"        % tapirV,
      "com.softwaremill.sttp.tapir"   %% "tapir-openapi-docs"      % tapirV,
      "com.softwaremill.sttp.tapir"   %% "tapir-files"             % tapirV,
      "com.softwaremill.sttp.apispec" %% "openapi-circe-yaml"      % apispecV,
      "org.scalameta"                 %% "munit"                   % munitV % Test,
      "com.softwaremill.sttp.tapir"   %% "tapir-sttp-stub-server"  % tapirV % Test
    )
  )
