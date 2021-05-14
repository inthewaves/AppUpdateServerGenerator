package org.grapheneos.appupdateservergenerator.commands

import kotlinx.cli.*
import kotlinx.coroutines.runBlocking
import org.grapheneos.appupdateservergenerator.apkparsing.AAPT2Invoker
import org.grapheneos.appupdateservergenerator.apkparsing.ApkSignerInvoker
import org.grapheneos.appupdateservergenerator.crypto.OpenSSLInvoker
import org.grapheneos.appupdateservergenerator.crypto.PKCS8PrivateKeyFile
import org.grapheneos.appupdateservergenerator.files.FileManager
import org.grapheneos.appupdateservergenerator.repo.AppRepoManager
import java.io.File
import java.io.IOException
import kotlin.system.exitProcess

@OptIn(ExperimentalCli::class)
class InsertApkCommand : Subcommand("insert-apk", "Inserts an APK into the local repository") {
    private val keyFile: String by option(
        ArgType.String,
        description = "A decrypted key in PKCS8 format used to sign the metadata. Only RSA and EC keys are supported",
        fullName = "key-file",
        shortName = "k"
    ).required()
    private val userSpecifiedRepoDirectory: String? by option(
        ArgType.String,
        description = "The directory to use for the app repo. Defaults to working directory.",
        fullName = "repo-directory",
        shortName = "d"
    )
    private val apkFilePaths: List<String> by argument(
        ArgType.String,
        description = "The APK file(s) to insert into the repository",
        fullName = "apk-file"
    ).vararg()

    private val aaptInvoker = AAPT2Invoker()
    private val apkSignerInvoker = ApkSignerInvoker()
    private val openSSLInvoker = OpenSSLInvoker()

    private fun printErrorAndExit(errorMessage: String?, cause: Throwable? = null): Nothing {
        errorMessage?.let { println("error: $it") } ?: println("error encountered when trying to insert APK(s)")
        cause?.printStackTrace()
        exitProcess(1)
    }

    override fun execute() = runBlocking {
        if (!aaptInvoker.isExecutablePresent()) {
            printErrorAndExit("unable to locate aapt2 at ${aaptInvoker.executablePath}; please add it to your PATH variable")
        }
        if (!apkSignerInvoker.isExecutablePresent()) {
            printErrorAndExit("unable to locate apksigner at ${aaptInvoker.executablePath}; please add it to your PATH variable")
        }
        if (!openSSLInvoker.isExecutablePresent()) {
            printErrorAndExit("unable to locate openssl at ${openSSLInvoker.executablePath}")
        }
        val fileManager = try {
            userSpecifiedRepoDirectory?.let { FileManager(File(it)) } ?: FileManager()
        } catch (e: IOException) {
            printErrorAndExit("failed to create root dir $userSpecifiedRepoDirectory", e)
        }

        val signingPrivateKey: PKCS8PrivateKeyFile = try {
            openSSLInvoker.getKeyWithType(File(keyFile))
        } catch (e: IOException) {
            printErrorAndExit("failed to parse key type from provided key file", e)
        }

        println("input signing key is of type $signingPrivateKey")

        val appRepoManager = AppRepoManager(
            fileManager = fileManager,
            aaptInvoker = aaptInvoker,
            apkSignerInvoker = apkSignerInvoker,
            openSSLInvoker = openSSLInvoker,
            signingPrivateKey = signingPrivateKey
        )
        try {
            appRepoManager.insertApksFromStringPaths(apkFilePaths)
        } catch (e: Exception) {
            printErrorAndExit(e.message, e)
        }
    }
}