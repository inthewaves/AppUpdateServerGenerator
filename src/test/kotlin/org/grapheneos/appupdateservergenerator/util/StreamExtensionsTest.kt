package org.grapheneos.appupdateservergenerator.util

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ArgumentsSource
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals

internal class StreamExtensionsTest {
    @ArgumentsSource(IntegerArgumentProvider::class)
    @ParameterizedTest
    fun testWriteAndReadInt32LE(int: Int) {
        val output = ByteArrayOutputStream(4)
        output.writeInt32Le(int)
        val bytes = output.toByteArray()
        assertEquals(int, bytes.inputStream().readInt32Le())
    }
}