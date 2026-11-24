package pw.binom.db.kpostgresql.orm

import pw.binom.db.DatabaseEngine
import pw.binom.db.kpostgresql.PostgresClient
import pw.binom.db.kpostgresql.PostgresConfig
import pw.binom.db.kpostgresql.pool.PgConnectionAdapter
import pw.binom.db.orm.Orm
import pw.binom.db.pool.ConnectionPool
import pw.binom.db.pool.ConnectionPoolImpl
import pw.binom.db.pool.PoolConfig

/**
 * Готовый ORM над PostgreSQL.
 *
 * ## Зачем отдельный класс
 *
 * Собрать `Orm` руками можно, но тогда на каждом проекте надо помнить
 * порядок: клиент → адаптер → пул → ORM, и не перепутать, что пул должен
 * быть поверх адаптера, а не поверх голого `PostgresClient`. Здесь это
 * собрано один раз и проверено в одном месте.
 *
 * ## Что происходит при [connect]
 *
 * Пул ленивый: соединения не открываются вперёд. Первое открывается на
 * первый запрос — это значит, что `connect` не падает, если БД недоступна,
 * и ошибку надо ловить на первом обращении. Такое поведение выбрано
 * осознанно: иначе сервис не стартует из-за временной недоступности БД, хотя
 * мог бы подняться и переподключиться.
 */
class PgOrm private constructor(
    val orm: Orm,
    private val pool: ConnectionPool,
) : AutoCloseable {

    /** Транзакция: соединение занято на всё время блока. */
    suspend fun <R> transaction(block: suspend (Orm) -> R): R = orm.transaction(block)

    /** Блок без транзакции: соединение занято, запросы независимы. */
    suspend fun <R> read(block: suspend (Orm) -> R): R = orm.read(block)

    /** Закрывает пул. Соединения закрываются вместе с ним. */
    override fun close() {
        pool.close()
    }

    companion object {
        /**
         * Поднимает пул и ORM.
         *
         * `poolSize` — потолок соединений. Для CRUD поверх Postgres разумно
         * брать число ядер: больше соединений не ускоряют работу, а
         * увеличивают конкуренцию за блокировки строк.
         *
         * `pingQuery` — чем проверять живость. `SELECT 1` работает везде;
         * `null` отключает проверку (нужно, если БД не поддерживает запросы
         * без таблиц).
         */
        fun connect(
            config: PostgresConfig,
            poolSize: Int = 8,
            pingQuery: String? = "SELECT 1",
        ): PgOrm {
            val factory: suspend () -> pw.binom.db.Connection = {
                PgConnectionAdapter(PostgresClient.connect(config))
            }
            val pool = ConnectionPoolImpl(
                config = PoolConfig(
                    maxConnections = poolSize,
                    pingQuery = pingQuery,
                ),
                factory = factory,
            )
            return PgOrm(Orm(pool, DatabaseEngine.POSTGRESQL), pool)
        }
    }
}
