package pw.binom.db.kpostgresql

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestcontainersIT : AbstractPgContainer() {

    @BeforeAll
    fun boot() {
        postgres
    }

    @AfterAll
    fun teardown() {
        postgres.stop()
    }

    @Test
    fun connectAndSelectOne() {
        runBlocking {
            val client = PostgresClient.connect(pgConfig())
            try {
                val rows = client.query("SELECT 1 AS one")
                assertTrue(rows is QueryResult.Rows)
                rows as QueryResult.Rows
                assertTrue(rows.next())
                assertEquals(1, rows.getInt(0))
                assertEquals("one", rows.columns[0].name)
                rows.close()
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun extendedQueryBindIntAndText() {
        runBlocking {
            val client = PostgresClient.connect(pgConfig())
            try {
                val rows = client.executeWithParams("SELECT \$1::int AS a, \$2::text AS b", listOf(42, "hello"))
                assertTrue(rows is QueryResult.Rows)
                rows as QueryResult.Rows
                assertEquals(2, rows.columns.size)
                assertTrue(rows.next())
                assertEquals(42, rows.getInt(0))
                assertEquals("hello", rows.getString(1))
                rows.close()
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun extendedQueryBindNullAndUuidAndBytea() {
        runBlocking {
            val client = PostgresClient.connect(pgConfig())
            try {
                val u = Uuid.parse("00112233-4455-6677-8899-aabbccddeeff")
                val ba = byteArrayOf(0x00, 0x7F.toByte(), 0x42, 0xFF.toByte())
                val rows = client.executeWithParams(
                    "SELECT \$1::uuid AS u, \$2::bytea AS b, \$3::int AS n",
                    listOf(u, ba, null),
                )
                assertTrue(rows is QueryResult.Rows)
                rows as QueryResult.Rows
                assertTrue(rows.next())
                assertEquals(u, rows.getUuid(0))
                assertEquals(ba.toList(), rows.getBytes(1)!!.toList())
                assertTrue(rows.isNull(2))
                rows.close()
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun preparedStatementExecuteMultiple() {
        runBlocking {
            val client = PostgresClient.connect(pgConfig())
            try {
                client.execute("CREATE TEMP TABLE _kpit_t (id int, label text)")
                val stmt = client.prepare("INSERT INTO _kpit_t VALUES (\$1, \$2)")
                val n = stmt.execute(1, "a") + stmt.execute(2, "b") + stmt.execute(3, "c")
                stmt.close()
                assertEquals(3L, n)
                val rows = client.query("SELECT id, label FROM _kpit_t ORDER BY id")
                assertTrue(rows is QueryResult.Rows)
                rows as QueryResult.Rows
                val collected = mutableListOf<Pair<Int?, String?>>()
                while (rows.next()) {
                    collected += rows.getInt(0) to rows.getString(1)
                }
                rows.close()
                assertEquals(listOf<Pair<Int?, String?>>(1 to "a", 2 to "b", 3 to "c"), collected)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun transactionCommits() {
        runBlocking {
            val client = PostgresClient.connect(pgConfig())
            try {
                client.execute("CREATE TEMP TABLE _kpit_tx (id int)")
                client.transaction { tx ->
                    tx.execute("INSERT INTO _kpit_tx VALUES (1),(2),(3)")
                }
                assertEquals('I', client.transactionStatus)
                val rows = client.query("SELECT count(*) FROM _kpit_tx")
                assertTrue(rows is QueryResult.Rows)
                rows as QueryResult.Rows
                assertTrue(rows.next())
                assertEquals(3L, rows.getLong(0))
                rows.close()
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun transactionRollbackOnException() {
        runBlocking {
            val client = PostgresClient.connect(pgConfig())
            try {
                client.execute("CREATE TEMP TABLE _kpit_txr (id int)")
                try {
                    client.transaction { tx ->
                        tx.execute("INSERT INTO _kpit_txr VALUES (1)")
                        throw IllegalStateException("abort")
                    }
                } catch (_: IllegalStateException) {
                }
                assertEquals('I', client.transactionStatus)
                val rows = client.query("SELECT count(*) FROM _kpit_txr")
                assertTrue(rows is QueryResult.Rows)
                rows as QueryResult.Rows
                assertTrue(rows.next())
                assertEquals(0L, rows.getLong(0))
                rows.close()
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun transactionRollbackOnQueryError() {
        runBlocking {
            val client = PostgresClient.connect(pgConfig())
            try {
                client.execute("CREATE TEMP TABLE _kpit_txe (id int)")
                try {
                    client.transaction { tx ->
                        tx.execute("INSERT INTO _kpit_txe VALUES (1)")
                        tx.query("SELECT * FROM no_such_table_kp_xyz")
                    }
                } catch (_: Throwable) {
                }
                assertEquals('I', client.transactionStatus)
                val rows = client.query("SELECT count(*) FROM _kpit_txe")
                assertTrue(rows is QueryResult.Rows)
                rows as QueryResult.Rows
                assertTrue(rows.next())
                assertEquals(0L, rows.getLong(0))
                rows.close()
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun recoverFromProtocolError() {
        runBlocking {
            val client = PostgresClient.connect(pgConfig())
            try {
                try {
                    client.query("SELECT * FROM no_such_table_kp_aaa")
                } catch (e: Throwable) {
                    assertNotNull(e)
                }
                val rows = client.query("SELECT 7 AS seven")
                assertTrue(rows is QueryResult.Rows)
                rows as QueryResult.Rows
                assertTrue(rows.next())
                assertEquals(7, rows.getInt(0))
                rows.close()
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun simpleCommandCompleteStatus() {
        runBlocking {
            val client = PostgresClient.connect(pgConfig())
            try {
                val res = client.query("CREATE TEMP TABLE _kpit_cc (id int)")
                assertTrue(res is QueryResult.Status)
                res as QueryResult.Status
                assertTrue(res.command.startsWith("CREATE"))
                assertEquals(0L, res.rowsAffected)
            } finally {
                client.close()
            }
        }
    }
}