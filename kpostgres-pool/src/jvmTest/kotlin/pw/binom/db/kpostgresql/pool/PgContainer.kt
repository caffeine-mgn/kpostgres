package pw.binom.db.kpostgresql.pool

import org.testcontainers.containers.PostgreSQLContainer
import pw.binom.db.kpostgresql.PostgresConfig

/**
 * Postgres для проверки пула.
 *
 * Контейнер один на прогон: поднимать его на каждый тест — значит мерить
 * скорость Docker, а не пула. Параметры те же, что в корневом модуле
 * (`scram-sha-256`), иначе проверка авторизации разъезжается с основной.
 */
internal object PgContainer {

    private val container: PostgreSQLContainer<*> by lazy {
        PostgreSQLContainer<Nothing>("postgres:16-alpine").apply {
            withDatabaseName("kp_pool_it")
            withUsername("kppool")
            withPassword("kppool_secret")
            withCommand("postgres", "-c", "password_encryption=scram-sha-256")
            start()
        }
    }

    /**
     * Конфиг на контейнер.
     *
     * [applicationName] задаётся явно: часть проверок смотрит, что он доехал
     * до сервера, и полагаться на дефолт драйвера в таком проверении нельзя.
     */
    fun config(applicationName: String = "kpostgres-pool-it"): PostgresConfig {
        val c = container
        return PostgresConfig.of(
            c.host,
            c.firstMappedPort,
            c.databaseName,
            c.username,
            c.password,
            applicationName = applicationName,
        )
    }
}
