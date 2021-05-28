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
    help = """Adds one or more APKs to the repository.
        
        This handles metadata and delta generation. If the repository directories don't already exist, new directories
        will be created. This will not delete / move the source APK provided to the tool. This command will ask for
        release notes for every package being inserted and only for the most recent version of a package.
        
        Warnings will be printed when there are updates in groups where some of the packages are not updated. For 
        example, if there is a groupId "G" tagged onto packages A, B, and C, and if you insert new APKs for packages A
        and B, then there will be warnings about missing an update for package C. These warnings occur because it is
        expected that clients atomically update all packages in a group. See the group command for more detail about
        groups.
    """.trimMargin()
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