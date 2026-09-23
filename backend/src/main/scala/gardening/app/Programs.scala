package gardening.app

import gardening.adapters.persistence.SqlitePlantStore
import gardening.adapters.system.{SystemClock, UuidIdGenerator}
import gardening.domain.attention.PlantAttentionMonitor
import gardening.domain.journal.PlantJournal

final case class Programs(
    plantJournal: PlantJournal,
    plantAttentionMonitor: PlantAttentionMonitor
)

object Programs:

  def make(resources: AppResources): Either[Throwable, Programs] =
    val store = SqlitePlantStore.make(resources.transactor)
    PlantAttentionMonitor.make(using store, SystemClock).map: plantAttentionMonitor =>
      Programs(
        plantJournal = PlantJournal.make(using store, UuidIdGenerator, SystemClock),
        plantAttentionMonitor
      )
