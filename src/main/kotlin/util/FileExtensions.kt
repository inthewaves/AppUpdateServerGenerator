package util

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest

@Throws(IOException::class)
fun File.digest(messageDigest: MessageDigest): ByteArray {
    DigestInputStream(inputStream().buffered(DEFAULT_BUFFER_SIZE), messageDigest).apply {
        val unusedBuffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var bytes = read(unusedBuffer)
        while (bytes >= 0) {
            bytes = read(unusedBuffer)
        }
        return messageDigest.digest()
    }
}
