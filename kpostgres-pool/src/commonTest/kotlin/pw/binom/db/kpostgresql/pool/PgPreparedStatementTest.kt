package pw.binom.db.kpostgresql.pool

import pw.binom.db.SqlValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * Тесты разбора timestamp и сбора параметров — без базы.
 *
 * ## Почему без сервера
 *
 * Обе проверяемые вещи — чистые преобразования: строка → [Instant] и
 * `names`/`bound` → список значений. Сервер здесь не нужен, а его участие
 * только замаскировало бы ошибку round-trip'ом.
 *
 * `collectParams()` в [PgPreparedStatement] приватный, а сам запрос требует
 * соединения и клиента. Поэтому логика сбора вынесена в доступную тестам
 * [collectPgParams], которую `collectParams()` и вызывает. Так проверяется
 * ровно тот код, что идёт в запрос, в обеих ветках: именованной и позиционной.
 */
class PgPreparedStatementTest {

    @Test
    fun `timestamp без зоны считается UTC`() {
        assertEquals(
            Instant.parse("2026-11-25T01:47:00Z"),
            parsePgTimestamp("2026-11-25 01:47:00"),
        )
    }

    @Test
    fun `микросекунды не обрезаются до миллисекунд`() {
        // Именно эта проверка ловит старую обрезку: .123456 должно остаться
        // .123456, а не превратиться в .123.
        assertEquals(
            Instant.parse("2026-11-25T01:47:00.123456Z"),
            parsePgTimestamp("2026-11-25 01:47:00.123456"),
        )
    }

    @Test
    fun `timestamptz со смещением`() {
        assertEquals(
            Instant.parse("2026-11-25T01:47:00+03:00"),
            parsePgTimestamp("2026-11-25 01:47:00+03"),
        )
    }

    @Test
    fun `date разбирается как полночь UTC`() {
        assertEquals(
            Instant.parse("2020-01-01T00:00:00Z"),
            parsePgTimestamp("2020-01-01"),
        )
    }

    @Test
    fun `мусор возвращает null`() {
        assertNull(parsePgTimestamp("not-a-timestamp"))
        assertNull(parsePgTimestamp(""))
    }

    @Test
    fun `именованные параметры собираются по индексу имени`() {
        val converted = ParamConverter.convert("UPDATE t SET a=:a, b=:b WHERE c=:a")
        assertEquals(listOf("a", "b"), converted.names)

        val params = collectPgParams(
            names = converted.names,
            bound = mapOf(
                0 to SqlValue.Text("first"),
                1 to SqlValue.Int32(2),
            ),
        )

        // Повторное имя `a` получает то же значение, лишних параметров нет.
        assertEquals(listOf("first", 2), params)
    }

    @Test
    fun `позиционные параметры берутся из ключей bound`() {
        val converted = ParamConverter.convert("SELECT * FROM t WHERE id=\$1 AND name=\$2")
        assertEquals(emptyList(), converted.names)

        val params = collectPgParams(
            names = converted.names,
            bound = mapOf(
                0 to SqlValue.Int32(7),
                1 to SqlValue.Text("Иван"),
            ),
        )

        assertEquals(listOf(7, "Иван"), params)
    }

    @Test
    fun `пропуски в позиционных параметрах заполняются null`() {
        val params = collectPgParams(
            names = emptyList(),
            bound = mapOf(
                0 to SqlValue.Text("a"),
                2 to SqlValue.Text("c"),
            ),
        )

        // $2 не привязан — на его месте должен быть NULL, а не сдвиг значений.
        assertEquals(listOf("a", null, "c"), params)
    }

    @Test
    fun `без привязок позиционный список пуст`() {
        assertEquals(
            emptyList(),
            collectPgParams(names = emptyList(), bound = emptyMap()),
        )
    }
}
