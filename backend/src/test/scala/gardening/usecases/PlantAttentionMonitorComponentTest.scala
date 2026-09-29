package gardening.usecases

import cats.syntax.option.*
import gardening.domain.*
import gardening.domain.attention.*
import gardening.domain.plants.*
import gardening.ports.{PlantAttentionStore, PlantAttentionMonitorMetricsApi}
import gardening.capabilities.TestImplicits
import io.github.iltotore.iron.autoRefine

import language.experimental.captureChecking

import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.concurrent.duration.*
import scala.jdk.DurationConverters.*

class PlantAttentionMonitorComponentTest extends munit.FunSuite with TestImplicits:

  private given metrics: PlantAttentionMonitorMetricsApi = new PlantAttentionMonitorMetricsApi:
    def setWateringUrgencyRatio(plant: PlantId, ratio: Double): Unit      = ()
    def setWateringCadence(plant: PlantId, cadence: FiniteDuration): Unit = ()

  private val referenceTime = Instant.parse("2026-01-01T00:00:00Z")
  private val componentId   = SubstrateComponentId(UUID.fromString("00000000-0000-4000-8000-000000000001"))
  private val substrate     = Substrate.of(List(SubstratePart(componentId, 100))).toOption.getOrElse(fail("invalid substrate"))
  private val plant         =
    Plant(PlantId("p1"), PlantDetails(Species("Ficus"), none, Location("Office"), substrate, PlantStatus.Active))

  test(s"should return ${WateringAttention.Unavailable} when a plant has fewer than five recorded waterings"):
    val measurementTime      = referenceTime
    val wateringInterval     = 1.day
    val elapsedSinceWatering = 12.hours
    val latestWatering       = measurementTime - elapsedSinceWatering
    val wateringDates        = Vector.tabulate(4)(index => latestWatering - wateringInterval * index.toLong)
    val wateringHistory      = wateringDates
    val samplesRead          = GetAttentionSamplesResult.Read(Vector(PlantAttentionSample(plant.id, wateringHistory)))
    val sampleResults        = Vector(samplesRead)

    val refs    = Refs()
    val monitor =
      buildMonitor(refs, now = () => measurementTime, getAttentionSamplesResults = sampleResults).getOrElse(fail("initial attention failed"))

    val expectedWatering   = WateringAttention.Unavailable(sampleCount = 4, maybeElapsed = elapsedSinceWatering.some)
    val expectedProjection = AttentionProjection(measuredAt = measurementTime, plants = Vector(PlantAttention(plant.id, expectedWatering)))

    assertEquals(monitor.current, expectedProjection)
    assertEquals(refs.getAttentionSamplesRequests.get(), Vector(20))

  test("should classify watering attention by elapsed time across the Current/Overdue/RedAlert boundaries"):
    val averageInterval = 1.day
    val cases           = Vector(
      averageInterval           -> WateringAttention.Current(sampleCount = 5, averageInterval = averageInterval, elapsed = averageInterval),
      averageInterval + 1.milli ->
        WateringAttention.Overdue(sampleCount = 5, averageInterval = averageInterval, elapsed = averageInterval + 1.milli),
      averageInterval + 24.hours - 1.milli ->
        WateringAttention.Overdue(sampleCount = 5, averageInterval = averageInterval, elapsed = averageInterval + 24.hours - 1.milli),
      averageInterval + 24.hours ->
        WateringAttention.RedAlert(sampleCount = 5, averageInterval = averageInterval, elapsed = averageInterval + 24.hours),
      averageInterval + 24.hours + 1.milli ->
        WateringAttention.RedAlert(sampleCount = 5, averageInterval = averageInterval, elapsed = averageInterval + 24.hours + 1.milli)
    )

    val actual = cases.map: (elapsedSinceWatering, _) =>
      val latestWatering  = referenceTime - elapsedSinceWatering
      val wateringDates   = Vector.tabulate(5)(index => latestWatering - averageInterval * index.toLong)
      val wateringHistory = wateringDates
      val sampleResults   = Vector(GetAttentionSamplesResult.Read(Vector(PlantAttentionSample(plant.id, wateringHistory))))

      buildMonitor(now = () => referenceTime, getAttentionSamplesResults = sampleResults).getOrElse(fail("initial attention failed")).current

    val expected =
      cases.map((_, expectedWatering) => AttentionProjection(measuredAt = referenceTime, plants = Vector(PlantAttention(plant.id, expectedWatering))))

    assertEquals(actual, expected)

  test("should publish an empty projection when there are no active plants"):
    val monitor            = buildMonitor().getOrElse(fail("initial attention failed"))
    val expectedProjection = AttentionProjection(measuredAt = referenceTime, plants = Vector.empty)

    assertEquals(monitor.current, expectedProjection)

  test(s"should return ${WateringAttention.Unavailable} when a plant has no watering samples"):
    val wateringHistory = Vector.empty
    val samplesRead     = GetAttentionSamplesResult.Read(Vector(PlantAttentionSample(plant.id, wateringHistory)))
    val sampleResults   = Vector(samplesRead)

    val monitor = buildMonitor(getAttentionSamplesResults = sampleResults).getOrElse(fail("initial attention failed"))

    val expectedWatering   = WateringAttention.Unavailable(sampleCount = 0, maybeElapsed = none)
    val expectedProjection = AttentionProjection(measuredAt = referenceTime, plants = Vector(PlantAttention(plant.id, expectedWatering)))

    assertEquals(monitor.current, expectedProjection)

  test(s"should return ${WateringAttention.Current} when watering timestamps and elapsed time are equal"):
    val wateringDates   = Vector.fill(5)(referenceTime)
    val wateringHistory = wateringDates
    val samplesRead     = GetAttentionSamplesResult.Read(Vector(PlantAttentionSample(plant.id, wateringHistory)))
    val sampleResults   = Vector(samplesRead)

    val monitor = buildMonitor(getAttentionSamplesResults = sampleResults).getOrElse(fail("initial attention failed"))

    val expectedWatering   = WateringAttention.Current(sampleCount = 5, averageInterval = 0.millis, elapsed = 0.millis)
    val expectedProjection = AttentionProjection(measuredAt = referenceTime, plants = Vector(PlantAttention(plant.id, expectedWatering)))

    assertEquals(monitor.current, expectedProjection)

  test(s"should return ${WateringAttention.Overdue} when equal watering timestamps have positive elapsed time"):
    val measurementTime = referenceTime + 1.milli
    val wateringDates   = Vector.fill(5)(referenceTime)
    val wateringHistory = wateringDates
    val samplesRead     = GetAttentionSamplesResult.Read(Vector(PlantAttentionSample(plant.id, wateringHistory)))
    val sampleResults   = Vector(samplesRead)

    val monitor = buildMonitor(now = () => measurementTime, getAttentionSamplesResults = sampleResults).getOrElse(fail("initial attention failed"))

    val expectedWatering   = WateringAttention.Overdue(sampleCount = 5, averageInterval = 0.millis, elapsed = 1.milli)
    val expectedProjection = AttentionProjection(measuredAt = measurementTime, plants = Vector(PlantAttention(plant.id, expectedWatering)))

    assertEquals(monitor.current, expectedProjection)

  test(s"should return ${WateringAttention.Current} immediately after construction before any refresh"):
    val initialMeasurementTime = referenceTime
    val averageInterval        = 1.day
    val initialElapsed         = 12.hours
    val latestWatering         = initialMeasurementTime - initialElapsed
    val wateringDates          = Vector.tabulate(5)(index => latestWatering - averageInterval * index.toLong)
    val wateringHistory        = wateringDates
    val samplesRead            = GetAttentionSamplesResult.Read(Vector(PlantAttentionSample(plant.id, wateringHistory)))
    val sampleResults          = Vector(samplesRead)

    val currentTime = AtomicReference(initialMeasurementTime)
    val monitor = buildMonitor(now = () => currentTime.get(), getAttentionSamplesResults = sampleResults).getOrElse(fail("initial attention failed"))

    val initialWatering    = WateringAttention.Current(sampleCount = 5, averageInterval = averageInterval, elapsed = initialElapsed)
    val expectedProjection = AttentionProjection(measuredAt = initialMeasurementTime, plants = Vector(PlantAttention(plant.id, initialWatering)))

    assertEquals(monitor.current, expectedProjection)

  test(s"should return ${WateringAttention.Overdue} then ${WateringAttention.RedAlert} as refreshes advance without new waterings"):
    val initialMeasurementTime = referenceTime
    val averageInterval        = 1.day
    val initialElapsed         = 12.hours
    val latestWatering         = initialMeasurementTime - initialElapsed
    val wateringDates          = Vector.tabulate(5)(index => latestWatering - averageInterval * index.toLong)
    val wateringHistory        = wateringDates
    val samplesRead            = GetAttentionSamplesResult.Read(Vector(PlantAttentionSample(plant.id, wateringHistory)))
    val sampleResults          = Vector(samplesRead)

    val currentTime = AtomicReference(initialMeasurementTime)
    val monitor = buildMonitor(now = () => currentTime.get(), getAttentionSamplesResults = sampleResults).getOrElse(fail("initial attention failed"))
    val refreshedMeasurementTime = initialMeasurementTime + 1.day
    currentTime.set(refreshedMeasurementTime)
    val refreshResult = monitor.refreshAll

    val refreshedElapsed    = initialElapsed + 1.day
    val refreshedWatering   = WateringAttention.Overdue(sampleCount = 5, averageInterval = averageInterval, elapsed = refreshedElapsed)
    val refreshedProjection = AttentionProjection(measuredAt = refreshedMeasurementTime, plants = Vector(PlantAttention(plant.id, refreshedWatering)))
    val expectedRefresh     = RefreshAttentionResult.Refreshed(refreshedProjection)

    assertEquals(refreshResult, expectedRefresh)

    val laterMeasurementTime = refreshedMeasurementTime + 1.day
    currentTime.set(laterMeasurementTime)
    val laterRefreshResult = monitor.refreshAll

    val laterElapsed    = refreshedElapsed + 1.day
    val laterWatering   = WateringAttention.RedAlert(sampleCount = 5, averageInterval = averageInterval, elapsed = laterElapsed)
    val laterProjection = AttentionProjection(measuredAt = laterMeasurementTime, plants = Vector(PlantAttention(plant.id, laterWatering)))

    assertEquals(laterRefreshResult, RefreshAttentionResult.Refreshed(laterProjection))

  test(s"should infer ${WateringAttention.Current} from a fifth recorded watering when refreshed"):
    val measurementTime       = referenceTime
    val averageInterval       = 1.day
    val elapsedSinceWatering  = 12.hours
    val latestWatering        = measurementTime - elapsedSinceWatering
    val beforeWateringDates   = Vector.tabulate(4)(index => latestWatering - averageInterval * index.toLong)
    val beforeWateringHistory = beforeWateringDates
    val beforeWatering        = GetAttentionSamplesResult.Read(Vector(PlantAttentionSample(plant.id, beforeWateringHistory)))
    val afterWateringDates    = Vector.tabulate(5)(index => latestWatering - averageInterval * index.toLong)
    val afterWateringHistory  = afterWateringDates
    val afterWatering         = GetAttentionSamplesResult.Read(Vector(PlantAttentionSample(plant.id, afterWateringHistory)))
    val sampleResults         = Vector(beforeWatering, afterWatering)

    val monitor = buildMonitor(now = () => measurementTime, getAttentionSamplesResults = sampleResults).getOrElse(fail("initial attention failed"))

    val expectedWatering      = WateringAttention.Current(sampleCount = 5, averageInterval = averageInterval, elapsed = elapsedSinceWatering)
    val refreshedProjection   = AttentionProjection(measuredAt = measurementTime, plants = Vector(PlantAttention(plant.id, expectedWatering)))
    val expectedRefreshResult = RefreshAttentionResult.Refreshed(refreshedProjection)

    assertEquals(monitor.refreshAll, expectedRefreshResult)

  test(s"should return ${WateringAttention.Unavailable} after a recorded watering is removed and attention is refreshed"):
    val measurementTime      = referenceTime
    val averageInterval      = 1.day
    val elapsedSinceWatering = 12.hours
    val latestWatering       = measurementTime - elapsedSinceWatering
    val beforeRemovalDates   = Vector.tabulate(5)(index => latestWatering - averageInterval * index.toLong)
    val beforeRemovalHistory = beforeRemovalDates
    val beforeRemoval        = GetAttentionSamplesResult.Read(Vector(PlantAttentionSample(plant.id, beforeRemovalHistory)))
    val afterRemovalDates    = Vector.tabulate(4)(index => latestWatering - averageInterval * index.toLong)
    val afterRemovalHistory  = afterRemovalDates
    val afterRemoval         = GetAttentionSamplesResult.Read(Vector(PlantAttentionSample(plant.id, afterRemovalHistory)))
    val sampleResults        = Vector(beforeRemoval, afterRemoval)

    val monitor = buildMonitor(now = () => measurementTime, getAttentionSamplesResults = sampleResults).getOrElse(fail("initial attention failed"))

    val expectedWatering      = WateringAttention.Unavailable(sampleCount = 4, maybeElapsed = elapsedSinceWatering.some)
    val refreshedProjection   = AttentionProjection(measuredAt = measurementTime, plants = Vector(PlantAttention(plant.id, expectedWatering)))
    val expectedRefreshResult = RefreshAttentionResult.Refreshed(refreshedProjection)

    assertEquals(monitor.refreshAll, expectedRefreshResult)

  test("should retain the last complete projection when refresh fails"):
    val measurementTime      = referenceTime
    val averageInterval      = 1.day
    val elapsedSinceWatering = 12.hours
    val latestWatering       = measurementTime - elapsedSinceWatering
    val wateringDates        = Vector.tabulate(5)(index => latestWatering - averageInterval * index.toLong)
    val wateringHistory      = wateringDates
    val samplesRead          = GetAttentionSamplesResult.Read(Vector(PlantAttentionSample(plant.id, wateringHistory)))
    val failure              = RuntimeException("store down")
    val samplesReadFailed    = GetAttentionSamplesResult.ReadFailed(failure)
    val sampleResults        = Vector(samplesRead, samplesReadFailed)

    val monitor = buildMonitor(now = () => measurementTime, getAttentionSamplesResults = sampleResults).getOrElse(fail("initial attention failed"))
    val initialProjection = monitor.current

    val expectedRefreshResult = RefreshAttentionResult.RefreshFailed(failure)

    assertEquals(monitor.refreshAll, expectedRefreshResult)
    assertEquals(monitor.current, initialProjection)

  test("should exclude an archived plant after refreshing attention from active samples"):
    val wateringHistory = Vector.empty
    val remainingPlant  = plant.copy(id = PlantId("still-active"))
    val initialSamples  = GetAttentionSamplesResult.Read(
      Vector(PlantAttentionSample(plant.id, wateringHistory), PlantAttentionSample(remainingPlant.id, wateringHistory))
    )
    val refreshedSamples = GetAttentionSamplesResult.Read(Vector(PlantAttentionSample(remainingPlant.id, wateringHistory)))
    val monitor = buildMonitor(getAttentionSamplesResults = Vector(initialSamples, refreshedSamples)).getOrElse(fail("initial attention failed"))

    val initialProjection = monitor.current
    val refreshed         = monitor.refreshAll
    val currentProjection = monitor.current

    val remainingAttention = PlantAttention(remainingPlant.id, WateringAttention.Unavailable(0, none))
    val expectedProjection = AttentionProjection(referenceTime, Vector(remainingAttention))

    assertEquals(initialProjection.plants.map(_.plantId), Vector(plant.id, remainingPlant.id))
    assertEquals(refreshed, RefreshAttentionResult.Refreshed(expectedProjection))
    assertEquals(currentProjection, expectedProjection)

  test("should fail construction when the initial projection cannot be materialized"):
    val failure           = RuntimeException("store down")
    val samplesReadFailed = GetAttentionSamplesResult.ReadFailed(failure)

    val monitorResult = buildMonitor(getAttentionSamplesResults = Vector(samplesReadFailed))

    val expectedResult = Left(failure)

    assertEquals(monitorResult, expectedResult)

  extension (instant: Instant)
    private def +(duration: FiniteDuration) = instant.plus(duration.toJava)
    private def -(duration: FiniteDuration) = instant.minus(duration.toJava)

  private case class Refs(
      getAttentionSamplesRequests: AtomicReference[Vector[WateringSampleSize]] = AtomicReference(Vector.empty)
  )

  private val minSampleCount: WateringSampleCount = 5
  private val historySize: WateringSampleSize     = 20
  private val overdueGracePeriod: FiniteDuration  = 24.hours

  private def buildMonitor(
      refs: Refs = Refs(),
      now: () => Instant = () => referenceTime,
      getAttentionSamplesResults: Vector[GetAttentionSamplesResult] = Vector(GetAttentionSamplesResult.Read(Vector.empty))
  ) =
    val readIndex = AtomicInteger()
    val store     = new PlantAttentionStore:
      override def getAttentionSamples(size: WateringSampleSize): GetAttentionSamplesResult =
        refs.getAttentionSamplesRequests.updateAndGet(_ :+ size)
        getAttentionSamplesResults
          .lift(readIndex.getAndIncrement())
          .orElse(getAttentionSamplesResults.lastOption)
          .getOrElse(fail("missing getAttentionSamples result"))
    PlantAttentionMonitor.make(minSampleCount, historySize, overdueGracePeriod)(using store, () => now())
