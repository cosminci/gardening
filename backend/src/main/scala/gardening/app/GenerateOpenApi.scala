package gardening.app

import gardening.adapters.http.OpenApiDocs

import java.nio.file.{Files, Path}

object GenerateOpenApi:

  def main(args: Array[String]): Unit =
    val target = Path.of(args.headOption.getOrElse("../contract/openapi.yaml"))
    Option(target.getParent).foreach { parent =>
      val _ = Files.createDirectories(parent)
    }
    val _ = Files.writeString(target, OpenApiDocs.yaml)
