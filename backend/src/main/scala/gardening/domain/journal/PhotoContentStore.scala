package gardening.domain.journal

trait PhotoContentStore:
  def put(id: PhotoId, content: PhotoContent): PhotoWriteResult
  def get(id: PhotoId): PhotoReadResult
  def delete(id: PhotoId): PhotoWriteResult
