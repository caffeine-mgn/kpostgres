package pw.binom.db.kpostgresql.internal

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Кодексы текстового представления — без сети и без базы.
 *
 * ## Почему это в `commonTest`
 *
 * `TextDecoders`/`TextEncoders` — чистые функции: байты на входе, значение
 * на выходе. Сервер здесь ни при чём, и гонять их через живой Postgres
 * значит проверять Postgres, а не свой разбор.
 *
 * Пока эти тесты жили только в интеграционных, JVM-прогон покрывал их
 * косвенно, а native — не покрывал вовсе. Теперь один и тот же набор идёт
 * на всех таргетах.
 *
 * ## Что ловится
 *
 * Postgres отдаёт в текстовом формате не то, что пишет Java:
 * - `boolean` — `t`/`f`, а не `true`/`false`;
 * - `bytea` — `\x` и hex-пары, а не base64;
 * - `timestamp` — `2024-01-15 10:30:00.123456+03`, а не ISO.
 *
 * Расхождение в любом из этих мест даёт ошибку, которую видно только на
 * живом сервере, — если не проверить разбор отдельно.
 */
@OptIn(ExperimentalUuidApi::class)
class TextCodecsTest {

    // --- строки ---

    @Test
    fun decodeStringUtf8() {
        val raw = "Привет, мир".encodeToByteArray()
        assertEquals("Привет, мир", TextDecoders.decodeString(raw))
    }

    @Test
    fun decodeEmptyString() {
        assertEquals("", TextDecoders.decodeString(ByteArray(0)))
    }

    // --- числа ---

    @Test
    fun decodeInt() {
        assertEquals(42, TextDecoders.decodeInt("42".encodeToByteArray()))
        assertEquals(-7, TextDecoders.decodeInt("-7".encodeToByteArray()))
    }

    @Test
    fun decodeIntTrimsSpaces() {
        // Postgres иногда отдаёт число с пробелом: numeric-формат.
        assertEquals(42, TextDecoders.decodeInt(" 42 ".encodeToByteArray()))
    }

    @Test
    fun decodeLongMaxValue() {
        assertEquals(Long.MAX_VALUE, TextDecoders.decodeLong(Long.MAX_VALUE.toString().encodeToByteArray()))
        assertEquals(Long.MIN_VALUE, TextDecoders.decodeLong(Long.MIN_VALUE.toString().encodeToByteArray()))
    }

    @Test
    fun decodeShort() {
        assertEquals(Short.MAX_VALUE, TextDecoders.decodeShort(Short.MAX_VALUE.toString().encodeToByteArray()))
    }

    @Test
    fun decodeDoubleAndFloat() {
        assertEquals(3.14, TextDecoders.decodeDouble("3.14".encodeToByteArray()))
        assertEquals(2.5f, TextDecoders.decodeFloat("2.5".encodeToByteArray()))
    }

    @Test
    fun decodeBadIntFails() {
        // «не число» должно падать, а не молча давать 0: тихий ноль в данных
        // хуже исключения.
        assertFailsWith<NumberFormatException> {
            TextDecoders.decodeInt("abc".encodeToByteArray())
        }
    }

    // --- boolean: главное расхождение с Java ---

    @Test
    fun decodeBooleanPostgresFormat() {
        // Postgres в текстовом формате отдаёт t/f, и только их.
        assertTrue(TextDecoders.decodeBoolean("t".encodeToByteArray()))
        assertEquals(false, TextDecoders.decodeBoolean("f".encodeToByteArray()))
    }

    @Test
    fun decodeBooleanAcceptsLongForms() {
        // Терпимость к true/false нужна для значений, записанных не Postgres:
        // например, при импорте из другой СУБД.
        assertTrue(TextDecoders.decodeBoolean("true".encodeToByteArray()))
        assertEquals(false, TextDecoders.decodeBoolean("false".encodeToByteArray()))
        assertTrue(TextDecoders.decodeBoolean("1".encodeToByteArray()))
        assertEquals(false, TextDecoders.decodeBoolean("0".encodeToByteArray()))
    }

    // --- bytea: hex, а не base64 ---

    @Test
    fun decodeByteaFromHex() {
        // Формат Postgres: \x плюс пары hex-цифр.
        val raw = "\\x48656c6c6f".encodeToByteArray()
        assertContentEquals("Hello".encodeToByteArray(), TextDecoders.decodeBytesFromHex(raw))
    }

    @Test
    fun decodeByteaEmpty() {
        assertContentEquals(ByteArray(0), TextDecoders.decodeBytesFromHex("\\x".encodeToByteArray()))
    }

    @Test
    fun decodeByteaAllByteValues() {
        // Проверка на всех 256 значениях: заодно ловит путаницу со знаковым
        // байтом, из-за которой 0xFF превращается в 0xFFFFFFFF.
        val all = ByteArray(256) { it.toByte() }
        val hex = buildString {
            append("\\x")
            for (b in all) {
                val v = b.toInt() and 0xFF
                append("0123456789abcdef"[v ushr 4])
                append("0123456789abcdef"[v and 0x0F])
            }
        }
        assertContentEquals(all, TextDecoders.decodeBytesFromHex(hex.encodeToByteArray()))
    }

    // --- uuid ---

    @Test
    fun decodeUuid() {
        val text = "550e8400-e29b-41d4-a716-446655440000"
        assertEquals(Uuid.parse(text), TextDecoders.decodeUuid(text.encodeToByteArray()))
    }

    @Test
    fun decodeUuidUpperCase() {
        val lower = "550e8400-e29b-41d4-a716-446655440000"
        val upper = lower.uppercase()
        assertEquals(Uuid.parse(lower), TextDecoders.decodeUuid(upper.encodeToByteArray()))
    }

    // --- кодирование на отправку ---

    @Test
    fun encodeIntAndLong() {
        assertEquals("42", TextEncoders.encodeInt(42))
        assertEquals("-1", TextEncoders.encodeLong(-1L))
    }

    @Test
    fun encodeStringPassesThrough() {
        // Строка уходит как есть: экранирование — забота протокола Bind, а не
        // кодека. Если бы кодек что-то экранировал, оно попало бы в данные.
        assertEquals("O'Brien", TextEncoders.encodeString("O'Brien"))
    }

    // --- OID: типы, на которые драйвер опирается ---

    @Test
    fun oidValuesMatchPostgresCatalog() {
        // Значения из pg_type. Расхождение здесь означает, что Bind пошлёт
        // неверный тип и сервер ответит ошибкой приведения.
        assertEquals(16, Oid.BOOL)
        assertEquals(17, Oid.BYTEA)
        assertEquals(20, Oid.INT8)
        assertEquals(21, Oid.INT2)
        assertEquals(23, Oid.INT4)
        assertEquals(25, Oid.TEXT)
        assertEquals(700, Oid.FLOAT4)
        assertEquals(701, Oid.FLOAT8)
        assertEquals(1043, Oid.VARCHAR)
        assertEquals(1114, Oid.TIMESTAMP)
        assertEquals(1184, Oid.TIMESTAMPTZ)
        assertEquals(2950, Oid.UUID)
        assertEquals(3802, Oid.JSONB)
    }
}
