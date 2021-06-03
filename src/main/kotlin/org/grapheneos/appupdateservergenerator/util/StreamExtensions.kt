package org.grapheneos.appupdateservergenerator.util

import java.io.InputStream
import java.io.OutputStream

/**
 * Writes a 32-bit integer in Little Endian byte order to `this` [OutputStream]
 */
fun OutputStream.writeInt32Le(int: Int) {
    write(int)
    write(int ushr 8)
    write(int ushr 16)
    write(int ushr 24)
}

/**
 * Reads a 32-bit integer in Little Endian byte order from `this` [InputStream].
 */
fun InputStream.readInt32Le(): Int {
    var int = 0
    for (byteNumber in 0 until 32 step 8) {
        val byte = read()
        if (byte == -1) {
            return int
        }
        int = int or (byte shl byteNumber)
    }
    return int
}
