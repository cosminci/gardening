package gardening.domain.plants

trait PhotoContentStore:
  def put(photo: PhotoId, content: PhotoContent): PhotoWriteResult
  def get(photo: PhotoId): PhotoReadResult
  def delete(photo: PhotoId): PhotoWriteResult
