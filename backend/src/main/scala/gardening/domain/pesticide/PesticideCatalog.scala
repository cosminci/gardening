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
  def editPesticide(id: PesticideId, data: PesticideData): PesticideUpdateResult
  def archivePesticide(id: PesticideId): PesticideUpdateResult

object PesticideCatalog:

  def make(using
      store: PesticideStore^,
      idGen: IdGenerator^
  )(using log: Logger^, metrics: PesticideCatalogMetricsApi^): PesticideCatalog^{store, idGen, log, metrics} =
    new LivePesticideCatalog

  private class LivePesticideCatalog(using store: PesticideStore^, idGen: IdGenerator^)(using log: Logger^, metrics: PesticideCatalogMetricsApi^)
      extends PesticideCatalog:

    override def getPesticides: CatalogReadResult[Pesticide] =
      store.getPesticides.tap:
        case CatalogReadResult.ReadFailed(reason) => log.error("get pesticides", reason)
        case CatalogReadResult.Read(found)        =>
          metrics.setPesticideDisplayNames(found.map(pesticide => pesticide.id -> pesticide.data.name.value))

    override def addPesticide(data: PesticideData): CatalogAddResult[Pesticide] =
      store.addPesticide(Pesticide(PesticideId(UUID.fromString(idGen.nextId())), data, PesticideStatus.Active)).tap:
        case CatalogAddResult.Added(entry)      => log.info(s"pesticide added $entry")
        case CatalogAddResult.AddFailed(reason) => log.error("add pesticide", reason)

    override def editPesticide(id: PesticideId, data: PesticideData): PesticideUpdateResult =
      update(id)(editFn = _.copy(data = data)).tap:
        case PesticideUpdateResult.Updated(pesticide)   => log.info(s"pesticide edited $pesticide")
        case PesticideUpdateResult.UpdateFailed(reason) => log.error("edit pesticide", reason)
        case _                                          => ()

    override def archivePesticide(id: PesticideId): PesticideUpdateResult =
      update(id)(editFn = _.copy(status = PesticideStatus.Archived)).tap:
        case PesticideUpdateResult.Updated(pesticide)   => log.info(s"pesticide archived $pesticide")
        case PesticideUpdateResult.UpdateFailed(reason) => log.error("archive pesticide", reason)
        case _                                          => ()

    private def update(id: PesticideId)(editFn: Pesticide => Pesticide): PesticideUpdateResult =
      store.getPesticide(id) match
        case GetPesticideResult.RecordMissing                                                    => PesticideUpdateResult.PesticideMissing
        case GetPesticideResult.ReadFailed(reason)                                               => PesticideUpdateResult.UpdateFailed(reason)
        case GetPesticideResult.Read(pesticide) if pesticide.status === PesticideStatus.Archived =>
          PesticideUpdateResult.PesticideArchived
        case GetPesticideResult.Read(pesticide) =>
          val edited = editFn(pesticide)
          store.updatePesticide(edited) match
            case UpdatePesticideResult.Updated              => PesticideUpdateResult.Updated(edited)
            case UpdatePesticideResult.UpdateFailed(reason) => PesticideUpdateResult.UpdateFailed(reason)
