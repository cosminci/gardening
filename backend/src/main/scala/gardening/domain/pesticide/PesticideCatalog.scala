package gardening.domain.pesticide

import gardening.domain.*
import gardening.domain.catalog.*

import java.util.UUID

import language.experimental.captureChecking

import scala.util.chaining.scalaUtilChainingOps

trait PesticideCatalog:
  def getPesticides: CatalogReadResult[Pesticide]
  def addPesticide(data: PesticideData): CatalogAddResult[Pesticide]
  def editPesticide(id: PesticideId, data: PesticideData): PesticideEditResult
  def archivePesticide(id: PesticideId): PesticideArchiveResult

object PesticideCatalog:

  def make(using store: PesticideStore^, idGen: IdGenerator^)(using log: Logger^): PesticideCatalog^{store, idGen, log} =
    new LivePesticideCatalog

  private class LivePesticideCatalog(using store: PesticideStore^, idGen: IdGenerator^)(using log: Logger^) extends PesticideCatalog:

    override def getPesticides: CatalogReadResult[Pesticide] =
      store.getPesticides.tap:
        case CatalogReadResult.ReadFailed(reason) => log.error("get pesticides", reason)
        case _                                    => ()

    override def addPesticide(data: PesticideData): CatalogAddResult[Pesticide] =
      store.addPesticide(Pesticide(PesticideId(UUID.fromString(idGen.nextId())), data, PesticideStatus.Active)).tap:
        case CatalogAddResult.Added(entry)      => log.info(s"pesticide added id=${entry.id.value}")
        case CatalogAddResult.AddFailed(reason) => log.error("add pesticide", reason)

    override def editPesticide(id: PesticideId, data: PesticideData): PesticideEditResult =
      store.editPesticide(id, data).tap:
        case PesticideEditResult.Edited(entry)                                            => log.info(s"pesticide edited id=${entry.id.value}")
        case PesticideEditResult.EditFailed(reason)                                       => log.error("edit pesticide", reason)
        case PesticideEditResult.PesticideMissing | PesticideEditResult.PesticideArchived => ()

    override def archivePesticide(id: PesticideId): PesticideArchiveResult =
      store.archivePesticide(id).tap:
        case PesticideArchiveResult.Archived(entry)                                           => log.info(s"pesticide archived id=${entry.id.value}")
        case PesticideArchiveResult.ArchiveFailed(reason)                                     => log.error("archive pesticide", reason)
        case PesticideArchiveResult.PesticideMissing | PesticideArchiveResult.AlreadyArchived => ()
