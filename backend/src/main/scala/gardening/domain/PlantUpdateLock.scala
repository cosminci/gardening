package gardening.domain

import java.util.concurrent.locks.ReentrantLock

// Plant edits and operation logging/editing/deletion both read-then-write a plant's substrate
// (directly, or as a side effect of the latest repot); a shared lock serializes both paths against
// each other, not just against themselves.
trait PlantUpdateLock:
  def exclusively[A](operation: => A): A

object PlantUpdateLock:

  def make: PlantUpdateLock = LivePlantUpdateLock()

  private class LivePlantUpdateLock extends PlantUpdateLock:
    private val mutex = ReentrantLock()

    override def exclusively[A](operation: => A): A =
      mutex.lock()
      try operation
      finally mutex.unlock()
