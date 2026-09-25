package gardening.app

import gardening.adapters.persistence.{SqlitePesticideStore, SqlitePlantJournalStore, SqliteSubstrateStore}
import gardening.adapters.storage.FilePhotoContentStore
import gardening.adapters.system.{SystemClock, UuidIdGenerator}
import gardening.domain.Logger
import gardening.domain.attention.PlantAttentionMonitor
import gardening.domain.journal.PlantJournal
import gardening.domain.pesticide.PesticideCatalog
import gardening.domain.substrate.SubstrateCatalog
import ox.{Ox, discard, forkDiscard, sleep}

import java.nio.file.Path

final case class Programs(
    plantJournal: PlantJournal,
    plantAttentionMonitor: PlantAttentionMonitor,
    substrateCatalog: SubstrateCatalog,
    pesticideCatalog: PesticideCatalog
)

object Programs:

  def make(resources: AppResources, photosDir: Path)(using Ox)(using log: Logger): Either[Throwable, Programs] =
    val store          = SqlitePlantJournalStore.make(resources.transactor)
    val contentStore   = FilePhotoContentStore.make(photosDir)
    val substrateStore = SqliteSubstrateStore.make(resources.transactor)
    val pesticideStore = SqlitePesticideStore.make(resources.transactor)
    PlantAttentionMonitor.make(using store, SystemClock).map: attention =>
      forkDiscard:
        Iterator.continually { sleep(AppConfig.attentionRecomputeInterval); attention.refreshAll.discard }.foreach(identity)
      Programs(
        PlantJournal.make(using store, contentStore, substrateStore, pesticideStore, UuidIdGenerator, SystemClock),
        attention,
        SubstrateCatalog.make(using substrateStore, UuidIdGenerator),
        PesticideCatalog.make(using pesticideStore, UuidIdGenerator)
      )
