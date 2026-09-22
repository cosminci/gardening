package gardening.app

import gardening.adapters.persistence.SqlitePlantJournalStore
import gardening.adapters.system.{SystemClock, UuidIdGenerator}
import gardening.domain.attention.{PlantAttentionProjection, PlantAttentionService}
import gardening.domain.journal.PlantJournal

import scala.concurrent.duration.FiniteDuration

final case class Programs(
    plantJournal: PlantJournal,
    plantAttentionProjection: PlantAttentionProjection,
    plantAttentionRefresher: PeriodicAttentionRefresher
)

object Programs:

  def make(
      resources: AppResources,
      attentionInterval: FiniteDuration
  ): Programs =
    val store                 = SqlitePlantJournalStore.make(resources.transactor)
    val plantAttentionService = PlantAttentionService.make(using store, SystemClock)
    Programs(
      plantJournal = PlantJournal.make(using store, UuidIdGenerator, SystemClock),
      plantAttentionProjection = plantAttentionService,
      plantAttentionRefresher = PeriodicAttentionRefresher.every(plantAttentionService, attentionInterval)
    )
