package org.grapheneos.appupdateservergenerator.util

import java.io.IOException
import java.security.NoSuchAlgorithmException

fun byteArrayOfChars(vararg chars: Char) = ByteArray(chars.size) { chars[it].code.toByte() }

fun byteArrayOfInts(vararg ints: Int) = ByteArray(ints.size) { ints[it].toByte() }

/**
 * Creates a [ByteArray] with `this` [Int] as a 32-bit  Little Endian integer.
 */
fun Int.writeInt32Le(): ByteArray = ByteArray(4) { index -> (this ushr (index * 8)).toByte() }

/**
 * Reads a 32-bit integer stored in Little Endian order from `this` [ByteArray], using the given [offset] if provided
 * (defaults to the start of the byte array). 4 bytes will be read from `this` array.
 *
 * @throws IndexOutOfBoundsException if the integer range `offset..(offset + 3)` is not a subset of the
 * [ByteArray.indices]
 */
fun ByteArray.readInt32Le(offset: Int = 0): Int {
    var int = (get(offset + 0).toInt() and 0xFF) shl 0
    int = int or ((get(offset + 1).toInt() and 0xFF) shl 8)
    int = int or ((get(offset + 2).toInt() and 0xFF) shl 16)
    return int or ((get(offset + 3).toInt() and 0xFF) shl 24)
}

/**
 * Generates a digest of the [ByteArray] using the specified [digestAlgorithm].
 *
 * @throws IOException if an I/O error occurs.
 * @throws NoSuchAlgorithmException if no Provider supports a MessageDigestSpi implementation for the specified
 * algorithm
 */
fun ByteArray.digest(digestAlgorithm: String): ByteArray = inputStream().digest(digestAlgorithm)
