package org.grapheneos.appupdateservergenerator.files

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
}