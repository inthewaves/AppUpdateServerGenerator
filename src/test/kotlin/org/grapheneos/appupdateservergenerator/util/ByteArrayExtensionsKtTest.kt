package org.grapheneos.appupdateservergenerator.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ArgumentsSource

internal class ByteArrayExtensionsKtTest {
    @ArgumentsSource(IntegerArgumentProvider::class)
    @ParameterizedTest
    fun testWriteAndReadInt32LE(int: Int) {
        val output = int.writeInt32Le()
        assertEquals(int, output.readInt32Le())
    }
}