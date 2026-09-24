package gardening.domain.pesticide

import gardening.domain.*
import gardening.domain.catalog.*

import java.util.UUID

import language.experimental.captureChecking

trait PesticideCatalog:
  def getPesticides: CatalogReadResult[Pesticide]
  def addPesticide(data: PesticideData): CatalogAddResult[Pesticide]
  def editPesticide(id: PesticideId, data: PesticideData): CatalogEditResult[Pesticide]

object PesticideCatalog:

  def make(using store: PesticideStore^, idGen: IdGenerator^): PesticideCatalog^{store, idGen} =
    new LivePesticideCatalog

  private class LivePesticideCatalog(using store: PesticideStore^, idGen: IdGenerator^) extends PesticideCatalog:

    override def getPesticides: CatalogReadResult[Pesticide] = store.getPesticides

    override def addPesticide(data: PesticideData): CatalogAddResult[Pesticide] =
      store.addPesticide(Pesticide(PesticideId(UUID.fromString(idGen.nextId())), data))

    override def editPesticide(id: PesticideId, data: PesticideData): CatalogEditResult[Pesticide] =
      store.editPesticide(id, data)
