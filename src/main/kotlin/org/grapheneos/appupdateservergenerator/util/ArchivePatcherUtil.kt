package org.grapheneos.appupdateservergenerator.util

import com.google.archivepatcher.applier.FileByFileV1DeltaApplier
import com.google.archivepatcher.generator.FileByFileV1DeltaGenerator
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Utilities for archive-patcher
 */
object ArchivePatcherUtil {
    @Throws(IOException::class)
    fun applyDelta(oldFile: File, deltaFile: File, outputFile: File, isDeltaGzipped: Boolean) {
        val inputStream = if (isDeltaGzipped) {
            GZIPInputStream(FileInputStream(deltaFile))
        } else {
            FileInputStream(deltaFile)
        }
        inputStream.use { inputPatchDelta ->
            outputFile.outputStream().use { newFileOutputStream ->
                FileByFileV1DeltaApplier().applyDelta(
                    oldFile,
                    inputPatchDelta,
                    newFileOutputStream
                )
            }
        }
    }

    @Throws(IOException::class, InterruptedException::class)
    fun generateDelta(oldFile: File, newFile: File, outputDeltaFile: File, outputGzip: Boolean) {
        val outputStream = if (outputGzip) {
            GZIPOutputStream(outputDeltaFile.outputStream())
        } else {
            outputDeltaFile.outputStream()
        }
        outputStream.use { deltaOutputStream ->
            FileByFileV1DeltaGenerator().generateDelta(oldFile, newFile, deltaOutputStream)
            deltaOutputStream.flush()
        }
    }
}