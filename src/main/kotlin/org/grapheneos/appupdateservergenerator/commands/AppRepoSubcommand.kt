package org.grapheneos.appupdateservergenerator.commands

import kotlinx.cli.ArgType
import kotlinx.cli.ExperimentalCli
import kotlinx.cli.Subcommand
import org.grapheneos.appupdateservergenerator.apkparsing.AAPT2Invoker
import org.grapheneos.appupdateservergenerator.apkparsing.ApkSignerInvoker
import org.grapheneos.appupdateservergenerator.crypto.OpenSSLInvoker
import org.grapheneos.appupdateservergenerator.files.FileManager
import org.grapheneos.appupdateservergenerator.repo.AppRepoManager
import java.io.File
import java.io.IOException
import kotlin.system.exitProcess

@OptIn(ExperimentalCli::class)
abstract class AppRepoSubcommand(name: String, actionDescription: String) : Subcommand(name, actionDescription) {
    protected val userSpecifiedRepoDirectory: String? by option(
        ArgType.String,
        description = "The directory to use for the app repo. Defaults to working directory.",
        fullName = "repo-directory",
        shortName = "d"
    )
    protected val aaptInvoker = AAPT2Invoker()
    protected val apkSignerInvoker = ApkSignerInvoker()
    protected val openSSLInvoker = OpenSSLInvoker()

    protected val fileManager by lazy {
        try {
            userSpecifiedRepoDirectory?.let { FileManager(File(it)) } ?: FileManager()
        } catch (e: IOException) {
            printErrorAndExit("failed to create root dir $userSpecifiedRepoDirectory", e)
        }
    }

    protected val appRepoManager by lazy {
        AppRepoManager(
            fileManager = fileManager,
            aaptInvoker = aaptInvoker,
            apkSignerInvoker = apkSignerInvoker,
            openSSLInvoker = openSSLInvoker
        )
    }

    protected fun printErrorAndExit(errorMessage: String?, cause: Throwable? = null): Nothing {
        errorMessage?.let { println("error: $it") } ?: println("error during repo validation")
        cause?.printStackTrace()
        exitProcess(1)
    }

    override fun execute() {
        if (!aaptInvoker.isExecutablePresent()) {
            printErrorAndExit("unable to locate aapt2 at ${aaptInvoker.executablePath}; please add it to your PATH variable")
        }
        if (!apkSignerInvoker.isExecutablePresent()) {
            printErrorAndExit("unable to locate apksigner at ${aaptInvoker.executablePath}; please add it to your PATH variable")
        }
        if (!openSSLInvoker.isExecutablePresent()) {
            printErrorAndExit("unable to locate openssl at ${openSSLInvoker.executablePath}")
        }
        executeAfterInvokerChecks()
    }

    abstract fun executeAfterInvokerChecks(): Unit
}