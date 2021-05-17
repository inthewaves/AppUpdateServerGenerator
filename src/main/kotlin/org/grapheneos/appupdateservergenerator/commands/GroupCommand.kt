package org.grapheneos.appupdateservergenerator.commands

import kotlinx.cli.ArgType
import kotlinx.cli.optional
import kotlinx.cli.required
import kotlinx.cli.vararg
import kotlinx.coroutines.runBlocking
import org.grapheneos.appupdateservergenerator.crypto.PKCS8PrivateKeyFile
import java.io.File
import java.io.IOException
import kotlin.system.exitProcess

class GroupCommand : AppRepoSubcommand(name = "group", actionDescription = "Manages groups") {
    private val keyFile: String by option(
        ArgType.String,
        description = "A decrypted key in PKCS8 DER format used to sign the metadata. Only RSA and EC keys are supported",
        fullName = "key-file",
        shortName = "k"
    ).required()

    private val remove: Boolean? by option(
        ArgType.Boolean,
        shortName = "r",
        fullName = "remove",
        description = "Whether to remove the groups from the given packages."
    )

    private val groupIdForCreate: String? by option(
        ArgType.String,
        shortName = "c",
        fullName = "create",
        description = "Given a groupId, adds the packages to an existing group or a new group if it doesn't already exist"
    )

    private val groupIdForAdd: String? by option(
        ArgType.String,
        shortName = "a",
        fullName = "add",
        description = "Adds the packages to an existing group from a groupId"
    )

    private val groupIdForDelete: String? by option(
        ArgType.String,
        fullName = "delete",
        description = "Deletes the groupId from the repository."
    )

    private val packagePaths: List<String> by argument(
        ArgType.String,
        description = "The package(s) to edit group information of. Needs to be provided for the --create and --add options",
        fullName = "packages"
    ).vararg().optional()

    override fun printErrorAndExit(errorMessage: String?, cause: Throwable?): Nothing {
        errorMessage?.let { println("error: $it") } ?: println("error during repo validation")
        exitProcess(1)
    }

    override fun executeAfterInvokerChecks() = runBlocking {
        val optionsGiven = sequenceOf(remove, groupIdForAdd, groupIdForCreate, groupIdForDelete).count { it != null }
        if (optionsGiven > 1) {
            printErrorAndExit("too many options given", null)
        } else if (optionsGiven < 1) {
            printErrorAndExit("not enough options given", null)
        }

        val signingPrivateKey: PKCS8PrivateKeyFile = try {
            openSSLInvoker.getKeyWithType(File(keyFile))
        } catch (e: IOException) {
            printErrorAndExit("failed to parse key type from provided key file", e)
        }

        println("input signing key is of type $signingPrivateKey")
        try {
            when {
                remove == true -> appRepoManager.removeGroupFromPackages(packagePaths, signingPrivateKey)
                groupIdForAdd != null -> {
                    if (packagePaths.isEmpty()) {
                        printErrorAndExit("need to provide packages for this option", null)
                    }

                    appRepoManager.setGroupForPackages(
                        groupId = groupIdForAdd!!,
                        packages = packagePaths,
                        signingPrivateKey = signingPrivateKey,
                        createNewGroupIfNotExists = false
                    )
                }
                groupIdForCreate != null -> {
                    if (packagePaths.isEmpty()) {
                        printErrorAndExit("need to provide packages for this option", null)
                    }

                    appRepoManager.setGroupForPackages(
                        groupId = groupIdForCreate!!,
                        packages = packagePaths,
                        signingPrivateKey = signingPrivateKey,
                        createNewGroupIfNotExists = true
                    )
                }
                groupIdForDelete != null -> {
                    appRepoManager.deleteGroup(
                        groupIdForDelete!!,
                        signingPrivateKey
                    )
                }
                else -> printErrorAndExit("must use one of the remove or add options", null)
            }
        } catch (e: Exception) {
            printErrorAndExit(e.message, e)
        }
    }
}