package pw.binom.db.kpostgresql

import pw.binom.db.kpostgresql.internal.PgStreamHandle

/**
 * Открытый портал потокового чтения.
 *
 * ## Что это
 *
 * Живой серверный курсор: портал Postgres, из которого строки берутся
 * порциями. В памяти живёт одна порция, а не весь результат.
 *
 * ## Требования
 *
 * Соединение должно быть в транзакции. Портал привязан к транзакции и
 * умирает вместе с ней — поэтому [close] вызывать обязательно, а транзакцию
 * завершать после него.
 *
 * ## Почему не `AutoCloseable`
 *
 * Закрытие портала — сетевой обмен (`Close` + `Sync`). `AutoCloseable.close`
 * синхронный, а в native заблокировать поток нельзя. Поэтому [close]
 * объявлен `suspend`, идиома `use` заменена на [usePortal].
 *
 * ## Пример
 *
 * ```kotlin
 * client.transaction {
 *     executeStream("SELECT * FROM huge_table", fetchSize = 500).usePortal { portal ->
 *         while (true) {
 *             val batch = portal.nextBatch()
 *             if (batch.isEmpty()) break
 *             for (row in batch) { /* обработка */ }
 *         }
 *     }
 * }
 * ```
 */
public class PgPortal internal constructor(
    private val handle: PgStreamHandle,
    /** Параметры запроса — для отладки и повторного Bind при нужде. */
    public val params: List<Any?>,
) {
    /** Имена и типы колонок. Известны до первой порции. */
    public val columns: List<ColumnMeta> get() = handle.columns

    /** Исчерпан ли портал. */
    public val isDone: Boolean get() = handle.isDone()

    /**
     * Следующая порция строк.
     *
     * Пустой список — строк больше нет. Размер порции может быть меньше
     * запрошенного: сервер не обязан набивать её до предела.
     */
    public suspend fun nextBatch(): List<Array<ByteArray?>> = handle.nextBatch()

    /** Освобождает портал на сервере. Идемпотентно. */
    public suspend fun close() = handle.close()
}

/** Гарантирует освобождение портала, даже если блок бросил исключение. */
public suspend inline fun <R> PgPortal.usePortal(block: (PgPortal) -> R): R {
    try {
        return block(this)
    } finally {
        runCatching { close() }
    }
}
