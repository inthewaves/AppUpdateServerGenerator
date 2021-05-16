package org.grapheneos.appupdateservergenerator

import kotlinx.cli.ArgParser
import kotlinx.cli.ExperimentalCli
import org.grapheneos.appupdateservergenerator.commands.ApplyDeltaCommand
import org.grapheneos.appupdateservergenerator.commands.GenerateDeltaCommand
import org.grapheneos.appupdateservergenerator.commands.InsertApkCommand
import org.grapheneos.appupdateservergenerator.commands.ValidateCommand
import kotlin.system.exitProcess

@OptIn(ExperimentalCli::class)
fun main(args: Array<String>) {
    // if (!DefaultDeflateCompatibilityWindow().isCompatible) {
    //     System.err.println("Warning: zlib not compatible on this system")
    // }

    val parser = ArgParser(programName = "appservergen")
    parser.subcommands(GenerateDeltaCommand(), ApplyDeltaCommand(), InsertApkCommand(), ValidateCommand())
    // force the help command to show up if the user doesn't supply arguments
    parser.parse(if (args.isNotEmpty()) args else arrayOf("-h"))

    exitProcess(0)
}
