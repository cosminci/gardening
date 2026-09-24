package gardening.domain.pesticide

import gardening.domain.*
import gardening.domain.catalog.*

trait PesticideStore:
  def getPesticides: CatalogReadResult[Pesticide]
  def addPesticide(pesticide: Pesticide): CatalogAddResult[Pesticide]
  def editPesticide(id: PesticideId, data: PesticideData): CatalogEditResult[Pesticide]
