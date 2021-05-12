package org.grapheneos.appupdateservergenerator.util

import java.io.IOException
import java.nio.file.Path

/**
 * A class that wraps around an executable (e.g., apksigner or openssl), providing type-safe methods for interacting
 * with an executable.
 */
open class Invoker(open val executablePath: Path) {
    /**
     * @throws IOException if unable to determine if the executable is present.
     */
    fun isExecutablePresent(): Boolean =
        with(ProcessBuilder("command", "-v", executablePath.toString()).start()) {
            waitFor()
            return exitValue() == 0
        }

}