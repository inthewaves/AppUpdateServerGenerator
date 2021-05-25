package org.grapheneos.appupdateservergenerator.commands

import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import kotlinx.coroutines.runBlocking
import org.grapheneos.appupdateservergenerator.crypto.PKCS8PrivateKeyFile
import java.io.File
import java.io.IOException

class AddApksCommand : AppRepoSubcommand(
    help = """Adds multiple APKs to the repository."""
) {
    val privateSigningKeyFile: File by option(names = arrayOf("--signing-key", "-k"))
        .file(mustExist = true, canBeDir = false, mustBeReadable = true)
        .required()
    val apkFilePaths: List<File> by argument("APKS")
        .file(mustExist = true, canBeDir = false, mustBeReadable = true)
        .multiple(required = true)

    override fun runAfterInvokerChecks() = runBlocking {
        val signingPrivateKey: PKCS8PrivateKeyFile = try {
            openSSLInvoker.getKeyWithType(privateSigningKeyFile)
        } catch (e: IOException) {
            printErrorAndExit("failed to parse key type from provided key file", e)
        }
        println("input signing key is of type $signingPrivateKey")
        try {
            appRepoManager.insertApks(apkFilePaths, signingPrivateKey)
        } catch (e: Exception) {
            printErrorAndExit(e.message, e)
        }
    }
}