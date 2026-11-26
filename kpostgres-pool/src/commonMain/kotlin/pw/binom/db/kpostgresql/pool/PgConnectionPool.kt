package pw.binom.db.kpostgresql.pool

import pw.binom.db.Connection
import pw.binom.db.kpostgresql.PostgresConfig
import pw.binom.db.pool.ConnectionFactory
import pw.binom.db.pool.ConnectionPool
import pw.binom.db.pool.ConnectionPoolImpl
import pw.binom.db.pool.PoolConfig

/**
 * Пул соединений к Postgres.
 *
 * ## Почему это здесь, а не в `kpostgres`
 *
 * Ядро драйвера обязано работать без обвязки: кому-то нужен голый
 * `PostgresClient`, и навязывать ему пул из `kdatabase` — значит плодить
 * зависимости в самом нижнем слое. Поэтому очередь, слоты, пинг и уборка
 * живут в `kdatabase`, а здесь от Postgres остаётся ровно то, что в нём
 * Postgres-специфично.
 *
 * ## Почему делегирование, а не наследование
 *
 * `ConnectionPoolImpl` закрывает свои состояния `private` и не рассчитан на
 * переопределение: наследник начал бы чинить механику, а не настраивать её.
 * Делегирование через `by` даёт тот же набор методов и честно говорит, что
 * пул — готовый, а не базовый класс. Цена — [close] нельзя дополнить своим
 * кодом; см. ниже.
 *
 * ## Чего здесь сознательно нет
 *
 * * `application_name` — уже часть [PostgresConfig] и уезжает в стартовый
 *   пакет сервера (`Transport`); дублировать его `SET`-запросом — значит
 *   дать две правды об одном параметре.
 * * `search_path` — драйвер его не поддерживает, и выдумывать его в пуле
 *   незачем: место для него — [PgConnectionFactory.open], сразу после
 *   соединения, когда в `PostgresConfig` появится соответствующее поле.
 * * собственный `close()` — см. [ConnectionPoolImpl.close]: синхронный close
 *   не может дождаться `suspend`-мьютекса, поэтому очистка соединений идёт
 *   через [ConnectionFactory.onDiscard] на следующем suspend-входе.
 */
class PgConnectionPool(
    config: PostgresConfig,
    poolConfig: PoolConfig = PoolConfig(),
) : ConnectionPool by ConnectionPoolImpl(poolConfig, PgConnectionFactory(config))

/**
 * Postgres-часть пула: как открыть соединение и что считать живым.
 *
 * Реализация тонкая, и это не лень, а граница: всё остальное — очередь
 * ожидающих, потолок `maxConnections`, переиспользование — механика,
 * одинаковая для любой БД. Здесь она только узнаёт, что такое «соединение с
 * Postgres».
 *
 * @param config параметры соединения. Пул держит их как есть и открывает
 *        соединение на каждый новый слот, поэтому старт приложения не
 *        требует живой базы.
 */
class PgConnectionFactory(
    private val config: PostgresConfig,
) : ConnectionFactory {

    /**
     * Соединение через [openConnection] — то есть `PgConnectionAdapter` поверх
     * `PostgresClient`, а не голый клиент: пул работает с контрактами
     * `kdatabase`, и иначе адаптер пришлось бы писать заново.
     */
    override suspend fun open(): Connection = openConnection(config)
}
