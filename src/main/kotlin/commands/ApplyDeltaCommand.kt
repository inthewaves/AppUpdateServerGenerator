package commands

import com.google.archivepatcher.applier.FileByFileV1DeltaApplier
import kotlinx.cli.ArgType
import kotlinx.cli.ExperimentalCli
import kotlinx.cli.Subcommand
import kotlinx.cli.default
import util.ArchivePatcherUtil
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream

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