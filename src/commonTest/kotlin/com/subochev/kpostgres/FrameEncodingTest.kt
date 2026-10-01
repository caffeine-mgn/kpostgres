package com.subochev.kpostgres

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class FrameEncodingTest {

    private fun expectedBytes(builder: ByteArray.() -> Unit): String =
        builder.let { ByteArray(0).apply(it) }.joinToString("") { "%02x".format(it) }

    @Test
    fun checkSingleByte() {
        val out = byteArrayOf(MessageTagToByteMap.PASSWORD)
        assertEquals("70".length / 2, out.size)
    }
}

private object MessageTagToByteMap {
    const val PASSWORD: Byte = 0x70
}