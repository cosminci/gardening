ThisBuild / scalaVersion := "3.8.4"
ThisBuild / organization := "com.cosminci.gardening"
ThisBuild / version      := sys.env.getOrElse("GARDENING_APP_VERSION", "0.0.0-dev")

val tapirV   = "1.13.31"
val apispecV = "0.11.10"
val magnumV  = "1.3.1"
val sqliteV  = "3.53.4.0"
val munitV   = "1.3.6"

lazy val root = (project in file("."))
  .enablePlugins(JavaAppPackaging, JlinkPlugin)
  .settings(
    name                := "gardening-backend",
    Compile / mainClass := Some("gardening.app.Main"),
    // Bundle a minimal jlink runtime (only the modules the app needs) so the image ships a ~60MB
    // JRE instead of a full 190MB one. Ignore jdeps' missing-optional-dependency errors that a
    // classpath (non-modular) app on Netty/Magnum otherwise trips.
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
    // Capture-checking syntax (`^`) is not yet understood by the scalameta parser scalafix
    // uses, so the one capability wrapper that needs it opts out of scalafix. It is still held
    // to -Werror, capture checking, WartRemover, and 100% coverage.
    Compile / scalafix / unmanagedSources := (Compile / unmanagedSources).value
      .filterNot(_.getName == "Database.scala"),
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
    // Composition root, HTTP transport wiring, and endpoint/DTO declarations carry no
    // branching domain logic; behaviour is proven through the ports they delegate to.
    coverageExcludedPackages := "gardening\\.app\\..*;gardening\\.adapters\\.http\\..*",
    Test / fork              := true,
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
      "com.augustnagro"               %% "magnum"                  % magnumV,
      "org.xerial"                     % "sqlite-jdbc"             % sqliteV,
      "org.scalameta"                 %% "munit"                   % munitV % Test
    )
  )
