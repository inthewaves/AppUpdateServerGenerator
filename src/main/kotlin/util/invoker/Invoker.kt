package util.invoker

import java.nio.file.Path

open class Invoker(open val executablePath: Path) {
    fun isExecutablePresent(): Boolean =
        with(ProcessBuilder("command", "-v", executablePath.toString()).start()) {
            waitFor()
            return exitValue() == 0
        }

}