package org.grapheneos.appupdateservergenerator.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.coroutines.runBlocking
import org.grapheneos.appupdateservergenerator.crypto.PKCS8PrivateKeyFile
import org.grapheneos.appupdateservergenerator.model.VersionCode
import java.io.File
import java.io.IOException

class EditCommand private constructor(): CliktCommand(name = "edit", help = "Commands to edit the repository directly.") {
    companion object {
        fun createWithSubcommands() = EditCommand()
            .subcommands(ResignMetadataCommand(), ReleaseNotesCommand())
    }

    class ResignMetadataCommand : AppRepoSubcommand(
        name = "resign",
        help = """Parses a package's metadata and then signs it again.
        """.trimIndent()
    ) {
        private val privateSigningKeyFile: File by option(names = arrayOf("--signing-key", "-k"))
            .file(mustExist = true, canBeDir = false, mustBeReadable = true)
            .required()
        private val packageToEdit: String by argument(
            name = "package",
            help = "The package to edit",
        )

        override fun runAfterInvokerChecks() = runBlocking {
            val signingPrivateKey: PKCS8PrivateKeyFile = try {
                openSSLInvoker.getKeyWithType(privateSigningKeyFile)
            } catch (e: IOException) {
                printErrorAndExit("failed to parse key type from provided key file", e)
            }

            appRepoManager.resignMetadataForPackage(
                pkg = packageToEdit,
                signingPrivateKey = signingPrivateKey
            )
        }
    }

    class ReleaseNotesCommand : AppRepoSubcommand(
        name = "release-notes",
        help = """
        Commands to edit or remove release notes.
    """.trimIndent()
    ) {
        private val privateSigningKeyFile: File by option(names = arrayOf("--signing-key", "-k"))
            .file(mustExist = true, canBeDir = false, mustBeReadable = true)
            .required()

        private val versionCode: VersionCode? by option(
            names = arrayOf("--versioncode", "-c"),
            help = "The version code to edit release notes for. Defaults to the most recent version."
        ).int().convert { VersionCode(it) }

        private val delete: Boolean by option(
            names = arrayOf("--delete"),
            help = "Whether to delete the release notes for the given package."
        ).flag()

        private val packageToEdit: String by argument(
            name = "package",
            help = "The package to edit",
        )

        override fun runAfterInvokerChecks() = runBlocking {
            val signingPrivateKey: PKCS8PrivateKeyFile = try {
                openSSLInvoker.getKeyWithType(privateSigningKeyFile)
            } catch (e: IOException) {
                printErrorAndExit("failed to parse key type from provided key file", e)
            }

            val metadata = try {
                appRepoManager.getMetadataForPackage(packageToEdit)
                    ?: throw IOException("unable to find package $packageToEdit")
            } catch (e: IOException) {
                printErrorAndExit(e.message, e)
            }

            if (delete) {
                if (metadata.latestRelease().releaseNotes == null) {
                    println("not deleting: $packageToEdit already has no release notes")
                } else {
                    println("deleting release notes for $packageToEdit")
                    appRepoManager.editReleaseNotesForPackage(
                        pkg = packageToEdit,
                        delete = true,
                        signingPrivateKey = signingPrivateKey
                    )
                }
                return@runBlocking
            }

            appRepoManager.editReleaseNotesForPackage(
                pkg = packageToEdit,
                delete = false,
                versionCode = versionCode,
                signingPrivateKey = signingPrivateKey
            )
        }
    }

    override fun run() {
    }
}