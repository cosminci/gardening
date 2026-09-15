package gardening.domain

trait IdGenerator:
  def nextId(): String
