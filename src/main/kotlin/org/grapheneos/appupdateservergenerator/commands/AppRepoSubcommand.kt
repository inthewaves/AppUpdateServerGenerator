package org.grapheneos.appupdateservergenerator.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.defaultLazy
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import org.grapheneos.appupdateservergenerator.apkparsing.AAPT2Invoker
import org.grapheneos.appupdateservergenerator.crypto.OpenSSLInvoker
import org.grapheneos.appupdateservergenerator.files.FileManager
import org.grapheneos.appupdateservergenerator.repo.AppRepoManager
import java.io.File
import java.io.IOException
import kotlin.system.exitProcess

/**
 * @see [CliktCommand] for information on the rest of the arguments.
 */
abstract class AppRepoSubcommand(
    help: String = "",
    epilog: String = "",
    name: String? = null,
    invokeWithoutSubcommand: Boolean = false,
    printHelpOnEmptyArgs: Boolean = false,
    helpTags: Map<String, String> = emptyMap(),
    autoCompleteEnvvar: String? = "",
    allowMultipleSubcommands: Boolean = false,
    treatUnknownOptionsAsArgs: Boolean = false
) : CliktCommand(
    help,
    epilog,
    name,
    invokeWithoutSubcommand,
    printHelpOnEmptyArgs,
    helpTags,
    autoCompleteEnvvar,
    allowMultipleSubcommands,
    treatUnknownOptionsAsArgs
) {
    protected val userSpecifiedRepoDirectory: File? by option(
        names = arrayOf("--repo-directory", "-d"),
        help = "The directory to use for the app repo. Defaults to working directory.",
    ).file(canBeFile = false, canBeDir = true)

    protected val verbose: Boolean by option(
        names = arrayOf("--verbose", "-v"),
        help = "Whether to turn on verbosity (exception stack traces will be printed)"
    ).flag()

    protected val numJobs: Int by option(
        names = arrayOf("--jobs", "-j"),
        help = "Number of threads / jobs to use for the thread pool.",
    ).int().default(
        Runtime.getRuntime().availableProcessors() + 2,
        defaultForHelp = "Defaults to numCpus + 2 (${Runtime.getRuntime().availableProcessors() + 2})"
    )

    protected val aaptInvoker = AAPT2Invoker()
    protected val openSSLInvoker = OpenSSLInvoker()

    protected val fileManager by lazy {
        try {
            userSpecifiedRepoDirectory?.let { FileManager(it) } ?: FileManager()
        } catch (e: IOException) {
            printErrorAndExit("failed to create root dir $userSpecifiedRepoDirectory", e)
        }
    }

    protected val appRepoManager by lazy {
        AppRepoManager(
            fileManager = fileManager,
            aaptInvoker = aaptInvoker,
            openSSLInvoker = openSSLInvoker,
            numJobs = numJobs
        )
    }

    private fun printPossibleCausesForException(exception: Throwable, firstLineToPrint: String) {
        if (exception.message != null && !firstLineToPrint.endsWith(exception.message!!)) {
            println(exception.message)
        }
        if (verbose) exception.printStackTrace()
        exception.cause?.let { printPossibleCausesForException(it, firstLineToPrint) }
    }

    protected fun printErrorAndExit(errorMessage: String?, cause: Throwable? = null): Nothing {
        val firstLineToPrint = errorMessage?.let { "error: $it" } ?: "error during repo command $commandName"
        println(firstLineToPrint)
        cause?.let { printPossibleCausesForException(it, firstLineToPrint) }
        exitProcess(1)
    }

    override fun run() {
        if (!aaptInvoker.isExecutablePresent()) {
            printErrorAndExit("unable to locate aapt2 at ${aaptInvoker.executablePath}; please add it to your PATH variable")
        }
        if (!openSSLInvoker.isExecutablePresent()) {
            printErrorAndExit("unable to locate openssl at ${openSSLInvoker.executablePath}")
        }

        try {
            runAfterInvokerChecks()
        } catch (e: Exception) {
            printErrorAndExit(e.message, e)
        }
    }

    abstract fun runAfterInvokerChecks()
}