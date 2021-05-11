package org.grapheneos.appupdateservergenerator.util

import java.io.IOException
import java.nio.file.Path

open class Invoker(open val executablePath: Path) {
    @Throws(IOException::class)
    fun isExecutablePresent(): Boolean =
        with(ProcessBuilder("command", "-v", executablePath.toString()).start()) {
            waitFor()
            return exitValue() == 0
        }

}