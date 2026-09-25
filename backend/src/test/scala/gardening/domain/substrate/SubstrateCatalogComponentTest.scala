package gardening.domain.substrate

import cats.syntax.option.*
import io.github.iltotore.iron.autoRefine
import gardening.domain.*
import gardening.domain.catalog.*

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import scala.util.chaining.scalaUtilChainingOps

class SubstrateCatalogComponentTest extends munit.FunSuite with TestImplicits:

  private val componentId   = SubstrateComponentId(UUID.fromString("10000000-0000-4000-8000-000000000001"))
  private val componentData = SubstrateComponentData(SubstrateComponentName("Pumice"), SubstrateComponentInfo("porous").some)
  private val component     = SubstrateComponent(componentId, componentData)

  private val mixId     = UUID.fromString("20000000-0000-4000-8000-000000000001")
  private val substrate = Substrate.of(List(SubstratePart(componentId, share = 100))).getOrElse(fail("invalid test substrate"))
  private val mixName   = SubstrateMixName("Cactus mix")
  private val mixNotes  = SubstrateMixNotes("Free-draining").some
  private val mix       = SubstrateMix(mixId, mixName, mixNotes, substrate)

  test("should assign identifiers and delegate catalog reads and edits"):
    val refs    = Refs()
    val catalog = buildCatalog(refs)

    val readResult = catalog.getSubstrateComponents
    val addResult  = catalog.addSubstrateComponent(componentData)
    val editResult = catalog.editSubstrateComponent(componentId, componentData)

    assertEquals(readResult, CatalogReadResult.Read(Vector(component)))
    assertEquals(addResult, CatalogAddResult.Added(component))
    assertEquals(editResult, CatalogEditResult.RecordMissing)
    assertEquals(refs.addedComponents.get(), Vector(component))
    assertEquals(refs.editedComponents.get(), Vector(componentId -> componentData))

  test("should return the edited substrate component"):
    val editResult = buildCatalog(editResult = CatalogEditResult.Edited(component)).editSubstrateComponent(componentId, componentData)

    assertEquals(editResult, CatalogEditResult.Edited(component))

  test("should preserve substrate component catalog failures"):
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

  test("should delegate substrate mix reads"):
    val readResult = buildCatalog(mixReadResult = CatalogReadResult.Read(Vector(mix))).getSubstrateMixes

    assertEquals(readResult, CatalogReadResult.Read(Vector(mix)))

  test("should assign an identifier when saving a substrate mix"):
    val refs    = Refs()
    val catalog = buildCatalog(refs, nextId = () => mixId.toString)

    val addResult = catalog.addSubstrateMix(mixName, mixNotes, substrate)

    assertEquals(addResult, AddSubstrateMixResult.Added(mix))
    assertEquals(refs.addedMixes.get(), Vector(mix))

  test("should reject saving a substrate mix with the same components and shares as an existing one"):
    val catalog = buildCatalog(mixReadResult = CatalogReadResult.Read(Vector(mix)))

    val addResult = catalog.addSubstrateMix(SubstrateMixName("Another name"), none, substrate)

    assertEquals(addResult, AddSubstrateMixResult.DuplicateSubstrate)

  test("should delegate permanent substrate mix deletion"):
    val refs    = Refs()
    val catalog = buildCatalog(refs)

    val deleteResult = catalog.deleteSubstrateMix(mixId)

    assertEquals(deleteResult, CatalogDeleteResult.Deleted)
    assertEquals(refs.deletedMixes.get(), Vector(mixId))

  test("should preserve substrate mix read and delete catalog failures"):
    val failure = RuntimeException("storage unavailable")
    val catalog = buildCatalog(mixReadResult = CatalogReadResult.ReadFailed(failure), mixDeleteResult = CatalogDeleteResult.DeleteFailed(failure))

    val readResult   = catalog.getSubstrateMixes
    val addResult    = catalog.addSubstrateMix(mixName, mixNotes, substrate)
    val deleteResult = catalog.deleteSubstrateMix(mixId)

    assertEquals(readResult, CatalogReadResult.ReadFailed(failure))
    assertEquals(addResult, AddSubstrateMixResult.AddFailed(failure))
    assertEquals(deleteResult, CatalogDeleteResult.DeleteFailed(failure))

  test("should preserve substrate mix write failures once the mix is known not to be a duplicate"):
    val failure = RuntimeException("storage unavailable")
    val catalog = buildCatalog(mixAddResult = CatalogAddResult.AddFailed(failure))

    val addResult = catalog.addSubstrateMix(mixName, mixNotes, substrate)

    assertEquals(addResult, AddSubstrateMixResult.AddFailed(failure))

  private case class Refs(
      addedComponents: AtomicReference[Vector[SubstrateComponent]] = AtomicReference(Vector.empty),
      editedComponents: AtomicReference[Vector[(SubstrateComponentId, SubstrateComponentData)]] = AtomicReference(Vector.empty),
      addedMixes: AtomicReference[Vector[SubstrateMix]] = AtomicReference(Vector.empty),
      deletedMixes: AtomicReference[Vector[UUID]] = AtomicReference(Vector.empty)
  )

  private def buildCatalog(
      refs: Refs = Refs(),
      readResult: CatalogReadResult[SubstrateComponent] = CatalogReadResult.Read(Vector(component)),
      addResult: CatalogAddResult[SubstrateComponent] = CatalogAddResult.Added(component),
      editResult: CatalogEditResult[SubstrateComponent] = CatalogEditResult.RecordMissing,
      mixReadResult: CatalogReadResult[SubstrateMix] = CatalogReadResult.Read(Vector.empty),
      mixAddResult: CatalogAddResult[SubstrateMix] = CatalogAddResult.Added(mix),
      mixDeleteResult: CatalogDeleteResult = CatalogDeleteResult.Deleted,
      nextId: () => String = () => componentId.value.toString
  ) =
    val store = new SubstrateStore:
      override def getSubstrateComponents: CatalogReadResult[SubstrateComponent]                          = readResult
      override def addSubstrateComponent(value: SubstrateComponent): CatalogAddResult[SubstrateComponent] =
        refs.addedComponents.updateAndGet(_ :+ value).pipe(_ => addResult)
      override def editSubstrateComponent(id: SubstrateComponentId, data: SubstrateComponentData): CatalogEditResult[SubstrateComponent] =
        refs.editedComponents.updateAndGet(_ :+ (id -> data)).pipe(_ => editResult)
      override def getSubstrateMixes: CatalogReadResult[SubstrateMix]                   = mixReadResult
      override def addSubstrateMix(value: SubstrateMix): CatalogAddResult[SubstrateMix] =
        refs.addedMixes.updateAndGet(_ :+ value).pipe(_ => mixAddResult)
      override def deleteSubstrateMix(id: UUID): CatalogDeleteResult =
        refs.deletedMixes.updateAndGet(_ :+ id).pipe(_ => mixDeleteResult)
    SubstrateCatalog.make(using store, () => nextId())
