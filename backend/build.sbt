ThisBuild / scalaVersion := "3.8.4"
ThisBuild / organization := "com.cosminci.gardening"
ThisBuild / version      := sys.env.getOrElse("GARDENING_APP_VERSION", "0.0.0-dev")

val tapirV    = "1.13.31"
val apispecV  = "0.11.10"
val ironV     = "3.3.2"
val munitV    = "1.3.6"
val magnumV   = "1.3.1"
val sqliteV   = "3.49.1.0"
val flywayV   = "13.7.0"
val archUnitV = "1.5.0"
val catsV     = "2.13.0"
val circeV    = "0.14.16"
val monocleV  = "3.3.0"
val slf4jV    = "2.0.19"
val oxV       = "1.0.2"

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
      Wart.EitherProjectionPartial,
      Wart.IterableOps,
      Wart.SeqApply
    ),
    coverageFailOnMinimum      := true,
    coverageMinimumStmtTotal   := 100,
    coverageMinimumBranchTotal := 100,
    coverageExcludedPackages := List(
      "gardening\\.app\\..*", // composition root; exercised by the packaged runtime, not unit tests
      "gardening\\.adapters\\.http\\.OpenApiDocs" // build-time OpenAPI projection
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
      "io.github.iltotore"            %% "iron"                    % ironV,
      "com.augustnagro"               %% "magnum"                  % magnumV,
      "org.xerial"                     % "sqlite-jdbc"              % sqliteV,
      "org.flywaydb"                   % "flyway-core"              % flywayV,
      "org.flywaydb"                   % "flyway-database-nc-sqlite" % flywayV,
      "org.typelevel"                 %% "cats-core"                % catsV,
      "io.circe"                      %% "circe-core"                % circeV,
      "io.circe"                      %% "circe-parser"              % circeV,
      "dev.optics"                    %% "monocle-macro"             % monocleV,
      "com.softwaremill.ox"           %% "core"                      % oxV,
      "org.slf4j"                      % "slf4j-simple"               % slf4jV % Runtime,
      "com.tngtech.archunit"           % "archunit"                 % archUnitV % Test,
      "org.scalameta"                 %% "munit"                   % munitV % Test,
      "com.softwaremill.sttp.tapir"   %% "tapir-sttp-stub-server"  % tapirV % Test
    )
  )
