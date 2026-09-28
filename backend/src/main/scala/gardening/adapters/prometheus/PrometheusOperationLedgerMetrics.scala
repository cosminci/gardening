package gardening.adapters.prometheus

import gardening.domain.{PesticideId, PlantId, SubstrateComponentId}
import gardening.domain.operations.{ActionType, MoistureLevel}
import gardening.ports.OperationLedgerMetricsApi
import io.prometheus.metrics.core.metrics.Counter
import io.prometheus.metrics.model.registry.PrometheusRegistry

object PrometheusOperationLedgerMetrics:

  /**
   * `substrateComponentUsageTotal` is built and registered by [[PrometheusPlantManagerMetrics]] and passed in here, since a repot increments the same
   * counter an initial mix does.
   */
  def make(registry: PrometheusRegistry, substrateComponentUsageTotal: Counter): OperationLedgerMetricsApi =
    val operationsTotal = Counter
      .builder()
      .name("gardening_journal_operations_total")
      .help("Care actions logged, by action type")
      .labelNames("action")
      .register(registry)
    val moistureReadingsTotal = Counter
      .builder()
      .name("gardening_journal_moisture_readings_total")
      .help("Moisture readings recorded, by level")
      .labelNames("level")
      .register(registry)
    val repotsTotal = Counter
      .builder()
      .name("gardening_journal_repots_total")
      .help("Repots logged, by plant")
      .labelNames("plant")
      .register(registry)
    val pesticideApplicationsTotal = Counter
      .builder()
      .name("gardening_journal_pesticide_applications_total")
      .help("Pesticide applications logged, by pesticide")
      .labelNames("pesticide")
      .register(registry)
    LiveOperationsMetrics(operationsTotal, moistureReadingsTotal, repotsTotal, substrateComponentUsageTotal, pesticideApplicationsTotal)

  private class LiveOperationsMetrics(
      operationsTotal: Counter,
      moistureReadingsTotal: Counter,
      repotsTotal: Counter,
      substrateComponentUsageTotal: Counter,
      pesticideApplicationsTotal: Counter
  ) extends OperationLedgerMetricsApi:

    override def incrementAction(kind: ActionType): Unit       = operationsTotal.labelValues(actionLabel(kind)).inc()
    override def incrementMoisture(level: MoistureLevel): Unit = moistureReadingsTotal.labelValues(moistureLabel(level)).inc()
    override def incrementRepot(plant: PlantId): Unit          = repotsTotal.labelValues(plant.value).inc()

    override def incrementSubstrateComponent(component: SubstrateComponentId): Unit =
      substrateComponentUsageTotal.labelValues(component.value.toString).inc()

    override def incrementPesticide(pesticide: PesticideId): Unit =
      pesticideApplicationsTotal.labelValues(pesticide.value.toString).inc()

    private def actionLabel(kind: ActionType): String = kind match
      case ActionType.Watered    => "watered"
      case ActionType.Showered   => "showered"
      case ActionType.Fertilized => "fertilized"
      case ActionType.Pesticide  => "pesticide"
      case ActionType.Pruned     => "pruned"
      case ActionType.NoAction   => "noAction"

    private def moistureLabel(level: MoistureLevel): String = level match
      case MoistureLevel.Wet           => "wet"
      case MoistureLevel.ModeratePlus  => "moderatePlus"
      case MoistureLevel.ModerateMinus => "moderateMinus"
      case MoistureLevel.Dry           => "dry"
      case MoistureLevel.NoReading     => "noReading"
