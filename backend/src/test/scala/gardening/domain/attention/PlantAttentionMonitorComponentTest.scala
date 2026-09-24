package gardening.domain.attention

import cats.syntax.option.*
import gardening.domain.*
import io.github.iltotore.iron.autoRefine

import language.experimental.captureChecking

import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.concurrent.duration.*
import scala.jdk.DurationConverters.*

class PlantAttentionMonitorComponentTest extends munit.FunSuite:

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
    val wateringHistory      = WateringHistory.from(wateringDates).fold(message => fail(message), identity)
    val samplesRead          = GetAttentionSamplesResult.Read(Vector(PlantAttentionSample(plant.id, wateringHistory)))
    val sampleResults        = Vector(samplesRead)

    val refs    = Refs(now = () => measurementTime, getAttentionSamplesResults = sampleResults)
    val monitor = buildMonitor(refs).getOrElse(fail("initial attention failed"))

    val expectedWatering   = WateringAttention.Unavailable(sampleCount = 4, maybeElapsed = elapsedSinceWatering.some)
    val expectedProjection = AttentionProjection(measuredAt = measurementTime, plants = Vector(PlantAttention(plant.id, expectedWatering)))

    assertEquals(monitor.current, expectedProjection)
    assertEquals(refs.getAttentionSamplesRequests.get(), Vector(20))

  test(s"should return ${WateringAttention.Current} when a plant was last watered one average interval ago"):
    val measurementTime      = referenceTime
    val averageInterval      = 1.day
    val elapsedSinceWatering = averageInterval
    val latestWatering       = measurementTime - elapsedSinceWatering
    val wateringDates        = Vector.tabulate(5)(index => latestWatering - averageInterval * index.toLong)
    val wateringHistory      = WateringHistory.from(wateringDates).fold(message => fail(message), identity)
    val samplesRead          = GetAttentionSamplesResult.Read(Vector(PlantAttentionSample(plant.id, wateringHistory)))
    val sampleResults        = Vector(samplesRead)

    val refs    = Refs(now = () => measurementTime, getAttentionSamplesResults = sampleResults)
    val monitor = buildMonitor(refs).getOrElse(fail("initial attention failed"))

    val expectedWatering   = WateringAttention.Current(sampleCount = 5, averageInterval = averageInterval, elapsed = elapsedSinceWatering)
    val expectedProjection = AttentionProjection(measuredAt = measurementTime, plants = Vector(PlantAttention(plant.id, expectedWatering)))

    assertEquals(monitor.current, expectedProjection)

  test(s"should return ${WateringAttention.Overdue} when a plant was last watered more than one average interval ago"):
    val measurementTime      = referenceTime
    val averageInterval      = 1.day
    val elapsedSinceWatering = averageInterval + 1.milli
    val latestWatering       = measurementTime - elapsedSinceWatering
    val wateringDates        = Vector.tabulate(5)(index => latestWatering - averageInterval * index.toLong)
    val wateringHistory      = WateringHistory.from(wateringDates).fold(message => fail(message), identity)
    val samplesRead          = GetAttentionSamplesResult.Read(Vector(PlantAttentionSample(plant.id, wateringHistory)))
    val sampleResults        = Vector(samplesRead)

    val refs    = Refs(now = () => measurementTime, getAttentionSamplesResults = sampleResults)
    val monitor = buildMonitor(refs).getOrElse(fail("initial attention failed"))

    val expectedWatering   = WateringAttention.Overdue(sampleCount = 5, averageInterval = averageInterval, elapsed = elapsedSinceWatering)
    val expectedProjection = AttentionProjection(measuredAt = measurementTime, plants = Vector(PlantAttention(plant.id, expectedWatering)))

    assertEquals(monitor.current, expectedProjection)

  test(s"should return ${WateringAttention.Overdue} until a plant is 24 hours past its average watering interval"):
    val measurementTime      = referenceTime
    val averageInterval      = 1.day
    val elapsedSinceWatering = averageInterval + 24.hours - 1.milli
    val latestWatering       = measurementTime - elapsedSinceWatering
    val wateringDates        = Vector.tabulate(5)(index => latestWatering - averageInterval * index.toLong)
    val wateringHistory      = WateringHistory.from(wateringDates).fold(message => fail(message), identity)
    val samplesRead          = GetAttentionSamplesResult.Read(Vector(PlantAttentionSample(plant.id, wateringHistory)))
    val sampleResults        = Vector(samplesRead)

    val refs    = Refs(now = () => measurementTime, getAttentionSamplesResults = sampleResults)
    val monitor = buildMonitor(refs).getOrElse(fail("initial attention failed"))

    val expectedWatering   = WateringAttention.Overdue(sampleCount = 5, averageInterval = averageInterval, elapsed = elapsedSinceWatering)
    val expectedProjection = AttentionProjection(measuredAt = measurementTime, plants = Vector(PlantAttention(plant.id, expectedWatering)))

    assertEquals(monitor.current, expectedProjection)

  test(s"should return ${WateringAttention.RedAlert} when a plant reaches 24 hours past its average watering interval"):
    val measurementTime      = referenceTime
    val averageInterval      = 1.day
    val elapsedSinceWatering = averageInterval + 24.hours
    val latestWatering       = measurementTime - elapsedSinceWatering
    val wateringDates        = Vector.tabulate(5)(index => latestWatering - averageInterval * index.toLong)
    val wateringHistory      = WateringHistory.from(wateringDates).fold(message => fail(message), identity)
    val samplesRead          = GetAttentionSamplesResult.Read(Vector(PlantAttentionSample(plant.id, wateringHistory)))
    val sampleResults        = Vector(samplesRead)

    val refs    = Refs(now = () => measurementTime, getAttentionSamplesResults = sampleResults)
    val monitor = buildMonitor(refs).getOrElse(fail("initial attention failed"))

    val expectedWatering   = WateringAttention.RedAlert(sampleCount = 5, averageInterval = averageInterval, elapsed = elapsedSinceWatering)
    val expectedProjection = AttentionProjection(measuredAt = measurementTime, plants = Vector(PlantAttention(plant.id, expectedWatering)))

    assertEquals(monitor.current, expectedProjection)

  test(s"should return ${WateringAttention.RedAlert} when a plant exceeds 24 hours past its average watering interval"):
    val measurementTime      = referenceTime
    val averageInterval      = 1.day
    val elapsedSinceWatering = averageInterval + 24.hours + 1.milli
    val latestWatering       = measurementTime - elapsedSinceWatering
    val wateringDates        = Vector.tabulate(5)(index => latestWatering - averageInterval * index.toLong)
    val wateringHistory      = WateringHistory.from(wateringDates).fold(message => fail(message), identity)
    val samplesRead          = GetAttentionSamplesResult.Read(Vector(PlantAttentionSample(plant.id, wateringHistory)))
    val sampleResults        = Vector(samplesRead)

    val refs    = Refs(now = () => measurementTime, getAttentionSamplesResults = sampleResults)
    val monitor = buildMonitor(refs).getOrElse(fail("initial attention failed"))

    val expectedWatering   = WateringAttention.RedAlert(sampleCount = 5, averageInterval = averageInterval, elapsed = elapsedSinceWatering)
    val expectedProjection = AttentionProjection(measuredAt = measurementTime, plants = Vector(PlantAttention(plant.id, expectedWatering)))

    assertEquals(monitor.current, expectedProjection)

  test("should publish an empty projection when there are no active plants"):
    val refs               = Refs()
    val monitor            = buildMonitor(refs).getOrElse(fail("initial attention failed"))
    val expectedProjection = AttentionProjection(measuredAt = referenceTime, plants = Vector.empty)

    assertEquals(monitor.current, expectedProjection)

  test(s"should return ${WateringAttention.Unavailable} when a plant has no watering samples"):
    val wateringHistory = WateringHistory.from(Vector.empty).fold(message => fail(message), identity)
    val samplesRead     = GetAttentionSamplesResult.Read(Vector(PlantAttentionSample(plant.id, wateringHistory)))
    val sampleResults   = Vector(samplesRead)

    val refs    = Refs(getAttentionSamplesResults = sampleResults)
    val monitor = buildMonitor(refs).getOrElse(fail("initial attention failed"))

    val expectedWatering   = WateringAttention.Unavailable(sampleCount = 0, maybeElapsed = none)
    val expectedProjection = AttentionProjection(measuredAt = referenceTime, plants = Vector(PlantAttention(plant.id, expectedWatering)))

    assertEquals(monitor.current, expectedProjection)

  test(s"should return ${WateringAttention.Current} when watering timestamps and elapsed time are equal"):
    val wateringDates   = Vector.fill(5)(referenceTime)
    val wateringHistory = WateringHistory.from(wateringDates).fold(message => fail(message), identity)
    val samplesRead     = GetAttentionSamplesResult.Read(Vector(PlantAttentionSample(plant.id, wateringHistory)))
    val sampleResults   = Vector(samplesRead)

    val refs    = Refs(getAttentionSamplesResults = sampleResults)
    val monitor = buildMonitor(refs).getOrElse(fail("initial attention failed"))

    val expectedWatering   = WateringAttention.Current(sampleCount = 5, averageInterval = 0.millis, elapsed = 0.millis)
    val expectedProjection = AttentionProjection(measuredAt = referenceTime, plants = Vector(PlantAttention(plant.id, expectedWatering)))

    assertEquals(monitor.current, expectedProjection)

  test(s"should return ${WateringAttention.Overdue} when equal watering timestamps have positive elapsed time"):
    val measurementTime = referenceTime + 1.milli
    val wateringDates   = Vector.fill(5)(referenceTime)
    val wateringHistory = WateringHistory.from(wateringDates).fold(message => fail(message), identity)
    val samplesRead     = GetAttentionSamplesResult.Read(Vector(PlantAttentionSample(plant.id, wateringHistory)))
    val sampleResults   = Vector(samplesRead)

    val refs    = Refs(now = () => measurementTime, getAttentionSamplesResults = sampleResults)
    val monitor = buildMonitor(refs).getOrElse(fail("initial attention failed"))

    val expectedWatering   = WateringAttention.Overdue(sampleCount = 5, averageInterval = 0.millis, elapsed = 1.milli)
    val expectedProjection = AttentionProjection(measuredAt = measurementTime, plants = Vector(PlantAttention(plant.id, expectedWatering)))

    assertEquals(monitor.current, expectedProjection)

  test(s"should return ${WateringAttention.Current} immediately after construction before any refresh"):
    val initialMeasurementTime = referenceTime
    val averageInterval        = 1.day
    val initialElapsed         = 12.hours
    val latestWatering         = initialMeasurementTime - initialElapsed
    val wateringDates          = Vector.tabulate(5)(index => latestWatering - averageInterval * index.toLong)
    val wateringHistory        = WateringHistory.from(wateringDates).fold(message => fail(message), identity)
    val samplesRead            = GetAttentionSamplesResult.Read(Vector(PlantAttentionSample(plant.id, wateringHistory)))
    val sampleResults          = Vector(samplesRead)

    val currentTime = AtomicReference(initialMeasurementTime)
    val refs        = Refs(now = () => currentTime.get(), getAttentionSamplesResults = sampleResults)
    val monitor     = buildMonitor(refs).getOrElse(fail("initial attention failed"))

    val initialWatering    = WateringAttention.Current(sampleCount = 5, averageInterval = averageInterval, elapsed = initialElapsed)
    val expectedProjection = AttentionProjection(measuredAt = initialMeasurementTime, plants = Vector(PlantAttention(plant.id, initialWatering)))

    assertEquals(monitor.current, expectedProjection)

  test(s"should return ${WateringAttention.Overdue} when refreshed to a later time without new waterings"):
    val initialMeasurementTime = referenceTime
    val averageInterval        = 1.day
    val initialElapsed         = 12.hours
    val latestWatering         = initialMeasurementTime - initialElapsed
    val wateringDates          = Vector.tabulate(5)(index => latestWatering - averageInterval * index.toLong)
    val wateringHistory        = WateringHistory.from(wateringDates).fold(message => fail(message), identity)
    val samplesRead            = GetAttentionSamplesResult.Read(Vector(PlantAttentionSample(plant.id, wateringHistory)))
    val sampleResults          = Vector(samplesRead)

    val currentTime              = AtomicReference(initialMeasurementTime)
    val refs                     = Refs(now = () => currentTime.get(), getAttentionSamplesResults = sampleResults)
    val monitor                  = buildMonitor(refs).getOrElse(fail("initial attention failed"))
    val refreshedMeasurementTime = initialMeasurementTime + 1.day
    currentTime.set(refreshedMeasurementTime)
    val refreshResult = monitor.refreshAll

    val refreshedElapsed    = initialElapsed + 1.day
    val refreshedWatering   = WateringAttention.Overdue(sampleCount = 5, averageInterval = averageInterval, elapsed = refreshedElapsed)
    val refreshedProjection = AttentionProjection(measuredAt = refreshedMeasurementTime, plants = Vector(PlantAttention(plant.id, refreshedWatering)))
    val expectedRefresh     = RefreshAttentionResult.Refreshed(refreshedProjection)

    assertEquals(refreshResult, expectedRefresh)

  test(s"should infer ${WateringAttention.Current} from a fifth recorded watering when refreshed"):
    val measurementTime       = referenceTime
    val averageInterval       = 1.day
    val elapsedSinceWatering  = 12.hours
    val latestWatering        = measurementTime - elapsedSinceWatering
    val beforeWateringDates   = Vector.tabulate(4)(index => latestWatering - averageInterval * index.toLong)
    val beforeWateringHistory = WateringHistory.from(beforeWateringDates).fold(message => fail(message), identity)
    val beforeWatering        = GetAttentionSamplesResult.Read(Vector(PlantAttentionSample(plant.id, beforeWateringHistory)))
    val afterWateringDates    = Vector.tabulate(5)(index => latestWatering - averageInterval * index.toLong)
    val afterWateringHistory  = WateringHistory.from(afterWateringDates).fold(message => fail(message), identity)
    val afterWatering         = GetAttentionSamplesResult.Read(Vector(PlantAttentionSample(plant.id, afterWateringHistory)))
    val sampleResults         = Vector(beforeWatering, afterWatering)

    val refs    = Refs(now = () => measurementTime, getAttentionSamplesResults = sampleResults)
    val monitor = buildMonitor(refs).getOrElse(fail("initial attention failed"))

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
    val beforeRemovalHistory = WateringHistory.from(beforeRemovalDates).fold(message => fail(message), identity)
    val beforeRemoval        = GetAttentionSamplesResult.Read(Vector(PlantAttentionSample(plant.id, beforeRemovalHistory)))
    val afterRemovalDates    = Vector.tabulate(4)(index => latestWatering - averageInterval * index.toLong)
    val afterRemovalHistory  = WateringHistory.from(afterRemovalDates).fold(message => fail(message), identity)
    val afterRemoval         = GetAttentionSamplesResult.Read(Vector(PlantAttentionSample(plant.id, afterRemovalHistory)))
    val sampleResults        = Vector(beforeRemoval, afterRemoval)

    val refs    = Refs(now = () => measurementTime, getAttentionSamplesResults = sampleResults)
    val monitor = buildMonitor(refs).getOrElse(fail("initial attention failed"))

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
    val wateringHistory      = WateringHistory.from(wateringDates).fold(message => fail(message), identity)
    val samplesRead          = GetAttentionSamplesResult.Read(Vector(PlantAttentionSample(plant.id, wateringHistory)))
    val failure              = RuntimeException("store down")
    val samplesReadFailed    = GetAttentionSamplesResult.ReadFailed(failure)
    val sampleResults        = Vector(samplesRead, samplesReadFailed)

    val refs              = Refs(now = () => measurementTime, getAttentionSamplesResults = sampleResults)
    val monitor           = buildMonitor(refs).getOrElse(fail("initial attention failed"))
    val initialProjection = monitor.current

    val expectedRefreshResult = RefreshAttentionResult.RefreshFailed(failure)

    assertEquals(monitor.refreshAll, expectedRefreshResult)
    assertEquals(monitor.current, initialProjection)

  test("should exclude an archived plant after refreshing attention from active samples"):
    val wateringHistory = WateringHistory.from(Vector.empty).fold(message => fail(message), identity)
    val remainingPlant  = plant.copy(id = PlantId("still-active"))
    val initialSamples  = GetAttentionSamplesResult.Read(
      Vector(PlantAttentionSample(plant.id, wateringHistory), PlantAttentionSample(remainingPlant.id, wateringHistory))
    )
    val refreshedSamples = GetAttentionSamplesResult.Read(Vector(PlantAttentionSample(remainingPlant.id, wateringHistory)))
    val refs             = Refs(getAttentionSamplesResults = Vector(initialSamples, refreshedSamples))
    val monitor          = buildMonitor(refs).getOrElse(fail("initial attention failed"))

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

    val refs          = Refs(getAttentionSamplesResults = Vector(samplesReadFailed))
    val monitorResult = buildMonitor(refs)

    val expectedResult = Left(failure)

    assertEquals(monitorResult, expectedResult)

  extension (instant: Instant)
    private def +(duration: FiniteDuration) = instant.plus(duration.toJava)
    private def -(duration: FiniteDuration) = instant.minus(duration.toJava)

  private case class Refs(
      now: () => Instant = () => referenceTime,
      getAttentionSamplesResults: Vector[GetAttentionSamplesResult] = Vector(GetAttentionSamplesResult.Read(Vector.empty)),
      getAttentionSamplesRequests: AtomicReference[Vector[WateringSampleSize]] = AtomicReference(Vector.empty)
  )

  private def buildMonitor(refs: Refs) =
    val readIndex = AtomicInteger()
    val store     = new PlantAttentionStore:
      override def getAttentionSamples(size: WateringSampleSize): GetAttentionSamplesResult =
        refs.getAttentionSamplesRequests.updateAndGet(_ :+ size)
        refs.getAttentionSamplesResults
          .lift(readIndex.getAndIncrement())
          .orElse(refs.getAttentionSamplesResults.lastOption)
          .getOrElse(fail("missing getAttentionSamples result"))
    PlantAttentionMonitor.make(using store, () => refs.now())
