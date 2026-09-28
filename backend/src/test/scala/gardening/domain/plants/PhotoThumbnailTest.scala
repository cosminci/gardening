package gardening.domain.plants

import munit.FunSuite
import scodec.bits.ByteVector

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.util.Random
import javax.imageio.ImageIO
import scala.util.chaining.scalaUtilChainingOps

class PhotoThumbnailTest extends FunSuite:

  test("should derive a jpeg thumbnail at or under the 100KB target from a small original"):
    val original = solidImage(width = 300, height = 200, color = Color.BLUE).pipe(image => encode(image, format = "png"))

    PhotoThumbnail.make.derive(original) match
      case ThumbnailDerivationResult.Derived(thumbnail) =>
        val actualBytes = thumbnail.bytes.length
        assertEquals(thumbnail.mediaType, PhotoMediaType.Jpeg)
        assert(actualBytes <= PhotoThumbnail.maxThumbnailSize.toBytes, s"expected <= ${PhotoThumbnail.maxThumbnailSize}, got $actualBytes bytes")
      case ThumbnailDerivationResult.DerivationFailed(reason) => fail(s"expected a derived thumbnail, got $reason")

  test("should derive a jpeg thumbnail at or under the 100KB target from a large, hard-to-compress original"):
    val original = noisyImage(width = 2000, height = 1500).pipe(image => encode(image, format = "jpg"))

    PhotoThumbnail.make.derive(original) match
      case ThumbnailDerivationResult.Derived(thumbnail) =>
        val actualBytes = thumbnail.bytes.length
        assertEquals(thumbnail.mediaType, PhotoMediaType.Jpeg)
        assert(actualBytes <= PhotoThumbnail.maxThumbnailSize.toBytes, s"expected <= ${PhotoThumbnail.maxThumbnailSize}, got $actualBytes bytes")
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

    PhotoThumbnail.make.derive(original) match
      case ThumbnailDerivationResult.Derived(thumbnail) =>
        val decoded = ImageIO.read(ByteArrayInputStream(thumbnail.bytes.toArray))
        assertEquals(decoded.getRGB(0, 0) & 0x00ffffff, 0x00ffffff)
      case ThumbnailDerivationResult.DerivationFailed(reason) => fail(s"expected a derived thumbnail, got $reason")

  test("should fail to derive a thumbnail from content that isn't a decodable image"):
    val garbage = PhotoContent(ByteVector(Array[Byte](1, 2, 3)), PhotoMediaType.Jpeg)

    PhotoThumbnail.make.derive(garbage) match
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
