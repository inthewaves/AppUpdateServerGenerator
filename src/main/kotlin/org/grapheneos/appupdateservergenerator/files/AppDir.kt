package org.grapheneos.appupdateservergenerator.files

import java.io.File

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
}