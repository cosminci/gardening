package gardening.adapters.persistence

import cats.syntax.traverse.*
import com.augustnagro.magnum.*
import gardening.domain.*
import gardening.domain.catalog.*
import gardening.domain.pesticide.{PesticideArchiveResult, PesticideEditResult, PesticideStore}

import scala.util.Try

object SqlitePesticideStore:

  def make(transactor: Transactor): PesticideStore = LiveSqlitePesticideStore(transactor)

  private class LiveSqlitePesticideStore(transactor: Transactor) extends PesticideStore:

    override def getPesticides: CatalogReadResult[Pesticide] =
      try
        val query      = sql"select id, name, type, info, status from pesticide order by rowid"
        val pesticides = trust(connect(transactor)(query.query[PesticideRow].run()).traverse(toPesticide))
        CatalogReadResult.Read(pesticides)
      catch case error: SqlException => CatalogReadResult.ReadFailed(error)

    override def addPesticide(pesticide: Pesticide): CatalogAddResult[Pesticide] =
      try
        val data          = pesticide.data
        val pesticideType = data.pesticideType.toString
        transact(transactor):
          sql"""insert into pesticide (id, name, type, info, status)
               values (${pesticide.id.value.toString}, ${data.name.value}, $pesticideType, ${data.maybeInfo.map(
              _.value
            )}, ${pesticide.status.toString})"""
            .update.run()
        CatalogAddResult.Added(pesticide)
      catch case error: SqlException => CatalogAddResult.AddFailed(error)

    override def editPesticide(id: PesticideId, data: PesticideData): PesticideEditResult =
      try
        transact(transactor):
          sql"""update pesticide set name = ${data.name.value}, type = ${data.pesticideType.toString}, info = ${data.maybeInfo.map(_.value)}
               where id = ${id.value.toString} and status = ${PesticideStatus.Active.toString}""".update.run()
        match
          case 1 => PesticideEditResult.Edited(Pesticide(id, data, PesticideStatus.Active))
          case _ => selectStatus(id) match
              case Some(PesticideStatus.Archived) => PesticideEditResult.PesticideArchived
              // The conditional update already requires status = 'Active'; a still-Active row after a 0-row update cannot happen.
              // $COVERAGE-OFF$
              case Some(PesticideStatus.Active) => PesticideEditResult.EditFailed(RuntimeException(s"pesticide ${id.value} edit affected no rows"))
              // $COVERAGE-ON$
              case None => PesticideEditResult.PesticideMissing
      catch case error: SqlException => PesticideEditResult.EditFailed(error)

    override def archivePesticide(id: PesticideId): PesticideArchiveResult =
      try
        transact(transactor):
          sql"""update pesticide set status = ${PesticideStatus.Archived.toString}
               where id = ${id.value.toString} and status = ${PesticideStatus.Active.toString}""".update.run()
        match
          case 1 =>
            selectPesticide(id) match
              case Some(pesticide) => PesticideArchiveResult.Archived(pesticide)
              // A row just updated by this same call cannot vanish before the following read.
              // $COVERAGE-OFF$
              case None => PesticideArchiveResult.ArchiveFailed(RuntimeException(s"pesticide ${id.value} archived but could not be re-read"))
              // $COVERAGE-ON$
          case _ => selectStatus(id) match
              case Some(PesticideStatus.Archived) => PesticideArchiveResult.AlreadyArchived
              // The conditional update already requires status = 'Active'; a still-Active row after a 0-row update cannot happen.
              // $COVERAGE-OFF$
              case Some(PesticideStatus.Active) =>
                PesticideArchiveResult.ArchiveFailed(RuntimeException(s"pesticide ${id.value} archive affected no rows"))
              // $COVERAGE-ON$
              case None => PesticideArchiveResult.PesticideMissing
      catch case error: SqlException => PesticideArchiveResult.ArchiveFailed(error)

    private def selectPesticide(id: PesticideId): Option[Pesticide] =
      connect(transactor)(selectRow(id).query[PesticideRow].run()).headOption.map(row => trust(toPesticide(row)))

    private def selectStatus(id: PesticideId): Option[PesticideStatus] =
      connect(transactor)(selectRow(id).query[PesticideRow].run()).headOption.map(row => trust(toPesticide(row)).status)

    private def selectRow(id: PesticideId) =
      sql"select id, name, type, info, status from pesticide where id = ${id.value.toString}"

    private def toPesticide(row: PesticideRow) =
      for
        id <- PesticideId
          .parse(row.id)
          .toRight(RuntimeException(s"invalid pesticide id: ${row.id}"))
        pesticideType <- Try(PesticideType.valueOf(row.pesticideType)).toEither.left.map:
          error =>
            // The schema check mirrors PesticideType; extending it requires a migration first.
            // $COVERAGE-OFF$
            RuntimeException(s"invalid pesticide type: ${row.pesticideType}", error)
            // $COVERAGE-ON$
        status <- Try(PesticideStatus.valueOf(row.status)).toEither.left.map:
          error =>
            // The schema check mirrors PesticideStatus; extending it requires a migration first.
            // $COVERAGE-OFF$
            RuntimeException(s"invalid pesticide status: ${row.status}", error)
            // $COVERAGE-ON$
      yield Pesticide(id, PesticideData(NomenclatureName(row.name), pesticideType, row.info.map(NomenclatureInfo.apply)), status)

    @SuppressWarnings(Array("org.wartremover.warts.TryPartial"))
    private def trust[A](decoded: Either[Throwable, A]) =
      decoded.left.map(DatabaseCorruption.apply).toTry.get

  private case class PesticideRow(id: String, name: String, pesticideType: String, info: Option[String], status: String) derives DbCodec
