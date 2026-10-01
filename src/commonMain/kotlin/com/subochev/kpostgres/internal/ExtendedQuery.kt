package com.subochev.kpostgres.internal

import com.subochev.kpostgres.ColumnMeta
import com.subochev.kpostgres.PostgresException
import com.subochev.kpostgres.PostgresProtocolException
import com.subochev.kpostgres.QueryResult
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.sync.withLock
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.writeString
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal suspend fun PgConnection.executeExtendedQuery(
    sql: String,
    paramTypes: List<Int>,
    params: List<Any?>,
): QueryResult {
    checkOpen()
    return sendLock.withLock {
        sendParse("", sql, paramTypes)
        sendBind("", "", params, paramFormat = 0, resultFormat = 0)
        sendDescribe("", 'P')
        sendExecute("", 0)
        sendSync()
        readExtendedQueryResult()
    }
}

internal suspend fun PgConnection.executeExtendedQuerySimple(
    sql: String,
    paramTypes: List<Int>,
    params: List<Any?>,
): QueryResult {
    checkOpen()
    return sendLock.withLock {
        sendParse("", sql, paramTypes)
        sendBind("", "", params, paramFormat = 0, resultFormat = 0)
        sendDescribe("", 'P')
        sendExecute("", 0)
        sendSync()
        readExtendedQueryResult()
    }
}

private suspend fun PgConnection.sendParse(
    statementName: String,
    sql: String,
    paramTypes: List<Int>,
) {
    val body = Buffer()
    body.writeCString(statementName)
    body.writeCString(sql)
    body.writeShort(paramTypes.size.toShort())
    for (oid in paramTypes) body.writeInt(oid)
    val bytes = body.readByteArray()
    writeFrontendMessage(MessageTag.PARSE, bytes)
}

private suspend fun PgConnection.sendBind(
    portalName: String,
    statementName: String,
    params: List<Any?>,
    paramFormat: Int,
    resultFormat: Int,
) {
    val body = Buffer()
    body.writeCString(portalName)
    body.writeCString(statementName)
    body.writeShort(1)
    body.writeShort(paramFormat.toShort())
    body.writeShort(params.size.toShort())
    for (p in params) {
        if (p == null) {
            body.writeInt(-1)
        } else {
            val encoded = encodeParamText(p)
            body.writeInt(encoded.size)
            body.write(encoded)
        }
    }
    body.writeShort(1)
    body.writeShort(resultFormat.toShort())
    val bytes = body.readByteArray()
    writeFrontendMessage(MessageTag.BIND, bytes)
}

private suspend fun PgConnection.sendDescribe(target: String, kind: Char) {
    val body = Buffer()
    body.writeByte(kind.code.toByte())
    body.writeCString(target)
    val bytes = body.readByteArray()
    writeFrontendMessage(MessageTag.DESCRIBE, bytes)
}

private suspend fun PgConnection.sendExecute(portalName: String, maxRows: Int) {
    val body = Buffer()
    body.writeCString(portalName)
    body.writeInt(maxRows)
    val bytes = body.readByteArray()
    writeFrontendMessage(MessageTag.EXECUTE, bytes)
}

private suspend fun PgConnection.sendSync() {
    writeFrontendMessage(MessageTag.SYNC, ByteArray(0))
}

private suspend fun PgConnection.writeFrontendMessage(tag: Byte, body: ByteArray) {
    val totalLen = body.size + 4
    val out = ByteArray(1 + totalLen)
    out[0] = tag
    out[1] = (totalLen shr 24).toByte()
    out[2] = (totalLen shr 16).toByte()
    out[3] = (totalLen shr 8).toByte()
    out[4] = totalLen.toByte()
    body.copyInto(out, destinationOffset = 5)
    writeChannel.writeFully(out, 0, out.size)
    writeChannel.flush()
}

private suspend fun Buffer.writeCString(value: String) {
    writeString(value)
    writeByte(0)
}

@OptIn(ExperimentalUuidApi::class)
private fun encodeParamText(value: Any?): ByteArray {
    val buf = Buffer()
    if (value == null) return buf.readByteArray()
    val text = when (value) {
        is String -> value
        is Int -> value.toString()
        is Long -> value.toString()
        is Short -> value.toString()
        is Float -> value.toString()
        is Double -> value.toString()
        is Boolean -> if (value) "t" else "f"
        is Uuid -> value.toString()
        is ByteArray -> encodeBytesAsHex(value)
        else -> value.toString()
    }
    buf.writeString(text)
    return buf.readByteArray()
}

private fun encodeBytesAsHex(value: ByteArray): String {
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

private suspend fun PgConnection.readExtendedQueryResult(): QueryResult {
    var columns: List<ColumnMeta>? = null
    val rows = ArrayList<Array<ByteArray?>>()
    var lastCommand = ""
    var lastRowsAffected = 0L
    var commandCompleteSeen = false
    var rowDescriptionSeen = false
    while (true) {
        val frame = receiveFrame()
        when (frame.tag) {
            MessageTag.PARSE_COMPLETE,
            MessageTag.BIND_COMPLETE,
            MessageTag.CLOSE_COMPLETE -> {
                frame.end()
            }
            MessageTag.ROW_DESCRIPTION -> {
                columns = readRowDescription(frame)
                rowDescriptionSeen = true
            }
            MessageTag.NO_DATA -> {
                frame.end()
            }
            MessageTag.DATA_ROW -> {
                val cols = frame.readShort().toInt()
                val row = arrayOfNulls<ByteArray>(cols)
                for (i in 0 until cols) {
                    val len = frame.readInt()
                    row[i] = when {
                        len < 0 -> null
                        len == 0 -> ByteArray(0)
                        else -> frame.readBytes(len)
                    }
                }
                frame.end()
                rows += row
            }
            MessageTag.COMMAND_COMPLETE -> {
                val (cmd, rowsAffected) = readCommandComplete(frame)
                lastCommand = cmd
                lastRowsAffected = rowsAffected
                commandCompleteSeen = true
            }
            MessageTag.EMPTY_QUERY_RESPONSE -> {
                frame.end()
            }
            MessageTag.ERROR_RESPONSE -> {
                val err = readErrorResponse(frame)
                consumeToReadyForQuery()
                throw PostgresException("Query error: ${err.message}", sqlState = err.sqlState)
            }
            MessageTag.READY_FOR_QUERY -> {
                val status = frame.readByte()
                frame.end()
                updateTxStatus(status)
                if (rowDescriptionSeen) {
                    return QueryResult.Rows(columns ?: emptyList(), PgResultSet(columns ?: emptyList(), rows))
                }
                if (commandCompleteSeen) {
                    return QueryResult.Status(command = lastCommand, rowsAffected = lastRowsAffected)
                }
                throw PostgresProtocolException("ReadyForQuery without any preceding result")
            }
            MessageTag.NOTICE_RESPONSE,
            MessageTag.PARAMETER_STATUS,
            MessageTag.NOTIFICATION_RESPONSE -> {
                frame.end()
            }
            else -> frame.end()
        }
    }
}
