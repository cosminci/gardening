package gardening.usecases

import gardening.domain.plants.*
import net.coobird.thumbnailator.Thumbnails
import scodec.bits.ByteVector
import squants.information.Information

import java.awt.Color
import java.awt.image.{BufferedImage, ImageObserver}
import java.io.{ByteArrayInputStream, ByteArrayOutputStream, IOException}
import javax.imageio.ImageIO
import scala.util.Try

trait PhotoThumbnailGenerator:
  def derive(original: PhotoContent): ThumbnailDerivationResult

object PhotoThumbnailGenerator:

  def make(maxThumbnailSize: Information): PhotoThumbnailGenerator = LivePhotoThumbnailGenerator(maxThumbnailSize)

  private val ladder: Vector[(Int, Float)] =
    Vector((1024, 0.8f), (800, 0.8f), (800, 0.6f), (600, 0.6f), (600, 0.4f), (400, 0.4f))

  final private class LivePhotoThumbnailGenerator(maxThumbnailSize: Information) extends PhotoThumbnailGenerator:

    override def derive(original: PhotoContent): ThumbnailDerivationResult =
      val bytes = original.bytes.toArray
      Option(ImageIO.read(ByteArrayInputStream(bytes))) match
        case None    => ThumbnailDerivationResult.DerivationFailed(IOException("no image reader available for this content"))
        case Some(_) =>
          Try {
            val upright  = Thumbnails.of(ByteArrayInputStream(bytes)).scale(1.0).useExifOrientation(true).asBufferedImage()
            val attempts = ladder.map((maxDim, quality) => encodeJpeg(upright, maxDim, quality))
            val smallest =
              attempts.foldLeft(Array.emptyByteArray)((soFar, attempt) => if soFar.isEmpty || attempt.length < soFar.length then attempt else soFar)
            val chosen = attempts.find(_.length <= maxThumbnailSize.toBytes).getOrElse(smallest)
            PhotoContent(ByteVector(chosen), PhotoMediaType.Jpeg)
          }.fold(
            // encodeJpeg cannot fail for any image ImageIO.read can decode; this mapping is defensive and not exercised.
            // $COVERAGE-OFF$
            ThumbnailDerivationResult.DerivationFailed.apply,
            // $COVERAGE-ON$
            ThumbnailDerivationResult.Derived.apply
          )

    // drawImage never calls back into this for a complete, non-progressively-loaded BufferedImage.
    // $COVERAGE-OFF$
    private val noopObserver: ImageObserver = (_, _, _, _, _, _) => true
    // $COVERAGE-ON$

    /**
     * JPEG has no alpha channel, and every thumbnail is JPEG-encoded to keep its byte size controllable (see the photo-thumbnails spec); flatten onto
     * an opaque background first so a transparent source doesn't fall back to black.
     */
    private def encodeJpeg(source: BufferedImage, maxDim: Int, quality: Float): Array[Byte] =
      val opaque   = BufferedImage(source.getWidth, source.getHeight, BufferedImage.TYPE_INT_RGB)
      val graphics = opaque.createGraphics()
      try
        graphics.setColor(Color.WHITE)
        graphics.fillRect(0, 0, source.getWidth, source.getHeight)
        graphics.drawImage(source, 0, 0, noopObserver)
      finally graphics.dispose()

      val scale  = 1.0.min(maxDim.toDouble / source.getWidth.max(source.getHeight))
      val output = ByteArrayOutputStream()
      Thumbnails.of(opaque).scale(scale).outputFormat("jpg").outputQuality(quality).toOutputStream(output)
      output.toByteArray
