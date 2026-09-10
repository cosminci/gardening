package gardening.adapters.system

import gardening.capabilities.IdGen

import java.util.UUID

/** Live [[IdGen]] producing random UUIDs. */
object UuidIdGen extends IdGen:
  def nextId(): String = UUID.randomUUID().toString
