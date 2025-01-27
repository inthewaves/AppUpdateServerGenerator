package org.grapheneos.appupdateservergenerator.repo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.yield
import org.grapheneos.appupdateservergenerator.db.DbWrapper
import org.grapheneos.appupdateservergenerator.files.AppDir
import org.grapheneos.appupdateservergenerator.files.FileManager
import org.grapheneos.appupdateservergenerator.model.PackageApkGroup
import org.grapheneos.appupdateservergenerator.model.PackageName
import org.grapheneos.appupdateservergenerator.model.VersionCode
import org.grapheneos.appupdateservergenerator.util.ArchivePatcherUtil
import java.io.File
import java.io.IOException
import java.text.DecimalFormat
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.system.measureTimeMillis

class DeltaGenerationManager(
    private val fileManager: FileManager,
) {
    companion object {
        private const val DEFAULT_MAX_PREVIOUS_VERSION_DELTAS = 5
        private const val MAX_CONCURRENT_DELTA_GENERATION = 5
        /** Amount of space to leave free in the temp dir during delta generation. */
        private const val TEMP_DIR_FREE_SPACE_SAFETY_BYTES = 200 shl 20 // 200 MiB

        private const val MAX_DEFERRALS_PER_APK_WHEN_NO_FREE_SPACE = 20
        private val DELTA_GEN_DEFERRAL_DELAY_MILLIS = TimeUnit.SECONDS.toMillis(30L)
        private val deltaSizeDecimalFormat = DecimalFormat("#.###")
    }

    /**
     * A [Mutex] to control asynchronous printing so that print messages don't leak into the editor when the user is
     * prompted to edit some text such as release notes.
     */
    val asyncPrintMutex = Mutex()
    private val deltaGenerationSemaphore = Semaphore(permits = MAX_CONCURRENT_DELTA_GENERATION)

    private val dbWrapper = DbWrapper.getInstance(fileManager)

    sealed interface GenerationRequest {
        @JvmInline
        value class ForPackage(val pkg: PackageName) : GenerationRequest
        object StartPrinting : GenerationRequest
    }

    sealed interface PrintRequest {
        class NewPackage(val pkg: PackageName, val numberOfDeltas: Int) : PrintRequest
        class DeltaFinished(val pkg: PackageName, val success: Boolean) : PrintRequest
        object ProgressPrint : PrintRequest
        @JvmInline
        value class NewLine(val string: String) : PrintRequest
        @JvmInline
        value class NewLines(val strings: Array<out String>) : PrintRequest
    }

    fun SendChannel<PrintRequest>.trySendNewLine(line: String) = trySend(PrintRequest.NewLine(line))

    fun SendChannel<PrintRequest>.trySendNewLines(vararg lines: String) = trySend(PrintRequest.NewLines(lines))

    private fun CoroutineScope.launchPrintJobAndChannel(): Pair<Job, Channel<PrintRequest>> {
        val printRequestChannel = Channel<PrintRequest>(capacity = Channel.UNLIMITED)
        val printJob = launch(start = CoroutineStart.LAZY) {
            var lastProgressMessage = ""
            var totalNumberOfDeltasToGenerate = 0L
            var numberOfDeltasGenerated = 0L
            val packageToDeltasLeftMap = sortedMapOf<PackageName, Int>()

            val progressAnimationDot = "."
            val progressAnimationMaxDots = 4
            val progressAnimationMaxDotSize = progressAnimationDot.length * progressAnimationMaxDots

            val terminalWidth = AtomicInteger(100)
            // we don't support windows, but why not check it
            val isWindows = System.getProperty("os.name").lowercase().contains("windows")
            if (!isWindows) {
                launch {
                    var numFailures = 0
                    while (isActive) {
                        ProcessBuilder("bash", "-c", "tput cols 2> /dev/tty").start().apply {
                            val colWidth = inputStream.use { it.readBytes() }.decodeToString().trim().toIntOrNull()
                            waitFor(1, TimeUnit.SECONDS)
                            if (colWidth == null) {
                                numFailures++
                            } else {
                                terminalWidth.set(colWidth)
                            }
                        }
                        delay(2000L)

                        if (numFailures >= 10) {
                            break
                        }
                    }
                }
            }

            fun printOnSameLine(stringToPrint: String, carriageReturn: Boolean) {
                val extraSpaceNeeded = lastProgressMessage.length - stringToPrint.length
                val lineToPrint = buildString {
                    if (carriageReturn) {
                        append("\r$stringToPrint")
                    } else {
                        append(stringToPrint)
                    }
                    if (extraSpaceNeeded > 0) {
                        append(" ".repeat(extraSpaceNeeded) + "\b".repeat(extraSpaceNeeded))
                    }
                }
                print(lineToPrint)
                lastProgressMessage = stringToPrint
            }
            fun createProgressLine() = if (totalNumberOfDeltasToGenerate > 0) {
                val terminalWidthNow = terminalWidth.get()
                buildString(capacity = terminalWidthNow) {
                    val percentage = DecimalFormat.getPercentInstance().format(
                        numberOfDeltasGenerated / totalNumberOfDeltasToGenerate.toDouble()
                    )
                    append("generated delta $numberOfDeltasGenerated of $totalNumberOfDeltasToGenerate ($percentage)")
                    if (packageToDeltasLeftMap.keys.isNotEmpty()) {
                        append(" --- ${packageToDeltasLeftMap.size} packages left")
                        val distanceFromWidth: Int = terminalWidthNow - (length + 1 + progressAnimationMaxDotSize * 3)
                        if (distanceFromWidth > 0) {
                            val packages = packageToDeltasLeftMap.keys.joinToString()
                            append(": ")
                            append(packages.substring(0, min(packages.length, distanceFromWidth)))
                        }
                    }
                }
            } else {
                ""
            }
            fun printProgress(carriageReturn: Boolean) {
                printOnSameLine(createProgressLine(), carriageReturn)
            }
            fun printNewLine(printMessage: String) {
                val firstLine = printMessage.lineSequence().first()
                val extraSpaceNeeded = lastProgressMessage.length - firstLine.length
                if (extraSpaceNeeded > 0) {
                    println('\r' + firstLine
                            + " ".repeat(extraSpaceNeeded)
                            + "\b".repeat(extraSpaceNeeded))
                } else {
                    println('\r' + firstLine)
                }
                if (printMessage.contains('\n')) {
                    // note: lineSequence with a \n at the end has a sequence where the last element is just empty
                    printMessage.lineSequence().drop(1).forEach(::println)
                }
                printProgress(false)
            }

            launch {
                var numDots = 0
                while (isActive) {
                    asyncPrintMutex.withLock {
                        if (numberOfDeltasGenerated == totalNumberOfDeltasToGenerate) return@withLock
                        if (numDots > progressAnimationMaxDots) numDots = 1
                        printOnSameLine(
                            createProgressLine() + " "
                                    + progressAnimationDot.repeat(numDots).padEnd(progressAnimationMaxDotSize),
                            carriageReturn = true,
                        )
                        numDots++
                    }
                    delay(350L)
                }
            }

            for (printRequest in printRequestChannel) {
                asyncPrintMutex.withLock {
                    when (printRequest) {
                        is PrintRequest.ProgressPrint -> printProgress(true)
                        is PrintRequest.NewLines -> printRequest.strings.forEach { printNewLine(it) }
                        is PrintRequest.NewLine -> printNewLine(printRequest.string)
                        is PrintRequest.DeltaFinished -> {
                            if (printRequest.success) numberOfDeltasGenerated++

                            val numDeltasLeftForPackage = packageToDeltasLeftMap[printRequest.pkg] ?: return@withLock
                            if (numDeltasLeftForPackage - 1 <= 0) {
                                packageToDeltasLeftMap.remove(printRequest.pkg)
                            } else {
                                packageToDeltasLeftMap[printRequest.pkg] = numDeltasLeftForPackage - 1
                            }
                            printProgress(true)
                        }
                        is PrintRequest.NewPackage -> {
                            totalNumberOfDeltasToGenerate += printRequest.numberOfDeltas
                            packageToDeltasLeftMap[printRequest.pkg] = printRequest.numberOfDeltas
                            printProgress(true)
                        }
                    }
                }
            }
            println()
            cancel()
        }

        return printJob to printRequestChannel
    }

    private data class DeltaInfo(
        val packageName: PackageName,
        val baseVersion: VersionCode,
        val targetVersion: VersionCode
    )

    fun CoroutineScope.launchDeltaGenerationActor(): SendChannel<GenerationRequest> {
        return actor(capacity = Channel.UNLIMITED) {
            val failedDeltaAppsMutex = Mutex()
            val failedDeltaApps = ArrayList<AppDir>()
            val anyDeltasGenerated = AtomicBoolean(false)
            val (printJob, printRequestChannel) = launchPrintJobAndChannel()

            val originalFreeSpace = fileManager.systemTempDir.freeSpace
            coroutineScope {
                for (request in channel) {
                    if (request is GenerationRequest.ForPackage) {
                        val appDir = fileManager.getDirForApp(request.pkg)
                        launch {
                            try {
                                val apks = PackageApkGroup.fromDirDescending(appDir)
                                val deltaInfos: List<DeltaInfo> = regenerateDeltas(
                                    apks,
                                    originalFreeSpace = originalFreeSpace,
                                    printChannel = printRequestChannel
                                )
                                if (deltaInfos.isNotEmpty()) {
                                    anyDeltasGenerated.set(true)
                                }
                            } catch (e: Throwable) {
                                printRequestChannel.trySend(PrintRequest.NewLines(
                                    arrayOf(
                                        "error during delta generation for ${appDir.packageName}",
                                        e.stackTraceToString()
                                    )
                                ))
                                printRequestChannel.trySend(PrintRequest.DeltaFinished(
                                    appDir.packageName,
                                    success = false
                                ))

                                failedDeltaAppsMutex.withLock { failedDeltaApps.add(appDir) }
                            }
                        }
                    } else {
                        check(request is GenerationRequest.StartPrinting)
                        printJob.start()
                    }
                }
            }
            printRequestChannel.close()
            printJob.join()

            if (anyDeltasGenerated.get()) {
                if (failedDeltaApps.isEmpty()) {
                    println("delta generation successful")
                } else {
                    println("delta generation complete, but some failed to generate: " +
                            "${failedDeltaApps.map { it.packageName }}")
                }
            }
        }
    }

    private val estimatedTotalSpaceUsageForCurrentDeltas = AtomicLong(0L)
    /**
     * Generates multiple deltas with the APK with the highest version code as the target and the top
     * [maxPreviousVersions] APKs as the bases.
     *
     * @return The version codes for which a delta is available to patching to create the highest version code APK
     * @throws IOException if an I/O error occurs
     */
    private suspend fun regenerateDeltas(
        apks: PackageApkGroup.DescendingOrder,
        originalFreeSpace: Long,
        maxPreviousVersions: Int = DEFAULT_MAX_PREVIOUS_VERSION_DELTAS,
        printChannel: SendChannel<PrintRequest>?
    ): List<DeltaInfo> = coroutineScope {
        if (apks.size <= 1) {
            return@coroutineScope emptyList<DeltaInfo>()
        }

        val newestApk = apks.highestVersionApk!!
        val numberOfDeltasToGenerate = Integer.min(apks.size - 1, maxPreviousVersions)
        printChannel?.trySend(PrintRequest.NewPackage(apks.packageName, numberOfDeltasToGenerate))
        yield()

        var deltaGenTime = 0L
        val deltasInProgressMutex = Mutex()
        val deltasInProgress = mutableSetOf<File>()
        return@coroutineScope try {
            apks.sortedApks.asSequence()
                .filter { it.versionCode != newestApk.versionCode }
                .take(numberOfDeltasToGenerate)
                .mapTo(ArrayList(numberOfDeltasToGenerate)) { previousApk ->
                    val outputDeltaFile = fileManager.getDeltaFileForApp(
                        apks.packageName,
                        previousApk.versionCode,
                        newestApk.versionCode
                    )
                    deltasInProgressMutex.withLock { deltasInProgress.add(outputDeltaFile) }
                    val estimatedSizeUsageForDeltas =
                        (1.05 * ArchivePatcherUtil.estimateTempSpaceNeededForGeneration(previousApk, newestApk)).toLong()
                    async {
                        var numDefersDueToFreeSpace = 0L
                        while (isActive) {
                            deltaGenerationSemaphore.withPermit {
                                // Determine whether generating this delta will exceed the space in the temp dir.
                                // If it will make the temp dir run out of space, then defer the delta generation
                                // repeatedly (up to a limit) and release the semaphore to let another request have a
                                // go at it.
                                var estimatedTotalSpaceUsage: Long? = null
                                try {
                                    estimatedTotalSpaceUsage = estimatedTotalSpaceUsageForCurrentDeltas
                                        .addAndGet(estimatedSizeUsageForDeltas)
                                    if (
                                        estimatedTotalSpaceUsage > originalFreeSpace - TEMP_DIR_FREE_SPACE_SAFETY_BYTES ||
                                        fileManager.systemTempDir.freeSpace < TEMP_DIR_FREE_SPACE_SAFETY_BYTES
                                    ) {
                                        if (numDefersDueToFreeSpace < MAX_DEFERRALS_PER_APK_WHEN_NO_FREE_SPACE) {
                                            printChannel?.trySendNewLines(
                                                "warning: there might not be free space left",
                                                " deferring ${outputDeltaFile.name} (${apks.packageName}) " +
                                                        "(times deferred: $numDefersDueToFreeSpace)"
                                            )
                                            numDefersDueToFreeSpace++
                                            // This releases the semaphore, and then this coroutine will be
                                            // delayed after the withPermit block.
                                            return@withPermit
                                        }

                                        printChannel?.trySendNewLine(
                                            "warning: there might not be free space left for " +
                                                    "${outputDeltaFile.name} (${apks.packageName}), " +
                                                    "but maximum deferrals reached " +
                                                    "($MAX_DEFERRALS_PER_APK_WHEN_NO_FREE_SPACE)"
                                        )
                                    }
                                    printChannel?.trySendNewLine(
                                        "generating ${outputDeltaFile.name} (${apks.packageName})"
                                    )

                                    yield()
                                    printChannel?.trySend(PrintRequest.ProgressPrint)
                                    deltaGenTime += measureTimeMillis {
                                        ArchivePatcherUtil.generateDelta(
                                            previousApk.apkFile,
                                            newestApk.apkFile,
                                            outputDeltaFile,
                                            outputGzip = true
                                        )
                                    }
                                    deltasInProgressMutex.withLock { deltasInProgress.remove(outputDeltaFile) }
                                } finally {
                                    if (estimatedTotalSpaceUsage != null) {
                                        estimatedTotalSpaceUsageForCurrentDeltas.addAndGet(-estimatedSizeUsageForDeltas)
                                    }
                                }

                                printChannel?.apply {
                                    val formattedSizeInMib = deltaSizeDecimalFormat.format(
                                        outputDeltaFile.length().toDouble() / (0x1 shl 20)
                                    )
                                    trySendNewLine(
                                        "finished ${outputDeltaFile.name} (${apks.packageName}) " +
                                                "($formattedSizeInMib MiB) --- took $deltaGenTime ms"
                                    )
                                    trySend(PrintRequest.DeltaFinished(apks.packageName, success = true))
                                }

                                // break out of the while loop
                                return@async DeltaInfo(
                                    previousApk.packageName,
                                    previousApk.versionCode,
                                    newestApk.versionCode,
                                )
                            }

                            if (deltaGenerationSemaphore.availablePermits != MAX_CONCURRENT_DELTA_GENERATION) {
                                delay(DELTA_GEN_DEFERRAL_DELAY_MILLIS)
                            }
                        }
                        ensureActive()
                        error("unreachable --- loop finished implies isActive is false implies ensureActive() throws")
                    }
                }
                .awaitAll()
                .also { deltaAvailableVersions ->
                    printChannel?.trySendNewLine(
                        "finished ${deltaAvailableVersions.size} deltas for ${apks.packageName}. " +
                                "Version codes with deltas available: ${deltaAvailableVersions.map { it.baseVersion.code }}"
                    )
                    printChannel?.trySendNewLine("deleting old deltas for ${apks.packageName}")
                    fileManager.getDirForApp(apks.packageName).allDeltasAsSequence()
                        .filter { it.targetVersion != newestApk.versionCode }
                        .forEach { it.delta.delete() }
                }
        } catch (e: Throwable) {
            deltasInProgressMutex.withLock {
                deltasInProgress.forEach { unfinishedDeltaFile ->
                    printChannel?.trySendNewLine(
                        "deleting $unfinishedDeltaFile for ${apks.packageName} due to failure"
                    )
                    try {
                        unfinishedDeltaFile.delete()
                    } catch (error: SecurityException) {
                        printChannel?.trySendNewLine(
                            "failed to delete $unfinishedDeltaFile for ${apks.packageName} after failure"
                        )
                        e.addSuppressed(error)
                    }
                }
            }
            throw e
        }
    }
}