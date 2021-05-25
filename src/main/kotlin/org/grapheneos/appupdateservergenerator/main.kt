package org.grapheneos.appupdateservergenerator

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import org.grapheneos.appupdateservergenerator.commands.AddApksCommand
import org.grapheneos.appupdateservergenerator.commands.ApplyDeltaCommand
import org.grapheneos.appupdateservergenerator.commands.GenerateDeltaCommand
import org.grapheneos.appupdateservergenerator.commands.Group
import org.grapheneos.appupdateservergenerator.commands.InfoCommand
import org.grapheneos.appupdateservergenerator.commands.ValidateRepoCommand
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    // if (!DefaultDeflateCompatibilityWindow().isCompatible) {
    //     System.err.println("Warning: zlib not compatible on this system")
    // }

    AppRepo()
        .subcommands(
            AddApksCommand(),
            ValidateRepoCommand(),
            Group.create(),
            InfoCommand(),
            ApplyDeltaCommand(),
            GenerateDeltaCommand(),
        )
        .main(args)

    exitProcess(0)
}

class AppRepo : CliktCommand(
    help = """This is a command-line tool to manage a repository for an Android app update server."""
) {
    override fun run() {
    }
}

