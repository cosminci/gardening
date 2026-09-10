package gardening.adapters.persistence

import org.sqlite.SQLiteDataSource

import javax.sql.DataSource

/** Builds SQLite-backed [[DataSource]]s. */
object SqliteDataSource:
  def apply(jdbcUrl: String): DataSource =
    val dataSource = new SQLiteDataSource()
    dataSource.setUrl(jdbcUrl)
    dataSource

  def inMemory(): DataSource = apply("jdbc:sqlite::memory:")
