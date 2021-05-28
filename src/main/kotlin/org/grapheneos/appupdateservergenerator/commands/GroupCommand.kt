package org.grapheneos.appupdateservergenerator.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import kotlinx.coroutines.runBlocking
import org.grapheneos.appupdateservergenerator.crypto.PKCS8PrivateKeyFile
import java.io.File
import java.io.IOException

class GroupCommand private constructor(): CliktCommand(
    name = "group",
    help = """Manages the groups in the repository.
        
        Packages can be tagged with a groupId that indicates to clients about packages that should be atomically 
        installed / updated. This is useful when there are apps that have shared libraries such as Chromium.
        
        Note: On the serverside, groups are purely used as a tag. There is a warning from the CLI tool around groups in
        the add command, where you will be warned if you are only updating a proper subset of a group's packages and not
        all of them. It is the client's responsibility to download all APKs in a group that have updates and to install
        them atomically. For this reason, it's important that the updates for packages in a group are pushed to the
        server all at once.
        
        For example, for a new Chromium update, the `add` command should be run on the new APKs for WebView, the
        Trichrome Library, and the Chrome app.
    """.trimIndent()
) {
    override fun run() {
    }

    companion object {
        fun createWithSubcommands(): GroupCommand = GroupCommand()
            .subcommands(
                GroupSubcommand.Remove(),
                GroupSubcommand.Create(),
                GroupSubcommand.Add(),
                GroupSubcommand.ListGroups()
            )
    }
}

private open class GroupSubcommand(name: String?, help: String) : AppRepoSubcommand(name = name, help = help) {
    class Remove : GroupSubcommand(name = "remove", help = "Removes a group id") {
        override fun runAfterInvokerChecks() = runBlocking {
            val signingPrivateKey: PKCS8PrivateKeyFile = try {
                openSSLInvoker.getKeyWithType(keyFile)
            } catch (e: IOException) {
                printErrorAndExit("failed to parse key type from provided key file", e)
            }

            appRepoManager.deleteGroup(groupId, signingPrivateKey)
        }
    }
    class Create : GroupSubcommand(
        name = "create",
        help = "Creates a group with the given group id and adds packages to it"
    ) {
        private val packages: List<String> by argument(
            help = "The package(s) to add to this new group",
        ).multiple(required = true)

        override fun runAfterInvokerChecks() = runBlocking {
            appRepoManager.setGroupForPackages(groupId, packages, signingPrivateKey, createNewGroupIfNotExists = true)
        }
    }
    class Add : GroupSubcommand(name = "add", help = "Adds packages to an existing group id") {
        private val packages: List<String> by argument(
            help = "The package(s) to add to the given group",
        ).multiple(required = true)

        override fun runAfterInvokerChecks() = runBlocking {
            appRepoManager.setGroupForPackages(groupId, packages, signingPrivateKey, createNewGroupIfNotExists = false)
        }
    }
    class ListGroups : AppRepoSubcommand(name = "list", help = "Lists the groups in the repo") {
        override fun runAfterInvokerChecks() = runBlocking {
            appRepoManager.printAllGroups()
        }
    }

    protected val groupId: String by argument(name = "group-id")
    protected val keyFile: File by option(
        names = arrayOf("--key-file", "-k"),
        help = "A decrypted key in PKCS8 DER format used to sign the metadata. Only RSA and EC keys are supported"
    ).file(mustExist = true, canBeDir = false, mustBeReadable = true)
        .required()

    protected val signingPrivateKey: PKCS8PrivateKeyFile by lazy {
        try {
            openSSLInvoker.getKeyWithType(keyFile)
        } catch (e: IOException) {
            printErrorAndExit("failed to parse key type from provided key file", e)
        }
    }

    override fun runAfterInvokerChecks() {
    }
}