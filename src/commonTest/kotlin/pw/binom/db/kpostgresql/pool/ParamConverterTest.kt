package pw.binom.db.kpostgresql.pool

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Тесты перевода `:name` → `$N`.
 *
 * ## Почему без БД
 *
 * Конвертер — чистое преобразование строки. Ошибка в нём даёт **неверные
 * данные**, а не падение: Postgres примет запрос, но подставит значения не
 * туда. Поэтому проверять надо каждое правило отдельно, а не «через живой
 * select».
 *
 * ## Что здесь главное
 *
 * Повторяющееся имя. `SET a=:x, b=:x` должно дать `$1` дважды и **два**
 * имени в списке (`x`, `y`), но одно значение на оба `$1`. Если положить
 * значение дважды, Postgres скажет «bind message supplies 3 parameters, but
 * prepared statement requires 2».
 */
class ParamConverterTest {

    @Test
    fun `простой параметр`() {
        val r = ParamConverter.convert("SELECT * FROM t WHERE id = :id")
        assertEquals("SELECT * FROM t WHERE id = \$1", r.sql)
        assertEquals(listOf("id"), r.names)
    }

    @Test
    fun `несколько параметров в порядке появления`() {
        val r = ParamConverter.convert("UPDATE t SET a=:a, b=:b WHERE c=:c")
        assertEquals("UPDATE t SET a=\$1, b=\$2 WHERE c=\$3", r.sql)
        assertEquals(listOf("a", "b", "c"), r.names)
    }

    @Test
    fun `повторяющееся имя получает тот же номер`() {
        val r = ParamConverter.convert("UPDATE t SET a=:x, b=:x WHERE c=:y")
        assertEquals("UPDATE t SET a=\$1, b=\$1 WHERE c=\$2", r.sql)
        // ВАЖНО: два имени, а не три. Иначе Postgres получит лишний параметр.
        assertEquals(listOf("x", "y"), r.names)
    }

    @Test
    fun `три вхождения одного имени`() {
        val r = ParamConverter.convert("SELECT :v, :v, :v FROM t WHERE x=:w AND y=:v")
        assertEquals("SELECT \$1, \$1, \$1 FROM t WHERE x=\$2 AND y=\$1", r.sql)
        assertEquals(listOf("v", "w"), r.names)
    }

    @Test
    fun `двоеточие в строковом литерале не параметр`() {
        val r = ParamConverter.convert("SELECT ':notparam' FROM t WHERE id=:id")
        assertEquals("SELECT ':notparam' FROM t WHERE id=\$1", r.sql)
        assertEquals(listOf("id"), r.names)
    }

    @Test
    fun `экранированная кавычка не закрывает литерал`() {
        val r = ParamConverter.convert("SELECT 'it''s :no' , :yes")
        assertEquals("SELECT 'it''s :no' , \$1", r.sql)
        assertEquals(listOf("yes"), r.names)
    }

    @Test
    fun `приведение типов не параметр`() {
        val r = ParamConverter.convert("SELECT id::text FROM t WHERE x=:x")
        assertEquals("SELECT id::text FROM t WHERE x=\$1", r.sql)
        assertEquals(listOf("x"), r.names)
    }

    @Test
    fun `комментарий не трогаем`() {
        val r = ParamConverter.convert("SELECT 1 -- :comment\nWHERE id=:id")
        assertEquals("SELECT 1 -- :comment\nWHERE id=\$1", r.sql)
        assertEquals(listOf("id"), r.names)
    }

    @Test
    fun `готовый позиционный параметр остаётся`() {
        val r = ParamConverter.convert("SELECT * FROM t WHERE a=\$1 AND b=:b")
        assertEquals("SELECT * FROM t WHERE a=\$1 AND b=\$1", r.sql)
        // Формально :b стал $1 — конфликт с ручным $1. Это осознанно: смешивать
        // стили в одном запросе нельзя, и лучше явная коллизия, чем тихо
        // неверные данные. ORM так не делает.
        assertEquals(listOf("b"), r.names)
    }

    @Test
    fun `имя с подчёркиванием и цифрами`() {
        val r = ParamConverter.convert("SELECT :user_id_2 FROM t")
        assertEquals("SELECT \$1 FROM t", r.sql)
        assertEquals(listOf("user_id_2"), r.names)
    }

    @Test
    fun `двоеточие перед буквой всегда параметр`() {
        // `:b` — валидное имя параметра, двоеточие поглощается при переводе.
        // `a` остаётся как есть: `a:b` вне литералов в SQL не встречается,
        // а если встретится — это осознанная ошибка в запросе, не наша.
        val r = ParamConverter.convert("SELECT a:b FROM t")
        assertEquals("SELECT a\$1 FROM t", r.sql)
        assertEquals(listOf("b"), r.names)
    }

    @Test
    fun `срез массива не параметр`() {
        // Postgres: arr[1:2] — срез. Двоеточие, за которым цифра, не имя
        // параметра, поэтому запрос не должен меняться.
        val sql = "SELECT arr[1:2] FROM t"
        val r = ParamConverter.convert(sql)
        assertEquals(sql, r.sql)
        assertEquals(emptyList(), r.names)
    }

    @Test
    fun `без параметров SQL не меняется`() {
        val sql = "SELECT * FROM t WHERE a=1"
        val r = ParamConverter.convert(sql)
        assertEquals(sql, r.sql)
        assertEquals(emptyList(), r.names)
    }

    @Test
    fun `кириллица в имени`() {
        val r = ParamConverter.convert("SELECT :имя FROM t")
        assertEquals("SELECT \$1 FROM t", r.sql)
        assertEquals(listOf("имя"), r.names)
    }
}
