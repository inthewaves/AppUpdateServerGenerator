package org.grapheneos.appupdateservergenerator.commands

import kotlinx.cli.ArgType
import kotlinx.cli.ExperimentalCli
import kotlinx.cli.Subcommand
import org.grapheneos.appupdateservergenerator.util.ArchivePatcherUtil
import java.io.File

@OptIn(ExperimentalCli::class)
class ApplyDeltaCommand : Subcommand("apply-delta", "Apply deltas directly") {
    private val oldFile by argument(ArgType.String, description = "The old file to serve as the basis for delta generation.")
    private val deltaFile by argument(ArgType.String, description = "The delta file")
    private val newFileName by argument(ArgType.String, description = "The output file after delta application")
    private val notGzipped: Boolean? by option(
        ArgType.Boolean,
        description = "Whether the input patch is gzip-compressed",
        fullName = "no-gzip"
    )

    override fun execute() {
        ArchivePatcherUtil.applyDelta(
            File(oldFile),
            File(deltaFile),
            File(newFileName),
            isDeltaGzipped = !(notGzipped ?: false)
        )
    }
}