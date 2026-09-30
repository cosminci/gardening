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
import javax.imageio.metadata.IIOMetadataNode
import javax.imageio.{IIOImage, ImageIO, ImageTypeSpecifier}
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

  test("should apply the source's EXIF orientation before deriving the thumbnail"):
    // orientation 6 means the stored pixels need a 90 CW rotation to display upright
    val original = solidImage(width = 4, height = 8, color = Color.BLUE).pipe(image => encodeJpegWithOrientation(image, orientation = 6))

    generator.derive(original) match
      case ThumbnailDerivationResult.Derived(thumbnail) =>
        val decoded = ImageIO.read(ByteArrayInputStream(thumbnail.bytes.toArray))
        assertEquals(decoded.getWidth, 8)
        assertEquals(decoded.getHeight, 4)
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

  /** Minimal single-entry TIFF IFD0 carrying only the Orientation tag (0x0112, SHORT), little-endian. */
  private def exifOrientationPayload(orientation: Int): Array[Byte] =
    val buffer            = ByteArrayOutputStream()
    def u8(v: Int): Unit  = buffer.write(v & 0xff)
    def u16(v: Int): Unit = { u8(v); u8(v >> 8) }
    def u32(v: Int): Unit = { u8(v); u8(v >> 8); u8(v >> 16); u8(v >> 24) }

    buffer.writeBytes("Exif\u0000\u0000".getBytes("ASCII"))
    buffer.writeBytes("II".getBytes("ASCII")) // little-endian
    u16(0x002a)                               // TIFF magic
    u32(8)                                    // offset to IFD0, relative to the TIFF header
    u16(1)                                    // one entry
    u16(0x0112)                               // Orientation tag
    u16(3)                                    // type SHORT
    u32(1)                                    // count
    u16(orientation)
    u16(0) // padding to fill the 4-byte value slot
    u32(0) // no next IFD
    buffer.toByteArray

  private def encodeJpegWithOrientation(image: BufferedImage, orientation: Int): PhotoContent =
    val writer                                      = ImageIO.getImageWritersByFormatName("jpeg").next()
    val param                                       = writer.getDefaultWriteParam
    val metadata                                    = writer.getDefaultImageMetadata(ImageTypeSpecifier(image), param)
    val streamData                                  = writer.getDefaultStreamMetadata(param)
    val noThumbnails: java.util.List[BufferedImage] = java.util.Collections.emptyList()
    val root                                        = metadata.getAsTree("javax_imageio_jpeg_image_1.0") match
      case node: IIOMetadataNode => node
      case other                 => fail(s"expected an IIOMetadataNode, got $other")
    val markerSequence = root.getElementsByTagName("markerSequence").item(0) match
      case node: IIOMetadataNode => node
      case other                 => fail(s"expected an IIOMetadataNode, got $other")
    val exifMarker = IIOMetadataNode("unknown")
    exifMarker.setAttribute("MarkerTag", "225") // APP1
    exifMarker.setUserObject(exifOrientationPayload(orientation))
    val _ = markerSequence.appendChild(exifMarker)
    metadata.setFromTree("javax_imageio_jpeg_image_1.0", root)

    val output = ByteArrayOutputStream()
    val stream = ImageIO.createImageOutputStream(output)
    try
      writer.setOutput(stream)
      writer.write(streamData, IIOImage(image, noThumbnails, metadata), param)
    finally
      writer.dispose()
      stream.close()
    PhotoContent(ByteVector(output.toByteArray), PhotoMediaType.Jpeg)
