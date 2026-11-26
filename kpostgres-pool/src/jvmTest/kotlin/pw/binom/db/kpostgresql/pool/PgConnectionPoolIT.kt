package pw.binom.db.kpostgresql.pool

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import pw.binom.db.pool.PoolConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Пул против живого сервера.
 *
 * ## Зачем, если механика покрыта в `kdatabase`
 *
 * Механика покрыта на фейковом драйвере. Здесь проверяется стык: что
 * [PgConnectionFactory] открывает соединение, годное для настоящих запросов,
 * и что адаптер ведёт себя честно при переиспользовании. Фейк такого не
 * покажет — он всегда готов.
 */
class PgConnectionPoolIT {

    private fun pool(maxConnections: Int = 4) = PgConnectionPool(
        config = PgContainer.config(),
        poolConfig = PoolConfig(
            maxConnections = maxConnections,
            acquireTimeout = 30.seconds,
            // Пинг — `SELECT 1`; он же проверка живости, и на реальном
            // сервере он обязан проходить.
        ),
    )

    @Test
    fun `пул выполняет запрос через живое соединение`() = runBlocking {
        val p = pool()
        try {
            p.withConnection { conn ->
                conn.query("SELECT 1 AS one").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(1, rs.getInt(0))
                }
            }
            assertEquals(1L, p.totalCreated)
        } finally {
            p.close()
        }
    }

    @Test
    fun `соединение переиспользуется между запросами`() = runBlocking {
        val p = pool()
        try {
            repeat(3) {
                p.withConnection { it.executeUpdate("SELECT 1") }
            }
            assertEquals(1L, p.totalCreated, "три запроса — одно соединение")
            assertEquals(1, p.idleCount)
        } finally {
            p.close()
        }
    }

    @Test
    fun `конфигурация доезжает до сервера`() = runBlocking {
        val p = pool()
        try {
            val appName = p.withConnection { conn ->
                conn.query("SELECT current_setting('application_name')").use { rs ->
                    assertTrue(rs.next())
                    rs.getString(0)
                }
            }
            // Стартовый пакет собирает транспорт, а не пул: проверка на то,
            // что пул не потерял конфиг по дороге.
            assertEquals("kpostgres-pool-it", appName)
        } finally {
            p.close()
        }
    }

    @Test
    fun `параллельные запросы не пробивают потолок`() = runBlocking {
        val p = pool(maxConnections = 3)
        try {
            val results = (1..8).map {
                async {
                    p.withConnection { conn ->
                        conn.query("SELECT pg_sleep(0.05) AS slept").use { rs ->
                            rs.next()
                            conn.isReadyForQuery
                        }
                    }
                }
            }.awaitAll()

            assertEquals(8, results.size)
            assertTrue(results.all { it }, "соединение после запроса готово к следующему")
            assertTrue(p.connectionCount <= 3, "открыто ${p.connectionCount} при потолке 3")
            assertEquals(0L, p.timeouts)
        } finally {
            p.close()
        }
    }
}
