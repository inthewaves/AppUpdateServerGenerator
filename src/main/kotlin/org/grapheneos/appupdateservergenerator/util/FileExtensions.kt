package org.grapheneos.appupdateservergenerator.util

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * Generates a digest of the file using the specified [messageDigest].
 *
 * @throws IOException if an I/O error occurs.
 */
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

/**
 * Prepends the [line] to this file. The implementation creates a temporary file, appends the [line] to the temp file,
 * copies the rest of the original contents under it, and then overwrites the original file with the temp file.
 * This file should be a plaintext file.
 *
 * @throws IOException if an I/O error occurs (especially around creating temporary files).
 */
fun File.prependLine(line: String) {
    val tempFile = Files.createTempFile(
        "temp-prependLine-${line.hashCode()}-${System.currentTimeMillis()}",
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
            println("failed to delete tempfile $tempFile: $e")
            e.printStackTrace()
        }
    }
}
