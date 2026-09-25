package gardening.domain.pesticide

import gardening.domain.*
import gardening.domain.catalog.*

import java.util.UUID

import language.experimental.captureChecking

import scala.util.chaining.scalaUtilChainingOps

trait PesticideCatalog:
  def getPesticides: CatalogReadResult[Pesticide]
  def addPesticide(data: PesticideData): CatalogAddResult[Pesticide]
  def editPesticide(id: PesticideId, data: PesticideData): CatalogEditResult[Pesticide]

object PesticideCatalog:

  def make(using store: PesticideStore^, idGen: IdGenerator^)(using log: Logger^): PesticideCatalog^{store, idGen, log} =
    new LivePesticideCatalog

  private class LivePesticideCatalog(using store: PesticideStore^, idGen: IdGenerator^)(using log: Logger^) extends PesticideCatalog:

    override def getPesticides: CatalogReadResult[Pesticide] =
      store.getPesticides.tap:
        case CatalogReadResult.ReadFailed(reason) => log.error("get pesticides", reason)
        case _                                    => ()

    override def addPesticide(data: PesticideData): CatalogAddResult[Pesticide] =
      store.addPesticide(Pesticide(PesticideId(UUID.fromString(idGen.nextId())), data)).tap:
        case CatalogAddResult.Added(entry)      => log.info(s"pesticide added id=${entry.id.value}")
        case CatalogAddResult.AddFailed(reason) => log.error("add pesticide", reason)

    override def editPesticide(id: PesticideId, data: PesticideData): CatalogEditResult[Pesticide] =
      store.editPesticide(id, data).tap:
        case CatalogEditResult.Edited(entry)      => log.info(s"pesticide edited id=${entry.id.value}")
        case CatalogEditResult.EditFailed(reason) => log.error("edit pesticide", reason)
        case CatalogEditResult.RecordMissing      => ()
