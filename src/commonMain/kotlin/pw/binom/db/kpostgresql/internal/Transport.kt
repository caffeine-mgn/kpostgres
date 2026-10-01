package pw.binom.db.kpostgresql.internal

import pw.binom.db.kpostgresql.PostgresConfig
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.writeString

internal interface PgSocketFactory {
    suspend fun open(host: String, port: Int): PgSocket
    suspend fun close()
}

internal class PgSocket internal constructor(
    val socket: Socket,
    val read: ByteReadChannel,
    val write: ByteWriteChannel,
) {
    suspend fun close() {
        runCatching { write.flushAndClose() }
        runCatching { read.cancel(null) }
        runCatching { socket.close() }
    }
}

internal class KtorSocketFactory(
    private val dispatcher: CoroutineDispatcher,
) : PgSocketFactory {
    private val selector = SelectorManager(dispatcher)

    override suspend fun open(host: String, port: Int): PgSocket {
        val socket = aSocket(selector).tcp().connect(host, port)
        val read = socket.openReadChannel()
        val write = socket.openWriteChannel(autoFlush = false)
        return PgSocket(socket, read, write)
    }

    override suspend fun close() {
        runCatching { selector.close() }
    }
}

internal fun buildStartupMessage(config: PostgresConfig, params: Map<String, String> = mapOf()): ByteArray {
    val effectiveParams = LinkedHashMap<String, String>().apply {
        putAll(params)
        put("user", config.user)
        put("database", config.database)
        put("application_name", config.applicationName)
        put("client_encoding", "UTF8")
    }
    val body = Buffer()
    body.writeInt(196608)
    effectiveParams.forEach { (k, v) ->
        body.writeString(k)
        body.writeByte(0)
        body.writeString(v)
        body.writeByte(0)
    }
    body.writeByte(0)
    val bodyBytes = body.readByteArray()
    val totalLen = bodyBytes.size + 4
    val out = ByteArray(totalLen)
    out[0] = (totalLen shr 24).toByte()
    out[1] = (totalLen shr 16).toByte()
    out[2] = (totalLen shr 8).toByte()
    out[3] = totalLen.toByte()
    bodyBytes.copyInto(out, destinationOffset = 4)
    return out
}

internal fun buildPasswordMessage(password: String): ByteArray {
    val body = Buffer()
    body.writeString(password)
    body.writeByte(0)
    val bodyBytes = body.readByteArray()
    val totalLen = bodyBytes.size + 4
    val out = ByteArray(1 + totalLen)
    out[0] = MessageTag.PASSWORD
    out[1] = (totalLen shr 24).toByte()
    out[2] = (totalLen shr 16).toByte()
    out[3] = (totalLen shr 8).toByte()
    out[4] = totalLen.toByte()
    bodyBytes.copyInto(out, destinationOffset = 5)
    return out
}

internal fun buildTerminateMessage(): ByteArray {
    val out = ByteArray(5)
    out[0] = MessageTag.TERMINATE
    out[1] = 0
    out[2] = 0
    out[3] = 0
    out[4] = 4
    return out
}