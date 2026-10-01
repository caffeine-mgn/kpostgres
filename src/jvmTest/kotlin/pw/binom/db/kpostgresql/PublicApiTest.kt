package pw.binom.db.kpostgresql

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class PublicApiTest {

    @Test
    fun preparedStatementExecuteQuery() = runBlocking {
        makeClient().use { client ->
            client.prepare("SELECT \$1::int AS a, \$2::text AS b").use { stmt ->
                stmt.executeQuery(7, "world").use { rows ->
                    assertEquals(2, rows.columns.size)
                    assertTrue(rows.next())
                    assertEquals(7, rows.getInt(0))
                    assertEquals("world", rows.getString(1))
                }
            }
        }
    }

    @Test
    fun preparedStatementExecuteReturnsRowsAffected() = runBlocking {
        makeClient().use { client ->
            client.execute("CREATE TEMP TABLE _kp_api_t (id int, label text)")
            client.prepare("INSERT INTO _kp_api_t VALUES (\$1, \$2)").use { stmt ->
                val n = stmt.execute(1, "a") + stmt.execute(2, "b") + stmt.execute(3, "c")
                assertEquals(3L, n)
            }
            client.query("SELECT count(*) FROM _kp_api_t").use { res ->
                assertTrue(res is QueryResult.Rows)
                res as QueryResult.Rows
                assertTrue(res.next())
                assertEquals(3L, res.getLong(0))
            }
        }
    }

    @Test
    fun transactionCommits() = runBlocking {
        makeClient().use { client ->
            client.execute("CREATE TEMP TABLE _kp_tx_t (id int)")
            client.transaction {
                it.execute("INSERT INTO _kp_tx_t VALUES (1),(2)")
            }
            assertEquals('I', client.transactionStatus)
            client.query("SELECT count(*) FROM _kp_tx_t").use { res ->
                assertTrue(res is QueryResult.Rows)
                res as QueryResult.Rows
                assertTrue(res.next())
                assertEquals(2L, res.getLong(0))
            }
        }
    }

    @Test
    fun transactionRollbackOnException() = runBlocking {
        makeClient().use { client ->
            client.execute("CREATE TEMP TABLE _kp_txr_t (id int)")
            try {
                client.transaction {
                    it.execute("INSERT INTO _kp_txr_t VALUES (1),(2)")
                    throw RuntimeException("boom")
                }
            } catch (e: RuntimeException) {
                assertEquals("boom", e.message)
            }
            assertEquals('I', client.transactionStatus)
            client.query("SELECT count(*) FROM _kp_txr_t").use { res ->
                assertTrue(res is QueryResult.Rows)
                res as QueryResult.Rows
                assertTrue(res.next())
                assertEquals(0L, res.getLong(0))
            }
        }
    }

    @Test
    fun transactionRollbackOnQueryError() = runBlocking {
        makeClient().use { client ->
            client.execute("CREATE TEMP TABLE _kp_txe_t (id int)")
            try {
                client.transaction {
                    it.execute("INSERT INTO _kp_txe_t VALUES (1)")
                    it.query("SELECT * FROM no_such_table_xyz")
                }
            } catch (e: Throwable) {
                assertNotNull(e)
            }
            assertEquals('I', client.transactionStatus)
            client.query("SELECT count(*) FROM _kp_txe_t").use { res ->
                assertTrue(res is QueryResult.Rows)
                res as QueryResult.Rows
                assertTrue(res.next())
                assertEquals(0L, res.getLong(0))
            }
        }
    }

    @Test
    fun uuidPreparedBind() = runBlocking {
        makeClient().use { client ->
            val u = Uuid.parse("12345678-1234-5678-1234-567812345678")
            client.prepare("SELECT \$1::uuid AS u").use { stmt ->
                stmt.executeQuery(u).use { rows ->
                    assertTrue(rows.next())
                    assertEquals(u, rows.getUuid(0))
                }
            }
        }
    }

    private fun makeClient(): PostgresClient {
        val container = AbstractPgContainer.startOnce()
        val config = PostgresConfig.of(
            host = container.host,
            port = container.firstMappedPort,
            database = container.databaseName,
            user = container.username,
            password = container.password,
        )
        return runBlocking { PostgresClient.connect(config) }
    }
}