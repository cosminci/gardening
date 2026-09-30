package gardening.usecases

import gardening.domain.plants.*
import munit.FunSuite
import scodec.bits.ByteVector
import squants.information.Information
import squants.information.InformationConversions.*

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.util.Random
import javax.imageio.ImageIO
import scala.util.chaining.scalaUtilChainingOps

class PhotoThumbnailGeneratorTest extends FunSuite:

  private val maxThumbnailSize: Information = 100.kibibytes
  private val generator                     = PhotoThumbnailGenerator.make(maxThumbnailSize)

  private val budgetCases = Seq(
    "small, easily-compressed" -> solidImage(width = 300, height = 200, color = Color.BLUE).pipe(image => encode(image, format = "png")),
    "large, hard-to-compress"  -> noisyImage(width = 2000, height = 1500).pipe(image => encode(image, format = "jpg"))
  )

  for (description, original) <- budgetCases do
    test(s"should derive a jpeg thumbnail at or under the 100KB target from a $description original"):
      generator.derive(original) match
        case ThumbnailDerivationResult.Derived(thumbnail) =>
          val actualBytes = thumbnail.bytes.length
          assertEquals(thumbnail.mediaType, PhotoMediaType.Jpeg)
          assert(
            actualBytes <= maxThumbnailSize.toBytes,
            s"expected <= ${maxThumbnailSize}, got $actualBytes bytes"
          )
        case ThumbnailDerivationResult.DerivationFailed(reason) => fail(s"expected a derived thumbnail, got $reason")

  test("should not enlarge a source already smaller than every thumbnail size in the ladder"):
    val original = solidImage(width = 300, height = 200, color = Color.BLUE).pipe(image => encode(image, format = "png"))

    generator.derive(original) match
      case ThumbnailDerivationResult.Derived(thumbnail) =>
        val decoded = ImageIO.read(ByteArrayInputStream(thumbnail.bytes.toArray))
        assertEquals(decoded.getWidth, 300)
        assertEquals(decoded.getHeight, 200)
      case ThumbnailDerivationResult.DerivationFailed(reason) => fail(s"expected a derived thumbnail, got $reason")

  test("should flatten a transparent original onto an opaque background"):
    val original = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)
      .tap { image =>
        val graphics = image.createGraphics()
        graphics.setColor(Color(0, 0, 0, 0))
        graphics.fillRect(0, 0, 4, 4)
        graphics.dispose()
      }
      .pipe(image => encode(image, format = "png"))

    generator.derive(original) match
      case ThumbnailDerivationResult.Derived(thumbnail) =>
        val decoded = ImageIO.read(ByteArrayInputStream(thumbnail.bytes.toArray))
        assertEquals(decoded.getRGB(0, 0) & 0x00ffffff, 0x00ffffff)
      case ThumbnailDerivationResult.DerivationFailed(reason) => fail(s"expected a derived thumbnail, got $reason")

  test("should orient the thumbnail to match how a browser renders the original, not how the camera stored it"):
    // real phone photo, stored landscape (4000x3000) with EXIF orientation 6; a browser renders it
    // portrait (3000x4000), and the thumbnail must match that, not the raw stored bytes
    val original = PhotoContent(ByteVector(readResource("rozmarin-orientation-6.jpg")), PhotoMediaType.Jpeg)

    generator.derive(original) match
      case ThumbnailDerivationResult.Derived(thumbnail) =>
        val decoded = ImageIO.read(ByteArrayInputStream(thumbnail.bytes.toArray))
        assert(decoded.getWidth < decoded.getHeight, s"expected a portrait thumbnail, got ${decoded.getWidth}x${decoded.getHeight}")
      case ThumbnailDerivationResult.DerivationFailed(reason) => fail(s"expected a derived thumbnail, got $reason")

  test("should fail to derive a thumbnail from content that isn't a decodable image"):
    val garbage = PhotoContent(ByteVector(Array[Byte](1, 2, 3)), PhotoMediaType.Jpeg)

    generator.derive(garbage) match
      case _: ThumbnailDerivationResult.DerivationFailed => ()
      case _: ThumbnailDerivationResult.Derived          => fail("expected derivation to fail for undecodable content")

  private def solidImage(width: Int, height: Int, color: Color): BufferedImage =
    BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).tap { image =>
      val graphics = image.createGraphics()
      graphics.setColor(color)
      graphics.fillRect(0, 0, width, height)
      graphics.dispose()
    }

  private def noisyImage(width: Int, height: Int): BufferedImage =
    BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).tap { image =>
      val random = Random(42)
      for
        x <- 0 until width
        y <- 0 until height
      do image.setRGB(x, y, random.nextInt())
    }

  private def encode(image: BufferedImage, format: String): PhotoContent =
    val output    = ByteArrayOutputStream()
    val _         = ImageIO.write(image, format, output)
    val mediaType = format match
      case "png" => PhotoMediaType.Png
      case _     => PhotoMediaType.Jpeg
    PhotoContent(ByteVector(output.toByteArray), mediaType)

  private def readResource(name: String): Array[Byte] =
    getClass.getResourceAsStream(name).readAllBytes()
