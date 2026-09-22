package gardening.app

import gardening.adapters.persistence.SqlitePlantJournalStore
import gardening.adapters.system.{SystemClock, UuidIdGenerator}
import gardening.domain.attention.PlantAttentionService
import gardening.domain.journal.PlantJournal

final case class Programs(
    plantJournal: PlantJournal,
    plantAttentionService: PlantAttentionService
)

object Programs:

  def make(resources: AppResources): Either[Throwable, Programs] =
    val store = SqlitePlantJournalStore.make(resources.transactor)
    PlantAttentionService.make(using store, SystemClock).map: plantAttentionService =>
      Programs(
        plantJournal = PlantJournal.make(using store, UuidIdGenerator, SystemClock),
        plantAttentionService
      )
