package com.subochev.kpostgres

import com.subochev.kpostgres.internal.Oid
import com.subochev.kpostgres.internal.PgConnection
import com.subochev.kpostgres.internal.executeExtendedQuery
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

public class PreparedStatement internal constructor(
    private val connection: PgConnection,
    private val sql: String,
) : AutoCloseable {

    @OptIn(ExperimentalUuidApi::class)
    public suspend fun executeQuery(vararg params: Any?): QueryResult.Rows {
        val res = executeExtendedQueryWithParams(params.toList())
        if (res !is QueryResult.Rows) {
            res.close()
            throw PostgresException("Prepared statement did not return a row set")
        }
        return res
    }

    public suspend fun execute(vararg params: Any?): Long {
        val res = executeExtendedQueryWithParams(params.toList())
        try {
            return when (res) {
                is QueryResult.Status -> res.rowsAffected
                is QueryResult.Rows -> 0L
            }
        } finally {
            res.close()
        }
    }

    private suspend fun executeExtendedQueryWithParams(params: List<Any?>): QueryResult {
        val types = params.map { pgTypeOf(it) }
        return connection.executeExtendedQuery(sql, types, params)
    }

    override fun close() = Unit

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
}
