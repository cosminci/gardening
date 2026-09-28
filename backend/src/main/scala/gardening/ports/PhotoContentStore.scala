package gardening.ports

import gardening.domain.plants.*

trait PhotoContentStore:
  def put(photo: PhotoId, original: PhotoContent, thumbnail: PhotoContent): PhotoWriteResult
  def get(photo: PhotoId, variant: PhotoVariant): PhotoReadResult
  def delete(photo: PhotoId): PhotoWriteResult
