package gardening.ports

import gardening.domain.*
import gardening.domain.pesticide.*

trait PesticideStore:
  def getPesticides: GetPesticidesResult
  def getPesticide(id: PesticideId): GetPesticideResult
  def addPesticide(pesticide: Pesticide): AddPesticideResult
  def updatePesticide(pesticide: Pesticide): UpdatePesticideResult
