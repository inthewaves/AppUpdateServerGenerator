package org.grapheneos.appupdateservergenerator.util

import java.io.IOException

fun Process.readTextFromErrorStream(): String = try {
    errorStream.bufferedReader().readText()
} catch (e: IOException) {
    "error trying to read error from process"
}