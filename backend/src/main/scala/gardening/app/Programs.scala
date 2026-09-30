package gardening.app

import gardening.adapters.sqlite.{SqliteOperationStore, SqlitePesticideStore, SqlitePhotoStore, SqlitePhotoWriteJournal, SqlitePlantStore, SqliteSubstrateStore}
import gardening.adapters.prometheus.*
import gardening.adapters.file.FilePhotoContentStore
import gardening.adapters.system.{SystemClock, UuidIdGenerator}
import gardening.capabilities.{Logger, PlantUpdateLock}
import gardening.usecases.PlantAttentionMonitor
import gardening.ports.PlantAttentionMonitorMetricsApi
import gardening.usecases.OperationLedger
import gardening.ports.OperationLedgerMetricsApi
import gardening.usecases.PesticideCatalog
import gardening.ports.PesticideCatalogMetricsApi
import gardening.usecases.PlantManager
import gardening.usecases.{PhotoManager, PhotoThumbnailGenerator, PhotoWriteRecovery}
import gardening.ports.PlantManagerMetricsApi
import gardening.usecases.SubstrateCatalog
import gardening.ports.SubstrateCatalogMetricsApi
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.{GreaterEqual, Positive}
import io.prometheus.metrics.model.registry.PrometheusRegistry
import ox.{Ox, discard, forkDiscard, sleep}

final case class Programs(
    plants: PlantManager,
    photos: PhotoManager,
    operations: OperationLedger,
    plantAttentionMonitor: PlantAttentionMonitor,
    substrateCatalog: SubstrateCatalog,
    pesticideCatalog: PesticideCatalog
)

object Programs:

  def make(resources: AppResources, config: AppConfig, registry: PrometheusRegistry)(using Ox)(using log: Logger): Either[Throwable, Programs] =
    val plantStore         = SqlitePlantStore.make(resources.transactor)
    val photoStore         = SqlitePhotoStore.make(resources.transactor)
    val photoWriteJournal  = SqlitePhotoWriteJournal.make(resources.transactor)
    val operationStore     = SqliteOperationStore.make(resources.transactor)
    val contentStore       = FilePhotoContentStore.make(config.storage.photosDir)
    val substrateStore     = SqliteSubstrateStore.make(resources.transactor)
    val pesticideStore     = SqlitePesticideStore.make(resources.transactor)
    val plantLock          = PlantUpdateLock.make
    val thumbnailGenerator = PhotoThumbnailGenerator.make(config.photo.maxThumbnailSize)

    PhotoWriteRecovery.make(using photoStore, contentStore, photoWriteJournal).reconcile()

    val (plantsMetrics, substrateComponentUsageTotal) = PrometheusPlantManagerMetrics.make(registry)
    given PlantManagerMetricsApi                      = plantsMetrics
    given OperationLedgerMetricsApi                   = PrometheusOperationLedgerMetrics.make(registry, substrateComponentUsageTotal)
    given PlantAttentionMonitorMetricsApi             = PrometheusPlantAttentionMonitorMetrics.make(registry)
    given SubstrateCatalogMetricsApi                  = PrometheusSubstrateCatalogMetrics.make(registry)
    given PesticideCatalogMetricsApi                  = PrometheusPesticideCatalogMetrics.make(registry)

    val watering        = config.attention.watering
    val monitorSettings = PlantAttentionMonitor.Settings(
      watering.minSampleCount.assume[GreaterEqual[0]],
      watering.maxSampleCount.assume[Positive],
      watering.overdueGracePeriod
    )

    PlantAttentionMonitor.make(monitorSettings)(using plantStore, SystemClock).map: attention =>
      forkDiscard:
        Iterator.continually { sleep(config.attention.recomputeInterval); attention.refreshAll.discard }.foreach(identity)
      Programs(
        PlantManager.make(using plantStore, substrateStore, UuidIdGenerator, plantLock),
        PhotoManager.make(using photoStore, contentStore, photoWriteJournal, thumbnailGenerator, UuidIdGenerator, SystemClock),
        OperationLedger.make(using operationStore, plantStore, substrateStore, pesticideStore, UuidIdGenerator, plantLock),
        attention,
        SubstrateCatalog.make(using substrateStore, UuidIdGenerator),
        PesticideCatalog.make(using pesticideStore, UuidIdGenerator)
      )
