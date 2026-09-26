package gardening.usecases

import cats.syntax.eq.*
import gardening.domain.*
import gardening.domain.pesticide.*
import gardening.ports.{PesticideStore, PesticideCatalogMetricsApi}
import gardening.capabilities.{IdGenerator, Logger}

import java.util.UUID

import language.experimental.captureChecking

import scala.util.chaining.scalaUtilChainingOps

trait PesticideCatalog:
  def getPesticides: GetPesticidesResult
  def addPesticide(data: PesticideData): AddPesticideResult
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

    override def getPesticides: GetPesticidesResult =
      store.getPesticides.tap:
        case GetPesticidesResult.ReadFailed(reason) => log.error("get pesticides", reason)
        case GetPesticidesResult.Read(found)        =>
          metrics.setPesticideDisplayNames(found.map(pesticide => pesticide.id -> pesticide.data.name.value))

    override def addPesticide(data: PesticideData): AddPesticideResult =
      store.addPesticide(Pesticide(PesticideId(UUID.fromString(idGen.nextId())), data, PesticideStatus.Active)).tap:
        case AddPesticideResult.Added(entry)      => log.info(s"pesticide added $entry")
        case AddPesticideResult.AddFailed(reason) => log.error("add pesticide", reason)

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
