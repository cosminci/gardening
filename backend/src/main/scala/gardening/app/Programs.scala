package gardening.app

import gardening.adapters.persistence.{SqlitePesticideStore, SqlitePlantJournalStore, SqliteSubstrateComponentStore}
import gardening.adapters.system.{SystemClock, UuidIdGenerator}
import gardening.domain.attention.PlantAttentionMonitor
import gardening.domain.journal.PlantJournal
import gardening.domain.pesticide.PesticideCatalog
import gardening.domain.substrate.SubstrateComponentCatalog
import ox.{Ox, discard, forkDiscard, sleep}

final case class Programs(
    plantJournal: PlantJournal,
    plantAttentionMonitor: PlantAttentionMonitor,
    substrateComponentCatalog: SubstrateComponentCatalog,
    pesticideCatalog: PesticideCatalog
)

object Programs:

  def make(resources: AppResources)(using Ox): Either[Throwable, Programs] =
    val store          = SqlitePlantJournalStore.make(resources.transactor)
    val substrateStore = SqliteSubstrateComponentStore.make(resources.transactor)
    val pesticideStore = SqlitePesticideStore.make(resources.transactor)
    PlantAttentionMonitor.make(using store, SystemClock).map: attention =>
      forkDiscard:
        Iterator.continually { sleep(AppConfig.attentionRecomputeInterval); attention.refreshAll.discard }.foreach(identity)
      Programs(
        PlantJournal.make(using store, substrateStore, pesticideStore, UuidIdGenerator),
        attention,
        SubstrateComponentCatalog.make(using substrateStore, UuidIdGenerator),
        PesticideCatalog.make(using pesticideStore, UuidIdGenerator)
      )
