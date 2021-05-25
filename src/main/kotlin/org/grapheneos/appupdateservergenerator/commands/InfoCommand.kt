package org.grapheneos.appupdateservergenerator.commands

import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.types.enum
import kotlinx.coroutines.runBlocking

class InfoCommand : AppRepoSubcommand(name = "info", help = "Commands to get information about the repository.") {
    private enum class InfoType { GROUPS, PACKAGES }
    private val infoType: InfoType by argument().enum { it.name.lowercase() }

    override fun runAfterInvokerChecks() = runBlocking {
        when (infoType) {
            InfoType.GROUPS -> appRepoManager.printAllGroups()
            InfoType.PACKAGES -> appRepoManager.printAllPackages()
        }
    }
}