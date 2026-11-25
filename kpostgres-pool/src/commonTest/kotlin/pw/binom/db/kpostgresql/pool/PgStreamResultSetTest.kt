package pw.binom.db.kpostgresql.pool

import kotlinx.coroutines.test.runTest
import pw.binom.db.ColumnType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Логика порционного чтения — без базы.
 *
 * ## Что здесь проверяется
 *
 * `PgStreamResultSet` — это буфер: «если в текущей порции есть строки, отдай
 * следующую; если кончилась — попроси у источника ещё». Логика от Postgres
 * не зависит, и гонять её на живом сервере значит проверять Postgres, а не
 * свой код.
 *
 * Поэтому источник заменён фейком, который отдаёт заранее заданные порции и
 * считает обращения. Живой сервер нужен только ядру драйвера — там, где
 * действительно проверяется протокол.
 *
 * ## Что этот тест ловит
 *
 * Ровно те ошибки, которые я допустил в живой версии и находил вручную:
 * - зависание на ровной границе порций (1000 строк при fetchSize 500);
 * - потерю строк при переходе между порциями;
 * - лишний запрос порции после исчерпания;
 * - неверный ответ на пустой результат.
 */
class PgStreamResultSetTest {

    /**
     * Фейковый источник: отдаёт заданные порции по порядку, потом пусто.
     *
     * `calls` считает обращения — по нему видно, не запрашивается ли порция
     * лишний раз.
     */
    private class FakePortal(
        private val batches: List<List<Array<ByteArray?>>>,
        override val columns: List<String> = listOf("id"),
        override val columnTypes: List<ColumnType> = listOf(ColumnType.BIGINT),
    ) : PortalSource {
        var calls: Int = 0
            private set
        private var index: Int = 0

        override suspend fun nextBatch(): List<Array<ByteArray?>> {
            calls++
            if (index >= batches.size) return emptyList()
            return batches[index++]
        }
    }

    private fun row(vararg values: String?): Array<ByteArray?> =
        values.map { it?.encodeToByteArray() }.toTypedArray()

    @Test
    fun `одна порция читается целиком`() = runTest {
        val portal = FakePortal(listOf(listOf(row("1"), row("2"), row("3"))))
        val rs = PgStreamResultSet(portal, fetchSize = 10)

        val ids = mutableListOf<Long>()
        while (rs.next()) ids += rs.getLong(0)!!

        assertEquals(listOf(1L, 2L, 3L), ids)
        assertEquals(1, portal.calls, "порция должна запрашиваться один раз")
    }

    @Test
    fun `переход между порциями не теряет строк`() = runTest {
        // Три порции по две: границы ровные — самый частый источник ошибок.
        val portal = FakePortal(
            listOf(
                listOf(row("1"), row("2")),
                listOf(row("3"), row("4")),
                listOf(row("5"), row("6")),
            ),
        )
        val rs = PgStreamResultSet(portal, fetchSize = 2)

        val ids = mutableListOf<Long>()
        while (rs.next()) ids += rs.getLong(0)!!

        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), ids)
    }

    @Test
    fun `ровная граница не даёт зависания`() = runTest {
        // Ровно две порции: после второй источник обязан вернуть пусто.
        val portal = FakePortal(
            listOf(
                listOf(row("1"), row("2")),
                listOf(row("3"), row("4")),
            ),
        )
        val rs = PgStreamResultSet(portal, fetchSize = 2)

        var n = 0
        while (rs.next()) n++

        assertEquals(4, n)
        // Три обращения: две порции и один пустой ответ. Четвёртого быть не
        // должно — иначе набор бесконечно спрашивает сервер.
        assertEquals(3, portal.calls)
    }

    @Test
    fun `пустой результат сразу возвращает false`() = runTest {
        val portal = FakePortal(emptyList())
        val rs = PgStreamResultSet(portal, fetchSize = 10)

        assertFalse(rs.next(), "пустой набор должен сразу сказать false")
    }

    @Test
    fun `повторный next после исчерпания остаётся false`() = runTest {
        val portal = FakePortal(listOf(listOf(row("1"))))
        val rs = PgStreamResultSet(portal, fetchSize = 10)

        assertTrue(rs.next())
        assertFalse(rs.next())
        assertFalse(rs.next(), "повторный вызов не должен оживлять набор")
    }

    @Test
    fun `null и пустая строка различаются`() = runTest {
        val portal = FakePortal(listOf(listOf(row(null), row(""))))
        val rs = PgStreamResultSet(portal, fetchSize = 10)

        assertTrue(rs.next())
        assertTrue(rs.isNull(0), "NULL должен читаться как null")

        assertTrue(rs.next())
        assertFalse(rs.isNull(0), "пустая строка — не NULL")
        assertEquals("", rs.getString(0))
    }

    @Test
    fun `getString и getInt читают значения`() = runTest {
        val portal = FakePortal(listOf(listOf(row("42"))))
        val rs = PgStreamResultSet(portal, fetchSize = 10)

        assertTrue(rs.next())
        assertEquals("42", rs.getString(0))
        assertEquals(42, rs.getInt(0))
        assertEquals(42L, rs.getLong(0))
    }

    @Test
    fun `boolean t и f разбираются`() = runTest {
        val portal = FakePortal(listOf(listOf(row("t"), row("f"))))
        val rs = PgStreamResultSet(portal, fetchSize = 10)

        assertTrue(rs.next())
        assertEquals(true, rs.getBoolean(0))
        assertTrue(rs.next())
        assertEquals(false, rs.getBoolean(0))
    }

    @Test
    fun `колонки и типы берутся из источника`() = runTest {
        val portal = FakePortal(
            batches = listOf(listOf(row("1", "a"))),
            columns = listOf("id", "name"),
            columnTypes = listOf(ColumnType.BIGINT, ColumnType.VARCHAR),
        )
        val rs = PgStreamResultSet(portal, fetchSize = 10)

        assertEquals(listOf("id", "name"), rs.columns)
        assertEquals(ColumnType.BIGINT, rs.columnType(0))
        assertEquals(ColumnType.VARCHAR, rs.columnType(1))
        assertTrue(rs.next())
    }

    @Test
    fun `доступ по имени работает`() = runTest {
        val portal = FakePortal(
            batches = listOf(listOf(row("7", "Иван"))),
            columns = listOf("id", "name"),
            columnTypes = listOf(ColumnType.BIGINT, ColumnType.VARCHAR),
        )
        val rs = PgStreamResultSet(portal, fetchSize = 10)

        assertTrue(rs.next())
        assertEquals(7L, rs.getLong("id"))
        assertEquals("Иван", rs.getString("name"))
    }

    @Test
    fun `закрытие прекращает чтение`() = runTest {
        val portal = FakePortal(listOf(listOf(row("1"), row("2"))))
        val rs = PgStreamResultSet(portal, fetchSize = 10)

        assertTrue(rs.next())
        rs.close()
        assertFalse(rs.next(), "после закрытия читать нечего")
    }

    @Test
    fun `getLong до первого next бросает`() = runTest {
        val portal = FakePortal(listOf(listOf(row("1"))))
        val rs = PgStreamResultSet(portal, fetchSize = 10)

        // До next() текущей строки нет — молчаливое чтение мусора было бы
        // худшим из возможных поведений.
        val failed = runCatching { rs.getLong(0) }.isFailure
        assertTrue(failed, "до next() чтение должно падать")
    }
}
