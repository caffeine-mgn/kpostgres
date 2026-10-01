package pw.binom.db.kpostgresql.internal

import pw.binom.db.kpostgresql.ColumnMeta

internal class PgResultSet internal constructor(
    val columns: List<ColumnMeta>,
    private val rows: List<Array<ByteArray?>>,
) {
    private var cursor: Int = -1
    private var currentRow: Array<ByteArray?>? = null

    internal suspend fun next(): Boolean {
        cursor += 1
        if (cursor >= rows.size) {
            currentRow = null
            return false
        }
        currentRow = rows[cursor]
        return true
    }

    internal fun getRaw(index: Int): ByteArray? {
        val row = currentRow ?: throw pw.binom.db.kpostgresql.PostgresException("No current row; call next() first")
        if (index < 0 || index >= row.size) {
            throw IndexOutOfBoundsException("Column index $index out of bounds [0, ${row.size})")
        }
        return row[index]
    }

    internal fun isNull(index: Int): Boolean = getRaw(index) == null

    internal suspend fun close() = Unit
}

internal suspend fun readRowDescription(frame: ServerMessage): List<ColumnMeta> {
    val count = frame.readShort().toInt()
    val list = ArrayList<ColumnMeta>(count)
    repeat(count) {
        val name = frame.readCString()
        val tableOid = frame.readInt()
        val colNum = frame.readShort().toInt()
        val typeOid = frame.readInt()
        val typeSize = frame.readShort()
        val typeMod = frame.readInt()
        val format = frame.readShort()
        list += ColumnMeta(
            name = name,
            tableObjectId = tableOid,
            columnNumber = colNum,
            typeOid = typeOid,
            typeSize = typeSize,
            typeModifier = typeMod,
            format = format,
        )
    }
    frame.end()
    return list
}

internal suspend fun readCommandComplete(frame: ServerMessage): Pair<String, Long> {
    val status = frame.readCString()
    frame.end()
    val (command, rows) = parseCommandComplete(status)
    return command to rows
}

private fun parseCommandComplete(status: String): Pair<String, Long> {
    val spaceIdx = status.lastIndexOf(' ')
    if (spaceIdx < 0) return status to 0L
    val command = status.substring(0, spaceIdx)
    val tail = status.substring(spaceIdx + 1)
    val rows = tail.toLongOrNull() ?: 0L
    return command to rows
}