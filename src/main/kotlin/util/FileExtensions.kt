package util

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
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

@Throws(IOException::class)
fun File.prependLine(line: String) {
    val tempFile = Files.createTempFile(
        "temp-${line.hashCode()}-${System.currentTimeMillis()}",
        null
    ).toFile().apply { deleteOnExit() }
    try {
        // Prepend the line
        tempFile.outputStream().bufferedWriter().use {
            it.appendLine(line)
            it.flush()
        }
        // Add the rest of the file
        FileOutputStream(tempFile, true).buffered().use { output ->
            this.inputStream().buffered().use { it.copyTo(output) }
        }
        tempFile.copyTo(this, overwrite = true)
    } finally {
        try {
            tempFile.delete()
        } catch (e: SecurityException) {
            println("failed to delete tempfile $tempFile")
        }
    }
}
