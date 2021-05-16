package org.grapheneos.appupdateservergenerator.files

import java.io.Closeable
import java.io.File
import java.nio.file.Files

@JvmInline
value class TempFile private constructor(val tmpFile: File): Closeable {
    companion object {
        fun create(prefix: String, suffix: String? = null): TempFile =
            TempFile(Files.createTempFile(prefix, suffix).toFile().apply { deleteOnExit() })
    }

    inline fun <T> useFile(block: (File) -> T): T = use { block(it.tmpFile) }

    override fun close() {
        try {
            tmpFile.delete()
        } catch (e: SecurityException) {
            println("warning: unable to delete temp file $tmpFile")
            e.printStackTrace()
        }
    }
}