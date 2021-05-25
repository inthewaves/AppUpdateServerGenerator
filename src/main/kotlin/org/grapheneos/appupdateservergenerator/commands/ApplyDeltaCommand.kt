package org.grapheneos.appupdateservergenerator.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import org.grapheneos.appupdateservergenerator.util.ArchivePatcherUtil
import java.io.File

class ApplyDeltaCommand : CliktCommand(name = "apply-delta", help = "Apply deltas directly") {
    private val oldFile: File by argument(help = "The old file to serve as the basis for delta generation.")
        .file(mustExist = true, canBeDir = false, mustBeReadable = true)
    private val deltaFile: File by argument(help = "The new file to serve as the target of the delta")
        .file(mustExist = true, canBeDir = false, mustBeReadable = true)
    private val outputFile: File by argument(help = "The output delta file")
        .file(canBeDir = false)
    private val noGzip: Boolean by option(
        names = arrayOf("-no-gzip"),
        help = "By default, deltas are gzip-compressed. This flag disables gzip compression."
    ).flag()

    override fun run() {
        ArchivePatcherUtil.applyDelta(
            oldFile,
            deltaFile,
            outputFile,
            isDeltaGzipped = !noGzip
        )
    }
}