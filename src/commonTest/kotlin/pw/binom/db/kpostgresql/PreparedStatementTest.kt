package pw.binom.db.kpostgresql.internal

import pw.binom.db.kpostgresql.PostgresConfig
import pw.binom.db.kpostgresql.PostgresException
import pw.binom.db.kpostgresql.QueryResult
import pw.binom.db.kpostgresql.useSuspending
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class PreparedStatementTest {

    @Test
    fun simpleQueryProtocolLifecycle() = runBlocking {
        newConnection().useSuspending { conn ->
            conn.executeSimpleQuery("SELECT 1::int AS a, 'x'::text AS b").use { res ->
                assertTrue(res is QueryResult.Rows, "expected Rows, got ${res::class.simpleName}")
                res as QueryResult.Rows
                assertEquals(2, res.columns.size)
                assertEquals(listOf("a", "b"), res.columns.map { it.name })
                assertTrue(res.next())
                assertEquals(1, res.getInt(0))
                assertEquals("x", res.getString(1))
            }
        }
    }

    @Test
    fun commandCompleteRowsAffected() = runBlocking {
        newConnection().useSuspending { conn ->
            conn.executeSimpleQuery("CREATE TEMP TABLE _kp_t (id int); INSERT INTO _kp_t VALUES (1),(2),(3);").use { res ->
                assertTrue(res is QueryResult.Status)
                assertEquals(3L, (res as QueryResult.Status).rowsAffected)
            }
        }
    }

    @Test
    fun nullAndEmpty() = runBlocking {
        newConnection().useSuspending { conn ->
            conn.executeSimpleQuery("SELECT NULL::int AS n, ''::text AS e").use { res ->
                assertTrue(res is QueryResult.Rows)
                res as QueryResult.Rows
                assertTrue(res.next())
                assertTrue(res.isNull(0))
                assertEquals(null, res.getInt(0))
                assertEquals("", res.getString(1))
            }
        }
    }

    @Test
    fun uuidRoundtrip() = runBlocking {
        newConnection().useSuspending { conn ->
            val u = Uuid.parse("12345678-1234-5678-1234-567812345678")
            conn.executeSimpleQuery("SELECT '$u'::uuid AS u").use { res ->
                assertTrue(res is QueryResult.Rows)
                res as QueryResult.Rows
                assertTrue(res.next())
                assertEquals(u, res.getUuid(0))
            }
        }
    }

    @Test
    fun protocolErrorIsReported() = runBlocking {
        newConnection().useSuspending { conn ->
            var caught: Throwable? = null
            try {
                conn.executeSimpleQuery("SELECT * FROM nonexistent_table_xyz")
            } catch (e: Throwable) {
                caught = e
            }
            assertNotNull(caught)
            assertTrue(caught!!.message!!.contains("nonexistent_table_xyz") || caught!!.message!!.contains("relation"))
            assertEquals('I'.code.toByte(), conn.transactionStatus())
        }
    }

    @Test
    fun extendedQueryReturnsRows() = runBlocking {
        newConnection().useSuspending { conn ->
            conn.executeExtendedQuery(
                sql = "SELECT \$1::int AS a, \$2::text AS b",
                paramTypes = listOf(Oid.INT4, Oid.TEXT),
                params = listOf(42, "hello"),
            ).use { res ->
                assertTrue(res is QueryResult.Rows, "got ${res::class.simpleName}")
                res as QueryResult.Rows
                assertEquals(2, res.columns.size)
                assertTrue(res.next())
                assertEquals(42, res.getInt(0))
                assertEquals("hello", res.getString(1))
            }
        }
    }

    @Test
    fun extendedQueryBindNulls() = runBlocking {
        newConnection().useSuspending { conn ->
            conn.executeExtendedQuery(
                sql = "SELECT \$1::int IS NULL AS is_null, \$2::text AS s",
                paramTypes = listOf(Oid.INT4, Oid.TEXT),
                params = listOf(null, "x"),
            ).use { res ->
                assertTrue(res is QueryResult.Rows)
                res as QueryResult.Rows
                assertTrue(res.next())
                assertTrue(res.isNull(0) || res.getBoolean(0) == true)
                assertEquals("x", res.getString(1))
            }
        }
    }

    @Test
    fun extendedQueryTypeMismatchReportsError() = runBlocking {
        newConnection().useSuspending { conn ->
            var caught: Throwable? = null
            try {
                conn.executeExtendedQuery(
                    sql = "SELECT \$1::int",
                    paramTypes = listOf(Oid.INT4),
                    params = listOf("not_a_number"),
                )
            } catch (e: Throwable) {
                caught = e
            }
            assertNotNull(caught)
            assertEquals('I'.code.toByte(), conn.transactionStatus())
        }
    }

    private fun newConnection(): PgConnection {
        val host = System.getenv("PGHOST") ?: "127.0.0.1"
        val port = System.getenv("PGPORT")?.toIntOrNull() ?: 5432
        val db = System.getenv("PGDATABASE") ?: "kp_test"
        val user = System.getenv("PGUSER") ?: "kpgtest"
        val password = System.getenv("PGPASSWORD") ?: "kp_secret"
        val config = PostgresConfig.of(host, port, db, user, password)
        return runBlocking {
            val factory = KtorSocketFactory(kotlinx.coroutines.Dispatchers.Default)
            val socket = factory.open(config.host, config.port)
            val conn = PgConnection(
                config = config,
                readChannel = socket.read,
                writeChannel = socket.write,
                socket = socket,
                factory = factory,
            )
            try {
                startupForTests(conn, config)
            } catch (e: Throwable) {
                conn.close()
                throw e
            }
            conn
        }
    }

    private suspend fun startupForTests(conn: PgConnection, config: PostgresConfig) {
        conn.sendStartupMessage()
        while (true) {
            val frame = conn.receiveFrame()
            when (frame.tag) {
                MessageTag.AUTHENTICATION -> {
                    val type = readAuthentication(frame)
                    when (type) {
                        AuthType.OK -> frame.end()
                        AuthType.CLEARTEXT_PASSWORD -> {
                            frame.end()
                            conn.sendPassword(config.password)
                        }
                        AuthType.MD5_PASSWORD -> throw PostgresException("MD5 not supported")
                        AuthType.SASL -> SaslAuthHandler().start(conn, config, frame)
                        else -> {
                            frame.end()
                            throw PostgresException("Auth $type not supported")
                        }
                    }
                }
                MessageTag.PARAMETER_STATUS -> readParameterStatus(frame)
                MessageTag.BACKEND_KEY_DATA -> frame.end()
                MessageTag.NOTICE_RESPONSE -> readNoticeResponse(frame)
                MessageTag.ERROR_RESPONSE -> {
                    val err = readErrorResponse(frame)
                    throw PostgresException("Startup error: ${err.message}", sqlState = err.sqlState)
                }
                MessageTag.READY_FOR_QUERY -> {
                    val s = frame.readByte()
                    frame.end()
                    conn.updateTxStatus(s)
                    return
                }
                else -> frame.end()
            }
        }
    }
}