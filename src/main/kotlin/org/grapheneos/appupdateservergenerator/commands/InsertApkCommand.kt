package org.grapheneos.appupdateservergenerator.commands

import kotlinx.cli.ArgType
import kotlinx.cli.ExperimentalCli
import kotlinx.cli.required
import kotlinx.cli.vararg
import kotlinx.coroutines.runBlocking
import org.grapheneos.appupdateservergenerator.crypto.PKCS8PrivateKeyFile
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCli::class)
class InsertApkCommand : AppRepoSubcommand("insert-apk", "Inserts an APK into the local repository") {
    private val keyFile: String by option(
        ArgType.String,
        description = "A decrypted key in PKCS8 format used to sign the metadata. Only RSA and EC keys are supported",
        fullName = "key-file",
        shortName = "k"
    ).required()
    private val apkFilePaths: List<String> by argument(
        ArgType.String,
        description = "The APK file(s) to insert into the repository",
        fullName = "apk-file"
    ).vararg()

    override fun executeAfterInvokerChecks() = runBlocking {
        val signingPrivateKey: PKCS8PrivateKeyFile = try {
            openSSLInvoker.getKeyWithType(File(keyFile))
        } catch (e: IOException) {
            printErrorAndExit("failed to parse key type from provided key file", e)
        }
        println("input signing key is of type $signingPrivateKey")
        try {
            appRepoManager.insertApksFromStringPaths(apkFilePaths, signingPrivateKey)
        } catch (e: Exception) {
            printErrorAndExit(e.message, e)
        }
    }
}