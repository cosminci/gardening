package gardening.adapters.persistence

class MagnumDatabaseProbeSuite extends munit.FunSuite:
  test("probe runs a query against SQLite and reports reachable"):
    assert(MagnumDatabaseProbe(SqliteDataSource.inMemory()).probe())
