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
        val client = makeClient()
        try {
            val stmt = client.prepare("SELECT \$1::int AS a, \$2::text AS b")
            val rows = stmt.executeQuery(7, "world")
            assertEquals(2, rows.columns.size)
            assertTrue(rows.next())
            assertEquals(7, rows.getInt(0))
            assertEquals("world", rows.getString(1))
            rows.close()
            stmt.close()
        } finally {
            client.close()
        }
    }

    @Test
    fun preparedStatementExecuteReturnsRowsAffected() = runBlocking {
        val client = makeClient()
        try {
            client.execute("CREATE TEMP TABLE _kp_api_t (id int, label text)")
            val stmt = client.prepare("INSERT INTO _kp_api_t VALUES (\$1, \$2)")
            val n = stmt.execute(1, "a") + stmt.execute(2, "b") + stmt.execute(3, "c")
            assertEquals(3L, n)
            stmt.close()
            val rows = client.query("SELECT count(*) FROM _kp_api_t")
            assertTrue(rows is QueryResult.Rows)
            rows as QueryResult.Rows
            assertTrue(rows.next())
            assertEquals(3L, rows.getLong(0))
            rows.close()
        } finally {
            client.close()
        }
    }

    @Test
    fun transactionCommits() = runBlocking {
        val client = makeClient()
        try {
            client.execute("CREATE TEMP TABLE _kp_tx_t (id int)")
            client.transaction {
                it.execute("INSERT INTO _kp_tx_t VALUES (1),(2)")
            }
            assertEquals('I', client.transactionStatus)
            val rows = client.query("SELECT count(*) FROM _kp_tx_t")
            assertTrue(rows is QueryResult.Rows)
            rows as QueryResult.Rows
            assertTrue(rows.next())
            assertEquals(2L, rows.getLong(0))
            rows.close()
        } finally {
            client.close()
        }
    }

    @Test
    fun transactionRollbackOnException() = runBlocking {
        val client = makeClient()
        try {
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
            val rows = client.query("SELECT count(*) FROM _kp_txr_t")
            assertTrue(rows is QueryResult.Rows)
            rows as QueryResult.Rows
            assertTrue(rows.next())
            assertEquals(0L, rows.getLong(0))
            rows.close()
        } finally {
            client.close()
        }
    }

    @Test
    fun transactionRollbackOnQueryError() = runBlocking {
        val client = makeClient()
        try {
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
            val rows = client.query("SELECT count(*) FROM _kp_txe_t")
            assertTrue(rows is QueryResult.Rows)
            rows as QueryResult.Rows
            assertTrue(rows.next())
            assertEquals(0L, rows.getLong(0))
            rows.close()
        } finally {
            client.close()
        }
    }

    @Test
    fun uuidPreparedBind() = runBlocking {
        val client = makeClient()
        try {
            val u = Uuid.parse("12345678-1234-5678-1234-567812345678")
            val stmt = client.prepare("SELECT \$1::uuid AS u")
            val rows = stmt.executeQuery(u)
            assertTrue(rows.next())
            assertEquals(u, rows.getUuid(0))
            rows.close()
            stmt.close()
        } finally {
            client.close()
        }
    }

    private fun makeClient(): PostgresClient {
        val host = System.getenv("PGHOST") ?: "127.0.0.1"
        val port = System.getenv("PGPORT")?.toIntOrNull() ?: 5432
        val db = System.getenv("PGDATABASE") ?: "kp_test"
        val user = System.getenv("PGUSER") ?: "kpgtest"
        val password = System.getenv("PGPASSWORD") ?: "kp_secret"
        val config = PostgresConfig.of(host, port, db, user, password)
        return runBlocking { PostgresClient.connect(config) }
    }
}