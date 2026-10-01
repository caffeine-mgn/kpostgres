package pw.binom.db.kpostgresql.internal

import pw.binom.db.kpostgresql.PostgresConfig
import pw.binom.db.kpostgresql.PostgresConnectionClosedException
import pw.binom.db.kpostgresql.PostgresException
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readByte
import io.ktor.utils.io.readInt
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class PgConnection internal constructor(
    private val config: PostgresConfig,
    internal val readChannel: ByteReadChannel,
    internal val writeChannel: ByteWriteChannel,
    internal val socket: PgSocket,
    internal val factory: PgSocketFactory,
) {
    internal val sendLock = Mutex()
    internal var closed: Boolean = false
    internal var txStatus: Byte = TransactionStatus.IDLE
        private set

    internal fun transactionStatus(): Byte = txStatus

    internal fun updateTxStatus(status: Byte) {
        txStatus = status
    }

    internal fun checkOpen() {
        if (closed) throw PostgresConnectionClosedException()
    }

    internal suspend fun send(bytes: ByteArray) {
        checkOpen()
        writeChannel.writeFully(bytes, 0, bytes.size)
        writeChannel.flush()
    }

    internal suspend fun sendStartupMessage() {
        send(buildStartupMessage(config))
    }

    internal suspend fun sendPassword(password: String) {
        send(buildPasswordMessage(password))
    }

    internal suspend fun sendTerminate() {
        send(buildTerminateMessage())
    }

    internal suspend fun receiveFrame(): ServerMessage {
        checkOpen()
        val tag = try {
            readChannel.readByte()
        } catch (e: Throwable) {
            closed = true
            throw PostgresConnectionClosedException("Connection closed while reading frame: ${e.message}")
        }
        val length = readChannel.readInt()
        if (length < 4) {
            throw PostgresException("Invalid frame length: $length (tag=$tag)")
        }
        val bodyLength = length - 4
        return ServerMessage(tag, bodyLength, readChannel)
    }

    internal suspend fun consumeToReadyForQuery(): Byte {
        while (true) {
            val frame = receiveFrame()
            when (frame.tag) {
                MessageTag.READY_FOR_QUERY -> {
                    val status = frame.readByte()
                    frame.end()
                    txStatus = status
                    return status
                }
                MessageTag.NOTICE_RESPONSE,
                MessageTag.PARAMETER_STATUS,
                MessageTag.NOTIFICATION_RESPONSE,
                MessageTag.PORTAL_SUSPENDED -> {
                    frame.end()
                }
                MessageTag.ERROR_RESPONSE -> {
                    val err = readErrorResponse(frame)
                    throw PostgresException("Server error: ${err.message}", sqlState = err.sqlState)
                }
                else -> frame.end()
            }
        }
    }

    internal suspend fun close() {
        if (closed) return
        closed = true
        runCatching {
            sendTerminate()
        }
        socket.close()
        factory.close()
    }
}

internal data class PgError(val message: String, val sqlState: String?, val fields: Map<Char, String>)

internal suspend fun readErrorResponse(msg: ServerMessage): PgError {
    val fields = mutableMapOf<Char, String>()
    var message: String? = null
    while (true) {
        val key = msg.readByte()
        if (key == 0.toByte()) break
        val value = msg.readCString()
        val c = key.toInt().toChar()
        fields[c] = value
        if (c == 'M') message = value
    }
    msg.end()
    return PgError(message ?: "Unknown error", fields['C'], fields)
}

internal suspend fun readNoticeResponse(msg: ServerMessage): Map<Char, String> {
    val fields = mutableMapOf<Char, String>()
    while (true) {
        val key = msg.readByte()
        if (key == 0.toByte()) break
        val value = msg.readCString()
        fields[key.toInt().toChar()] = value
    }
    msg.end()
    return fields
}

internal suspend fun readParameterStatus(msg: ServerMessage): Pair<String, String> {
    val name = msg.readCString()
    val value = msg.readCString()
    msg.end()
    return name to value
}

internal suspend fun readBackendKeyData(msg: ServerMessage): Pair<Int, Int> {
    val pid = msg.readInt()
    val secret = msg.readInt()
    msg.end()
    return pid to secret
}

internal suspend fun readAuthentication(msg: ServerMessage): Int {
    val type = msg.readInt()
    return type
}