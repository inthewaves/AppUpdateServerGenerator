package org.grapheneos.appupdateservergenerator.files

import org.grapheneos.appupdateservergenerator.files.FileManager.Companion.DELTA_REGEX
import org.grapheneos.appupdateservergenerator.model.PackageName
import org.grapheneos.appupdateservergenerator.model.VersionCode
import java.io.File
import java.io.FileFilter
import java.io.IOException

/**
 * Represents a directory in which an app's APKs and latest version metadata are stored.
 *
 * @throws IOException if the given [dir] exists but is not a directory
 * @see FileManager.getDirForApp
 */
@JvmInline
value class AppDir(val dir: File) {
    init {
        if (dir.exists()) {
            if (!dir.isDirectory) throw IOException("trying to create AppDir $dir but it exists and is not a directory")
        }
    }

    data class DeltaFile private constructor(
        val delta: File,
        val packageName: PackageName,
        val baseVersion: VersionCode,
        val targetVersion: VersionCode
    ) {
        companion object {
            private const val DELTA_FILE_FORMAT = "delta-%d-to-%d.gz"

            /**
             * Returns a [Sequence] of [DeltaFile]s from the [appDir]. This includes deltas for all versions.
             *
             * @throws IOException
             */
            fun allFromAppDir(appDir: AppDir): Sequence<DeltaFile> {
                val allDeltas = appDir.dir.listFiles(
                    FileFilter { it.isFile && DELTA_REGEX.matchEntire(it.name) != null}
                ) ?: throw IOException("unable to list deltas for $appDir")

                return allDeltas.asSequence()
                    .mapNotNull { deltaFile ->
                        val match = DELTA_REGEX.matchEntire(deltaFile.name) ?: return@mapNotNull null
                        val baseVersion = VersionCode(match.groupValues[1].toLong())
                        val targetVersionFromFile = VersionCode(match.groupValues[2].toLong())
                        DeltaFile(deltaFile, appDir.packageName, baseVersion, targetVersionFromFile)
                    }
            }
        }
    }

    val packageName: PackageName get() = PackageName(dir.name)

    /**
     * Lists the APK files in this app directory. As this uses [File.listFiles], "there is no guarantee that the name
     * strings in the resulting array will appear in any specific order; they are not, in particular, guaranteed to
     * appear in alphabetical order."
     *
     * @throws IOException
     */
    fun listApkFilesUnsorted(): Array<File> =
        dir.listFiles(FileFilter { it.isFile && FileManager.APK_REGEX.matches(it.name)})
            ?: throw IOException("unable to list APK files for $dir")

    fun allDeltasAsSequence(): Sequence<DeltaFile> = DeltaFile.allFromAppDir(this)

    /**
     * Lists the delta files in this app directory. As this uses [File.listFiles], "there is no guarantee that the name
     * strings in the resulting array will appear in any specific order; they are not, in particular, guaranteed to
     * appear in alphabetical order."
     *
     * @throws IOException
     */
    fun listDeltaFilesUnsorted(): Array<File> =
        dir.listFiles(FileFilter { it.isFile && DELTA_REGEX.matchEntire(it.name) != null})
            ?: throw IOException("unable to list delta files for $dir")

    /**
     * @throws IOException
     */
    fun listDeltaFilesUnsortedMappedToVersion(): Map<Pair<VersionCode, VersionCode>, File> =
        listDeltaFilesUnsorted().associateBy { deltaFile ->
            DELTA_REGEX
                .matchEntire(deltaFile.name)!!
                .let { VersionCode(it.groupValues[1].toLong()) to VersionCode(it.groupValues[2].toLong()) }
        }
}