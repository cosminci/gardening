package gardening.domain.plants

import munit.FunSuite
import scodec.bits.ByteVector

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.util.Random
import javax.imageio.ImageIO

class PhotoThumbnailTest extends FunSuite:

  private val maxThumbnailBytes = 102400

  test("should derive a jpeg thumbnail at or under the 100KB target from a small original"):
    val original = encode(solidImage(300, 200, Color.BLUE), "png")

    val result = PhotoThumbnail.derive(original)

    result match
      case Right(thumbnail) =>
        assertEquals(thumbnail.mediaType, PhotoMediaType.Jpeg)
        assert(thumbnail.bytes.length <= maxThumbnailBytes, s"expected <= $maxThumbnailBytes bytes, got ${thumbnail.bytes.length}")
      case Left(reason) => fail(s"expected a derived thumbnail, got $reason")

  test("should derive a jpeg thumbnail at or under the 100KB target from a large, hard-to-compress original"):
    val original = encode(noisyImage(2000, 1500), "jpg")

    val result = PhotoThumbnail.derive(original)

    result match
      case Right(thumbnail) =>
        assertEquals(thumbnail.mediaType, PhotoMediaType.Jpeg)
        assert(thumbnail.bytes.length <= maxThumbnailBytes, s"expected <= $maxThumbnailBytes bytes, got ${thumbnail.bytes.length}")
      case Left(reason) => fail(s"expected a derived thumbnail, got $reason")

  test("should flatten a transparent original onto an opaque background"):
    val transparent = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)
    val graphics    = transparent.createGraphics()
    graphics.setColor(Color(0, 0, 0, 0))
    graphics.fillRect(0, 0, 4, 4)
    graphics.dispose()
    val original = encode(transparent, "png")

    val result = PhotoThumbnail.derive(original)

    result match
      case Right(thumbnail) =>
        val decoded = ImageIO.read(ByteArrayInputStream(thumbnail.bytes.toArray))
        assertEquals(decoded.getRGB(0, 0) & 0x00ffffff, 0x00ffffff)
      case Left(reason) => fail(s"expected a derived thumbnail, got $reason")

  test("should fail to derive a thumbnail from content that isn't a decodable image"):
    val garbage = PhotoContent(ByteVector(Array[Byte](1, 2, 3)), PhotoMediaType.Jpeg)

    PhotoThumbnail.derive(garbage) match
      case Left(_)  => ()
      case Right(_) => fail("expected derivation to fail for undecodable content")

  private def solidImage(width: Int, height: Int, color: Color): BufferedImage =
    val image    = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val graphics = image.createGraphics()
    graphics.setColor(color)
    graphics.fillRect(0, 0, width, height)
    graphics.dispose()
    image

  private def noisyImage(width: Int, height: Int): BufferedImage =
    val image  = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val random = Random(42)
    for
      x <- 0 until width
      y <- 0 until height
    do image.setRGB(x, y, random.nextInt())
    image

  private def encode(image: BufferedImage, format: String): PhotoContent =
    val output    = ByteArrayOutputStream()
    val _         = ImageIO.write(image, format, output)
    val mediaType = format match
      case "png" => PhotoMediaType.Png
      case _     => PhotoMediaType.Jpeg
    PhotoContent(ByteVector(output.toByteArray), mediaType)
