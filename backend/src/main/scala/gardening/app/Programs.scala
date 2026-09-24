package gardening.app

import gardening.adapters.persistence.{SqlitePlantStore, SqliteSubstrateComponentStore}
import gardening.adapters.system.{SystemClock, UuidIdGenerator}
import gardening.domain.attention.PlantAttentionMonitor
import gardening.domain.journal.PlantJournal
import gardening.domain.substrate.SubstrateComponentCatalog

final case class Programs(
    plantJournal: PlantJournal,
    plantAttentionMonitor: PlantAttentionMonitor,
    substrateComponentCatalog: SubstrateComponentCatalog
)

object Programs:

  def make(resources: AppResources): Either[Throwable, Programs] =
    val store          = SqlitePlantStore.make(resources.transactor)
    val substrateStore = SqliteSubstrateComponentStore.make(resources.transactor)
    PlantAttentionMonitor.make(using store, SystemClock).map: attention =>
      Programs(
        PlantJournal.make(using store, substrateStore, UuidIdGenerator),
        attention,
        SubstrateComponentCatalog.make(using substrateStore, UuidIdGenerator)
      )
