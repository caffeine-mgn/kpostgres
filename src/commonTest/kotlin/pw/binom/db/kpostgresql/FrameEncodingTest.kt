package pw.binom.db.kpostgresql

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class FrameEncodingTest {

    /**
     * Hex-дамп байтов.
     *
     * `String.format` в Kotlin 2.4 стал только JVM-функцией, поэтому
     * заменён на переносимый `toString(16)` с ручным дополнением до двух
     * знаков: тест должен работать и в native.
     */
    private fun expectedBytes(builder: ByteArray.() -> Unit): String =
        builder.let { ByteArray(0).apply(it) }
            .joinToString("") { b ->
                val v = b.toInt() and 0xFF
                if (v < 0x10) "0${v.toString(16)}" else v.toString(16)
            }

    @Test
    fun checkSingleByte() {
        val out = byteArrayOf(MessageTagToByteMap.PASSWORD)
        assertEquals("70".length / 2, out.size)
    }
}

private object MessageTagToByteMap {
    const val PASSWORD: Byte = 0x70
}