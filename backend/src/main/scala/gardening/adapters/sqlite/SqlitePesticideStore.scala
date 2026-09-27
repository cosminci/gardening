package gardening.adapters.sqlite

import cats.syntax.traverse.*
import com.augustnagro.magnum.*
import gardening.domain.*
import gardening.domain.pesticide.{AddPesticideResult, GetPesticideResult, GetPesticidesResult, UpdatePesticideResult}
import gardening.ports.PesticideStore

import scala.util.Try

object SqlitePesticideStore:

  def make(transactor: Transactor): PesticideStore = LiveSqlitePesticideStore(transactor)

  private class LiveSqlitePesticideStore(transactor: Transactor) extends PesticideStore:

    override def getPesticides: GetPesticidesResult =
      try
        val query      = sql"select id, name, type, info, status from pesticide order by rowid"
        val pesticides = trust(connect(transactor)(query.query[PesticideRow].run()).traverse(toPesticide))
        GetPesticidesResult.Read(pesticides)
      catch case error: SqlException => GetPesticidesResult.ReadFailed(error)

    override def getPesticide(id: PesticideId): GetPesticideResult =
      try
        connect(transactor)(selectPesticide(id.value.toString).query[PesticideRow].run().headOption) match
          case None      => GetPesticideResult.RecordMissing
          case Some(row) => GetPesticideResult.Read(trust(toPesticide(row)))
      catch case error: SqlException => GetPesticideResult.ReadFailed(error)

    override def addPesticide(pesticide: Pesticide): AddPesticideResult =
      try
        val id   = pesticide.id.value.toString
        val data = pesticide.data
        transact(transactor):
          sql"insert into pesticide (id, name, type, info, status) values ($id, ${data.name.value}, ${data.kind.toString}, ${data.maybeInfo.map(_.value)}, ${pesticide.status.toString})"
            .update.run()
        AddPesticideResult.Added(pesticide)
      catch case error: SqlException => AddPesticideResult.AddFailed(error)

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
        id <- PesticideId.parse(row.id).toRight(RuntimeException(s"invalid pesticide id: ${row.id}"))
        kind   = trust(Try(PesticideType.valueOf(row.kind)).toEither)
        status = trust(Try(PesticideStatus.valueOf(row.status)).toEither)
      yield Pesticide(id, PesticideData(PesticideName(row.name), kind, row.info.map(PesticideInfo.apply)), status)

  private case class PesticideRow(id: String, name: String, kind: String, info: Option[String], status: String) derives DbCodec
