package org.grapheneos.appupdateservergenerator.util

fun Int.writeInt32Le(): ByteArray = ByteArray(4) { index -> (this ushr (index * 8)).toByte() }

fun ByteArray.readInt32Le(): Int = foldIndexed(0) { index: Int, acc: Int, byte: Byte ->
    acc or ((byte.toInt() and 0xFF) shl index * 8)
}