package gardening.usecases

import cats.syntax.option.*
import io.github.iltotore.iron.autoRefine
import gardening.domain.*
import gardening.domain.substrate.*
import gardening.ports.{SubstrateStore, SubstrateCatalogMetricsApi}
import gardening.capabilities.{IdGenerator, TestImplicits}

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import scala.util.chaining.scalaUtilChainingOps

class SubstrateCatalogComponentTest extends munit.FunSuite with TestImplicits:

  private given metrics: SubstrateCatalogMetricsApi = new SubstrateCatalogMetricsApi:
    def setComponentDisplayNames(components: Vector[(SubstrateComponentId, String)]): Unit = ()

  private val componentId   = SubstrateComponentId(UUID.fromString("10000000-0000-4000-8000-000000000001"))
  private val componentData = SubstrateComponentData(SubstrateComponentName("Pumice"), SubstrateComponentInfo("porous").some)
  private val component     = SubstrateComponent(componentId, componentData, SubstrateComponentStatus.Active)
  private val archived      = component.copy(status = SubstrateComponentStatus.Archived)

  private val mixId     = UUID.fromString("20000000-0000-4000-8000-000000000001")
  private val substrate = Substrate.of(List(SubstratePart(componentId, share = 100))).getOrElse(fail("invalid test substrate"))
  private val mixName   = SubstrateMixName("Cactus mix")
  private val mixNotes  = SubstrateMixNotes("Free-draining").some
  private val mix       = SubstrateMix(mixId, mixName, mixNotes, substrate)

  test("should assign identifiers, list components, and reject an edit or archive of a missing one"):
    val refs    = Refs()
    val catalog = buildCatalog(refs)

    val readResult    = catalog.getSubstrateComponents
    val addResult     = catalog.addSubstrateComponent(componentData)
    val editResult    = catalog.editSubstrateComponent(componentId, componentData)
    val archiveResult = catalog.archiveSubstrateComponent(componentId)

    assertEquals(readResult, GetSubstrateComponentsResult.Read(Vector(component)))
    assertEquals(addResult, AddSubstrateComponentResult.Added(component))
    assertEquals(editResult, SubstrateComponentUpdateResult.ComponentMissing)
    assertEquals(archiveResult, SubstrateComponentUpdateResult.ComponentMissing)
    assertEquals(refs.addedComponents.get(), Vector(component))
    assertEquals(refs.updatedComponents.get(), Vector.empty)

  test("should edit an active substrate component's data and persist it unchanged from status"):
    val refs       = Refs()
    val editResult = buildCatalog(refs, getResult = GetSubstrateComponentResult.Read(component)).editSubstrateComponent(componentId, componentData)

    assertEquals(editResult, SubstrateComponentUpdateResult.Updated(component))
    assertEquals(refs.updatedComponents.get(), Vector(component))

  test("should archive an active substrate component"):
    val refs          = Refs()
    val archiveResult = buildCatalog(refs, getResult = GetSubstrateComponentResult.Read(component)).archiveSubstrateComponent(componentId)

    assertEquals(archiveResult, SubstrateComponentUpdateResult.Updated(archived))
    assertEquals(refs.updatedComponents.get(), Vector(archived))

  test("should reject editing or re-archiving an already-archived substrate component without writing"):
    val refs          = Refs()
    val catalog       = buildCatalog(refs, getResult = GetSubstrateComponentResult.Read(archived))
    val editResult    = catalog.editSubstrateComponent(componentId, componentData)
    val archiveResult = catalog.archiveSubstrateComponent(componentId)

    assertEquals(editResult, SubstrateComponentUpdateResult.ComponentArchived)
    assertEquals(archiveResult, SubstrateComponentUpdateResult.ComponentArchived)
    assertEquals(refs.updatedComponents.get(), Vector.empty)

  test("should preserve substrate component catalog failures"):
    val readFailure  = RuntimeException("storage unavailable")
    val writeFailure = RuntimeException("write unavailable")
    val catalog      = buildCatalog(
      readResult = GetSubstrateComponentsResult.ReadFailed(readFailure),
      addResult = AddSubstrateComponentResult.AddFailed(readFailure),
      getResult = GetSubstrateComponentResult.ReadFailed(readFailure)
    )
    val writeFailingCatalog =
      buildCatalog(getResult = GetSubstrateComponentResult.Read(component), updateResult = UpdateSubstrateComponentResult.UpdateFailed(writeFailure))

    val readResult          = catalog.getSubstrateComponents
    val addResult           = catalog.addSubstrateComponent(componentData)
    val editResult          = catalog.editSubstrateComponent(componentId, componentData)
    val archiveResult       = catalog.archiveSubstrateComponent(componentId)
    val writeFailingEdit    = writeFailingCatalog.editSubstrateComponent(componentId, componentData)
    val writeFailingArchive = writeFailingCatalog.archiveSubstrateComponent(componentId)

    assertEquals(readResult, GetSubstrateComponentsResult.ReadFailed(readFailure))
    assertEquals(addResult, AddSubstrateComponentResult.AddFailed(readFailure))
    assertEquals(editResult, SubstrateComponentUpdateResult.UpdateFailed(readFailure))
    assertEquals(archiveResult, SubstrateComponentUpdateResult.UpdateFailed(readFailure))
    assertEquals(writeFailingEdit, SubstrateComponentUpdateResult.UpdateFailed(writeFailure))
    assertEquals(writeFailingArchive, SubstrateComponentUpdateResult.UpdateFailed(writeFailure))

  test("should delegate substrate mix reads"):
    val readResult = buildCatalog(mixReadResult = GetSubstrateMixesResult.Read(Vector(mix))).getSubstrateMixes

    assertEquals(readResult, GetSubstrateMixesResult.Read(Vector(mix)))

  test("should assign an identifier when saving a substrate mix"):
    val refs    = Refs()
    val catalog = buildCatalog(refs, nextId = () => mixId.toString)

    val addResult = catalog.addSubstrateMix(mixName, mixNotes, substrate)

    assertEquals(addResult, AddSubstrateMixResult.Added(mix))
    assertEquals(refs.addedMixes.get(), Vector(mix))

  test("should reject saving a substrate mix with the same components and shares as an existing one"):
    val catalog = buildCatalog(mixReadResult = GetSubstrateMixesResult.Read(Vector(mix)))

    val addResult = catalog.addSubstrateMix(SubstrateMixName("Another name"), none, substrate)

    assertEquals(addResult, AddSubstrateMixResult.DuplicateSubstrate)

  test("should delegate permanent substrate mix deletion"):
    val refs    = Refs()
    val catalog = buildCatalog(refs)

    val deleteResult = catalog.deleteSubstrateMix(mixId)

    assertEquals(deleteResult, DeleteSubstrateMixResult.Deleted)
    assertEquals(refs.deletedMixes.get(), Vector(mixId))

  test("should preserve substrate mix read and delete catalog failures"):
    val failure = RuntimeException("storage unavailable")
    val catalog =
      buildCatalog(mixReadResult = GetSubstrateMixesResult.ReadFailed(failure), mixDeleteResult = DeleteSubstrateMixResult.DeleteFailed(failure))

    val readResult   = catalog.getSubstrateMixes
    val addResult    = catalog.addSubstrateMix(mixName, mixNotes, substrate)
    val deleteResult = catalog.deleteSubstrateMix(mixId)

    assertEquals(readResult, GetSubstrateMixesResult.ReadFailed(failure))
    assertEquals(addResult, AddSubstrateMixResult.AddFailed(failure))
    assertEquals(deleteResult, DeleteSubstrateMixResult.DeleteFailed(failure))

  test("should preserve substrate mix write failures once the mix is known not to be a duplicate"):
    val failure = RuntimeException("storage unavailable")
    val catalog = buildCatalog(mixAddResult = SaveSubstrateMixResult.SaveFailed(failure))

    val addResult = catalog.addSubstrateMix(mixName, mixNotes, substrate)

    assertEquals(addResult, AddSubstrateMixResult.AddFailed(failure))

  private case class Refs(
      addedComponents: AtomicReference[Vector[SubstrateComponent]] = AtomicReference(Vector.empty),
      updatedComponents: AtomicReference[Vector[SubstrateComponent]] = AtomicReference(Vector.empty),
      addedMixes: AtomicReference[Vector[SubstrateMix]] = AtomicReference(Vector.empty),
      deletedMixes: AtomicReference[Vector[UUID]] = AtomicReference(Vector.empty)
  )

  private def buildCatalog(
      refs: Refs = Refs(),
      readResult: GetSubstrateComponentsResult = GetSubstrateComponentsResult.Read(Vector(component)),
      addResult: AddSubstrateComponentResult = AddSubstrateComponentResult.Added(component),
      getResult: GetSubstrateComponentResult = GetSubstrateComponentResult.RecordMissing,
      updateResult: UpdateSubstrateComponentResult = UpdateSubstrateComponentResult.Updated,
      mixReadResult: GetSubstrateMixesResult = GetSubstrateMixesResult.Read(Vector.empty),
      mixAddResult: SaveSubstrateMixResult = SaveSubstrateMixResult.Saved(mix),
      mixDeleteResult: DeleteSubstrateMixResult = DeleteSubstrateMixResult.Deleted,
      nextId: () => String = () => componentId.value.toString
  ) =
    val store = new SubstrateStore:
      override def getSubstrateComponents: GetSubstrateComponentsResult                          = readResult
      override def getSubstrateComponent(id: SubstrateComponentId): GetSubstrateComponentResult  = getResult
      override def addSubstrateComponent(value: SubstrateComponent): AddSubstrateComponentResult =
        refs.addedComponents.updateAndGet(_ :+ value).pipe(_ => addResult)
      override def updateSubstrateComponent(value: SubstrateComponent): UpdateSubstrateComponentResult =
        refs.updatedComponents.updateAndGet(_ :+ value).pipe(_ => updateResult)
      override def getSubstrateMixes: GetSubstrateMixesResult                    = mixReadResult
      override def saveSubstrateMix(value: SubstrateMix): SaveSubstrateMixResult =
        refs.addedMixes.updateAndGet(_ :+ value).pipe(_ => mixAddResult)
      override def deleteSubstrateMix(id: UUID): DeleteSubstrateMixResult =
        refs.deletedMixes.updateAndGet(_ :+ id).pipe(_ => mixDeleteResult)
    SubstrateCatalog.make(using store, () => nextId())
