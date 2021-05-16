package org.grapheneos.appupdateservergenerator.files

import org.grapheneos.appupdateservergenerator.files.FileManager.Companion.DELTA_REGEX
import org.grapheneos.appupdateservergenerator.model.VersionCode
import java.io.File
import java.io.FileFilter
import java.io.IOException

/**
 * Represents a directory in which an app's APKs and latest version metadata are stored.
 *
 * @see FileManager.getDirForApp
 */
@JvmInline
value class AppDir(val dir: File) {
    init {
        if (dir.exists()) {
            require(dir.isDirectory)
        }
    }

    val packageName: String get() = dir.name

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
                .let { VersionCode(it.groupValues[1].toInt()) to VersionCode(it.groupValues[2].toInt()) }
        }
}