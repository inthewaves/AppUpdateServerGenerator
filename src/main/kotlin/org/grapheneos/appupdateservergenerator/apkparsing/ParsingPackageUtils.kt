package org.grapheneos.appupdateservergenerator.apkparsing

object ParsingPackageUtils {
    /**
     * https://android.googlesource.com/platform/frameworks/base/+/7b02279e0ab3000f1d353992e9059db5d5b3cbfd/core/java/android/content/pm/parsing/ParsingPackageUtils.java#2600
     */
    fun validateName(name: String, requireSeparator: Boolean, requireFilename: Boolean): Result<Unit?> {
        val N = name.length
        var hasSep = false
        var front = true
        for (i in 0 until N) {
            val c = name[i]
            if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z') {
                front = false
                continue
            }
            if (!front) {
                if (c >= '0' && c <= '9' || c == '_') {
                    continue
                }
            }
            if (c == '.') {
                hasSep = true
                front = true
                continue
            }
            return Result.failure(Throwable("bad character '$c'"))
        }
        if (requireFilename && !FileUtils.isValidExtFilename(name)) {
            return Result.failure(Throwable("Invalid filename"))
        }
        return if (hasSep || !requireSeparator) {
            Result.success(null)
        } else {
            Result.failure(Throwable("must have at least one '.' separator"))
        }
    }
}