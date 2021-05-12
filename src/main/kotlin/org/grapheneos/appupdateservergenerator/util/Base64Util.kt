package org.grapheneos.appupdateservergenerator.util

import kotlin.math.ceil

object Base64Util {
    /**
     * Returns the length of a base64 encoding of a [ByteArray] that is [originalLength] bytes long.
     * This is derived from the fact that base64 encodes 3 bytes into 4 bytes.
     *
     * Alternatively, (originalLength + 2 - ((originalLength + 2) % 3)) / 3 * 4
     */
    fun getBase64SizeForLength(originalLength: Int): Int = ceil(originalLength / 3.0).toInt() * 4
}