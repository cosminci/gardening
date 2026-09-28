package gardening.domain.plants

import scodec.bits.ByteVector

import java.awt.{Color, RenderingHints}
import java.awt.image.{BufferedImage, ImageObserver}
import java.io.{ByteArrayInputStream, ByteArrayOutputStream, IOException}
import javax.imageio.{IIOImage, ImageIO, ImageWriteParam}
import scala.util.Try

// Always re-encodes as JPEG, regardless of the original's media type: JPEG's quality dial is what
// makes the byte target reachable (PNG has no lossy dial). A transparent PNG loses its transparency
// here, flattened onto a white background - the thumbnail-only tradeoff the spec accepts.
object PhotoThumbnail:

  private val MaxBytes = 102400

  private val ladder: Vector[(Int, Float)] =
    Vector((1024, 0.8f), (800, 0.8f), (800, 0.6f), (600, 0.6f), (600, 0.4f), (400, 0.4f))

  def derive(original: PhotoContent): Either[Throwable, PhotoContent] =
    Try {
      val decoded = ImageIO.read(ByteArrayInputStream(original.bytes.toArray))
      if decoded == null then throw IOException("no image reader available for this content")
      val attempts = ladder.map((maxDim, quality) => encodeJpeg(decoded, maxDim, quality))
      val smallest =
        attempts.foldLeft(Array.emptyByteArray)((soFar, attempt) => if soFar.isEmpty || attempt.length < soFar.length then attempt else soFar)
      val chosen = attempts.find(_.length <= MaxBytes).getOrElse(smallest)
      PhotoContent(ByteVector(chosen), PhotoMediaType.Jpeg)
    }.toEither

  // drawImage's ImageObserver callback is for asynchronously-loaded images; this drawing is fully
  // synchronous, so a no-op observer (never null - wartremover forbids it) is all that's needed.
  private val noopObserver: ImageObserver = (_, _, _, _, _, _) => true

  @SuppressWarnings(Array("org.wartremover.warts.Null"))
  private def encodeJpeg(source: BufferedImage, maxDim: Int, quality: Float): Array[Byte] =
    val scale  = math.min(1.0, maxDim.toDouble / math.max(source.getWidth, source.getHeight))
    val width  = math.max(1, math.round(source.getWidth * scale).toInt)
    val height = math.max(1, math.round(source.getHeight * scale).toInt)

    val resized  = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val graphics = resized.createGraphics()
    try
      graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
      graphics.setColor(Color.WHITE)
      graphics.fillRect(0, 0, width, height)
      graphics.drawImage(source, 0, 0, width, height, noopObserver)
    finally graphics.dispose()

    val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
    val params = writer.getDefaultWriteParam
    params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT)
    params.setCompressionQuality(quality)

    val output = ByteArrayOutputStream()
    val stream = ImageIO.createImageOutputStream(output)
    try
      writer.setOutput(stream)
      // No stream metadata and no thumbnails to attach - both genuinely absent, per IIOImage/write's own API.
      writer.write(null, IIOImage(resized, null, null), params)
    finally
      writer.dispose()
      stream.close()
    output.toByteArray
