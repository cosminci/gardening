package gardening.adapters.persistence

import cats.syntax.either.*
import cats.syntax.traverse.*
import com.augustnagro.magnum.*
import gardening.domain.*
import gardening.domain.attention.*
import gardening.domain.plants.*
import io.circe.parser.decode
import io.circe.syntax.*
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder
import java.util.UUID
import scala.util.Try

import Codecs.given

object SqlitePlantStore:

  private val timestampFormatter = DateTimeFormatterBuilder().appendInstant(9).toFormatter

  def make(transactor: Transactor): PlantStore & PlantAttentionStore = LiveSqlitePlantStore(transactor)

  private class LiveSqlitePlantStore(transactor: Transactor) extends PlantStore, PlantAttentionStore:

    override def addPlant(plant: Plant): AddPlantResult =
      try
        val details = plant.details
        transact(transactor):
          sql"""insert into plant (id, species, nickname, location, substrate, status)
               values (${plant.id.value}, ${details.species.value}, ${details.maybeNickname.map(_.value)},
                       ${details.location.value}, ${details.substrate.asJson.noSpaces}, ${details.status.toString})""".update.run()
        AddPlantResult.Added
      catch case error: SqlException => AddPlantResult.AddFailed(error)

    override def getPlants(status: PlantStatus): GetPlantsResult =
      try
        val rows = connect(transactor):
          sql"select id, species, nickname, location, substrate, status from plant where status = ${status.toString} order by rowid".query[
            PlantRow
          ].run()
        val plants = trust(rows.traverse(toPlant))
        GetPlantsResult.Read(plants)
      catch
        case error: SqlException       => GetPlantsResult.ReadFailed(error)
        case error: DatabaseCorruption => GetPlantsResult.ReadFailed(error)

    override def getArchivedCount: ArchivedCountResult =
      try
        connect(transactor)(sql"select count(*) from plant where status = 'Archived'".query[Long].run().headOption) match
          case Some(count) => ArchivedCountResult.Counted(count)
          // An aggregate without GROUP BY always returns exactly one row.
          // $COVERAGE-OFF$
          case None => ArchivedCountResult.ReadFailed(DatabaseCorruption(IllegalStateException("archived plant count query returned no row")))
          // $COVERAGE-ON$
      catch case error: SqlException => ArchivedCountResult.ReadFailed(error)

    override def getPlant(plant: PlantId): GetPlantResult =
      try
        connect(transactor)(selectPlant(plant.value).query[PlantRow].run().headOption) match
          case None      => GetPlantResult.RecordMissing
          case Some(row) => GetPlantResult.Read(trust(toPlant(row)))
      catch
        case error: SqlException       => GetPlantResult.ReadFailed(error)
        case error: DatabaseCorruption => GetPlantResult.ReadFailed(error)

    override def updatePlant(plant: Plant): UpdatePlantResult =
      try
        transact(transactor)(updatePlantRow(plant).update.run()) match
          case 1 => UpdatePlantResult.Updated
          case _ => UpdatePlantResult.UpdateFailed(RuntimeException(s"plant not found while updating: ${plant.id.value}"))
      catch case error: SqlException => UpdatePlantResult.UpdateFailed(error)

    private def selectPlant(id: String) =
      sql"select id, species, nickname, location, substrate, status from plant where id = $id"

    private def toPlant(row: PlantRow) =
      for
        substrate <- decode[Substrate](row.substrate).leftMap(invalidSubstrate)
        status    <- PlantStatus.values
          .find(_.toString.equals(row.status))
          .toRight(
            // The schema check constrains every stored status to a PlantStatus name.
            // $COVERAGE-OFF$
            RuntimeException(s"invalid stored plant status: ${row.status}")
            // $COVERAGE-ON$
          )
      yield
        val details = PlantDetails(Species(row.species), row.nickname.map(Nickname.apply), Location(row.location), substrate, status)
        Plant(PlantId(row.id), details)

    override def getAttentionSamples(size: WateringSampleSize): GetAttentionSamplesResult =
      try
        val rows    = connect(transactor)(selectAttentionSamples(size).query[AttentionSampleRow].run())
        val samples = trust(rows.traverse(toAttentionSample))
        GetAttentionSamplesResult.Read(samples)
      catch
        case error: SqlException       => GetAttentionSamplesResult.ReadFailed(error)
        case error: DatabaseCorruption => GetAttentionSamplesResult.ReadFailed(error)

    private def selectAttentionSamples(size: WateringSampleSize) =
      val sampleSize: Int = size
      sql"""select plant.id,
                   coalesce((
                     select json_group_array(watering.date order by watering.date desc, watering.id desc)
                     from (
                       select operation.id, operation.date
                       from operation
                       where operation.plant_id = plant.id
                         and operation.kind = 'Care'
                         and exists (
                           select 1
                           from json_each(operation.payload, '$$.actions')
                           where value = 'Watered'
                         )
                       order by operation.date desc, operation.id desc
                       limit $sampleSize
                     ) watering
                   ), json('[]')) as watering_dates
            from plant
            where plant.status = 'Active'
            order by plant.rowid"""

    private def toAttentionSample(row: AttentionSampleRow) =
      val storedDates = decodeWateringDates(row.wateringDates)
      for
        wateringDates   <- storedDates.traverse(parseTimestamp)
        wateringHistory <- WateringHistory
          .from(wateringDates)
          .leftMap:
            message =>
              // The SQL query limits each history to the maximum representable length.
              // $COVERAGE-OFF$
              DatabaseCorruption(RuntimeException(s"invalid stored watering history: $message"))
              // $COVERAGE-ON$
      yield PlantAttentionSample(PlantId(row.id), wateringHistory)

    private def decodeWateringDates(value: String) =
      trust(decode[Vector[String]](value))

    private def parseTimestamp(value: String) =
      Try(Instant.parse(value)).toEither.left.map(_ => RuntimeException(s"invalid stored operation date: $value"))

    override def addPhoto(photo: PlantPhoto): AddPhotoResult =
      try
        transact(transactor):
          sql"""insert into plant_photo (id, plant_id, captured_at)
               select ${photo.id.value.toString}, ${photo.plantId.value}, ${timestampFormatter.format(photo.capturedAt)}
               where exists (select 1 from plant where id = ${photo.plantId.value})""".update.run() match
            case 1 => AddPhotoResult.Added(photo)
            case _ =>
              sql"select id from plant where id = ${photo.plantId.value}".query[String].run().headOption match
                case None => AddPhotoResult.PlantMissing
                // A row for this plant_id exists but insert failed: treated as AddFailed below.
                // $COVERAGE-OFF$
                case Some(_) => AddPhotoResult.AddFailed(RuntimeException(s"photo insert returned 0 rows for plant: ${photo.plantId.value}"))
                // $COVERAGE-ON$
      catch case error: SqlException => AddPhotoResult.AddFailed(error)

    override def removePhoto(photo: PhotoId): RemovePhotoResult =
      try
        transact(transactor):
          sql"delete from plant_photo where id = ${photo.value.toString} returning id, plant_id, captured_at"
            .query[PlantPhotoRow]
            .run()
            .headOption match
            case None      => RemovePhotoResult.PhotoMissing
            case Some(row) => RemovePhotoResult.Removed(trust(toPhoto(row)))
      catch
        case error: SqlException       => RemovePhotoResult.RemoveFailed(error)
        case error: DatabaseCorruption => RemovePhotoResult.RemoveFailed(error)

    override def getPhotos(plant: PlantId, window: PhotoWindow): GetPhotosResult =
      try
        val readSize: Int = window.size + 1
        val offset: Int   = window.offset
        val rows          = connect(transactor):
          sql"""select id, plant_id, captured_at from plant_photo
                where plant_id = ${plant.value}
                order by captured_at desc, id desc
                limit $readSize offset $offset""".query[PlantPhotoRow].run()
        val photos = trust(rows.traverse(toPhoto))
        GetPhotosResult.Read(PhotoPage(photos.take(window.size), photos.size > window.size))
      catch
        case error: SqlException       => GetPhotosResult.ReadFailed(error)
        case error: DatabaseCorruption => GetPhotosResult.ReadFailed(error)

    private def toPhoto(row: PlantPhotoRow): Either[Throwable, PlantPhoto] =
      for
        capturedAt <- Try(Instant.parse(row.capturedAt)).toEither.left.map(_ =>
          RuntimeException(s"invalid stored photo capturedAt: ${row.capturedAt}")
        )
        id <- Try(UUID.fromString(row.id)).toEither.left.map(_ =>
          RuntimeException(s"invalid stored photo id: ${row.id}")
        )
      yield PlantPhoto(PhotoId(id), PlantId(row.plantId), capturedAt)

    @SuppressWarnings(Array("org.wartremover.warts.TryPartial"))
    private def trust[A](decoded: Either[Throwable, A]) =
      // Writes are validated before persistence; a decode failure is an invariant violation.
      decoded.left.map(DatabaseCorruption.apply).toTry.get

    private def updatePlantRow(plant: Plant) =
      val details = plant.details
      sql"""update plant
           set species = ${details.species.value},
               nickname = ${details.maybeNickname.map(_.value)},
               location = ${details.location.value},
               substrate = ${details.substrate.asJson.noSpaces},
               status = ${details.status.toString}
           where id = ${plant.id.value} and (status = ${details.status.toString} or (status = 'Active' and ${details.status.toString} = 'Archived'))"""

    private def invalidSubstrate(reason: io.circe.Error) =
      RuntimeException(s"invalid stored substrate: ${reason.getMessage}", reason)

  private case class PlantRow(id: String, species: String, nickname: Option[String], location: String, substrate: String, status: String)
      derives DbCodec

  private case class AttentionSampleRow(
      id: String,
      wateringDates: String
  ) derives DbCodec
  private case class PlantPhotoRow(id: String, plantId: String, capturedAt: String) derives DbCodec
