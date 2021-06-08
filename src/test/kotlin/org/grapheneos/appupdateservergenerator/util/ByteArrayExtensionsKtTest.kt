package org.grapheneos.appupdateservergenerator.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ArgumentsSource

internal class ByteArrayExtensionsKtTest {
    @ArgumentsSource(IntegerArgumentProvider::class)
    @ParameterizedTest
    fun testWriteAndReadInt32LE(int: Int) {
        val output = int.writeInt32Le()
        assertEquals(int, output.readInt32Le())

        val outputWithOffsetAtIndex4 = ByteArray(16)
        output.copyInto(outputWithOffsetAtIndex4, destinationOffset = 4)
        assertEquals(int, outputWithOffsetAtIndex4.readInt32Le(offset = 4))
    }

    @Test
    fun testWriteInt32LE() {
        val testValue = 0x12345678
        val stored = testValue.writeInt32Le()
        val expected = byteArrayOf(0x78, 0x56, 0x34, 0x12)
        assertEquals(expected.asList(), stored.asList())
    }

    @Test
    fun testReadInt32LE() {
        val expectedValue = 0x12345678
        val actualValue = byteArrayOf(0x78, 0x56, 0x34, 0x12).readInt32Le()
        assertEquals(expectedValue, actualValue)
    }

    @Test
    fun testReadInt16LE() {
        val expectedValue = 0x00005678
        val actualValue = byteArrayOf(0x78, 0x56).readInt16Le()
        assertEquals(expectedValue, actualValue)
    }

    @Test
    fun testReadInt16LEAndJoinValues() {
        val expectedValue = 0x12345678
        val leastSignificantBytes = byteArrayOf(0x78, 0x56).readInt16Le()
        val mostSignificantBytes = byteArrayOf(0x34, 0x12).readInt16Le()
        assertEquals(expectedValue, (mostSignificantBytes shl 16) or leastSignificantBytes)
    }
}