package gardening.app

import gardening.adapters.persistence.{SqliteOperationStore, SqlitePesticideStore, SqlitePlantStore, SqliteSubstrateStore}
import gardening.adapters.storage.FilePhotoContentStore
import gardening.adapters.system.{SystemClock, UuidIdGenerator}
import gardening.domain.{Logger, PlantUpdateLock}
import gardening.domain.attention.PlantAttentionMonitor
import gardening.domain.operations.Operations
import gardening.domain.pesticide.PesticideCatalog
import gardening.domain.plants.Plants
import gardening.domain.substrate.SubstrateCatalog
import ox.{Ox, discard, forkDiscard, sleep}

import java.nio.file.Path

final case class Programs(
    plants: Plants,
    operations: Operations,
    plantAttentionMonitor: PlantAttentionMonitor,
    substrateCatalog: SubstrateCatalog,
    pesticideCatalog: PesticideCatalog
)

object Programs:

  def make(resources: AppResources, photosDir: Path)(using Ox)(using log: Logger): Either[Throwable, Programs] =
    val plantStore     = SqlitePlantStore.make(resources.transactor)
    val operationStore = SqliteOperationStore.make(resources.transactor)
    val contentStore   = FilePhotoContentStore.make(photosDir)
    val substrateStore = SqliteSubstrateStore.make(resources.transactor)
    val pesticideStore = SqlitePesticideStore.make(resources.transactor)
    val plantLock      = PlantUpdateLock.make
    PlantAttentionMonitor.make(using plantStore, SystemClock).map: attention =>
      forkDiscard:
        Iterator.continually { sleep(AppConfig.attentionRecomputeInterval); attention.refreshAll.discard }.foreach(identity)
      Programs(
        Plants.make(using plantStore, contentStore, substrateStore, UuidIdGenerator, SystemClock, plantLock),
        Operations.make(using operationStore, plantStore, substrateStore, pesticideStore, UuidIdGenerator, plantLock),
        attention,
        SubstrateCatalog.make(using substrateStore, UuidIdGenerator),
        PesticideCatalog.make(using pesticideStore, UuidIdGenerator)
      )
