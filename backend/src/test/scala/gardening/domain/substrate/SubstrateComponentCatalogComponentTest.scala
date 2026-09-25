package gardening.domain.substrate

import cats.syntax.option.*
import gardening.domain.*
import gardening.domain.catalog.*

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import scala.util.chaining.scalaUtilChainingOps

class SubstrateComponentCatalogComponentTest extends munit.FunSuite with TestImplicits:

  private val componentId   = SubstrateComponentId(UUID.fromString("10000000-0000-4000-8000-000000000001"))
  private val componentData = SubstrateComponentData(NomenclatureName("Pumice"), NomenclatureInfo("porous").some)
  private val component     = SubstrateComponent(componentId, componentData)

  test("should assign identifiers and delegate catalog reads and edits"):
    val refs    = Refs()
    val catalog = buildCatalog(refs)

    val readResult = catalog.getSubstrateComponents
    val addResult  = catalog.addSubstrateComponent(componentData)
    val editResult = catalog.editSubstrateComponent(componentId, componentData)

    assertEquals(readResult, CatalogReadResult.Read(Vector(component)))
    assertEquals(addResult, CatalogAddResult.Added(component))
    assertEquals(editResult, CatalogEditResult.RecordMissing)
    assertEquals(refs.added.get(), Vector(component))
    assertEquals(refs.edited.get(), Vector(componentId -> componentData))

  test("should return the edited substrate component"):
    val editResult = buildCatalog(editResult = CatalogEditResult.Edited(component)).editSubstrateComponent(componentId, componentData)

    assertEquals(editResult, CatalogEditResult.Edited(component))

  test("should preserve catalog failures"):
    val failure = RuntimeException("storage unavailable")
    val catalog = buildCatalog(
      readResult = CatalogReadResult.ReadFailed(failure),
      addResult = CatalogAddResult.AddFailed(failure),
      editResult = CatalogEditResult.EditFailed(failure)
    )

    val readResult = catalog.getSubstrateComponents
    val addResult  = catalog.addSubstrateComponent(componentData)
    val editResult = catalog.editSubstrateComponent(componentId, componentData)

    assertEquals(readResult, CatalogReadResult.ReadFailed(failure))
    assertEquals(addResult, CatalogAddResult.AddFailed(failure))
    assertEquals(editResult, CatalogEditResult.EditFailed(failure))

  private case class Refs(
      added: AtomicReference[Vector[SubstrateComponent]] = AtomicReference(Vector.empty),
      edited: AtomicReference[Vector[(SubstrateComponentId, SubstrateComponentData)]] = AtomicReference(Vector.empty)
  )

  private def buildCatalog(
      refs: Refs = Refs(),
      readResult: CatalogReadResult[SubstrateComponent] = CatalogReadResult.Read(Vector(component)),
      addResult: CatalogAddResult[SubstrateComponent] = CatalogAddResult.Added(component),
      editResult: CatalogEditResult[SubstrateComponent] = CatalogEditResult.RecordMissing,
      nextId: () => String = () => componentId.value.toString
  ) =
    val store = new SubstrateComponentStore:
      override def getSubstrateComponents: CatalogReadResult[SubstrateComponent]                          = readResult
      override def addSubstrateComponent(value: SubstrateComponent): CatalogAddResult[SubstrateComponent] =
        refs.added.updateAndGet(_ :+ value).pipe(_ => addResult)
      override def editSubstrateComponent(id: SubstrateComponentId, data: SubstrateComponentData): CatalogEditResult[SubstrateComponent] =
        refs.edited.updateAndGet(_ :+ (id -> data)).pipe(_ => editResult)
    SubstrateComponentCatalog.make(using store, () => nextId())
