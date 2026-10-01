package pw.binom.db.kpostgresql

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
            PostgresClient.connect(pgConfig()).use { client ->
                client.query("SELECT 1 AS one").use { rows ->
                    assertTrue(rows is QueryResult.Rows)
                    rows as QueryResult.Rows
                    assertTrue(rows.next())
                    assertEquals(1, rows.getInt(0))
                    assertEquals("one", rows.columns[0].name)
                }
            }
        }
    }

    @Test
    fun extendedQueryBindIntAndText() {
        runBlocking {
            PostgresClient.connect(pgConfig()).use { client ->
                client.executeWithParams(
                    "SELECT \$1::int AS a, \$2::text AS b",
                    listOf(42, "hello"),
                ).use { rows ->
                    assertTrue(rows is QueryResult.Rows)
                    rows as QueryResult.Rows
                    assertEquals(2, rows.columns.size)
                    assertTrue(rows.next())
                    assertEquals(42, rows.getInt(0))
                    assertEquals("hello", rows.getString(1))
                }
            }
        }
    }

    @Test
    fun extendedQueryBindNullAndUuidAndBytea() {
        runBlocking {
            PostgresClient.connect(pgConfig()).use { client ->
                val u = Uuid.parse("00112233-4455-6677-8899-aabbccddeeff")
                val ba = byteArrayOf(0x00, 0x7F.toByte(), 0x42, 0xFF.toByte())
                client.executeWithParams(
                    "SELECT \$1::uuid AS u, \$2::bytea AS b, \$3::int AS n",
                    listOf(u, ba, null),
                ).use { rows ->
                    assertTrue(rows is QueryResult.Rows)
                    rows as QueryResult.Rows
                    assertTrue(rows.next())
                    assertEquals(u, rows.getUuid(0))
                    assertEquals(ba.toList(), rows.getBytes(1)!!.toList())
                    assertTrue(rows.isNull(2))
                }
            }
        }
    }

    @Test
    fun preparedStatementExecuteMultiple() {
        runBlocking {
            PostgresClient.connect(pgConfig()).use { client ->
                client.execute("CREATE TEMP TABLE _kpit_t (id int, label text)")
                client.prepare("INSERT INTO _kpit_t VALUES (\$1, \$2)").use { stmt ->
                    val n = stmt.execute(1, "a") + stmt.execute(2, "b") + stmt.execute(3, "c")
                    assertEquals(3L, n)
                }
                client.query("SELECT id, label FROM _kpit_t ORDER BY id").use { rows ->
                    assertTrue(rows is QueryResult.Rows)
                    rows as QueryResult.Rows
                    val collected = mutableListOf<Pair<Int?, String?>>()
                    while (rows.next()) {
                        collected += rows.getInt(0) to rows.getString(1)
                    }
                    assertEquals(listOf<Pair<Int?, String?>>(1 to "a", 2 to "b", 3 to "c"), collected)
                }
            }
        }
    }

    @Test
    fun transactionCommits() {
        runBlocking {
            PostgresClient.connect(pgConfig()).use { client ->
                client.execute("CREATE TEMP TABLE _kpit_tx (id int)")
                client.transaction {
                    it.execute("INSERT INTO _kpit_tx VALUES (1),(2),(3)")
                }
                assertEquals('I', client.transactionStatus)
                client.query("SELECT count(*) FROM _kpit_tx").use { rows ->
                    assertTrue(rows is QueryResult.Rows)
                    rows as QueryResult.Rows
                    assertTrue(rows.next())
                    assertEquals(3L, rows.getLong(0))
                }
            }
        }
    }

    @Test
    fun transactionRollbackOnException() {
        runBlocking {
            PostgresClient.connect(pgConfig()).use { client ->
                client.execute("CREATE TEMP TABLE _kpit_txr (id int)")
                try {
                    client.transaction {
                        it.execute("INSERT INTO _kpit_txr VALUES (1)")
                        throw IllegalStateException("abort")
                    }
                } catch (_: IllegalStateException) {
                }
                assertEquals('I', client.transactionStatus)
                client.query("SELECT count(*) FROM _kpit_txr").use { rows ->
                    assertTrue(rows is QueryResult.Rows)
                    rows as QueryResult.Rows
                    assertTrue(rows.next())
                    assertEquals(0L, rows.getLong(0))
                }
            }
        }
    }

    @Test
    fun transactionRollbackOnQueryError() {
        runBlocking {
            PostgresClient.connect(pgConfig()).use { client ->
                client.execute("CREATE TEMP TABLE _kpit_txe (id int)")
                try {
                    client.transaction {
                        it.execute("INSERT INTO _kpit_txe VALUES (1)")
                        it.query("SELECT * FROM no_such_table_kp_xyz")
                    }
                } catch (_: Throwable) {
                }
                assertEquals('I', client.transactionStatus)
                client.query("SELECT count(*) FROM _kpit_txe").use { rows ->
                    assertTrue(rows is QueryResult.Rows)
                    rows as QueryResult.Rows
                    assertTrue(rows.next())
                    assertEquals(0L, rows.getLong(0))
                }
            }
        }
    }

    @Test
    fun recoverFromProtocolError() {
        runBlocking {
            PostgresClient.connect(pgConfig()).use { client ->
                try {
                    client.query("SELECT * FROM no_such_table_kp_aaa")
                } catch (e: Throwable) {
                    assertNotNull(e)
                }
                client.query("SELECT 7 AS seven").use { rows ->
                    assertTrue(rows is QueryResult.Rows)
                    rows as QueryResult.Rows
                    assertTrue(rows.next())
                    assertEquals(7, rows.getInt(0))
                }
            }
        }
    }

    @Test
    fun simpleCommandCompleteStatus() {
        runBlocking {
            PostgresClient.connect(pgConfig()).use { client ->
                client.query("CREATE TEMP TABLE _kpit_cc (id int)").use { res ->
                    assertTrue(res is QueryResult.Status)
                    res as QueryResult.Status
                    assertTrue(res.command.startsWith("CREATE"))
                    assertEquals(0L, res.rowsAffected)
                }
            }
        }
    }

    @Test
    fun isBusyClearsAfterQuery() {
        runBlocking {
            PostgresClient.connect(pgConfig()).use { client ->
                assertEquals(false, client.isBusy)
                client.query("SELECT 1 AS one").use { rows ->
                    assertEquals(false, client.isBusy)
                }
                assertEquals(false, client.isBusy)
            }
        }
    }

    @Test
    fun isBusyDuringLongQuery() {
        runBlocking {
            PostgresClient.connect(pgConfig()).use { client ->
                val seenBusy = ArrayList<Boolean>(1)
                val launcher = launch {
                    client.query("SELECT pg_sleep(0.3)").use { res ->
                        val rows = res as QueryResult.Rows
                        rows.next()
                    }
                }
                delay(50)
                seenBusy += client.isBusy
                launcher.join()
                assertEquals(true, seenBusy.single(), "Connection must be busy while pg_sleep runs")
                assertEquals(false, client.isBusy)
            }
        }
    }
}