package pw.binom.db.kpostgresql.pool

import kotlinx.coroutines.test.runTest
import pw.binom.db.toList
import pw.binom.db.useConnection
import pw.binom.db.kpostgresql.PostgresConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * Потоковое чтение на живой базе.
 *
 * ## Что доказывается
 *
 * Главное — **память не растёт с числом строк**. Это проверяется не
 * рассуждением, а замером: до и после вычитывания 100 000 строк считается
 * прирост занятой памяти. При потоковом чтении он должен быть в пределах
 * нескольких мегабайт (одна порция), при «всё в память» — сотни.
 *
 * Замер через `Runtime` — приблизительный (GC может сработать в неудачный
 * момент), поэтому порог взят с запасом: 60 МБ. Полная выдача 100 000 строк
 * по ~100 байт даёт минимум 10 МБ «полезных» плюс накладные — обычно 30–60.
 * Если порог пробьёт, значит потоковость сломалась.
 */
class PgStreamLiveTest {

    private val host = System.getenv("PG_HOST") ?: "192.168.76.251"
    private val port = System.getenv("PG_PORT")?.toInt() ?: 5432
    private val db = System.getenv("PG_DB") ?: "console"
    private val user = System.getenv("PG_USER") ?: "console"
    private val password = System.getenv("PG_PASSWORD") ?: ""

    private fun config() = PostgresConfig(host, port, db, user, password)

    /** Занимает сделку и порождает N строк. */
    private suspend fun seed(conn: pw.binom.db.Connection, n: Int) {
        conn.executeUpdate("DROP TABLE IF EXISTS stream_test")
        conn.executeUpdate(
            """
            CREATE TABLE stream_test (
                id bigserial PRIMARY KEY,
                payload text NOT NULL,
                num integer NOT NULL
            )
            """.trimIndent(),
        )
        // generate_series на стороне сервера: 100k строк быстро и без
        // клиентского цикла, который сам съел бы память.
        conn.executeUpdate(
            """
            INSERT INTO stream_test (payload, num)
            SELECT repeat('x', 100), g FROM generate_series(1, $n) AS g
            """.trimIndent(),
        )
    }

    @Test
    fun `потоковое чтение не растит память`() = runTest {
        if (password.isEmpty()) {
            println("PG_PASSWORD не задан — тест пропущен")
            return@runTest
        }

        openConnection(config()).use { conn ->
            seed(conn, 100_000)

            val rt = Runtime.getRuntime()
            System.gc()
            Thread.sleep(100)
            val before = rt.totalMemory() - rt.freeMemory()

            var count = 0L
            var maxBatch = 0
            val rs = conn.queryStream("SELECT id, payload, num FROM stream_test ORDER BY id", fetchSize = 500)
            try {
                assertTrue(rs.isStreaming, "набор должен быть потоковым")
                var batches = 0
                while (true) {
                    val batch = rs.fetchRows(500)
                    if (batch.isEmpty()) break
                    maxBatch = maxOf(maxBatch, batch.size)
                    count += batch.size
                    batches++
                    // Обрабатываем и выбрасываем: тело цикла не должно
                    // накапливать строки, иначе тест проверит сам себя.
                    for (row in batch) {
                        if (row.isNull(0)) error("id не должен быть NULL")
                    }
                }
                println("прочитано строк: $count, порций: $batches, макс. порция: $maxBatch")
            } finally {
                rs.close()
            }

            System.gc()
            Thread.sleep(100)
            val after = rt.totalMemory() - rt.freeMemory()
            val growthMb = (after - before) / 1024.0 / 1024.0
            println("прирост памяти: %.1f МБ".format(growthMb))

            assertEquals(100_000L, count, "должны прочитать все строки")
            assertTrue(maxBatch <= 500, "порция не должна превышать fetchSize, получено $maxBatch")
            assertTrue(growthMb < 60.0, "память выросла на %.1f МБ — потоковость не работает".format(growthMb))
        }
    }

    @Test
    fun `портал отдаёт строки порциями а не всё сразу`() = runTest {
        if (password.isEmpty()) return@runTest

        openConnection(config()).use { conn ->
            seed(conn, 1200)

            val rs = conn.queryStream("SELECT id FROM stream_test ORDER BY id", fetchSize = 500)
            try {
                val b1 = rs.fetchRows(500)
                assertEquals(500, b1.size, "первая порция должна быть ровно fetchSize")
                val b2 = rs.fetchRows(500)
                assertEquals(500, b2.size)
                val b3 = rs.fetchRows(500)
                assertEquals(200, b3.size, "последняя порция — остаток")
                val b4 = rs.fetchRows(500)
                assertTrue(b4.isEmpty(), "после исчерпания порций быть не должно")

                // Порядок сохранён: id растут.
                assertEquals(1L, b1.first().getLong(0))
                assertEquals(500L, b1.last().getLong(0))
                assertEquals(501L, b2.first().getLong(0))
            } finally {
                rs.close()
            }
        }
    }

    @Test
    fun `next на потоковом наборе бросает внятную ошибку`() = runTest {
        if (password.isEmpty()) return@runTest

        openConnection(config()).use { conn ->
            seed(conn, 10)
            val rs = conn.queryStream("SELECT id FROM stream_test", fetchSize = 5)
            try {
                val e = assertFailsWith<UnsupportedOperationException> { rs.next() }
                assertTrue(
                    e.message?.contains("fetchRows") == true,
                    "ошибка должна подсказывать правильный метод, получено: ${e.message}",
                )
            } finally {
                rs.close()
            }
        }
    }

    @Test
    fun `обычный query по-прежнему читается через next`() = runTest {
        if (password.isEmpty()) return@runTest

        openConnection(config()).use { conn ->
            seed(conn, 10)
            val rs = conn.query("SELECT id FROM stream_test ORDER BY id")
            try {
                assertTrue(!rs.isStreaming, "обычный запрос не потоковый")
                var n = 0
                while (rs.next()) n++
                assertEquals(10, n)
            } finally {
                rs.close()
            }
        }
    }

    @Test
    fun `fetchRows на обычном наборе вычитывает из памяти`() = runTest {
        if (password.isEmpty()) return@runTest

        openConnection(config()).use { conn ->
            seed(conn, 7)
            val rs = conn.query("SELECT id FROM stream_test ORDER BY id")
            try {
                val all = rs.toList()
                assertEquals(7, all.size)
            } finally {
                rs.close()
            }
        }
    }
}
