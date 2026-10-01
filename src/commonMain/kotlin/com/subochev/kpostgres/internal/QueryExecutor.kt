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

internal suspend fun PgConnection.executeSimpleQuery(sql: String): QueryResult {
    checkOpen()
    return sendLock.withLock {
        sendSimpleQuery(sql)
        readSimpleQueryResult()
    }
}

internal suspend fun PgConnection.sendSimpleQuery(sql: String) {
    val body = Buffer()
    body.writeString(sql)
    body.writeByte(0)
    val bodyBytes = body.readByteArray()
    val totalLen = bodyBytes.size + 4
    val out = ByteArray(1 + totalLen)
    out[0] = MessageTag.QUERY
    out[1] = (totalLen shr 24).toByte()
    out[2] = (totalLen shr 16).toByte()
    out[3] = (totalLen shr 8).toByte()
    out[4] = totalLen.toByte()
    bodyBytes.copyInto(out, destinationOffset = 5)
    writeChannel.writeFully(out, 0, out.size)
    writeChannel.flush()
}

private suspend fun PgConnection.readSimpleQueryResult(): QueryResult {
    var columns: List<ColumnMeta>? = null
    val rows = ArrayList<Array<ByteArray?>>()
    var lastCommand = ""
    var lastRowsAffected = 0L
    var commandCompleteSeen = false
    while (true) {
        val frame = receiveFrame()
        when (frame.tag) {
            MessageTag.ROW_DESCRIPTION -> {
                columns = readRowDescription(frame)
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
                consumeToReadyForQuery()
                return QueryResult.Status(command = "", rowsAffected = 0L)
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
                if (columns != null) {
                    return QueryResult.Rows(columns, PgResultSet(columns, rows))
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