package org.grapheneos.appupdateservergenerator.commands

import kotlinx.cli.ExperimentalCli
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalCli::class)
class ValidateCommand : AppRepoSubcommand(name = "validate", actionDescription = "Validates the repository contents") {
    override fun executeAfterInvokerChecks(): Unit = runBlocking {
        try {
            appRepoManager.validateRepo()
        } catch (e: Exception) {
            printErrorAndExit(e.message, e)
        }
    }
}