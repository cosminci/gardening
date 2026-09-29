package gardening.adapters.sqlite

import gardening.domain.SubstrateComponentId
import gardening.domain.plants.PlantStatus

import java.sql.{Connection, Types}
import java.util.UUID
import javax.sql.DataSource

object SqliteHelpers:

  val perliteId: SubstrateComponentId = SubstrateComponentId(UUID.fromString("00000000-0000-4000-8000-000000000003"))

  def makeReadOnly(connection: Connection): Unit =
    val statement = connection.createStatement()
    val _         = statement.execute("PRAGMA query_only = ON")
    statement.close()

  def execute(dataSource: DataSource, sql: String, parameters: String*): Unit =
    val connection = dataSource.getConnection()
    try
      val statement = connection.prepareStatement(sql)
      try
        parameters.zipWithIndex.foreach((parameter, index) => statement.setString(index + 1, parameter))
        val _ = statement.executeUpdate()
      finally statement.close()
    finally connection.close()

  def seedPlant(
      dataSource: DataSource,
      id: String,
      species: String = "Ficus lyrata",
      maybeNickname: Option[String] = None,
      location: String = "Balcony",
      status: PlantStatus = PlantStatus.Active,
      substrate: List[(SubstrateComponentId, Int)] = List(perliteId -> 100)
  ): Unit =
    val connection = dataSource.getConnection()
    try
      val statement = connection.prepareStatement("insert into plant (id, species, nickname, location, status, substrate) values (?, ?, ?, ?, ?, ?)")
      statement.setString(1, id)
      statement.setString(2, species)
      maybeNickname.fold(statement.setNull(3, Types.VARCHAR))(nickname => statement.setString(3, nickname))
      statement.setString(4, location)
      statement.setString(5, status.toString)
      val substrateJson = substrate.map((component, share) => s"""{"component":"${component.value}","share":$share}""").mkString("[", ",", "]")
      statement.setString(6, substrateJson)
      val _ = statement.executeUpdate()
      statement.close()
    finally connection.close()
