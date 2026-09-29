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
import gardening.domain.attention.{WateringSampleCount, WateringSampleSize}
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
    val plantStore        = SqlitePlantStore.make(resources.transactor)
    val photoStore        = SqlitePhotoStore.make(resources.transactor)
    val photoWriteJournal = SqlitePhotoWriteJournal.make(resources.transactor)
    val operationStore    = SqliteOperationStore.make(resources.transactor)
    val contentStore      = FilePhotoContentStore.make(config.photosDir)
    val substrateStore    = SqliteSubstrateStore.make(resources.transactor)
    val pesticideStore    = SqlitePesticideStore.make(resources.transactor)
    val plantLock         = PlantUpdateLock.make

    PhotoWriteRecovery.make(using photoStore, contentStore, photoWriteJournal).reconcile()

    val (plantsMetrics, substrateComponentUsageTotal) = PrometheusPlantManagerMetrics.make(registry)
    given PlantManagerMetricsApi                      = plantsMetrics
    given OperationLedgerMetricsApi                   = PrometheusOperationLedgerMetrics.make(registry, substrateComponentUsageTotal)
    given PlantAttentionMonitorMetricsApi             = PrometheusPlantAttentionMonitorMetrics.make(registry)
    given SubstrateCatalogMetricsApi                  = PrometheusSubstrateCatalogMetrics.make(registry)
    given PesticideCatalogMetricsApi                  = PrometheusPesticideCatalogMetrics.make(registry)

    // Both counts are `>= 2` in config, so `>= 0` and positive hold without a re-check.
    val minSampleCount: WateringSampleCount = config.watering.minSampleCount.assume[GreaterEqual[0]]
    val historySize: WateringSampleSize     = config.watering.maxSampleCount.assume[Positive]

    PlantAttentionMonitor.make(minSampleCount, historySize, config.watering.overdueGracePeriod)(using plantStore, SystemClock).map: attention =>
      forkDiscard:
        Iterator.continually { sleep(config.attentionRecomputeInterval); attention.refreshAll.discard }.foreach(identity)
      Programs(
        PlantManager.make(using plantStore, substrateStore, UuidIdGenerator, plantLock),
        PhotoManager.make(using
          photoStore,
          contentStore,
          photoWriteJournal,
          PhotoThumbnailGenerator.make(config.photo.maxThumbnailSize),
          UuidIdGenerator,
          SystemClock
        ),
        OperationLedger.make(using operationStore, plantStore, substrateStore, pesticideStore, UuidIdGenerator, plantLock),
        attention,
        SubstrateCatalog.make(using substrateStore, UuidIdGenerator),
        PesticideCatalog.make(using pesticideStore, UuidIdGenerator)
      )
