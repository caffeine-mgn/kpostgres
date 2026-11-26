package pw.binom.db.kpostgresql.pool

import pw.binom.db.ColumnType
import pw.binom.db.Connection
import pw.binom.db.ConnectionClosedException
import pw.binom.db.ConnectionLostException
import pw.binom.db.ConstraintViolationException
import pw.binom.db.DatabaseEngine
import pw.binom.db.DatabaseInfo
import pw.binom.db.PreparedStatement
import pw.binom.db.ResultSet
import pw.binom.db.SqlErrorException
import pw.binom.db.SqlException
import pw.binom.db.SqlValue
import pw.binom.db.TransactionMode
import pw.binom.db.kpostgresql.PostgresClient
import pw.binom.db.kpostgresql.PostgresConnectionClosedException
import pw.binom.db.kpostgresql.PostgresException
import pw.binom.db.kpostgresql.PostgresProtocolException
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Адаптер `kpostgres` к контракту [Connection] из `kdatabase`.
 *
 * ## Зачем адаптер, а не правка драйвера
 *
 * `kpostgres` обязан работать сам по себе: его можно взять без пула и без
 * ORM. Здесь только перевод одного API в другой — драйвер про `kdatabase`
 * не знает и знать не должен.
 *
 * ## Про `$1` и `:name`
 *
 * `kpostgres` понимает **позиционные** параметры (`$1`, `$2`). ORM генерирует
 * именованные (`:name`) — так удобнее собирать SQL по сериализатору.
 * Перевод делает [ParamConverter]: он превращает `:name` в `$N` и возвращает
 * порядок имён, в котором надо подать значения.
 *
 * Повторяющееся имя (`SET a=:x, b=:x`) даёт `$1` дважды и **одно** значение
 * в списке позиций. Это главная ловушка таких конвертеров: если положить
 * значение дважды, Postgres получит лишний параметр и запрос упадёт с
 * «bind message supplies 3 parameters, but prepared statement requires 2».
 */
class PgConnectionAdapter(
    private val client: PostgresClient,
) : Connection {

    override val isClosed: Boolean get() = client.isClosed

    override val dbInfo: DatabaseInfo = PgDatabaseInfo

    /**
     * Готово ли соединение принять запрос.
     *
     * `kpostgres` отдаёт статус транзакции: `'I'` — вне транзакции, `'T'` — в
     * транзакции, `'E'` — в проваленной. Пул возвращает в набор только при
     * `'I'`: иначе следующий клиент унаследует чужую незакрытую транзакцию.
     */
    override val isReadyForQuery: Boolean
        get() = !client.isClosed && client.transactionStatus == 'I' && !client.isBusy

    override val transactionMode: TransactionMode get() = currentMode

    private var currentMode: TransactionMode = TransactionMode.READ_COMMITTED

    /**
     * Открыта ли транзакция сейчас.
     *
     * ## Зачем нужен этот флаг
     *
     * Контракт `kdatabase` объявляет [isInTransaction], но базовое значение —
     * `false`. Без переопределения PG-соединение всегда отвечало «нет», и
     * верхние слои (`korm`, сторы) не могли отличить «начать транзакцию» от
     * «присоединиться к уже открытой». Цена ошибки — не лишний BEGIN, а
     * потерянная атомарность: метод, вызванный внутри чужой транзакции,
     * открывал собственную на другом соединении и коммитил половину работы
     * независимо от исхода внешней.
     *
     * Именно так в провижинере аренда CID оставалась в БД после отката
     * создания машины.
     */
    private var inTransaction = false

    override val isInTransaction: Boolean get() = inTransaction

    /**
     * Потоковый запрос через портал.
     *
     * ## Почему транзакция начинается здесь
     *
     * Портал живёт только внутри транзакции: вне неё `Sync` его уничтожает,
     * и сервер молча отдаёт всё разом — ровно та болезнь, от которой уходим.
     * Требовать от вызывающего «не забудь BEGIN» значит гарантированно
     * получить её при первом же забывании, причём без ошибки: данные придут
     * верные, просто все сразу.
     *
     * Транзакция остаётся открытой до [ResultSet.close]: закрытие портала
     * коммитит её. Читателю это безразлично (он только читает), а снимок
     * снимает ровно на время чтения.
     */
    override suspend fun queryStream(sql: String, fetchSize: Int): ResultSet {
        require(fetchSize > 0) { "fetchSize должен быть > 0" }
        val ownTransaction = client.transactionStatus == 'I'
        if (ownTransaction) exec("BEGIN")
        val portal = client.executeStream(sql, emptyList(), fetchSize)
        return PgStreamResultSet(PgPortalSource(portal), fetchSize)
    }

    override suspend fun setTransactionMode(mode: TransactionMode) {
        currentMode = mode
        // Уровень применится при beginTransaction: `SET TRANSACTION ISOLATION
        // LEVEL` вне BEGIN в Postgres бесполезен и молча игнорируется.
    }

    override suspend fun beginTransaction() {
        // Вложенный BEGIN в Postgres — это WARNING "there is already a
        // transaction in progress", а не ошибка: сервер молча игнорирует его,
        // и последующий COMMIT закрывает ВНЕШНЮЮ транзакцию. Поэтому
        // вложенный вызов — не «начать заново», а «я уже внутри».
        if (inTransaction) return
        exec("BEGIN ISOLATION LEVEL ${currentMode.sqlName()}")
        inTransaction = true
    }

    override suspend fun commit() {
        // Симметрично: COMMIT без открытой транзакции Postgres выполнит, но
        // это скрывает логическую ошибку вызывающего, а не исправляет её.
        if (!inTransaction) return
        exec("COMMIT")
        inTransaction = false
    }

    override suspend fun rollback() {
        if (!inTransaction) return
        // Флаг снимается до exec: если ROLLBACK упал из-за обрыва связи,
        // соединение всё равно уже не в транзакции (сервер её закрыл), и
        // оставлять флаг взведённым значит врать о состоянии.
        inTransaction = false
        exec("ROLLBACK")
    }

    override suspend fun prepare(sql: String): PreparedStatement {
        checkOpen()
        val converted = ParamConverter.convert(sql)
        return PgPreparedStatement(this, client, converted.sql, converted.names)
    }

    override suspend fun executeUpdate(sql: String): Long = exec(sql)

    /**
     * Запрос без параметров.
     *
     * Выполняется здесь, в `suspend`: `ResultSet.next()` синхронный, поэтому
     * к моменту возврата строки уже получены.
     */
    override suspend fun query(sql: String): ResultSet {
        checkOpen()
        val converted = ParamConverter.convert(sql)
        return PgPreparedStatement(this, client, converted.sql, converted.names).executeQuery()
    }

    override fun close() {
        if (!client.isClosed) client.close()
    }

    private suspend fun exec(sql: String): Long = try {
        client.execute(sql)
    } catch (e: Throwable) {
        throw e.toSqlException()
    }

    private fun checkOpen() {
        if (client.isClosed) throw ConnectionClosedException()
    }

    private fun TransactionMode.sqlName(): String = when (this) {
        TransactionMode.SERIALIZABLE -> "SERIALIZABLE"
        TransactionMode.REPEATABLE_READ -> "REPEATABLE READ"
        TransactionMode.READ_COMMITTED -> "READ COMMITTED"
        TransactionMode.READ_UNCOMMITTED -> "READ UNCOMMITTED"
    }
}

/** Сведения о Postgres для ORM: кавычки двойные, движок известен. */
object PgDatabaseInfo : DatabaseInfo {
    override val engine: DatabaseEngine get() = DatabaseEngine.POSTGRESQL
    override val tableNameQuotesStart: String get() = "\""
    override val tableNameQuotesEnd: String get() = "\""
}

/**
 * Результат перевода именованных параметров.
 *
 * `names` — имена в порядке появления в SQL **без повторов**. По этому
 * списку ORM подаёт значения, и ровно столько параметров ждёт Postgres.
 */
internal class ConvertedSql(val sql: String, val names: List<String>)

/**
 * Перевод `:name` → `$N`.
 *
 * ## Правила
 *
 * * имя — буквы, цифры, подчёркивание; начинается с буквы или подчёркивания;
 * * `:name` внутри строковых литералов и комментариев не трогаем;
 * * `::` (приведение типов Postgres) не параметр;
 * * повторное имя получает тот же номер и **не** добавляется в `names`;
 * * готовый `$1` в исходном SQL остаётся как есть.
 *
 * ## Почему отдельный объект
 *
 * Конвертер — место, где легко ошибиться, и его надо тестировать без БД.
 * Он ничего не знает про соединение и не делает никакого ввода-вывода.
 */
internal object ParamConverter {

    fun convert(sql: String): ConvertedSql {
        val out = StringBuilder(sql.length + 16)
        val names = mutableListOf<String>()
        val indices = mutableMapOf<String, Int>()

        var i = 0
        while (i < sql.length) {
            val c = sql[i]

            // Строковый литерал: внутри параметров нет. Удвоенная кавычка
            // ('') — экранирование, а не конец литерала.
            if (c == '\'') {
                out.append(c)
                i++
                while (i < sql.length) {
                    out.append(sql[i])
                    if (sql[i] == '\'') {
                        if (i + 1 < sql.length && sql[i + 1] == '\'') {
                            out.append(sql[i + 1])
                            i += 2
                            continue
                        }
                        i++
                        break
                    }
                    i++
                }
                continue
            }

            // Комментарий `--` до конца строки.
            if (c == '-' && i + 1 < sql.length && sql[i + 1] == '-') {
                val end = sql.indexOf('\n', i).let { if (it < 0) sql.length else it }
                out.append(sql, i, end)
                i = end
                continue
            }

            // Приведение типов `::` — не параметр.
            if (c == ':' && i + 1 < sql.length && sql[i + 1] == ':') {
                out.append("::")
                i += 2
                continue
            }

            if (c == ':') {
                val start = i + 1
                var end = start
                while (end < sql.length && (sql[end].isLetterOrDigit() || sql[end] == '_')) end++

                if (end > start && (sql[start].isLetter() || sql[start] == '_')) {
                    val name = sql.substring(start, end)
                    val idx = indices.getOrPut(name) {
                        names.add(name)
                        names.size // $1 — первый
                    }
                    out.append('$').append(idx)
                    i = end
                    continue
                }

                // Двоеточие без валидного имени — оставляем как есть.
                out.append(c)
                i++
                continue
            }

            out.append(c)
            i++
        }

        return ConvertedSql(out.toString(), names)
    }
}

/** Ошибки `kpostgres` → ошибки `kdatabase`, от которых зависит пул. */
internal fun Throwable.toSqlException(): SqlException = when (this) {
    is SqlException -> this
    is PostgresConnectionClosedException -> ConnectionLostException(message, this)
    is PostgresProtocolException -> ConnectionLostException(message, this)
    is PostgresException -> {
        // SQLSTATE: класс 23 — нарушение ограничений (unique, fk, not null,
        // check); 08 — сбой соединения; 57P01/57P02 — сервер гасит сессию.
        val state = sqlState
        when {
            state != null && state.startsWith("23") -> ConstraintViolationException(message, this)
            state != null && (state.startsWith("08") || state == "57P01" || state == "57P02") ->
                ConnectionLostException(message, this)
            else -> SqlErrorException(message, this)
        }
    }
    else -> SqlErrorException(message ?: this::class.simpleName, this)
}

/** Значение `kdatabase` → значение, которое понимает `kpostgres`. */
@OptIn(ExperimentalUuidApi::class)
internal fun SqlValue.toPgParam(): Any? = when (this) {
    is SqlValue.Null -> null
    is SqlValue.Text -> value
    is SqlValue.Bool -> value
    is SqlValue.Int8 -> value.toInt()
    is SqlValue.Int16 -> value
    is SqlValue.Int32 -> value
    is SqlValue.Int64 -> value
    is SqlValue.Float32 -> value
    is SqlValue.Float64 -> value
    is SqlValue.Bytes -> value
    is SqlValue.Time -> value.toString()
    is SqlValue.UuidValue -> value
}
