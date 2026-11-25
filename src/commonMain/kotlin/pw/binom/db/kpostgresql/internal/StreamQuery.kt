package pw.binom.db.kpostgresql.internal

import kotlinx.coroutines.sync.withLock
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.writeString
import pw.binom.db.kpostgresql.ColumnMeta
import pw.binom.db.kpostgresql.PostgresException
import pw.binom.db.kpostgresql.QueryResult

/**
 * Потоковая выдача результата через портал сервера.
 *
 * ## Как это работает в протоколе
 *
 * Postgres позволяет ограничить порцию прямо в `Execute`: поле `max_rows`.
 * Сервер отдаёт не больше N строк и завершает ответ сообщением
 * `PortalSuspended` вместо `CommandComplete`. Портал остаётся живым, и
 * следующий `Execute` с тем же именем продолжает с того же места.
 *
 * Важное следствие: **соединение обязано быть в транзакции**. Вне
 * транзакции `Sync` уничтожает портал, и сервер молча отдаёт всё разом —
 * именно то, от чего уходим. Поэтому транзакцию начинает вызывающий
 * (`queryStream` в адаптере это делает), а здесь только проверяется.
 *
 * ## Почему портал, а не `DECLARE CURSOR`
 *
 * `DECLARE` требует явного SQL и имени, а портал живёт на уровне протокола:
 * никаких `DECLARE`/`FETCH`/`CLOSE` в тексте запроса, никаких коллизий имён.
 * Параметры при этом работают как обычно — через `Bind`.
 */
internal class PgStreamHandle(
    private val conn: PgConnection,
    private val portalName: String,
    private val maxRows: Int,
    /** Колонки, полученные из `RowDescription` на первом шаге. */
    val columns: List<ColumnMeta>,
) {
    private var done = false
    private var closed = false

    /** Есть ли ещё строки. `false` — портал исчерпан. */
    fun isDone(): Boolean = done

    /**
     * Запрашивает следующую порцию.
     *
     * Возвращает готовые строки в том же виде, что и обычный путь:
     * массив сырых байтов на колонку. `null` в элементе — SQL `NULL`,
     * пустой массив — пустая строка или пустой `bytea`.
     *
     * Размер порции может оказаться меньше запрошенного: сервер не обязан
     * набивать её до предела, а на последней порции всегда меньше.
     */
    suspend fun nextBatch(): List<Array<ByteArray?>> {
        if (done || closed) return emptyList()

        return conn.sendLock.withLock {
            conn.checkOpen()
            // Execute с ограничением: сервер отдаст не больше maxRows.
            conn.sendExecute(portalName, maxRows)
            // Flush, а не Sync: Sync закрывает пакет и уничтожает портал,
            // после чего сервер молча ждёт — именно это давало зависание на
            // второй порции. Flush только выталкивает вывод.
            conn.sendFlush()
            readBatch()
        }
    }

    /**
     * Читает порцию до `PortalSuspended` или `CommandComplete`.
     *
     * `PortalSuspended` — строки кончились, портал ждёт следующего `Execute`.
     * `CommandComplete` — данных больше нет, портал закрыт сервером.
     */
    private suspend fun readBatch(): List<Array<ByteArray?>> {
        val batch = ArrayList<Array<ByteArray?>>(maxRows)
        while (true) {
            val frame = conn.receiveFrame()
            when (frame.tag) {
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
                    batch += row
                }
                MessageTag.PORTAL_SUSPENDED -> {
                    frame.end()
                    break
                }
                MessageTag.COMMAND_COMPLETE -> {
                    frame.end()
                    done = true
                    break
                }
                MessageTag.EMPTY_QUERY_RESPONSE -> {
                    frame.end()
                    done = true
                }
                MessageTag.ERROR_RESPONSE -> {
                    val err = readErrorResponse(frame)
                    frame.end()
                    // Ошибка посреди потока: дочитываем до ReadyForQuery,
                    // иначе следующая порция прочитает хвост этой.
                    conn.consumeToReadyForQuery()
                    throw PostgresException("Query error: ${err.message}", sqlState = err.sqlState)
                }
                MessageTag.READY_FOR_QUERY -> {
                    // ReadyForQuery несёт статус транзакции — без него
                    // соединение не узнает, что транзакция всё ещё открыта.
                    val status = frame.readByte()
                    frame.end()
                    conn.updateTxStatus(status)
                    // Портал исчерпан: сервер прислал ReadyForQuery вместо
                    // PortalSuspended.
                    done = true
                    break
                }
                else -> frame.end()
            }
        }
        return batch
    }

    /** Закрывает портал на сервере. Идемпотентно. */
    suspend fun close() {
        if (closed) return
        closed = true
        runCatching {
            conn.sendLock.withLock {
                conn.checkOpen()
                conn.sendClosePortal(portalName)
                conn.sendSync()
                conn.consumeToReadyForQuery()
            }
        }
    }
}

/**
 * Начинает потоковый запрос.
 *
 * Возвращает дескриптор с колонками; строки берутся через
 * [PgStreamHandle.nextBatch]. Первая порция **не** читается здесь: вызывающий
 * сам решает, нужна ли она.
 *
 * `Bind` идёт с `resultFormat = 0` (текстовый): бинарный формат требует
 * знания типа каждой колонки на клиенте, а текст разбирается существующими
 * декодерами.
 */
internal suspend fun PgConnection.startStream(
    sql: String,
    paramTypes: List<Int>,
    params: List<Any?>,
    fetchSize: Int,
): PgStreamHandle {
    checkOpen()
    markBusy()
    return sendLock.withLock {
        sendParse("", sql, paramTypes)
        sendBind("", "", params, paramFormat = 0, resultFormat = 0)
        sendDescribe("", 'P')
        // Execute не шлём: Describe вернёт RowDescription, а строки пойдут
        // порциями по требованию.
        sendSync()
        readDescriptionOnly()
    }.let { cols ->
        PgStreamHandle(this, "", fetchSize, cols)
    }
}

/**
 * Читает ответ до `ReadyForQuery`, забирая только `RowDescription`.
 *
 * Нужен после `Describe`: сервер в ответ на `Describe` + `Sync` отдаёт
 * описание колонок и `ReadyForQuery`, не выполняя запрос.
 */
private suspend fun PgConnection.readDescriptionOnly(): List<ColumnMeta> {
    var columns: List<ColumnMeta>? = null
    while (true) {
        val frame = receiveFrame()
        when (frame.tag) {
            MessageTag.ROW_DESCRIPTION -> columns = readRowDescription(frame)
            MessageTag.NO_DATA -> frame.end()
            MessageTag.PARSE_COMPLETE,
            MessageTag.BIND_COMPLETE -> frame.end()
            MessageTag.ERROR_RESPONSE -> {
                val err = readErrorResponse(frame)
                frame.end()
                consumeToReadyForQuery()
                throw PostgresException("Query error: ${err.message}", sqlState = err.sqlState)
            }
            MessageTag.READY_FOR_QUERY -> {
                val status = frame.readByte()
                frame.end()
                updateTxStatus(status)
                return columns ?: emptyList()
            }
            MessageTag.EMPTY_QUERY_RESPONSE -> {
                frame.end()
                return emptyList()
            }
            else -> frame.end()
        }
    }
}
