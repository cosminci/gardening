package gardening.domain.pesticide

import cats.syntax.option.*
import gardening.domain.*
import gardening.domain.catalog.*

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import scala.util.chaining.scalaUtilChainingOps

class PesticideCatalogComponentTest extends munit.FunSuite:

  private val pesticideId   = PesticideId(UUID.fromString("10000000-0000-4000-8000-000000000002"))
  private val pesticideData = PesticideData(NomenclatureName("Sulfur"), PesticideType.Fungicide, NomenclatureInfo("2g/L").some)
  private val pesticide     = Pesticide(pesticideId, pesticideData)

  test("should assign identifiers and delegate pesticide reads and edits"):
    val refs    = Refs()
    val catalog = buildCatalog(refs)

    val readResult = catalog.getPesticides
    val addResult  = catalog.addPesticide(pesticideData)
    val editResult = catalog.editPesticide(pesticideId, pesticideData)

    assertEquals(readResult, CatalogReadResult.Read(Vector(pesticide)))
    assertEquals(addResult, CatalogAddResult.Added(pesticide))
    assertEquals(editResult, CatalogEditResult.RecordMissing)
    assertEquals(refs.added.get(), Vector(pesticide))
    assertEquals(refs.edited.get(), Vector(pesticideId -> pesticideData))

  test("should preserve pesticide catalog failures"):
    val failure = RuntimeException("storage unavailable")
    val catalog = buildCatalog(
      readResult = CatalogReadResult.ReadFailed(failure),
      addResult = CatalogAddResult.AddFailed(failure),
      editResult = CatalogEditResult.EditFailed(failure)
    )

    val readResult = catalog.getPesticides
    val addResult  = catalog.addPesticide(pesticideData)
    val editResult = catalog.editPesticide(pesticideId, pesticideData)

    assertEquals(readResult, CatalogReadResult.ReadFailed(failure))
    assertEquals(addResult, CatalogAddResult.AddFailed(failure))
    assertEquals(editResult, CatalogEditResult.EditFailed(failure))

  private case class Refs(
      added: AtomicReference[Vector[Pesticide]] = AtomicReference(Vector.empty),
      edited: AtomicReference[Vector[(PesticideId, PesticideData)]] = AtomicReference(Vector.empty)
  )

  private def buildCatalog(
      refs: Refs = Refs(),
      readResult: CatalogReadResult[Pesticide] = CatalogReadResult.Read(Vector(pesticide)),
      addResult: CatalogAddResult[Pesticide] = CatalogAddResult.Added(pesticide),
      editResult: CatalogEditResult[Pesticide] = CatalogEditResult.RecordMissing,
      nextId: () => String = () => pesticideId.value.toString
  ) =
    val store = new PesticideStore:
      override def getPesticides: CatalogReadResult[Pesticide]                 = readResult
      override def addPesticide(value: Pesticide): CatalogAddResult[Pesticide] =
        refs.added.updateAndGet(_ :+ value).pipe(_ => addResult)
      override def editPesticide(id: PesticideId, data: PesticideData): CatalogEditResult[Pesticide] =
        refs.edited.updateAndGet(_ :+ (id -> data)).pipe(_ => editResult)
    PesticideCatalog.make(using store, () => nextId())
