package org.grapheneos.appupdateservergenerator.util

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

/**
 * Generates a digest of the [InputStream] using the specified [digestAlgorithm].
 *
 * **Note**: It is the caller's responsibility to close this [InputStream].
 *
 * @throws IOException if an I/O error occurs.
 * @throws NoSuchAlgorithmException if no Provider supports a MessageDigestSpi implementation for the specified
 * algorithm
 */
fun InputStream.digest(digestAlgorithm: String): ByteArray = digest(MessageDigest.getInstance(digestAlgorithm))

/**
 * Generates a digest of the [InputStream] using the specified [messageDigest].
 *
 * **Note**: It is the caller's responsibility to close this [InputStream].
 *
 * @throws IOException if an I/O error occurs.
 */
fun InputStream.digest(messageDigest: MessageDigest): ByteArray {
    DigestOutputStream(OutputStream.nullOutputStream(), messageDigest).use { outputStream ->
        this@digest.copyTo(outputStream)
        return messageDigest.digest()
    }
}

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
