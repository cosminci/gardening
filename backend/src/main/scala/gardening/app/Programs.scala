package gardening.app

import gardening.adapters.sqlite.{SqliteOperationStore, SqlitePesticideStore, SqlitePhotoStore, SqlitePlantStore, SqliteSubstrateStore}
import gardening.adapters.prometheus.*
import gardening.adapters.file.FilePhotoContentStore
import gardening.domain.plants.PhotoThumbnail
import gardening.adapters.system.{SystemClock, UuidIdGenerator}
import gardening.capabilities.{Logger, PlantUpdateLock}
import gardening.usecases.PlantAttentionMonitor
import gardening.ports.PlantAttentionMonitorMetricsApi
import gardening.usecases.OperationLedger
import gardening.ports.OperationLedgerMetricsApi
import gardening.usecases.PesticideCatalog
import gardening.ports.PesticideCatalogMetricsApi
import gardening.usecases.PlantManager
import gardening.usecases.PhotoManager
import gardening.ports.PlantManagerMetricsApi
import gardening.usecases.SubstrateCatalog
import gardening.ports.SubstrateCatalogMetricsApi
import io.prometheus.metrics.model.registry.PrometheusRegistry
import ox.{Ox, discard, forkDiscard, sleep}

import java.nio.file.Path

final case class Programs(
    plants: PlantManager,
    photos: PhotoManager,
    operations: OperationLedger,
    plantAttentionMonitor: PlantAttentionMonitor,
    substrateCatalog: SubstrateCatalog,
    pesticideCatalog: PesticideCatalog
)

object Programs:

  def make(resources: AppResources, photosDir: Path, registry: PrometheusRegistry)(using Ox)(using log: Logger): Either[Throwable, Programs] =
    val plantStore     = SqlitePlantStore.make(resources.transactor)
    val photoStore     = SqlitePhotoStore.make(resources.transactor)
    val operationStore = SqliteOperationStore.make(resources.transactor)
    val contentStore   = FilePhotoContentStore.make(photosDir)
    val substrateStore = SqliteSubstrateStore.make(resources.transactor)
    val pesticideStore = SqlitePesticideStore.make(resources.transactor)
    val plantLock      = PlantUpdateLock.make

    val (plantsMetrics, substrateComponentUsageTotal) = PrometheusPlantManagerMetrics.make(registry)
    given PlantManagerMetricsApi                      = plantsMetrics
    given OperationLedgerMetricsApi                   = PrometheusOperationLedgerMetrics.make(registry, substrateComponentUsageTotal)
    given PlantAttentionMonitorMetricsApi             = PrometheusPlantAttentionMonitorMetrics.make(registry)
    given SubstrateCatalogMetricsApi                  = PrometheusSubstrateCatalogMetrics.make(registry)
    given PesticideCatalogMetricsApi                  = PrometheusPesticideCatalogMetrics.make(registry)

    PlantAttentionMonitor.make(using plantStore, SystemClock).map: attention =>
      forkDiscard:
        Iterator.continually { sleep(AppConfig.attentionRecomputeInterval); attention.refreshAll.discard }.foreach(identity)
      Programs(
        PlantManager.make(using plantStore, substrateStore, UuidIdGenerator, plantLock),
        PhotoManager.make(using photoStore, contentStore, PhotoThumbnail.make, UuidIdGenerator, SystemClock),
        OperationLedger.make(using operationStore, plantStore, substrateStore, pesticideStore, UuidIdGenerator, plantLock),
        attention,
        SubstrateCatalog.make(using substrateStore, UuidIdGenerator),
        PesticideCatalog.make(using pesticideStore, UuidIdGenerator)
      )
