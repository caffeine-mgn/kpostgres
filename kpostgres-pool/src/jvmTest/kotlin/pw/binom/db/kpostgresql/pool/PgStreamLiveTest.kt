package pw.binom.db.kpostgresql.pool

import kotlinx.coroutines.test.runTest
import pw.binom.db.kpostgresql.PostgresConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Потоковое чтение через портал на живой базе.
 *
 * ## Что доказывается
 *
 * Главное — **память не растёт с числом строк**. Проверяется замером: до и
 * после вычитывания 100 000 строк считается прирост занятой памяти. При
 * потоковом чтении он должен быть в пределах нескольких мегабайт (одна
 * порция), при «всё в память» — десятки.
 *
 * Замер через `Runtime` приблизительный (GC может сработать в неудачный
 * момент), поэтому порог взят с запасом — 60 МБ.
 *
 * ## Почему цикл один и тот же
 *
 * Здесь нет отдельного API для потока: `while (rs.next())` работает и для
 * обычного набора, и для потокового. Разница только внутри драйвера — он
 * докачивает порцию сам. Это и проверяется: код вызывающего не меняется.
 */
class PgStreamLiveTest {

    private val host = System.getenv("PG_HOST") ?: "192.168.76.251"
    private val port = System.getenv("PG_PORT")?.toInt() ?: 5432
    private val db = System.getenv("PG_DB") ?: "console"
    private val user = System.getenv("PG_USER") ?: "console"
    private val password = System.getenv("PG_PASSWORD") ?: ""

    private fun config() = PostgresConfig(host, port, db, user, password)

    /** Заполняет таблицу N строками на стороне сервера. */
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
        // generate_series на сервере: клиентский цикл сам съел бы память и
        // сделал бы замер бессмысленным.
        conn.executeUpdate(
            "INSERT INTO stream_test (payload, num) " +
                "SELECT repeat('x', 100), g FROM generate_series(1, $n) AS g",
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
            val rs = conn.queryStream("SELECT id, payload, num FROM stream_test ORDER BY id", fetchSize = 500)
            try {
                // Ровно тот же цикл, что и для обычного набора. Никаких
                // fetchRows: порционность — дело драйвера.
                while (rs.next()) {
                    if (rs.isNull(0)) error("id не должен быть NULL")
                    count++
                }
            } finally {
                rs.close()
            }

            System.gc()
            Thread.sleep(100)
            val after = rt.totalMemory() - rt.freeMemory()
            val growthMb = (after - before) / 1024.0 / 1024.0
            println("прочитано строк: $count, прирост памяти: %.1f МБ".format(growthMb))

            assertEquals(100_000L, count, "должны прочитать все строки")
            assertTrue(growthMb < 60.0, "память выросла на %.1f МБ — потоковость не работает".format(growthMb))
        }
    }

    @Test
    fun `порции идут по fetchSize и сохраняют порядок`() = runTest {
        if (password.isEmpty()) return@runTest

        openConnection(config()).use { conn ->
            seed(conn, 1200)

            val rs = conn.queryStream("SELECT id FROM stream_test ORDER BY id", fetchSize = 500)
            try {
                val ids = mutableListOf<Long>()
                while (rs.next()) ids += rs.getLong(0)!!

                assertEquals(1200, ids.size)
                assertEquals(1L, ids.first(), "первая строка должна быть id=1")
                assertEquals(1200L, ids.last(), "последняя — id=1200")
                // Порядок строго возрастающий: значит порции склеены верно.
                assertTrue(ids.zipWithNext().all { (a, b) -> b == a + 1 }, "порядок нарушен")
            } finally {
                rs.close()
            }
        }
    }

    @Test
    fun `порция ровно по границе и последняя неполная`() = runTest {
        if (password.isEmpty()) return@runTest

        openConnection(config()).use { conn ->
            // 1000 = ровно две порции по 500: проверяет, что после второй
            // порции набор не «зависает» в ожидании несуществующей третьей.
            seed(conn, 1000)
            val rs = conn.queryStream("SELECT id FROM stream_test ORDER BY id", fetchSize = 500)
            try {
                var n = 0
                while (rs.next()) n++
                assertEquals(1000, n)
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
            conn.query("SELECT id FROM stream_test ORDER BY id").use { rs ->
                var n = 0
                while (rs.next()) n++
                assertEquals(10, n)
            }
        }
    }

    @Test
    fun `пустой результат не зависает`() = runTest {
        if (password.isEmpty()) return@runTest

        openConnection(config()).use { conn ->
            seed(conn, 0)
            conn.queryStream("SELECT id FROM stream_test", fetchSize = 100).use { rs ->
                assertTrue(!rs.next(), "пустой набор сразу должен вернуть false")
            }
        }
    }
}
