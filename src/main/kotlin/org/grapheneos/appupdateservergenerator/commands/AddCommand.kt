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
        
        Ensure that you have enough space in the system's temporary directory (usually /tmp on Linux) for delta
        generation.
        
        When adding an APK for an app / package, a new release for the app is initiated. For each app that is being 
        updated, a prompt will appear to add new release notes for the (most recent) version. This means that if inserting
        APKs for x different apps, you will be prompted x times for each app. The --skip-notes (-s) option allows for 
        bypassing release notes. Release notes can be edited or added in later with the edit command.
        
        Warnings will be printed when there are updates in groups where some of the packages are not updated. For 
        example, if there is a groupId "G" tagged onto packages A, B, and C, and if you insert new APKs for packages A
        and B, then there will be warnings about missing an update for package C. These warnings occur because it is
        expected that clients atomically update all packages in a group. See the group command for more detail about
        groups.
    """.trimMargin()
) {
    private val privateSigningKeyFile: File by option(
        names = arrayOf("--signing-key", "-k"),
        help = "A decrypted key in PKCS8 DER format used to sign the metadata. Only RSA and EC keys are supported."
    ).file(mustExist = true, canBeDir = false, mustBeReadable = true)
        .required()
    private val apkFilePaths: List<File> by argument(name = "APKS", help = """
        A list of APK files to add to the repo. These must be new APK files; insertion will be rejected for an app if
        any of these APK files are older or matches a version of what's already in the repo. All of these APKs will have
        their signing details verified.
        
        These should be full APK files that can be installed without needing split APKs or patch expansion files (OBBs).
        (Split APKs and OBBs are not supported at this time.)
        
        APK Signing Scheme v4 signatures are optional. To include such a signature, the .apk.idsig files need to be in 
        the same directory as the APK with the same filename (without extension) as the APK for the tool to recognize it
        and include it in signature verification and metadata.
    """.trimIndent())
        .file(mustExist = true, canBeDir = false, mustBeReadable = true)
        .multiple(required = true)
    private val skipReleaseNotes: Boolean by option(
        names = arrayOf("--skip-notes", "-s"),
        help = """By default, a prompt will come up to enter release notes for all packages that are being inserted. Set
            this flag to disable release note prompting.
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