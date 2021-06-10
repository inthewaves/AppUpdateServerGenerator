package org.grapheneos.appupdateservergenerator.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class HexStringTest {
    @Test
    fun testHexEncoding() {
        val hexBytes = ByteArray(16) { it.toByte() }
        assertEquals(HexString.fromHex("000102030405060708090a0b0c0d0e0f"), hexBytes.encodeToHexString())

        val singleByte = ByteArray(1) { 5.toByte() }
        assertEquals(HexString.fromHex("05"), HexString.encodeFromBytes(singleByte))
    }
}