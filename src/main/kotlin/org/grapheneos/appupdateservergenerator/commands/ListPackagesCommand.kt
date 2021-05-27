package org.grapheneos.appupdateservergenerator.commands

import kotlinx.coroutines.runBlocking

class ListPackagesCommand : AppRepoSubcommand(name = "list-packages", help = "Lists all packages in the repo") {
    override fun runAfterInvokerChecks() = runBlocking {
        appRepoManager.printAllPackages()
    }
}