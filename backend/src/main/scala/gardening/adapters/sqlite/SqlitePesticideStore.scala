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
        val (id, data)           = (pesticide.id.value.toString, pesticide.data)
        val (name, status, info) = (data.name.value, pesticide.status.toString, data.maybeInfo.map(_.value))
        transact(transactor):
          val query = sql"insert into pesticide (id, name, type, info, status) values ($id, $name, ${data.kind.toString}, $info, $status)"
          query.update.run()
        AddPesticideResult.Added(pesticide)
      catch case error: SqlException => AddPesticideResult.AddFailed(error)

    override def updatePesticide(pesticide: Pesticide): UpdatePesticideResult =
      try
        val data                     = pesticide.data
        val (id, name, status, info) = (pesticide.id.value.toString, data.name.value, pesticide.status.toString, data.maybeInfo.map(_.value))
        val command = sql"""update pesticide set name = $name, type = ${data.kind.toString}, info = $info, status = $status where id = $id"""
        transact(transactor)(command.update.run()) match
          case 1 => UpdatePesticideResult.Updated
          case _ => UpdatePesticideResult.UpdateFailed(RuntimeException(s"pesticide not found while updating: ${pesticide.id.value}"))
      catch case error: SqlException => UpdatePesticideResult.UpdateFailed(error)

    private def selectPesticide(id: String) =
      sql"select id, name, type, info, status from pesticide where id = $id"

    private def toPesticide(row: PesticideRow) =
      for
        id <- PesticideId.parse(row.id).toRight(RuntimeException(s"invalid pesticide id: ${row.id}"))
        kind   = trust(Try(PesticideType.valueOf(row.kind)).toEither)
        status = trust(Try(PesticideStatus.valueOf(row.status)).toEither)
      yield Pesticide(id, PesticideData(PesticideName(row.name), kind, row.info.map(PesticideInfo.apply)), status)

  private case class PesticideRow(id: String, name: String, kind: String, info: Option[String], status: String) derives DbCodec
