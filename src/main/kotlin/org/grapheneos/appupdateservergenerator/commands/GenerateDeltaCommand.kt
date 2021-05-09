package org.grapheneos.appupdateservergenerator.commands

import kotlinx.cli.ArgType
import kotlinx.cli.ExperimentalCli
import kotlinx.cli.Subcommand
import org.grapheneos.appupdateservergenerator.util.ArchivePatcherUtil
import java.io.File

@OptIn(ExperimentalCli::class)
class GenerateDeltaCommand : Subcommand("generate-delta", "Generate deltas directly") {
    private val oldFile by argument(ArgType.String, description = "The old file to serve as the basis for delta generation.")
    private val newFile by argument(ArgType.String, description = "The new file to serve as the target of the delta")
    private val outputDelta by argument(ArgType.String, description = "The output delta file")
    private val noGzip: Boolean? by option(
        ArgType.Boolean,
        description = "By default, deltas are gzip-compressed. This flag disables gzip compression.",
        fullName = "no-gzip"
    )

    override fun execute() {
        ArchivePatcherUtil.generateDelta(
            File(oldFile),
            File(newFile),
            File(outputDelta),
            outputGzip = !(noGzip ?: false)
        )
    }
}