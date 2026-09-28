package gardening.domain.plants

import scodec.bits.ByteVector
import squants.information.Information
import squants.information.InformationConversions.*

import java.awt.{Color, RenderingHints}
import java.awt.image.{BufferedImage, ImageObserver}
import java.io.{ByteArrayInputStream, ByteArrayOutputStream, IOException}
import javax.imageio.metadata.IIOMetadata
import javax.imageio.{IIOImage, ImageIO, ImageWriteParam}
import scala.util.Try

trait PhotoThumbnail:
  def derive(original: PhotoContent): ThumbnailDerivationResult

object PhotoThumbnail:

  def make: PhotoThumbnail = LivePhotoThumbnail

  val maxThumbnailSize: Information = 100.kibibytes

  private val ladder: Vector[(Int, Float)] =
    Vector((1024, 0.8f), (800, 0.8f), (800, 0.6f), (600, 0.6f), (600, 0.4f), (400, 0.4f))

  private object LivePhotoThumbnail extends PhotoThumbnail:

    override def derive(original: PhotoContent): ThumbnailDerivationResult =
      Option(ImageIO.read(ByteArrayInputStream(original.bytes.toArray))) match
        case None          => ThumbnailDerivationResult.DerivationFailed(IOException("no image reader available for this content"))
        case Some(decoded) =>
          Try {
            val attempts = ladder.map((maxDim, quality) => encodeJpeg(decoded, maxDim, quality))
            val smallest =
              attempts.foldLeft(Array.emptyByteArray)((soFar, attempt) => if soFar.isEmpty || attempt.length < soFar.length then attempt else soFar)
            val chosen = attempts.find(_.length <= maxThumbnailSize.toBytes).getOrElse(smallest)
            PhotoContent(ByteVector(chosen), PhotoMediaType.Jpeg)
          }.fold(ThumbnailDerivationResult.DerivationFailed.apply, ThumbnailDerivationResult.Derived.apply)

    // drawImage never calls back into this for a complete, non-progressively-loaded BufferedImage.
    // $COVERAGE-OFF$
    private val noopObserver: ImageObserver = (_, _, _, _, _, _) => true
    // $COVERAGE-ON$

    @SuppressWarnings(Array("org.wartremover.warts.Null"))
    private def encodeJpeg(source: BufferedImage, maxDim: Int, quality: Float): Array[Byte] =
      val scale  = 1.0.min(maxDim.toDouble / source.getWidth.max(source.getHeight))
      val width  = 1.max(math.round(source.getWidth * scale).toInt)
      val height = 1.max(math.round(source.getHeight * scale).toInt)

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
        // ImageWriter#write has no metadata-free overload that also accepts an ImageWriteParam; the Java API requires literal nulls here.
        // scalafix:off DisableSyntax.null
        val noStreamMetadata: IIOMetadata                       = null
        val noAttachedThumbnails: java.util.List[BufferedImage] = null
        val noImageMetadata: IIOMetadata                        = null
        // scalafix:on DisableSyntax.null
        writer.write(noStreamMetadata, IIOImage(resized, noAttachedThumbnails, noImageMetadata), params)
      finally
        writer.dispose()
        stream.close()
      output.toByteArray
