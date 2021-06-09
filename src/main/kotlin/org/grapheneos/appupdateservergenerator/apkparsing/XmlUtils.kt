package org.grapheneos.appupdateservergenerator.apkparsing

object XmlUtils {
    /**
     * https://android.googlesource.com/platform/frameworks/base/+/cc0a3a9bef94bd2ad7061a17f0a3297be5d7f270/core/java/com/android/internal/util/XmlUtils.java#98
     */
    fun convertValueToInt(charSeq: CharSequence?, defaultValue: Int): Int {
        if (charSeq.isNullOrEmpty()) {
            return defaultValue
        }
        val nm = charSeq.toString()

        // XXX This code is copied from Integer.decode() so we don't
        // have to instantiate an Integer!
        var value: Int
        var sign = 1
        var index = 0
        val len = nm.length
        var base = 10
        if ('-' == nm[0]) {
            sign = -1
            index++
        }
        if ('0' == nm[index]) {
            //  Quick check for a zero by itself
            if (index == len - 1) return 0
            val c = nm[index + 1]
            if ('x' == c || 'X' == c) {
                index += 2
                base = 16
            } else {
                index++
                base = 8
            }
        } else if ('#' == nm[index]) {
            index++
            base = 16
        }
        return nm.substring(index).toInt(base) * sign
    }
}