package pw.binom.db.kpostgresql.internal

import pw.binom.db.kpostgresql.PostgresException
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.discardExact
import io.ktor.utils.io.readByte
import io.ktor.utils.io.readFully
import io.ktor.utils.io.readInt
import io.ktor.utils.io.readShort
import io.ktor.utils.io.writeByte
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeInt
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

internal class FrameSender(
    private val channel: ByteWriteChannel,
) {
    private val body = Buffer()

    suspend inline fun send(tag: Byte, build: Buffer.() -> Unit) {
        body.clear()
        body.build()
        val bodySize = body.size.toInt()
        channel.writeByte(tag)
        channel.writeInt(bodySize + 4)
        if (bodySize > 0) {
            channel.writeFully(body.readByteArray(), 0, bodySize)
        }
        channel.flush()
    }

    suspend fun sendRaw(startupBytes: ByteArray) {
        channel.writeFully(startupBytes, 0, startupBytes.size)
        channel.flush()
    }
}

internal class FrameReader(
    private val channel: ByteReadChannel,
) {
    suspend fun readFrame(): ServerMessage {
        val tag = channel.readByte()
        val length = channel.readInt()
        if (length < 4) {
            throw PostgresException("Invalid frame length: $length (tag=$tag)")
        }
        val bodyLength = length - 4
        return ServerMessage(tag, bodyLength, channel)
    }
}

internal class ServerMessage internal constructor(
    val tag: Byte,
    val bodyLength: Int,
    private val channel: ByteReadChannel,
) {
    private var position: Int = 0

    val remaining: Int get() = bodyLength - position

    suspend fun end() {
        val left = bodyLength - position
        if (left > 0) channel.discardExact(left.toLong())
        position = bodyLength
    }

    suspend fun readByte(): Byte {
        val b = channel.readByte()
        position++
        return b
    }

    suspend fun readShort(): Short {
        val s = channel.readShort()
        position += 2
        return s
    }

    suspend fun readInt(): Int {
        val i = channel.readInt()
        position += 4
        return i
    }

    suspend fun readBytes(count: Int): ByteArray {
        val buf = ByteArray(count)
        channel.readFully(buf, 0, count)
        position += count
        return buf
    }

    suspend fun readCString(): String {
        val sb = StringBuilder()
        while (true) {
            val b = channel.readByte()
            position++
            if (b == 0.toByte()) break
            sb.append(b.toInt().toChar())
        }
        return sb.toString()
    }

    suspend fun skip(count: Int) {
        if (count > 0) channel.discardExact(count.toLong())
        position += count
    }

    fun remainingBytes(): Int = bodyLength - position
}