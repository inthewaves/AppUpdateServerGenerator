package org.grapheneos.appupdateservergenerator

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.grapheneos.appupdateservergenerator.commands.AddCommand
import org.grapheneos.appupdateservergenerator.commands.ApplyDeltaCommand
import org.grapheneos.appupdateservergenerator.commands.EditCommand
import org.grapheneos.appupdateservergenerator.commands.GenerateDeltaCommand
import org.grapheneos.appupdateservergenerator.commands.GroupCommand
import org.grapheneos.appupdateservergenerator.commands.ListPackagesCommand
import org.grapheneos.appupdateservergenerator.commands.ValidateRepoCommand
import java.security.Security
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    Security.addProvider(BouncyCastleProvider())
    // if (!DefaultDeflateCompatibilityWindow().isCompatible) {
    //     System.err.println("Warning: zlib not compatible on this system")
    // }
    AppRepo()
        .subcommands(
            AddCommand(),
            ValidateRepoCommand(),
            GroupCommand.createWithSubcommands(),
            EditCommand.createWithSubcommands(),
            ListPackagesCommand(),
            ApplyDeltaCommand(),
            GenerateDeltaCommand(),
        )
        .main(args)

    exitProcess(0)
}

class AppRepo : CliktCommand(
    name = "appservergen",
    help = """This is a command-line tool to manage a repository for an Android app update server."""
) {
    override fun run() {
    }
}

