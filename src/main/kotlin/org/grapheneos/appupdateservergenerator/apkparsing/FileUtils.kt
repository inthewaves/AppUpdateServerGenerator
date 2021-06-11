package org.grapheneos.appupdateservergenerator.apkparsing

import java.nio.charset.StandardCharsets

/**
 * https://android.googlesource.com/platform/frameworks/base/+/7b02279e0ab3000f1d353992e9059db5d5b3cbfd/core/java/android/os/FileUtils.java
 */
object FileUtils {
    /**
     * Check if given filename is valid for an ext4 filesystem.
     */
    fun isValidExtFilename(name: String?): Boolean {
        return name != null && name == buildValidExtFilename(name)
    }

    /**
     * Mutate the given filename to make it valid for an ext4 filesystem,
     * replacing any invalid characters with "_".
     */
    fun buildValidExtFilename(name: String): String {
        if (name.isEmpty() || "." == name || ".." == name) {
            return "(invalid)"
        }
        val res = StringBuilder(name.length)
        for (i in 0 until name.length) {
            val c = name[i]
            if (isValidExtFilenameChar(c)) {
                res.append(c)
            } else {
                res.append('_')
            }
        }
        trimFilename(res, 255)
        return res.toString()
    }

    private fun isValidExtFilenameChar(c: Char): Boolean {
        return when (c) {
            '\u0000', '/' -> false
            else -> true
        }
    }

    private fun trimFilename(res: StringBuilder, maxBytes: Int) {
        var maxBytes = maxBytes
        var raw: ByteArray = res.toString().toByteArray(StandardCharsets.UTF_8)
        if (raw.size > maxBytes) {
            maxBytes -= 3
            while (raw.size > maxBytes) {
                res.deleteCharAt(res.length / 2)
                raw = res.toString().toByteArray(StandardCharsets.UTF_8)
            }
            res.insert(res.length / 2, "...")
        }
    }
}