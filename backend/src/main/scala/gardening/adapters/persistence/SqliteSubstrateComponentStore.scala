package gardening.adapters.persistence

import cats.syntax.traverse.*
import com.augustnagro.magnum.*
import gardening.domain.*
import gardening.domain.catalog.*
import gardening.domain.substrate.SubstrateComponentStore

object SqliteSubstrateComponentStore:

  def make(transactor: Transactor): SubstrateComponentStore = LiveSqliteSubstrateComponentStore(transactor)

  private class LiveSqliteSubstrateComponentStore(transactor: Transactor) extends SubstrateComponentStore:

    override def getSubstrateComponents: CatalogReadResult[SubstrateComponent] =
      try
        val components = trust(
          connect(transactor)(sql"select id, name, info from substrate_component order by rowid".query[ComponentRow].run()).traverse(toComponent)
        )
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
          SubstrateComponent(id, SubstrateComponentData(NomenclatureName(row.name), row.info.map(NomenclatureInfo.apply)))

    @SuppressWarnings(Array("org.wartremover.warts.TryPartial"))
    private def trust[A](decoded: Either[Throwable, A]) =
      decoded.left.map(DatabaseCorruption.apply).toTry.get

  private case class ComponentRow(id: String, name: String, info: Option[String]) derives DbCodec
