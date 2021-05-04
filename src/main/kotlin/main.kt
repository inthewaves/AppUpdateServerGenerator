import commands.ApplyDeltaCommand
import commands.GenerateDeltaCommand
import commands.InsertApkCommand
import kotlinx.cli.ArgParser
import kotlinx.cli.ExperimentalCli
import util.FileManager

@OptIn(ExperimentalCli::class)
fun main(args: Array<String>) {
    // if (!DefaultDeflateCompatibilityWindow().isCompatible) {
        // System.err.println("Warning: zlib not compatible on this system")
        // exitProcess(1)
    // }
    val parser = ArgParser(programName = "app-update-server-generator")
    parser.subcommands(GenerateDeltaCommand(), ApplyDeltaCommand(), InsertApkCommand())
    // force the help command to show up if the user doesn't supply arguments
    parser.parse(if (args.isNotEmpty()) args else arrayOf("-h"))
    println("data root dir: ${FileManager().dataRootDirectory}")
}
