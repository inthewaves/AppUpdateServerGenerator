package org.grapheneos.appupdateservergenerator.commands

import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import kotlinx.coroutines.runBlocking
import org.grapheneos.appupdateservergenerator.crypto.PKCS8PrivateKeyFile
import java.io.File
import java.io.IOException

class AddCommand : AppRepoSubcommand(
    help = """Adds one or more APKs to the repository."""
) {
    private val privateSigningKeyFile: File by option(
        names = arrayOf("--signing-key", "-k"),
        help = "A decrypted key in PKCS8 DER format used to sign the metadata. Only RSA and EC keys are supported"
    ).file(mustExist = true, canBeDir = false, mustBeReadable = true)
        .required()
    private val apkFilePaths: List<File> by argument("APKS")
        .file(mustExist = true, canBeDir = false, mustBeReadable = true)
        .multiple(required = true)
    private val skipReleaseNotes: Boolean by option(
        names = arrayOf("--skip-notes", "-s"),
        help = """By default, a prompt will come up to enter release notes for all packages that are being inserted.
            There will only be one release note prompt per package (inserting multiple versions of a package
            will only prompt release notes for the latest version). Set this flag to disable release note prompting.
        """.trimIndent()
    ).flag()

    override fun runAfterInvokerChecks() = runBlocking {
        val signingPrivateKey: PKCS8PrivateKeyFile = try {
            openSSLInvoker.getKeyWithType(privateSigningKeyFile)
        } catch (e: IOException) {
            printErrorAndExit("failed to parse key type from provided key file", e)
        }
        println("input signing key is of type $signingPrivateKey")
        try {
            appRepoManager.insertApks(
                apkFilePaths = apkFilePaths,
                signingPrivateKey = signingPrivateKey,
                promptForReleaseNotes = !skipReleaseNotes
            )
        } catch (e: Exception) {
            printErrorAndExit(e.message, e)
        }
    }
}