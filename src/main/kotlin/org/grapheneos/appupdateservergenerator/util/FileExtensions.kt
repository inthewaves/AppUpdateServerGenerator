package org.grapheneos.appupdateservergenerator.util

import org.grapheneos.appupdateservergenerator.files.TempFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.DigestInputStream
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

/**
 * Generates a digest of the file using the specified [messageDigest].
 *
 * @throws IOException if an I/O error occurs.
 */
fun File.digest(messageDigest: MessageDigest): ByteArray {
    DigestInputStream(inputStream().buffered(DEFAULT_BUFFER_SIZE), messageDigest).use { inputStream ->
        val unusedBuffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var bytes = inputStream.read(unusedBuffer)
        while (bytes >= 0) {
            bytes = inputStream.read(unusedBuffer)
        }
        return messageDigest.digest()
    }
}

/**
 * Generates a digest of the file using the specified [digestAlgorithm].
 *
 * @throws IOException if an I/O error occurs.
 * @throws NoSuchAlgorithmException if no Provider supports a MessageDigestSpi implementation for the specified
 * algorithm
 */
fun File.digest(digestAlgorithm: String): ByteArray = digest(MessageDigest.getInstance(digestAlgorithm))

/**
 * Prepends the [string] to this file. The implementation creates a temporary file, appends the [string] to the temp
 * file, copies the rest of the original contents to the end of the [string], and then overwrites the original file with
 * the temp file. This [File] should be a plaintext file.
 *
 * The caller can decide if the [string] should be a new line or if the contents of the file should just be appended to
 * the end of the file by choosing to include a line feed (`\n`) character in the [string].
 *
 * @throws IOException if an I/O error occurs (especially around creating temporary files).
 */
fun File.prependString(string: String) {
    TempFile.create("temp-prependLine").useFile { tempFile ->
        // Prepend the line
        tempFile.outputStream().bufferedWriter().use {
            it.append(string)
        }
        // Add the rest of the file
        FileOutputStream(tempFile, true).buffered().use { output ->
            this.inputStream().buffered().use { it.copyTo(output) }
        }
        tempFile.copyTo(this, overwrite = true)
    }
}

/**
 * Edits the file so that the first line is removed, and then returns the first line. If the file is empty, then null
 * is returned.
 *
 * @throws IOException
 */
fun File.removeFirstLine(): String? {
    TempFile.create("temp-removeFirstLine").useFile { tempFile ->
        val firstLine = bufferedReader().use { it.readLine() } ?: return null
        var offset: Long = 0
        // Find the place where the first line stops
        this.bufferedReader().use { reader ->
            var lastCharRead: Char?
            do {
                lastCharRead = reader.read().let { charAsInt ->
                    if (charAsInt == -1) {
                        null
                    } else {
                        offset++

                        charAsInt.toChar()
                    }
                }
            } while (lastCharRead != null && lastCharRead != '\n' && lastCharRead != '\r')
            if (offset == 0L) {
                return null
            }

            // Account for CRLF
            if (lastCharRead == '\r') {
                lastCharRead = reader.read().takeUnless { it == -1 }?.toChar()
                if (lastCharRead == '\n') {
                    offset++
                }
            }
        }

        tempFile.outputStream().buffered().use { output ->
            this.inputStream().buffered().use { input ->
                input.skip(offset)
                input.copyTo(output)
            }
        }
        tempFile.copyTo(this, overwrite = true)

        return firstLine
    }
}
