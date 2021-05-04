package util

import com.google.archivepatcher.applier.FileByFileV1DeltaApplier
import com.google.archivepatcher.generator.FileByFileV1DeltaGenerator
import java.io.File
import java.io.FileInputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Utilities for archive-patcher
 */
object ArchivePatcherUtil {
    fun applyDelta(oldFile: File, deltaFile: File, outputFile: File, isDeltaGzipped: Boolean) {
        val inputStream = if (isDeltaGzipped) {
            GZIPInputStream(FileInputStream(deltaFile))
        } else {
            FileInputStream(deltaFile)
        }
        inputStream.use { inputPatchDelta ->
            outputFile.outputStream().use { newFileOut ->
                FileByFileV1DeltaApplier().applyDelta(
                    oldFile,
                    inputPatchDelta,
                    newFileOut
                )
            }
        }
    }

    fun generateDelta(oldFile: File, newFile: File, outputDeltaFile: File, outputGzip: Boolean) {
        val outputStream = if (outputGzip) {
            GZIPOutputStream(outputDeltaFile.outputStream())
        } else {
            outputDeltaFile.outputStream()
        }
        outputStream.use { patchOut ->
            FileByFileV1DeltaGenerator().generateDelta(oldFile, newFile, patchOut)
            patchOut.flush()
        }
    }
}