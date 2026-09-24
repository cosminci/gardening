package gardening.adapters.persistence

import cats.syntax.traverse.*
import com.augustnagro.magnum.*
import gardening.domain.*
import gardening.domain.catalog.*
import gardening.domain.pesticide.PesticideStore

import scala.util.Try

object SqlitePesticideStore:

  def make(transactor: Transactor): PesticideStore = LiveSqlitePesticideStore(transactor)

  private class LiveSqlitePesticideStore(transactor: Transactor) extends PesticideStore:

    override def getPesticides: CatalogReadResult[Pesticide] =
      try
        val query      = sql"select id, name, type, info from pesticide order by rowid"
        val pesticides = trust(connect(transactor)(query.query[PesticideRow].run()).traverse(toPesticide))
        CatalogReadResult.Read(pesticides)
      catch case error: SqlException => CatalogReadResult.ReadFailed(error)

    override def addPesticide(pesticide: Pesticide): CatalogAddResult[Pesticide] =
      try
        val data          = pesticide.data
        val pesticideType = data.pesticideType.toString
        transact(transactor):
          sql"""insert into pesticide (id, name, type, info)
               values (${pesticide.id.value.toString}, ${data.name.value}, $pesticideType, ${data.maybeInfo.map(_.value)})""".update.run()
        CatalogAddResult.Added(pesticide)
      catch case error: SqlException => CatalogAddResult.AddFailed(error)

    override def editPesticide(id: PesticideId, data: PesticideData): CatalogEditResult[Pesticide] =
      try
        transact(transactor):
          sql"""update pesticide set name = ${data.name.value}, type = ${data.pesticideType.toString}, info = ${data.maybeInfo.map(_.value)}
               where id = ${id.value.toString}""".update.run()
        match
          case 1 => CatalogEditResult.Edited(Pesticide(id, data))
          case _ => CatalogEditResult.RecordMissing
      catch case error: SqlException => CatalogEditResult.EditFailed(error)

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
      yield Pesticide(id, PesticideData(NomenclatureName(row.name), pesticideType, row.info.map(NomenclatureInfo.apply)))

    @SuppressWarnings(Array("org.wartremover.warts.TryPartial"))
    private def trust[A](decoded: Either[Throwable, A]) =
      decoded.left.map(DatabaseCorruption.apply).toTry.get

  private case class PesticideRow(id: String, name: String, pesticideType: String, info: Option[String]) derives DbCodec
