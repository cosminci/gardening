package gardening.app

import java.nio.file.{Files, Path}

/**
 * Writes the tapir-derived OpenAPI document to the path given as the first argument (default:
 * ../contract/openapi.yaml). The contract-drift check regenerates and diffs it.
 */
object GenerateOpenApi:
  def main(args: Array[String]): Unit =
    val target = Path.of(args.headOption.getOrElse("../contract/openapi.yaml"))
    Option(target.getParent).foreach { parent =>
      val _ = Files.createDirectories(parent)
    }
    val _ = Files.writeString(target, gardening.openapi.OpenApiDocs.yaml)
