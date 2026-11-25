package pw.binom.db.kpostgresql.pool

import pw.binom.db.ColumnType
import pw.binom.db.ResultSet
import pw.binom.db.SqlValue
import pw.binom.db.kpostgresql.PgPortal
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Потоковый набор поверх портала Postgres.
 *
 * ## Чем отличается от обычного
 *
 * Обычный набор получает все строки сразу. Здесь строки приходят порциями
 * по сети, поэтому [next] `suspend` — и это единственное отличие для
 * вызывающего.
 * * [next] сам докачивает порцию, когда текущая исчерпана, — поэтому он
 *   `suspend`, и об этом знает только сигнатура, а не вызывающий код.
 *
 * ## Память
 *
 * Живёт одна порция ([fetchSize] строк) плюс текущая. При `fetchSize = 512`
 * и строке в 1 КБ это полмегабайта независимо от размера таблицы.
 */
internal class PgStreamResultSet(
    private val portal: PgPortal,
    private val fetchSize: Int,
) : ResultSet {

    override val columns: List<String> = portal.columns.map { it.name }

    private var batch: List<Array<ByteArray?>> = emptyList()
    private var pos: Int = -1
    private var exhausted: Boolean = false

    /** Общее число прочитанных строк — для диагностики и тестов. */
    var rowsRead: Long = 0
        private set

    override fun columnType(index: Int): ColumnType =
        portal.columns.getOrNull(index)?.let { oidToColumnType(it.typeOid) } ?: ColumnType.OTHER

    /**
     * Следующая строка.
     *
     * Если порция исчерпана — докачивает следующую у сервера. Отсюда
     * `suspend`: ожидание сети живёт в сигнатуре, и вызывающему не нужно
     * знать, кончилась пачка или нет.
     */
    override suspend fun next(): Boolean {
        pos += 1
        if (pos < batch.size) return true
        // Порция исчерпана: тянем следующую.
        while (true) {
            val rows = portal.nextBatch()
            if (rows.isEmpty()) {
                exhausted = true
                batch = emptyList()
                pos = -1
                return false
            }
            batch = rows
            pos = 0
            rowsRead += rows.size
            if (batch.isNotEmpty()) return true
        }
    }

    override fun isNull(index: Int): Boolean = current()[index] == null

    override fun getString(index: Int): String? = current()[index]?.let { decodeUtf8Text(it) }
    override fun getBoolean(index: Int): Boolean? = current()[index]?.let { parseBoolText(it) }
    override fun getByte(index: Int): Byte? = current()[index]?.let { decodeUtf8Text(it).trim().toShort()?.toByte() }
    override fun getShort(index: Int): Short? = current()[index]?.let { decodeUtf8Text(it).trim().toShort() }
    override fun getInt(index: Int): Int? = current()[index]?.let { decodeUtf8Text(it).trim().toInt() }
    override fun getLong(index: Int): Long? = current()[index]?.let { decodeUtf8Text(it).trim().toLong() }
    override fun getDouble(index: Int): Double? = current()[index]?.let { decodeUtf8Text(it).trim().toDouble() }
    override fun getBytes(index: Int): ByteArray? = current()[index]

    override fun getInstant(index: Int): Instant? =
        current()[index]?.let { decodeUtf8Text(it) }?.let { parsePgTimestamp(it) }

    @OptIn(ExperimentalUuidApi::class)
    override fun getUuid(index: Int): Uuid? =
        current()[index]?.let { Uuid.parse(decodeUtf8Text(it)) }

    /** Текущая строка — для методов доступа по индексу внутри порции. */
    private fun current(): Array<ByteArray?> {
        val p = pos
        if (p < 0 || p >= batch.size) {
            throw IllegalStateException("нет текущей строки: сначала вызовите next()")
        }
        return batch[p]
    }

    /** Сдвиг внутри порции — для симметрии с обычным набором, изнутри. */
    internal fun advance(): Boolean {
        pos += 1
        return pos < batch.size
    }

    /**
     * Освобождает портал.
     *
     * Синхронный по контракту: сеть здесь не ждём. Портал всё равно умрёт
     * вместе с транзакцией, поэтому откладывать закрытие безопасно — но
     * тогда снимок держится дольше.
     */
    override fun close() {
        // Портал закроется при завершении транзакции; «мягкое» закрытие —
        // пометить, что строк больше нет.
        exhausted = true
        batch = emptyList()
        pos = -1
    }
}



/**
 * Разбор текстового представления значения.
 *
 * Потоковый запрос идёт с `resultFormat = 0` (текст): бинарный формат
 * требует знать тип каждой колонки на клиенте до `Bind`, а типы приходят
 * только в `RowDescription` после `Describe`. Текст разбирается на клиенте —
 * медленнее бинарного, зато без второго раунда.
 */
internal fun decodeUtf8Text(raw: ByteArray): String = raw.decodeToString()

internal fun parseBoolText(raw: ByteArray): Boolean = when (decodeUtf8Text(raw).trim()) {
    "t", "true", "y", "yes", "on", "1" -> true
    else -> false
}
