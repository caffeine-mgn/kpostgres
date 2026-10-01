package pw.binom.db.kpostgresql

import pw.binom.db.kpostgresql.internal.AuthType
import pw.binom.db.kpostgresql.internal.KtorSocketFactory
import pw.binom.db.kpostgresql.internal.MessageTag
import pw.binom.db.kpostgresql.internal.Oid
import pw.binom.db.kpostgresql.internal.PgConnection
import pw.binom.db.kpostgresql.internal.PgSocketFactory
import pw.binom.db.kpostgresql.internal.SaslAuthHandler
import pw.binom.db.kpostgresql.internal.TransactionStatus
import pw.binom.db.kpostgresql.internal.executeExtendedQuery
import pw.binom.db.kpostgresql.internal.executeSimpleQuery
import pw.binom.db.kpostgresql.internal.readAuthentication
import pw.binom.db.kpostgresql.internal.readErrorResponse
import pw.binom.db.kpostgresql.internal.readNoticeResponse
import pw.binom.db.kpostgresql.internal.readParameterStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

public class PostgresClient internal constructor(
    private val connection: PgConnection,
    private val factory: PgSocketFactory,
) : AutoCloseable {

    public suspend fun query(sql: String): QueryResult {
        return connection.executeSimpleQuery(sql)
    }

    public suspend fun execute(sql: String): Long {
        val res = query(sql)
        try {
            return when (res) {
                is QueryResult.Status -> res.rowsAffected
                is QueryResult.Rows -> 0L
            }
        } finally {
            res.close()
        }
    }

    public suspend fun prepare(sql: String): PreparedStatement {
        return PreparedStatement(connection, sql)
    }

    @OptIn(ExperimentalUuidApi::class)
    public suspend fun executeWithParams(sql: String, params: List<Any?>): QueryResult {
        val types = params.map { pgTypeOf(it) }
        return connection.executeExtendedQuery(sql, types, params)
    }

    public suspend fun <T> transaction(block: suspend (PostgresClient) -> T): T {
        connection.executeSimpleQuery("BEGIN")
        return try {
            val result = block(this)
            connection.executeSimpleQuery("COMMIT")
            result
        } catch (e: Throwable) {
            runCatching { connection.executeSimpleQuery("ROLLBACK") }
            throw e
        }
    }

    public val isClosed: Boolean
        get() = connection.closed

    public val transactionStatus: Char
        get() = when (connection.transactionStatus()) {
            TransactionStatus.IDLE -> 'I'
            TransactionStatus.IN_TRANSACTION -> 'T'
            TransactionStatus.IN_FAILED_TRANSACTION -> 'E'
            else -> '?'
        }

    override fun close() {
        if (connection.closed) return
        runBlocking { connection.close() }
    }

    public companion object {
        public suspend fun connect(
            config: PostgresConfig,
            dispatcher: CoroutineDispatcher = Dispatchers.Default,
        ): PostgresClient {
            val factory = KtorSocketFactory(dispatcher)
            val socket = factory.open(config.host, config.port)
            val conn = PgConnection(
                config = config,
                readChannel = socket.read,
                writeChannel = socket.write,
                socket = socket,
                factory = factory,
            )
            try {
                performStartup(conn, config)
            } catch (e: Throwable) {
                conn.close()
                throw e
            }
            return PostgresClient(conn, factory)
        }
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun pgTypeOf(value: Any?): Int = when (value) {
    null -> 0
    is Int -> Oid.INT4
    is Long -> Oid.INT8
    is Short -> Oid.INT2
    is Float -> Oid.FLOAT4
    is Double -> Oid.FLOAT8
    is Boolean -> Oid.BOOL
    is String -> Oid.TEXT
    is Uuid -> Oid.UUID
    is ByteArray -> Oid.BYTEA
    else -> Oid.TEXT
}

private suspend fun performStartup(conn: PgConnection, config: PostgresConfig) {
    conn.sendStartupMessage()
    while (true) {
        val frame = conn.receiveFrame()
        when (frame.tag) {
            MessageTag.AUTHENTICATION -> {
                val authType = readAuthentication(frame)
                when (authType) {
                    AuthType.OK -> frame.end()
                    AuthType.CLEARTEXT_PASSWORD -> {
                        frame.end()
                        conn.sendPassword(config.password)
                    }
                    AuthType.MD5_PASSWORD -> {
                        throw PostgresException("MD5 password authentication is not supported")
                    }
                    AuthType.SASL -> {
                        SaslAuthHandler().start(conn, config, frame)
                    }
                    else -> {
                        frame.end()
                        throw PostgresException("Unsupported authentication type: $authType")
                    }
                }
            }
            MessageTag.PARAMETER_STATUS -> {
                readParameterStatus(frame)
            }
            MessageTag.BACKEND_KEY_DATA -> {
                frame.end()
            }
            MessageTag.NOTICE_RESPONSE -> {
                readNoticeResponse(frame)
            }
            MessageTag.ERROR_RESPONSE -> {
                val err = readErrorResponse(frame)
                throw PostgresException("Startup error: ${err.message}", sqlState = err.sqlState)
            }
            MessageTag.READY_FOR_QUERY -> {
                val status = frame.readByte()
                frame.end()
                conn.updateTxStatus(status)
                return
            }
            else -> frame.end()
        }
    }
}