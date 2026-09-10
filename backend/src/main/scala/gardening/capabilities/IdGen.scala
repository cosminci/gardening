package gardening.capabilities

/**
 * Capability generating opaque unique identifiers. Injected via `using` so identifier generation is substitutable under
 * test. Reserved for feature work; the walking skeleton does not yet mint identifiers.
 */
trait IdGen:
  def nextId(): String
