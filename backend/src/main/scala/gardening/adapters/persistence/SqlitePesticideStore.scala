package gardening.adapters.persistence

import cats.syntax.traverse.*
import com.augustnagro.magnum.*
import gardening.domain.*
import gardening.domain.catalog.*
import gardening.domain.pesticide.{GetPesticideResult, PesticideStore, UpdatePesticideResult}

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

    override def getPesticide(id: PesticideId): GetPesticideResult =
      try
        connect(transactor)(selectPesticide(id.value.toString).query[PesticideRow].run().headOption) match
          case None      => GetPesticideResult.RecordMissing
          case Some(row) => GetPesticideResult.Read(trust(toPesticide(row)))
      catch case error: SqlException => GetPesticideResult.ReadFailed(error)

    override def addPesticide(pesticide: Pesticide): CatalogAddResult[Pesticide] =
      try
        val id   = pesticide.id.value.toString
        val data = pesticide.data
        transact(transactor):
          sql"insert into pesticide (id, name, type, info, status) values ($id, ${data.name.value}, ${data.kind.toString}, ${data.maybeInfo.map(_.value)}, ${pesticide.status.toString})"
            .update.run()
        CatalogAddResult.Added(pesticide)
      catch case error: SqlException => CatalogAddResult.AddFailed(error)

    override def updatePesticide(pesticide: Pesticide): UpdatePesticideResult =
      try
        transact(transactor)(updatePesticideRow(pesticide).update.run()) match
          case 1 => UpdatePesticideResult.Updated
          case _ => UpdatePesticideResult.UpdateFailed(RuntimeException(s"pesticide not found while updating: ${pesticide.id.value}"))
      catch case error: SqlException => UpdatePesticideResult.UpdateFailed(error)

    private def selectPesticide(id: String) =
      sql"select id, name, type, info, status from pesticide where id = $id"

    private def updatePesticideRow(pesticide: Pesticide) =
      val data = pesticide.data
      sql"""update pesticide set name = ${data.name.value}, type = ${data.kind.toString}, info = ${data.maybeInfo.map(_.value)},
           status = ${pesticide.status.toString} where id = ${pesticide.id.value.toString}"""

    private def toPesticide(row: PesticideRow) =
      for
        id <- PesticideId
          .parse(row.id)
          .toRight(RuntimeException(s"invalid pesticide id: ${row.id}"))
        kind <- Try(PesticideType.valueOf(row.kind)).toEither.left.map:
          error =>
            // The schema check mirrors PesticideType; extending it requires a migration first.
            // $COVERAGE-OFF$
            RuntimeException(s"invalid pesticide type: ${row.kind}", error)
            // $COVERAGE-ON$
        status <- Try(PesticideStatus.valueOf(row.status)).toEither.left.map:
          error =>
            // The schema check mirrors PesticideStatus; extending it requires a migration first.
            // $COVERAGE-OFF$
            RuntimeException(s"invalid pesticide status: ${row.status}", error)
            // $COVERAGE-ON$
      yield Pesticide(id, PesticideData(PesticideName(row.name), kind, row.info.map(PesticideInfo.apply)), status)

    @SuppressWarnings(Array("org.wartremover.warts.TryPartial"))
    private def trust[A](decoded: Either[Throwable, A]) =
      decoded.left.map(DatabaseCorruption.apply).toTry.get

  private case class PesticideRow(id: String, name: String, kind: String, info: Option[String], status: String) derives DbCodec
