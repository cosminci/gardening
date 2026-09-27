package gardening.app

import gardening.adapters.http.OpenApiDocs
import scala.util.chaining._

import java.nio.file.{Files, Path}

object GenerateOpenApi:

  def main(args: Array[String]): Unit =
    val target = Path.of(args.headOption.getOrElse("../contract/openapi.yaml"))
    Option(target.getParent).foreach(parent => Files.createDirectories(parent).pipe(_ => ()))
    val _ = Files.writeString(target, OpenApiDocs.yaml)
