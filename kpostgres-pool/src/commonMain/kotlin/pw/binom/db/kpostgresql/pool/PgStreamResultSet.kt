package pw.binom.db.kpostgresql.pool

import pw.binom.db.ColumnType
import pw.binom.db.ResultSet
import pw.binom.db.Row
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
 * Обычный [pgResultSet] получает все строки сразу и потому имеет синхронный
 * [next]. Здесь строки приходят порциями по сети, поэтому:
 *
 * * [next] **не работает** — бросает `UnsupportedOperationException`:
 *   синхронный метод не может ждать сеть, а притворяться, что может, значит
 *   вешать поток;
 * * строки берутся через `fetchRows` из `kdatabase` — он `suspend`;
 * * [isStreaming] = `true`, чтобы вызывающий выбрал правильный путь.
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

    override val isStreaming: Boolean get() = true

    private var batch: List<Array<ByteArray?>> = emptyList()
    private var pos: Int = -1
    private var exhausted: Boolean = false

    /** Общее число прочитанных строк — для диагностики и тестов. */
    var rowsRead: Long = 0
        private set

    override fun columnType(index: Int): ColumnType =
        portal.columns.getOrNull(index)?.let { oidToColumnType(it.typeOid) } ?: ColumnType.OTHER

    /**
     * Не поддерживается: синхронный метод не может дождаться сети.
     *
     * Если нужен построчный обход потокового набора — использовать
     * `fetchRows`/`rowSequence` из `kdatabase`.
     */
    override fun next(): Boolean = throw UnsupportedOperationException(
        "потоковый набор читается через fetchRows, а не next()",
    )

    /**
     * Следующая порция строк.
     *
     * Переопределяет `ResultSet.fetchRows`: у потокового набора порция
     * приходит из портала, а не из памяти.
     */
    override suspend fun fetchRows(size: Int): List<Row> {
        if (exhausted) return emptyList()

        val rows = portal.nextBatch()
        if (rows.isEmpty()) {
            exhausted = true
            return emptyList()
        }

        batch = rows
        pos = -1
        rowsRead += rows.size

        // Последняя порция: сервер отдал меньше запрошенного — больше не будет.
        if (rows.size < fetchSize) exhausted = true

        return rows.map { ConcreteRow(columns, it) }
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
            throw IllegalStateException("нет текущей строки: потоковый набор читается через fetchRows")
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
 * Строка, отделённая от курсора.
 *
 * Нужна потому, что потоковый курсор качает следующую порцию, пока
 * потребитель обрабатывает текущую строку: держать строку «внутри» курсора
 * нельзя — данные перезапишутся.
 */
internal class ConcreteRow(
    override val columns: List<String>,
    private val values: Array<ByteArray?>,
) : Row {

    override fun isNull(index: Int): Boolean = values[index] == null

    override fun getString(index: Int): String? = values[index]?.let { decodeUtf8Text(it) }
    override fun getBoolean(index: Int): Boolean? = values[index]?.let { parseBoolText(it) }
    override fun getInt(index: Int): Int? = values[index]?.let { decodeUtf8Text(it).trim().toInt() }
    override fun getLong(index: Int): Long? = values[index]?.let { decodeUtf8Text(it).trim().toLong() }
    override fun getDouble(index: Int): Double? = values[index]?.let { decodeUtf8Text(it).trim().toDouble() }
    override fun getBytes(index: Int): ByteArray? = values[index]

    override fun getInstant(index: Int): Instant? =
        values[index]?.let { decodeUtf8Text(it) }?.let { parsePgTimestamp(it) }

    @OptIn(ExperimentalUuidApi::class)
    override fun getUuid(index: Int): Uuid? =
        values[index]?.let { Uuid.parse(decodeUtf8Text(it)) }
}

/**
 * Разбор текстового представления значения.
 *
 * Потоковый запрос идёт с `resultFormat = 0` (текст): бинарный формат
 * требует знать тип каждой колонки на клиенте до `Bind`, а типы приходят
 * только в `RowDescription` после `Describe`. Текст разбирается здесь, на
 * клиенте, — это медленнее бинарного, зато не требует второго раунда.
 */
internal fun decodeUtf8Text(raw: ByteArray): String = raw.decodeToString()

internal fun parseBoolText(raw: ByteArray): Boolean = when (decodeUtf8Text(raw).trim()) {
    "t", "true", "y", "yes", "on", "1" -> true
    else -> false
}
