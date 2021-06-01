package org.grapheneos.appupdateservergenerator.util

import com.google.archivepatcher.applier.FileByFileV1DeltaApplier
import com.google.archivepatcher.generator.FileByFileV1DeltaGenerator
import org.grapheneos.appupdateservergenerator.model.AndroidApk
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.max

/**
 * Utilities for the archive-patcher library.
 */
object ArchivePatcherUtil {
    fun estimateTempSpaceNeededForGeneration(baseApk: AndroidApk, targetApk: AndroidApk): Long {
        val suffixSortSize = 4 * (baseApk.apkFile.length() + 1)
        // We add up the base and target APK sizes, because the library copies them as RandomAccessFiles.
        return baseApk.apkFile.length() + targetApk.apkFile.length() + suffixSortSize
    }

    /**
     * Applies the [deltaFile] to the [oldFile] to create the [outputFile]. A [GZIPInputStream] will be wrapped if
     * [isDeltaGzipped] is true.
     *
     * @throws IOException if an I/O error occurs
     */
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

    /**
     * Generates a delta file using the [oldFile] as the base and the [newFile] as the target. The output will be
     * GZIP-compressed if [outputGzip] is true.
     *
     * @throws IOException if an I/O error occurs
     */
    fun generateDelta(
        oldFile: File,
        newFile: File,
        outputDeltaFile: File,
        outputGzip: Boolean
    ) {
        val outputStream = if (outputGzip) {
            GZIPOutputStream(outputDeltaFile.outputStream().buffered())
        } else {
            outputDeltaFile.outputStream().buffered()
        }

        outputStream.use { deltaOutputStream ->
            FileByFileV1DeltaGenerator().generateDelta(oldFile, newFile, deltaOutputStream)
            deltaOutputStream.flush()
        }
    }
}