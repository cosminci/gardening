package gardening.domain

opaque type PlantId = String
object PlantId:
  def apply(value: String): PlantId         = value
  extension (id: PlantId) def value: String = id
