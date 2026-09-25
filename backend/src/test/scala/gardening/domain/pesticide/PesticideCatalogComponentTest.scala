package gardening.domain.pesticide

import cats.syntax.option.*
import gardening.domain.*
import gardening.domain.catalog.*

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import scala.util.chaining.scalaUtilChainingOps

class PesticideCatalogComponentTest extends munit.FunSuite with TestImplicits:

  private val pesticideId   = PesticideId(UUID.fromString("10000000-0000-4000-8000-000000000002"))
  private val pesticideData = PesticideData(NomenclatureName("Sulfur"), PesticideType.Fungicide, NomenclatureInfo("2g/L").some)
  private val pesticide     = Pesticide(pesticideId, pesticideData, PesticideStatus.Active)

  test("should assign identifiers and delegate pesticide reads, edits, and archiving"):
    val refs    = Refs()
    val catalog = buildCatalog(refs)

    val readResult    = catalog.getPesticides
    val addResult     = catalog.addPesticide(pesticideData)
    val editResult    = catalog.editPesticide(pesticideId, pesticideData)
    val archiveResult = catalog.archivePesticide(pesticideId)

    assertEquals(readResult, CatalogReadResult.Read(Vector(pesticide)))
    assertEquals(addResult, CatalogAddResult.Added(pesticide))
    assertEquals(editResult, PesticideEditResult.PesticideMissing)
    assertEquals(archiveResult, PesticideArchiveResult.PesticideMissing)
    assertEquals(refs.added.get(), Vector(pesticide))
    assertEquals(refs.edited.get(), Vector(pesticideId -> pesticideData))
    assertEquals(refs.archived.get(), Vector(pesticideId))

  test("should return the edited pesticide"):
    val editResult = buildCatalog(editResult = PesticideEditResult.Edited(pesticide)).editPesticide(pesticideId, pesticideData)

    assertEquals(editResult, PesticideEditResult.Edited(pesticide))

  test("should return the archived pesticide"):
    val archiveResult = buildCatalog(archiveResult = PesticideArchiveResult.Archived(pesticide)).archivePesticide(pesticideId)

    assertEquals(archiveResult, PesticideArchiveResult.Archived(pesticide))

  test("should reject editing an archived pesticide"):
    val editResult = buildCatalog(editResult = PesticideEditResult.PesticideArchived).editPesticide(pesticideId, pesticideData)

    assertEquals(editResult, PesticideEditResult.PesticideArchived)

  test("should reject archiving an already-archived pesticide"):
    val archiveResult = buildCatalog(archiveResult = PesticideArchiveResult.AlreadyArchived).archivePesticide(pesticideId)

    assertEquals(archiveResult, PesticideArchiveResult.AlreadyArchived)

  test("should preserve pesticide catalog failures"):
    val failure = RuntimeException("storage unavailable")
    val catalog = buildCatalog(
      readResult = CatalogReadResult.ReadFailed(failure),
      addResult = CatalogAddResult.AddFailed(failure),
      editResult = PesticideEditResult.EditFailed(failure),
      archiveResult = PesticideArchiveResult.ArchiveFailed(failure)
    )

    val readResult    = catalog.getPesticides
    val addResult     = catalog.addPesticide(pesticideData)
    val editResult    = catalog.editPesticide(pesticideId, pesticideData)
    val archiveResult = catalog.archivePesticide(pesticideId)

    assertEquals(readResult, CatalogReadResult.ReadFailed(failure))
    assertEquals(addResult, CatalogAddResult.AddFailed(failure))
    assertEquals(editResult, PesticideEditResult.EditFailed(failure))
    assertEquals(archiveResult, PesticideArchiveResult.ArchiveFailed(failure))

  private case class Refs(
      added: AtomicReference[Vector[Pesticide]] = AtomicReference(Vector.empty),
      edited: AtomicReference[Vector[(PesticideId, PesticideData)]] = AtomicReference(Vector.empty),
      archived: AtomicReference[Vector[PesticideId]] = AtomicReference(Vector.empty)
  )

  private def buildCatalog(
      refs: Refs = Refs(),
      readResult: CatalogReadResult[Pesticide] = CatalogReadResult.Read(Vector(pesticide)),
      addResult: CatalogAddResult[Pesticide] = CatalogAddResult.Added(pesticide),
      editResult: PesticideEditResult = PesticideEditResult.PesticideMissing,
      archiveResult: PesticideArchiveResult = PesticideArchiveResult.PesticideMissing,
      nextId: () => String = () => pesticideId.value.toString
  ) =
    val store = new PesticideStore:
      override def getPesticides: CatalogReadResult[Pesticide]                 = readResult
      override def addPesticide(value: Pesticide): CatalogAddResult[Pesticide] =
        refs.added.updateAndGet(_ :+ value).pipe(_ => addResult)
      override def editPesticide(id: PesticideId, data: PesticideData): PesticideEditResult =
        refs.edited.updateAndGet(_ :+ (id -> data)).pipe(_ => editResult)
      override def archivePesticide(id: PesticideId): PesticideArchiveResult =
        refs.archived.updateAndGet(_ :+ id).pipe(_ => archiveResult)
    PesticideCatalog.make(using store, () => nextId())
