package gardening.domain.pesticide

import cats.syntax.eq.*
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
      store.getPesticide(id) match
        case GetPesticideResult.RecordMissing      => PesticideEditResult.PesticideMissing
        case GetPesticideResult.ReadFailed(reason) =>
          PesticideEditResult.EditFailed(reason).tap(_ => log.error("edit pesticide", reason))
        case GetPesticideResult.Read(pesticide) if pesticide.status === PesticideStatus.Archived => PesticideEditResult.PesticideArchived
        case GetPesticideResult.Read(pesticide)                                                  => persistEdit(pesticide.copy(data = data))

    private def persistEdit(edited: Pesticide): PesticideEditResult =
      store.updatePesticide(edited) match
        case UpdatePesticideResult.Updated => PesticideEditResult.Edited(edited).tap(_ => log.info(s"pesticide edited id=${edited.id.value}"))
        case UpdatePesticideResult.UpdateFailed(reason) => PesticideEditResult.EditFailed(reason).tap(_ => log.error("edit pesticide", reason))

    override def archivePesticide(id: PesticideId): PesticideArchiveResult =
      store.getPesticide(id) match
        case GetPesticideResult.RecordMissing      => PesticideArchiveResult.PesticideMissing
        case GetPesticideResult.ReadFailed(reason) =>
          PesticideArchiveResult.ArchiveFailed(reason).tap(_ => log.error("archive pesticide", reason))
        case GetPesticideResult.Read(pesticide) if pesticide.status === PesticideStatus.Archived => PesticideArchiveResult.AlreadyArchived
        case GetPesticideResult.Read(pesticide) => persistArchive(pesticide.copy(status = PesticideStatus.Archived))

    private def persistArchive(archived: Pesticide): PesticideArchiveResult =
      store.updatePesticide(archived) match
        case UpdatePesticideResult.Updated =>
          PesticideArchiveResult.Archived(archived).tap(_ => log.info(s"pesticide archived id=${archived.id.value}"))
        case UpdatePesticideResult.UpdateFailed(reason) =>
          PesticideArchiveResult.ArchiveFailed(reason).tap(_ => log.error("archive pesticide", reason))
