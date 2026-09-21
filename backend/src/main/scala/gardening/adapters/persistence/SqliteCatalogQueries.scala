package gardening.adapters.persistence

import cats.syntax.traverse.*
import com.augustnagro.magnum.*
import gardening.domain.*

private[persistence] object SqliteCatalogQueries:

  def getSubstrateComponents(transactor: Transactor): CatalogReadResult[SubstrateComponent] =
    try
      readComponents(connect(transactor)(sql"select id, name, info from substrate_component order by rowid".query[ComponentRow].run()))
    catch case error: SqlException => CatalogReadResult.ReadFailed(error)

  def addSubstrateComponent(transactor: Transactor, component: SubstrateComponent): CatalogAddResult[SubstrateComponent] =
    try
      val row = ComponentRow(component.id.value.toString, component.data.name.value, component.data.maybeInfo.map(_.value))
      transact(transactor):
        sql"insert into substrate_component (id, name, info) values (${row.id}, ${row.name}, ${row.info})".update.run()
      CatalogAddResult.Added(component)
    catch case error: SqlException => CatalogAddResult.AddFailed(error)

  def editSubstrateComponent(
      transactor: Transactor,
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

  def getPesticides(transactor: Transactor): CatalogReadResult[Pesticide] =
    try
      readPesticides(connect(transactor)(sql"select id, name, type, info from pesticide order by rowid".query[PesticideRow].run()))
    catch case error: SqlException => CatalogReadResult.ReadFailed(error)

  def addPesticide(transactor: Transactor, pesticide: Pesticide): CatalogAddResult[Pesticide] =
    try
      val data = pesticide.data
      transact(transactor):
        sql"""insert into pesticide (id, name, type, info)
             values (${pesticide.id.value.toString}, ${data.name.value}, ${data.pesticideType.value}, ${data.maybeInfo.map(_.value)})""".update.run()
      CatalogAddResult.Added(pesticide)
    catch case error: SqlException => CatalogAddResult.AddFailed(error)

  def editPesticide(transactor: Transactor, id: PesticideId, data: PesticideData): CatalogEditResult[Pesticide] =
    try
      transact(transactor):
        sql"""update pesticide set name = ${data.name.value}, type = ${data.pesticideType.value}, info = ${data.maybeInfo.map(_.value)}
             where id = ${id.value.toString}""".update.run()
      match
        case 1 => CatalogEditResult.Edited(Pesticide(id, data))
        case _ => CatalogEditResult.RecordMissing
    catch case error: SqlException => CatalogEditResult.EditFailed(error)

  private def readComponents(rows: Vector[ComponentRow]): CatalogReadResult[SubstrateComponent] =
    rows.traverse(toComponent).fold(CatalogReadResult.ReadFailed.apply, CatalogReadResult.Read.apply)

  private def toComponent(row: ComponentRow): Either[Throwable, SubstrateComponent] =
    SubstrateComponentId.parse(row.id)
      .toRight(RuntimeException(s"invalid substrate component id: ${row.id}"))
      .map: id =>
        SubstrateComponent(id, SubstrateComponentData(NomenclatureName(row.name), row.info.map(NomenclatureInfo.apply)))

  private def readPesticides(rows: Vector[PesticideRow]): CatalogReadResult[Pesticide] =
    rows.traverse(toPesticide).fold(CatalogReadResult.ReadFailed.apply, CatalogReadResult.Read.apply)

  private def toPesticide(row: PesticideRow): Either[Throwable, Pesticide] =
    PesticideId.parse(row.id)
      .toRight(RuntimeException(s"invalid pesticide id: ${row.id}"))
      .map: id =>
        Pesticide(id, PesticideData(NomenclatureName(row.name), PesticideType(row.pesticideType), row.info.map(NomenclatureInfo.apply)))

  private case class ComponentRow(id: String, name: String, info: Option[String]) derives DbCodec
  private case class PesticideRow(id: String, name: String, pesticideType: String, info: Option[String]) derives DbCodec
