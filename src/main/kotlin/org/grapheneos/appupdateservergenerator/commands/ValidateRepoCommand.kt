package org.grapheneos.appupdateservergenerator.commands

import kotlinx.coroutines.runBlocking

class ValidateRepoCommand : AppRepoSubcommand(name = "validate", help = "Validates the repository contents") {
    override fun runAfterInvokerChecks(): Unit = runBlocking {
        try {
            appRepoManager.validateRepo()
        } catch (e: Exception) {
            printErrorAndExit(e.message, e)
        }
    }
}