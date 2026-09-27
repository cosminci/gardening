package gardening.adapters.system

import gardening.capabilities.IdGenerator

import java.util.UUID

object UuidIdGenerator extends IdGenerator:

  def nextId(): String = UUID.randomUUID().toString
