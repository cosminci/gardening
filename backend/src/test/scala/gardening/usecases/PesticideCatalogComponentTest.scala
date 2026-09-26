package gardening.usecases

import cats.syntax.option.*
import gardening.domain.*
import gardening.domain.pesticide.*
import gardening.ports.{PesticideStore, PesticideCatalogMetricsApi}
import gardening.capabilities.{IdGenerator, TestImplicits}

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import scala.util.chaining.scalaUtilChainingOps

class PesticideCatalogComponentTest extends munit.FunSuite with TestImplicits:

  private given metrics: PesticideCatalogMetricsApi = new PesticideCatalogMetricsApi:
    def setPesticideDisplayNames(pesticides: Vector[(PesticideId, String)]): Unit = ()

  private val pesticideId   = PesticideId(UUID.fromString("10000000-0000-4000-8000-000000000002"))
  private val pesticideData = PesticideData(PesticideName("Sulfur"), PesticideType.Fungicide, PesticideInfo("2g/L").some)
  private val pesticide     = Pesticide(pesticideId, pesticideData, PesticideStatus.Active)
  private val archived      = pesticide.copy(status = PesticideStatus.Archived)

  test("should assign identifiers, list pesticides, and reject an edit or archive of a missing one"):
    val refs    = Refs()
    val catalog = buildCatalog(refs)

    val readResult    = catalog.getPesticides
    val addResult     = catalog.addPesticide(pesticideData)
    val editResult    = catalog.editPesticide(pesticideId, pesticideData)
    val archiveResult = catalog.archivePesticide(pesticideId)

    assertEquals(readResult, GetPesticidesResult.Read(Vector(pesticide)))
    assertEquals(addResult, AddPesticideResult.Added(pesticide))
    assertEquals(editResult, PesticideUpdateResult.PesticideMissing)
    assertEquals(archiveResult, PesticideUpdateResult.PesticideMissing)
    assertEquals(refs.added.get(), Vector(pesticide))
    assertEquals(refs.updated.get(), Vector.empty)

  test("should edit an active pesticide's data and persist it unchanged from status"):
    val refs       = Refs()
    val editResult = buildCatalog(refs, getResult = GetPesticideResult.Read(pesticide)).editPesticide(pesticideId, pesticideData)

    assertEquals(editResult, PesticideUpdateResult.Updated(pesticide))
    assertEquals(refs.updated.get(), Vector(pesticide))

  test("should archive an active pesticide"):
    val refs          = Refs()
    val archiveResult = buildCatalog(refs, getResult = GetPesticideResult.Read(pesticide)).archivePesticide(pesticideId)

    assertEquals(archiveResult, PesticideUpdateResult.Updated(archived))
    assertEquals(refs.updated.get(), Vector(archived))

  test("should reject editing or re-archiving an already-archived pesticide without writing"):
    val refs          = Refs()
    val catalog       = buildCatalog(refs, getResult = GetPesticideResult.Read(archived))
    val editResult    = catalog.editPesticide(pesticideId, pesticideData)
    val archiveResult = catalog.archivePesticide(pesticideId)

    assertEquals(editResult, PesticideUpdateResult.PesticideArchived)
    assertEquals(archiveResult, PesticideUpdateResult.PesticideArchived)
    assertEquals(refs.updated.get(), Vector.empty)

  test("should preserve pesticide catalog failures"):
    val readFailure  = RuntimeException("storage unavailable")
    val writeFailure = RuntimeException("write unavailable")
    val catalog      = buildCatalog(
      readResult = GetPesticidesResult.ReadFailed(readFailure),
      addResult = AddPesticideResult.AddFailed(readFailure),
      getResult = GetPesticideResult.ReadFailed(readFailure)
    )
    val writeFailingCatalog =
      buildCatalog(getResult = GetPesticideResult.Read(pesticide), updateResult = UpdatePesticideResult.UpdateFailed(writeFailure))

    val readResult          = catalog.getPesticides
    val addResult           = catalog.addPesticide(pesticideData)
    val editResult          = catalog.editPesticide(pesticideId, pesticideData)
    val archiveResult       = catalog.archivePesticide(pesticideId)
    val writeFailingEdit    = writeFailingCatalog.editPesticide(pesticideId, pesticideData)
    val writeFailingArchive = writeFailingCatalog.archivePesticide(pesticideId)

    assertEquals(readResult, GetPesticidesResult.ReadFailed(readFailure))
    assertEquals(addResult, AddPesticideResult.AddFailed(readFailure))
    assertEquals(editResult, PesticideUpdateResult.UpdateFailed(readFailure))
    assertEquals(archiveResult, PesticideUpdateResult.UpdateFailed(readFailure))
    assertEquals(writeFailingEdit, PesticideUpdateResult.UpdateFailed(writeFailure))
    assertEquals(writeFailingArchive, PesticideUpdateResult.UpdateFailed(writeFailure))

  private case class Refs(
      added: AtomicReference[Vector[Pesticide]] = AtomicReference(Vector.empty),
      updated: AtomicReference[Vector[Pesticide]] = AtomicReference(Vector.empty)
  )

  private def buildCatalog(
      refs: Refs = Refs(),
      readResult: GetPesticidesResult = GetPesticidesResult.Read(Vector(pesticide)),
      addResult: AddPesticideResult = AddPesticideResult.Added(pesticide),
      getResult: GetPesticideResult = GetPesticideResult.RecordMissing,
      updateResult: UpdatePesticideResult = UpdatePesticideResult.Updated,
      nextId: () => String = () => pesticideId.value.toString
  ) =
    val store = new PesticideStore:
      override def getPesticides: GetPesticidesResult                 = readResult
      override def getPesticide(id: PesticideId): GetPesticideResult  = getResult
      override def addPesticide(value: Pesticide): AddPesticideResult =
        refs.added.updateAndGet(_ :+ value).pipe(_ => addResult)
      override def updatePesticide(value: Pesticide): UpdatePesticideResult =
        refs.updated.updateAndGet(_ :+ value).pipe(_ => updateResult)
    PesticideCatalog.make(using store, () => nextId())
