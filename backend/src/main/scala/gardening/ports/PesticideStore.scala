package gardening.ports

import gardening.domain.*
import gardening.domain.catalog.*
import gardening.domain.pesticide.*

trait PesticideStore:
  def getPesticides: CatalogReadResult[Pesticide]
  def getPesticide(id: PesticideId): GetPesticideResult
  def addPesticide(pesticide: Pesticide): CatalogAddResult[Pesticide]
  def updatePesticide(pesticide: Pesticide): UpdatePesticideResult
