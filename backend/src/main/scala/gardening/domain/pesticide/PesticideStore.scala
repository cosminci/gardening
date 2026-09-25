package gardening.domain.pesticide

import gardening.domain.*
import gardening.domain.catalog.*

trait PesticideStore:
  def getPesticides: CatalogReadResult[Pesticide]
  def getPesticide(id: PesticideId): GetPesticideResult
  def addPesticide(pesticide: Pesticide): CatalogAddResult[Pesticide]
  def updatePesticide(pesticide: Pesticide): UpdatePesticideResult
