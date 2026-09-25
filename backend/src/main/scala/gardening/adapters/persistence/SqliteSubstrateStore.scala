package gardening.adapters.persistence

import cats.syntax.either.*
import cats.syntax.traverse.*
import com.augustnagro.magnum.*
import gardening.domain.*
import gardening.domain.catalog.*
import gardening.domain.substrate.SubstrateStore
import io.circe.parser.decode
import io.circe.syntax.*
import io.circe.{Decoder, Encoder}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.Interval

import java.util.UUID
import scala.util.Try

object SqliteSubstrateStore:

  def make(transactor: Transactor): SubstrateStore = LiveSqliteSubstrateStore(transactor)

  private class LiveSqliteSubstrateStore(transactor: Transactor) extends SubstrateStore:

    override def getSubstrateComponents: CatalogReadResult[SubstrateComponent] =
      try
        val query      = sql"select id, name, info from substrate_component order by rowid"
        val components = trust(connect(transactor)(query.query[ComponentRow].run()).traverse(toComponent))
        CatalogReadResult.Read(components)
      catch case error: SqlException => CatalogReadResult.ReadFailed(error)

    override def addSubstrateComponent(component: SubstrateComponent): CatalogAddResult[SubstrateComponent] =
      try
        val row = ComponentRow(component.id.value.toString, component.data.name.value, component.data.maybeInfo.map(_.value))
        transact(transactor):
          sql"insert into substrate_component (id, name, info) values (${row.id}, ${row.name}, ${row.info})".update.run()
        CatalogAddResult.Added(component)
      catch case error: SqlException => CatalogAddResult.AddFailed(error)

    override def editSubstrateComponent(
        id: SubstrateComponentId,
        data: SubstrateComponentData
    ): CatalogEditResult[SubstrateComponent] =
      try
        transact(transactor):
          sql"""update substrate_component set name = ${data.name.value}, info = ${data.maybeInfo.map(_.value)}
               where id = ${id.value.toString}""".update.run()
        match
          case 1 => CatalogEditResult.Edited(SubstrateComponent(id, data))
          case _ => CatalogEditResult.RecordMissing
      catch case error: SqlException => CatalogEditResult.EditFailed(error)

    private def toComponent(row: ComponentRow) =
      SubstrateComponentId
        .parse(row.id)
        .toRight(RuntimeException(s"invalid substrate component id: ${row.id}"))
        .map: id =>
          SubstrateComponent(id, SubstrateComponentData(SubstrateComponentName(row.name), row.info.map(SubstrateComponentInfo.apply)))

    override def getSubstrateMixes: CatalogReadResult[SubstrateMix] =
      try
        val query = sql"select id, name, notes, substrate from substrate_mix order by rowid"
        val mixes = trust(connect(transactor)(query.query[MixRow].run()).traverse(toMix))
        CatalogReadResult.Read(mixes)
      catch case error: SqlException => CatalogReadResult.ReadFailed(error)

    override def addSubstrateMix(mix: SubstrateMix): CatalogAddResult[SubstrateMix] =
      try
        val row = MixRow(mix.id.toString, mix.name.value, mix.notes.map(_.value), mix.substrate.asJson.noSpaces)
        transact(transactor):
          sql"insert into substrate_mix (id, name, notes, substrate) values (${row.id}, ${row.name}, ${row.notes}, ${row.substrate})".update.run()
        CatalogAddResult.Added(mix)
      catch case error: SqlException => CatalogAddResult.AddFailed(error)

    override def deleteSubstrateMix(id: UUID): CatalogDeleteResult =
      try
        transact(transactor)(sql"delete from substrate_mix where id = ${id.toString}".update.run())
        CatalogDeleteResult.Deleted
      catch case error: SqlException => CatalogDeleteResult.DeleteFailed(error)

    private def toMix(row: MixRow) =
      for
        id        <- Try(UUID.fromString(row.id)).toEither.leftMap(_ => RuntimeException(s"invalid substrate mix id: ${row.id}"))
        substrate <- decode[Substrate](row.substrate).leftMap(invalidSubstrate)
      yield SubstrateMix(id, SubstrateMixName(row.name), row.notes.map(SubstrateMixNotes.apply), substrate)

    private def invalidSubstrate(reason: io.circe.Error) =
      RuntimeException(s"invalid stored substrate mix substrate: ${reason.getMessage}", reason)

    @SuppressWarnings(Array("org.wartremover.warts.TryPartial"))
    private def trust[A](decoded: Either[Throwable, A]) =
      decoded.left.map(DatabaseCorruption.apply).toTry.get

    private given Decoder[SubstrateComponentId] =
      Decoder.decodeString.emap(value => SubstrateComponentId.parse(value).toRight(s"invalid substrate component id: $value"))
    private given Encoder[SubstrateComponentId] = Encoder.encodeString.contramap(_.value.toString)

    private given Decoder[Percentage] = Decoder.decodeInt.emap(value => value.refineOption[Interval.Closed[1, 100]].toRight(s"invalid share: $value"))
    private given Encoder[Percentage] = Encoder.encodeInt.contramap(value => value: Int)

    private given Decoder[SubstratePart] = Decoder.forProduct2("component", "share")(SubstratePart.apply)
    private given Encoder[SubstratePart] = Encoder.forProduct2("component", "share")(part => (part.componentId, part.share))

    private given Decoder[Substrate] = Decoder.decodeList[SubstratePart].emap(parts => Substrate.of(parts).leftMap(_.toString))
    private given Encoder[Substrate] = Encoder.encodeList[SubstratePart].contramap(_.parts)

  private case class ComponentRow(id: String, name: String, info: Option[String]) derives DbCodec
  private case class MixRow(id: String, name: String, notes: Option[String], substrate: String) derives DbCodec
