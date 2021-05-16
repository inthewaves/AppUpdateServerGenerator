package org.grapheneos.appupdateservergenerator.commands

import kotlinx.cli.ExperimentalCli
import kotlinx.coroutines.runBlocking

private const val DESCRIPTION =
    "Validates the repository contents. " +
        "Validations include: Metadata consistency, signatures, APK details, delta application, checksums"

@OptIn(ExperimentalCli::class)
class ValidateCommand : AppRepoSubcommand(name = "validate", actionDescription = DESCRIPTION) {
    override fun executeAfterInvokerChecks(): Unit = runBlocking {
        try {
            appRepoManager.validateRepo()
        } catch (e: Exception) {
            printErrorAndExit(e.message, e)
        }
    }
}