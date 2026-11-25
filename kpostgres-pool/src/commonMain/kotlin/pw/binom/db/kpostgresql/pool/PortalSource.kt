package pw.binom.db.kpostgresql.pool

import pw.binom.db.ColumnType

/**
 * Источник порций строк.
 *
 * Узкий интерфейс, за которым может стоять что угодно: настоящий портал
 * Postgres ([PgPortalSource]) или фейк в тестах. Нужен потому, что
 * `PgStreamResultSet` — это логика «докачай порцию, когда кончилась», и
 * проверять её на живом сервере незачем: она от сервера не зависит.
 *
 * Всё, что требует сети, спрятано за [nextBatch]; всё остальное — чистая
 * логика буфера, и она тестируется без базы.
 */
internal interface PortalSource {
    /** Имена колонок. */
    val columns: List<String>

    /** Типы колонок по индексу. */
    val columnTypes: List<ColumnType>

    /**
     * Следующая порция строк.
     *
     * Пустой список — строк больше нет. Размер порции определяет источник.
     */
    suspend fun nextBatch(): List<Array<ByteArray?>>
}

/** Настоящий портал Postgres. */
internal class PgPortalSource(
    private val portal: pw.binom.db.kpostgresql.PgPortal,
) : PortalSource {
    override val columns: List<String> = portal.columns.map { it.name }
    override val columnTypes: List<ColumnType> =
        portal.columns.map { oidToColumnType(it.typeOid) }

    override suspend fun nextBatch(): List<Array<ByteArray?>> = portal.nextBatch()
}
