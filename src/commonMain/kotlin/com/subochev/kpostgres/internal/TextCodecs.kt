package com.subochev.kpostgres.internal

import com.subochev.kpostgres.PostgresException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.io.Buffer
import kotlinx.io.readString

@OptIn(ExperimentalUuidApi::class)
internal object TextDecoders {

    private fun decodeUtf8(raw: ByteArray): String {
        val buf = Buffer()
        buf.write(raw)
        return buf.readString()
    }

    fun decodeString(raw: ByteArray): String = decodeUtf8(raw)

    fun decodeInt(raw: ByteArray): Int = decodeUtf8(raw).trim().toInt()

    fun decodeLong(raw: ByteArray): Long = decodeUtf8(raw).trim().toLong()

    fun decodeShort(raw: ByteArray): Short = decodeUtf8(raw).trim().toShort()

    fun decodeFloat(raw: ByteArray): Float = decodeUtf8(raw).trim().toFloat()

    fun decodeDouble(raw: ByteArray): Double = decodeUtf8(raw).trim().toDouble()

    fun decodeBoolean(raw: ByteArray): Boolean {
        return when (decodeUtf8(raw).trim()) {
            "t", "true", "yes", "on", "1" -> true
            "f", "false", "no", "off", "0" -> false
            else -> throw PostgresException("Cannot decode boolean: ${decodeUtf8(raw)}")
        }
    }

    fun decodeUuid(raw: ByteArray): Uuid = Uuid.parse(decodeUtf8(raw).trim())

    fun decodeBytesFromHex(raw: ByteArray): ByteArray {
        val str = decodeUtf8(raw).trim()
        if (!str.startsWith("\\x")) {
            throw PostgresException("Cannot decode bytea (expected \\x prefix): $str")
        }
        val hex = str.substring(2)
        if (hex.length % 2 != 0) {
            throw PostgresException("Odd hex length in bytea: $str")
        }
        val out = ByteArray(hex.length / 2)
        for (i in 0 until hex.length / 2) {
            val hi = hexDigit(hex[i * 2])
            val lo = hexDigit(hex[i * 2 + 1])
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun hexDigit(c: Char): Int = when (c) {
        in '0'..'9' -> c.code - '0'.code
        in 'a'..'f' -> c.code - 'a'.code + 10
        in 'A'..'F' -> c.code - 'A'.code + 10
        else -> throw PostgresException("Invalid hex digit: $c")
    }
}

@OptIn(ExperimentalUuidApi::class)
internal object TextEncoders {
    fun encodeString(value: String): String = value

    fun encodeInt(value: Int): String = value.toString()

    fun encodeLong(value: Long): String = value.toString()

    fun encodeShort(value: Short): String = value.toString()

    fun encodeFloat(value: Float): String = value.toString()

    fun encodeDouble(value: Double): String = value.toString()

    fun encodeBoolean(value: Boolean): String = if (value) "t" else "f"

    fun encodeUuid(value: Uuid): String = value.toString()

    fun encodeBytesAsHex(value: ByteArray): String {
        val sb = StringBuilder(value.size * 2 + 2)
        sb.append("\\x")
        for (b in value) {
            val v = b.toInt() and 0xff
            val hi = v ushr 4
            val lo = v and 0x0f
            sb.append("0123456789abcdef"[hi])
            sb.append("0123456789abcdef"[lo])
        }
        return sb.toString()
    }
}

internal object Oid {
    const val BOOL: Int = 16
    const val BYTEA: Int = 17
    const val CHAR: Int = 18
    const val INT8: Int = 20
    const val INT2: Int = 21
    const val INT4: Int = 23
    const val TEXT: Int = 25
    const val FLOAT4: Int = 700
    const val FLOAT8: Int = 701
    const val VARCHAR: Int = 1043
    const val DATE: Int = 1082
    const val TIME: Int = 1083
    const val TIMESTAMP: Int = 1114
    const val TIMESTAMPTZ: Int = 1184
    const val UUID: Int = 2950
    const val JSON: Int = 114
    const val JSONB: Int = 3802
}