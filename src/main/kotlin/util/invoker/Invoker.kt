package util.invoker

import java.nio.file.Path

open class Invoker(protected open val executablePath: Path) {
    fun isExecutablePresent(): Boolean {
        ProcessBuilder().apply {
            command("command", "-v", executablePath.toString())
        }.start().apply {
            waitFor()
            return exitValue() == 0
        }
    }
}