package org.grapheneos.appupdateservergenerator.commands

import kotlinx.cli.ArgType
import kotlinx.coroutines.runBlocking

class InfoCommand: AppRepoSubcommand("info", "Commands for information") {
    private enum class InfoType { GROUPS, PACKAGES }

    private val option: InfoType by argument(
        ArgType.Choice<InfoType>(
            toVariant = { InfoType.valueOf(it.trim().uppercase()) },
            toString = { it.name.lowercase() }
        ),
        fullName = "info-type"
    )

    override fun executeAfterInvokerChecks() = runBlocking {
        when (option) {
            InfoType.GROUPS -> appRepoManager.printAllGroups()
            InfoType.PACKAGES -> appRepoManager.printAllPackages()
        }
    }
}