package pw.binom.db.kpostgresql.pool

import pw.binom.db.ColumnType
import pw.binom.db.Connection
import pw.binom.db.PreparedStatement
import pw.binom.db.ResultSet
import pw.binom.db.SqlValue
import pw.binom.db.kpostgresql.PostgresClient
import pw.binom.db.kpostgresql.QueryResult
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Подготовленный запрос поверх `kpostgres`.
 *
 * SQL уже переведён в позиционный вид ([ParamConverter]), `names` — порядок
 * имён из исходного `:name`-SQL. При выполнении значения собираются в этот
 * порядок и подаются как `vararg`.
 */
internal class PgPreparedStatement(
    override val connection: Connection,
    private val client: PostgresClient,
    private val positionalSql: String,
    private val names: List<String>,
) : PreparedStatement {

    private val bound = mutableMapOf<Int, SqlValue>()

    override suspend fun reset() {
        bound.clear()
    }

    override suspend fun bind(index: Int, value: SqlValue) {
        bound[index] = value
    }

    /**
     * Выполняет запрос и возвращает **готовый** результат.
     *
     * Выполнение происходит здесь, в `suspend`, а не в `ResultSet.next()`:
     * `next()` синхронный по контракту, а сеть ждать умеет только `suspend`.
     * Так на каждой строке нет ложного `suspend`, и курсор остаётся простым.
     */
    override suspend fun executeQuery(): ResultSet {
        val params = collectParams()
        val raw = try {
            client.executeWithParams(positionalSql, params)
        } catch (e: Throwable) {
            throw e.toSqlException()
        }
        val rows = raw as? QueryResult.Rows
            ?: run {
                raw.close()
                // Запрос без строк (например, INSERT без RETURNING) — пустой
                // результат, а не ошибка: ORM может вызывать query() наугад.
                return EmptyResultSet
            }
        return PgResultSet(rows)
    }

    override suspend fun executeUpdate(): Long {
        val params = collectParams()
        return try {
            val r = client.executeWithParams(positionalSql, params)
            val n = (r as? QueryResult.Status)?.rowsAffected ?: 0L
            r.close()
            n
        } catch (e: Throwable) {
            throw e.toSqlException()
        }
    }

    /**
     * Значения в порядке позиций.
     *
     * Позиция `i` соответствует `names[i]`. Берём привязку по индексу имени,
     * поэтому повторяющееся имя автоматически получает одно значение на все
     * свои `$N` — Postgres ждёт ровно `names.size` параметров.
     */
    private fun collectParams(): List<Any?> = names.indices.map { pos ->
        (bound[pos] ?: SqlValue.Null).toPgParam()
    }

    override fun close() = Unit
}

/**
 * Курсор по готовому результату `kpostgres`.
 *
 * Ничего не выполняет: строки уже получены в [PgPreparedStatement.executeQuery].
 */
internal class PgResultSet(
    private val rows: QueryResult.Rows,
) : ResultSet {

    override val columns: List<String> = rows.columns.map { it.name }

    override fun columnType(index: Int): ColumnType {
        val meta = rows.columns.getOrNull(index) ?: return ColumnType.OTHER
        return oidToColumnType(meta.typeOid)
    }

    override fun next(): Boolean = rows.next()

    override fun isNull(index: Int): Boolean = rows.isNull(index)

    override fun getString(index: Int): String? = rows.getString(index)
    override fun getBoolean(index: Int): Boolean? = rows.getBoolean(index)
    override fun getByte(index: Int): Byte? = rows.getShort(index)?.toByte()
    override fun getShort(index: Int): Short? = rows.getShort(index)
    override fun getInt(index: Int): Int? = rows.getInt(index)
    override fun getLong(index: Int): Long? = rows.getLong(index)
    override fun getDouble(index: Int): Double? = rows.getDouble(index)
    override fun getBytes(index: Int): ByteArray? = rows.getBytes(index)

    /**
     * Момент времени.
     *
     * `kpostgres` не имеет декодера для timestamp, отдаёт текст. Парсим
     * стандартный формат Postgres; `timestamptz` приходит с offset, `timestamp`
     * без. `Instant.parse` требует `Z`, поэтому формат нормализуется.
     */
    override fun getInstant(index: Int): Instant? {
        val raw = rows.getString(index) ?: return null
        return parsePgTimestamp(raw)
    }

    @OptIn(ExperimentalUuidApi::class)
    override fun getUuid(index: Int): Uuid? = rows.getUuid(index)

    override fun close() = rows.close()
}

/** Пустой результат: запрос без строк. */
internal object EmptyResultSet : ResultSet {
    override val columns: List<String> = emptyList()
    override fun columnType(index: Int): ColumnType = ColumnType.OTHER
    override fun next(): Boolean = false
    override fun isNull(index: Int): Boolean = true
    override fun getString(index: Int): String? = null
    override fun getBoolean(index: Int): Boolean? = null
    override fun getByte(index: Int): Byte? = null
    override fun getShort(index: Int): Short? = null
    override fun getInt(index: Int): Int? = null
    override fun getLong(index: Int): Long? = null
    override fun getDouble(index: Int): Double? = null
    override fun getBytes(index: Int): ByteArray? = null
    override fun getInstant(index: Int): Instant? = null
    @OptIn(ExperimentalUuidApi::class)
    override fun getUuid(index: Int): Uuid? = null
    override fun close() = Unit
}

/**
 * Разбор timestamp Postgres.
 *
 * Форматы, которые отдаёт сервер:
 * * `2026-11-25 01:47:00` — timestamp без зоны;
 * * `2026-11-25 01:47:00.123` — с микросекундами;
 * * `2026-11-25 01:47:00+03` — timestamptz со смещением.
 *
 * `Instant.parse` принимает только ISO-8601 с `Z`, поэтому приводим:
 * пробел → `T`, обрезаем микросекунды до миллисекунд, отсутствующую зону
 * считаем UTC (для `timestamp` это единственное осмысленное допущение —
 * Postgres не хранит зону).
 */
internal fun parsePgTimestamp(raw: String): Instant? {
    var s = raw.trim().replace(' ', 'T')
    if (s.isEmpty()) return null

    // Смещение вида +03 или +0300 → +03:00
    val signIdx = s.indexOfLast { it == '+' || it == '-' }
    if (signIdx > 10) {
        val off = s.substring(signIdx)
        s = s.substring(0, signIdx)
        val digits = off.drop(1).replace(":", "")
        val hh = digits.take(2)
        val mm = digits.drop(2).take(2).ifEmpty { "00" }
        s = "$s${off[0]}$hh:$mm"
    }

    // Микросекунды → миллисекунды: 6 знаков после точки режем до 3.
    val dot = s.indexOf('.')
    if (dot > 0) {
        var endOfFrac = dot + 1
        while (endOfFrac < s.length && s[endOfFrac].isDigit()) endOfFrac++
        val frac = s.substring(dot + 1, endOfFrac)
        if (frac.length > 3) {
            s = s.substring(0, dot + 1) + frac.take(3) + s.substring(endOfFrac)
        }
    }

    if (!s.endsWith("Z") && s.length <= 19) s = "${s}Z"

    return runCatching { Instant.parse(s) }.getOrNull()
}

/** OID типа Postgres → [ColumnType]. Неизвестные — `OTHER`. */
internal fun oidToColumnType(oid: Int): ColumnType = when (oid) {
    16 -> ColumnType.BIT            // bool
    21 -> ColumnType.SMALLINT       // int2
    23 -> ColumnType.INTEGER        // int4
    20 -> ColumnType.BIGINT         // int8
    700 -> ColumnType.FLOAT         // float4
    701 -> ColumnType.DOUBLE        // float8
    1700 -> ColumnType.NUMERIC      // numeric
    1042 -> ColumnType.CHAR         // bpchar
    1043 -> ColumnType.VARCHAR      // varchar
    25 -> ColumnType.LONGVARCHAR    // text
    17 -> ColumnType.BINARY         // bytea
    1082 -> ColumnType.DATE         // date
    1083 -> ColumnType.TIME         // time
    1114 -> ColumnType.TIMESTAMP    // timestamp
    1184 -> ColumnType.TIMESTAMP    // timestamptz
    2950 -> ColumnType.UUID         // uuid
    else -> ColumnType.OTHER
}
