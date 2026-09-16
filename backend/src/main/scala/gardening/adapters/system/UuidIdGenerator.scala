package gardening.adapters.system

import gardening.domain.IdGenerator

import java.util.UUID

object UuidIdGenerator extends IdGenerator:

  def nextId(): String = UUID.randomUUID().toString
