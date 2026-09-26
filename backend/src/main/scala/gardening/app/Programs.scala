package gardening.app

import gardening.adapters.persistence.{SqliteOperationStore, SqlitePesticideStore, SqlitePlantStore, SqliteSubstrateStore}
import gardening.adapters.prometheus.{
  PrometheusOperationsMetrics,
  PrometheusPesticideCatalogMetrics,
  PrometheusPlantAttentionMonitorMetrics,
  PrometheusPlantsMetrics,
  PrometheusSubstrateCatalogMetrics
}
import gardening.adapters.storage.FilePhotoContentStore
import gardening.adapters.system.{SystemClock, UuidIdGenerator}
import gardening.domain.{Logger, PlantUpdateLock}
import gardening.domain.attention.{PlantAttentionMonitor, PlantAttentionMonitorMetricsApi}
import gardening.domain.operations.{Operations, OperationsMetricsApi}
import gardening.domain.pesticide.{PesticideCatalog, PesticideCatalogMetricsApi}
import gardening.domain.plants.{Plants, PlantsMetricsApi}
import gardening.domain.substrate.{SubstrateCatalog, SubstrateCatalogMetricsApi}
import io.prometheus.metrics.model.registry.PrometheusRegistry
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

  def make(resources: AppResources, photosDir: Path, registry: PrometheusRegistry)(using Ox)(using log: Logger): Either[Throwable, Programs] =
    val plantStore     = SqlitePlantStore.make(resources.transactor)
    val operationStore = SqliteOperationStore.make(resources.transactor)
    val contentStore   = FilePhotoContentStore.make(photosDir)
    val substrateStore = SqliteSubstrateStore.make(resources.transactor)
    val pesticideStore = SqlitePesticideStore.make(resources.transactor)
    val plantLock      = PlantUpdateLock.make

    val (plantsMetrics, substrateComponentUsageTotal) = PrometheusPlantsMetrics.make(registry)
    given PlantsMetricsApi                            = plantsMetrics
    given OperationsMetricsApi                        = PrometheusOperationsMetrics.make(registry, substrateComponentUsageTotal)
    given PlantAttentionMonitorMetricsApi             = PrometheusPlantAttentionMonitorMetrics.make(registry)
    given SubstrateCatalogMetricsApi                  = PrometheusSubstrateCatalogMetrics.make(registry)
    given PesticideCatalogMetricsApi                  = PrometheusPesticideCatalogMetrics.make(registry)

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
