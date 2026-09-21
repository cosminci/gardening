package gardening.domain

import java.util.UUID

object TestNomenclatureIds:
  val Perlite: SubstrateComponentId  = SubstrateComponentId(UUID.fromString("00000000-0000-4000-8000-000000000003"))
  val PineBark: SubstrateComponentId = SubstrateComponentId(UUID.fromString("00000000-0000-4000-8000-000000000004"))
  val Sand3to5: SubstrateComponentId = SubstrateComponentId(UUID.fromString("00000000-0000-4000-8000-000000000005"))
  val Leca: SubstrateComponentId     = SubstrateComponentId(UUID.fromString("00000000-0000-4000-8000-000000000007"))
  val Vertab: PesticideId            = PesticideId(UUID.fromString("00000000-0000-4000-8001-000000000003"))
  val NeemOil: PesticideId           = PesticideId(UUID.fromString("00000000-0000-4000-8001-000000000007"))
