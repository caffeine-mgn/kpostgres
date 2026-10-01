package com.subochev.kpostgres

import com.subochev.kpostgres.internal.PgResultSet
import com.subochev.kpostgres.internal.TextDecoders
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

public data class ColumnMeta(
    public val name: String,
    public val tableObjectId: Int,
    public val columnNumber: Int,
    public val typeOid: Int,
    public val typeSize: Short,
    public val typeModifier: Int,
    public val format: Short,
)

public sealed class QueryResult {
    public abstract suspend fun close()

    public class Status internal constructor(
        public val command: String,
        public val rowsAffected: Long,
    ) : QueryResult() {
        override suspend fun close() = Unit
    }

    public class Rows internal constructor(
        public val columns: List<ColumnMeta>,
        internal val data: PgResultSet,
    ) : QueryResult() {
        public suspend fun next(): Boolean = data.next()

        public fun getRaw(index: Int): ByteArray? = data.getRaw(index)

        public fun isNull(index: Int): Boolean = data.isNull(index)

        public fun getString(index: Int): String? =
            data.getRaw(index)?.let { TextDecoders.decodeString(it) }

        public fun getInt(index: Int): Int? =
            data.getRaw(index)?.let { TextDecoders.decodeInt(it) }

        public fun getLong(index: Int): Long? =
            data.getRaw(index)?.let { TextDecoders.decodeLong(it) }

        public fun getShort(index: Int): Short? =
            data.getRaw(index)?.let { TextDecoders.decodeShort(it) }

        public fun getFloat(index: Int): Float? =
            data.getRaw(index)?.let { TextDecoders.decodeFloat(it) }

        public fun getDouble(index: Int): Double? =
            data.getRaw(index)?.let { TextDecoders.decodeDouble(it) }

        public fun getBoolean(index: Int): Boolean? =
            data.getRaw(index)?.let { TextDecoders.decodeBoolean(it) }

        public fun getBytes(index: Int): ByteArray? =
            data.getRaw(index)?.let { TextDecoders.decodeBytesFromHex(it) }

        @ExperimentalUuidApi
        public fun getUuid(index: Int): Uuid? =
            data.getRaw(index)?.let { TextDecoders.decodeUuid(it) }

        public fun findColumn(name: String): Int {
            for ((i, c) in columns.withIndex()) {
                if (c.name == name) return i
            }
            return -1
        }

        override suspend fun close() = data.close()
    }
}