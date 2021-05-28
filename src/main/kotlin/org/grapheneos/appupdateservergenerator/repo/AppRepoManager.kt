package org.grapheneos.appupdateservergenerator.repo

import com.github.ajalt.clikt.output.TermUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.grapheneos.appupdateservergenerator.api.AppMetadata
import org.grapheneos.appupdateservergenerator.api.AppRepoIndex
import org.grapheneos.appupdateservergenerator.api.BulkAppMetadata
import org.grapheneos.appupdateservergenerator.apkparsing.AAPT2Invoker
import org.grapheneos.appupdateservergenerator.apkparsing.ApkSignerInvoker
import org.grapheneos.appupdateservergenerator.crypto.OpenSSLInvoker
import org.grapheneos.appupdateservergenerator.crypto.PEMPublicKey
import org.grapheneos.appupdateservergenerator.crypto.PKCS8PrivateKeyFile
import org.grapheneos.appupdateservergenerator.files.AppDir
import org.grapheneos.appupdateservergenerator.files.FileManager
import org.grapheneos.appupdateservergenerator.files.TempFile
import org.grapheneos.appupdateservergenerator.model.AndroidApk
import org.grapheneos.appupdateservergenerator.model.Base64String
import org.grapheneos.appupdateservergenerator.model.HexString
import org.grapheneos.appupdateservergenerator.model.PackageApkGroup
import org.grapheneos.appupdateservergenerator.model.UnixTimestamp
import org.grapheneos.appupdateservergenerator.model.VersionCode
import org.grapheneos.appupdateservergenerator.util.ArchivePatcherUtil
import org.grapheneos.appupdateservergenerator.util.Either
import org.grapheneos.appupdateservergenerator.util.asLeft
import org.grapheneos.appupdateservergenerator.util.asRight
import org.grapheneos.appupdateservergenerator.util.digest
import org.grapheneos.appupdateservergenerator.util.symmetricDifference
import java.io.File
import java.io.FileFilter
import java.io.FileNotFoundException
import java.io.IOException
import java.lang.Integer.min
import java.security.MessageDigest
import java.text.DecimalFormat
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.system.measureTimeMillis

/**
 * A general manager for the app repo.
 * Contains validation, APK insertion, metadata generation and signing
 */
interface AppRepoManager {
    /**
     * Inserts APKs from the provided [apkFilePaths]. The APKs will be first validated (ensure that they
     * verify with apksigner and that the contents of their manifests can be parsed).
     *
     * If [promptForReleaseNotes] is true, release notes will be prompted for every package.
     *
     * @throws AppRepoException
     * @throws IOException
     */
    suspend fun insertApks(
        apkFilePaths: Collection<File>,
        signingPrivateKey: PKCS8PrivateKeyFile,
        promptForReleaseNotes: Boolean
    )

    /**
     * Validates the entire repository to ensure that the metadata is consistent with the actual files in the repo,
     * and that the APK details are correct, and that the deltas actually apply to get the target files. This also
     * checks the signatures for the metadata using [FileManager.publicSigningKeyPem].
     *
     * @throws AppRepoException
     * @throws IOException
     */
    suspend fun validateRepo()

    /**
     * Sets the groupId for the packages / apps to the given [groupId].
     *
     * If [createNewGroupIfNotExists] is false and the group does not already exist (i.e. it's not being used), the
     * function will return an error.
     */
    suspend fun setGroupForPackages(
        groupId: String,
        packages: List<String>,
        signingPrivateKey: PKCS8PrivateKeyFile,
        createNewGroupIfNotExists: Boolean
    )

    /**
     * Removes the group IDs from the given [packages]
     */
    suspend fun removeGroupFromPackages(packages: List<String>, signingPrivateKey: PKCS8PrivateKeyFile)

    /**
     * Deletes the [groupId] from the repository.
     */
    suspend fun deleteGroup(groupId: String, signingPrivateKey: PKCS8PrivateKeyFile)

    suspend fun printAllPackages()

    suspend fun printAllGroups()

    fun doesPackageExist(pkg: String): Boolean

    fun getMetadataForPackage(pkg: String): AppMetadata

    suspend fun editReleaseNotesForPackage(pkg: String, delete: Boolean, signingPrivateKey: PKCS8PrivateKeyFile)
}

fun AppRepoManager(
    fileManager: FileManager,
    aaptInvoker: AAPT2Invoker,
    apkSignerInvoker: ApkSignerInvoker,
    openSSLInvoker: OpenSSLInvoker
): AppRepoManager = AppRepoManagerImpl(fileManager, aaptInvoker, apkSignerInvoker, openSSLInvoker)

/**
 * The implementation of [AppRepoManager].
 * @see AppRepoManager
 */
@ObsoleteCoroutinesApi
private class AppRepoManagerImpl(
    private val fileManager: FileManager,
    private val aaptInvoker: AAPT2Invoker,
    private val apkSignerInvoker: ApkSignerInvoker,
    private val openSSLInvoker: OpenSSLInvoker
): AppRepoManager {
    companion object {
        private const val DEFAULT_MAX_PREVIOUS_VERSION_DELTAS = 5
        private const val MAX_CONCURRENT_DELTA_GENERATION = 3
        private const val MAX_CONCURRENT_DELTA_APPLIES_FOR_VERIFICATION = 4

        private const val EDITOR_IGNORE_PREFIX = "//##:"
    }

    val deltaGenerationSemaphore = Semaphore(permits = MAX_CONCURRENT_DELTA_GENERATION)
    private val executorService: ExecutorService = Executors.newFixedThreadPool(64)
    private val repoDispatcher = executorService.asCoroutineDispatcher()

    /**
     * @see AppRepoManager.validateRepo
     */
    override suspend fun validateRepo(): Unit = withContext(repoDispatcher) {
        val packageDirs = fileManager.appDirectory.listFiles(FileFilter { it.isDirectory })
            ?.sortedBy { it.name }
            ?: throw IOException("unable to get directories")
        if (packageDirs.isEmpty()) {
            println("repo is empty")
            return@withContext
        }

        println("validating metadata consistency")
        val publicKey = fileManager.publicSigningKeyPem
        openSSLInvoker.verifyFileWithSignatureHeader(fileManager.appIndex, publicKey)
        openSSLInvoker.verifyFileWithSignatureHeader(fileManager.bulkAppMetadata, publicKey)

        val metadataFromDirs = AppMetadata.getAllAppMetadataFromDisk(fileManager)
        val packagesFromDirectoryListing = packageDirs.mapTo(sortedSetOf()) { it.name }
        val packagesFromAllMetadataOnDisk = metadataFromDirs.mapTo(sortedSetOf()) { it.packageName }
        if (packagesFromDirectoryListing != packagesFromAllMetadataOnDisk) {
            val problemDirectories = packagesFromDirectoryListing symmetricDifference packagesFromAllMetadataOnDisk
            throw AppRepoException.InvalidRepoState("some directories are not valid app directories: $problemDirectories")
        }

        val existingIndex = AppRepoIndex.readFromExistingIndexFile(fileManager)
        val packagesFromExistingIndex = existingIndex.packageToVersionMap.mapTo(sortedSetOf()) { it.key }
        if (packagesFromExistingIndex != packagesFromAllMetadataOnDisk) {
            throwExceptionWithMissingIntersectionElements(
                firstPackages = packagesFromExistingIndex,
                missingFromFirstError = "app directory has packages not included in the index",
                secondPackages = packagesFromAllMetadataOnDisk,
                missingFromSecondError = "index contains packages for which there are no directories for"
            )
        }

        val bulkAppMetadata = BulkAppMetadata.readFromExistingFile(fileManager).allAppMetadata
        if (bulkAppMetadata != metadataFromDirs) {
            throwExceptionWithMissingIntersectionElements(
                firstPackages = bulkAppMetadata.map { it.packageName},
                missingFromFirstError = "bulk app metadata file doesn't include packages",
                secondPackages = metadataFromDirs.map { it.packageName },
                missingFromSecondError = "missing latest.txt metadata for packages included in bulk app metadata file"
            )
        }

        check(packageDirs.size == metadataFromDirs.size)
        println("validating details, APKs, and delta files of " +
                "${packageDirs.size} ${if (packageDirs.size == 1) "app" else "apps"}")
        val appDirAndMetadata = packageDirs.zip(metadataFromDirs) { a, b -> check(a.name == b.packageName); a to b }
        coroutineScope {
            appDirAndMetadata.forEach { (appDir, metadata) ->
                launch { validateAppDir(AppDir(appDir), metadata, publicKey, existingIndex) }
            }
        }

        println("repo successfully validated")
    }

    private fun throwExceptionWithMissingIntersectionElements(
        firstPackages: Iterable<String>,
        missingFromFirstError: String,
        secondPackages: Iterable<String>,
        missingFromSecondError: String
    ): Nothing {
        val errorMessage = buildString {
            val intersection = firstPackages intersect secondPackages
            (firstPackages subtract intersection)
                .takeIf { it.isNotEmpty() }
                ?.let { appendLine("$missingFromSecondError: $it") }
            (secondPackages subtract intersection)
                .takeIf { it.isNotEmpty() }
                ?.let { appendLine("$missingFromFirstError: $it") }
        }.trimEnd('\n')
        throw AppRepoException.InvalidRepoState(errorMessage)
    }

    private suspend fun validateAppDir(
        appDir: AppDir,
        metadata: AppMetadata,
        publicKey: File,
        existingIndex: AppRepoIndex,
    ): Unit = coroutineScope {
        require(appDir.packageName == metadata.packageName)

        val metadataFile = fileManager.getLatestAppMetadata(metadata.packageName)
        openSSLInvoker.verifyFileWithSignatureHeader(metadataFile, publicKey)
        val pkg = metadata.packageName
        if (metadata.lastUpdateTimestamp > existingIndex.timestamp) {
            throw AppRepoException.InvalidRepoState(
                "$pkg: metadata timestamp (${metadata.lastUpdateTimestamp}) " +
                        "> index timestamp (${existingIndex.timestamp})"
            )
        }
        val apks = appDir.listApkFilesUnsorted().sortedBy { it.nameWithoutExtension }
        if (apks.isEmpty()) {
            throw AppRepoException.InvalidRepoState("$pkg: app directory with no APKs in it")
        }

        val latestVersionCodeFromFileName: Int = apks.last().nameWithoutExtension.toInt()
        if (metadata.latestVersionCode.code != latestVersionCodeFromFileName) {
            throw AppRepoException.InvalidRepoState(
                "$pkg: metadata latestVersionCode (${metadata.latestVersionCode.code}) " +
                        "> apk version from file name (${apks.last()})"
            )
        }
        val latestApk =
            AndroidApk.verifyApkSignatureAndBuildFromApkFile(apks.last(), aaptInvoker, apkSignerInvoker)
        if (metadata.latestVersionCode != latestApk.versionCode) {
            throw AppRepoException.InvalidRepoState(
                "$pkg: latest APK versionCode in manifest mismatches with filename"
            )
        }
        val latestApkDigest = Base64String.fromBytes(
            latestApk.apkFile.digest(MessageDigest.getInstance("SHA-256"))
        )
        if (metadata.sha256Checksum != latestApkDigest) {
            throw AppRepoException.InvalidRepoState(
                "$pkg: sha256 checksum from latest apk file doesn't match the checksum in the metadata"
            )
        }
        val iconFile = fileManager.getAppIconFile(latestApk.packageName)
        TempFile.create("icon").useFile { tempIconFile ->
            val didIconExtractionSucceed = aaptInvoker.getApplicationIconFromApk(
                latestApk.apkFile,
                AAPT2Invoker.Density.MEDIUM,
                tempIconFile
            )
            if (didIconExtractionSucceed) {
                val iconFileDigest = iconFile.digest("SHA-256")
                val parsedIconFileDigest = tempIconFile.digest("SHA-256")
                if (!iconFileDigest.contentEquals(parsedIconFileDigest)) {
                    throw AppRepoException.InvalidRepoState(
                        "$pkg: icon from $latestApk doesn't match current icon in repo $iconFile"
                    )
                }
            } else if (iconFile.exists()) {
                println("warning: $pkg has icon $iconFile but extraction of icon failed")
            }
        }

        val versionsToDeltaMap: Map<Pair<VersionCode, VersionCode>, File> = appDir.listDeltaFilesUnsortedMappedToVersion()
        val baseDeltaVersionsFromDirFiles: List<VersionCode> = versionsToDeltaMap
            .map { (versionPair, file)  ->
                val (baseVersion, targetVersion) = versionPair
                if (targetVersion != metadata.latestVersionCode) {
                    throw AppRepoException.InvalidRepoState(
                        "$pkg: delta file ${file.name} doesn't target latest version ${metadata.latestVersionCode.code}"
                    )
                }

                baseVersion
            }
            .sorted()
        val baseDeltaVersionsToFileMapFromMetadata: Map<VersionCode, AppMetadata.DeltaInfo> =
            metadata.deltaInfo.associateBy { it.versionCode }

        if (baseDeltaVersionsToFileMapFromMetadata.keys.sorted() != baseDeltaVersionsFromDirFiles) {
            val problemVersions = (baseDeltaVersionsToFileMapFromMetadata.keys symmetricDifference
                    baseDeltaVersionsFromDirFiles)
                .map { it.code }

            throw AppRepoException.InvalidRepoState(
                "$pkg: mismatch between delta files in repo and delta files in metadata for these base versions: " +
                        "$problemVersions"
            )
        }

        /** Accepts a triple of the form (baseFile, deltaFile, expectedFile) */
        val deltaApplicationChannel = actor<Triple<AndroidApk, File, AndroidApk>> {
            val deltaApplicationSemaphore = Semaphore(MAX_CONCURRENT_DELTA_APPLIES_FOR_VERIFICATION)
            coroutineScope {
                for ((baseFile, deltaFile, expectedFile) in channel) {
                    launch {
                        deltaApplicationSemaphore.withPermit {
                            applyDeltaAndValidate(baseFile, deltaFile, expectedFile)
                        }
                    }
                }
            }
        }
        val parsedApks: List<AndroidApk> = coroutineScope {
            apks.map { apkFile ->
                async {
                    val parsedApk = AndroidApk.verifyApkSignatureAndBuildFromApkFile(
                        apkFile,
                        aaptInvoker,
                        apkSignerInvoker
                    )
                    if (parsedApk.versionCode.code != apkFile.nameWithoutExtension.toInt()) {
                        throw AppRepoException.InvalidRepoState(
                            "$pkg: mismatch between filename version code and version from manifest ($apkFile)"
                        )
                    }
                    if (parsedApk.versionCode > metadata.latestVersionCode) {
                        throw AppRepoException.InvalidRepoState(
                            "$pkg: APK ($apkFile) exceeds the latest version code from metadata"
                        )
                    }

                    if (parsedApk.versionCode in baseDeltaVersionsToFileMapFromMetadata.keys) {
                        val deltaFile = fileManager.getDeltaFileForApp(
                            metadata.packageName,
                            parsedApk.versionCode,
                            metadata.latestVersionCode
                        )
                        if (!deltaFile.exists()) {
                            throw AppRepoException.InvalidRepoState(
                                "$pkg: missing a delta file from ${parsedApk.versionCode} " +
                                        "to ${metadata.latestVersionCode}"
                            )
                        }
                        val deltaInfo = baseDeltaVersionsToFileMapFromMetadata[parsedApk.versionCode]
                            ?: error("impossible")
                        val deltaDigest = Base64String.fromBytes(deltaFile.digest("SHA-256"))
                        if (deltaDigest != deltaInfo.sha256Checksum) {
                            throw AppRepoException.InvalidRepoState(
                                "$pkg: delta file $deltaFile checksum doesn't match metadata checksum for $deltaInfo"
                            )
                        }

                        deltaApplicationChannel.send(Triple(parsedApk, deltaFile, latestApk))
                    }

                    parsedApk
                }
            }.awaitAll()
        }
        deltaApplicationChannel.close()
        validateApkSigningCertChain(newApks = null, parsedApks)
    }

    private fun applyDeltaAndValidate(
        baseFile: AndroidApk,
        deltaFile: File,
        expectedFile: AndroidApk,
    ) {
        println(
            "${baseFile.packageName}: validating delta from version codes " +
                    "${baseFile.versionCode.code} to " +
                    "${expectedFile.versionCode.code} using ${deltaFile.name}"
        )

        TempFile.create("deltaapply", ".apk").useFile { tempOutputFile ->
            try {
                ArchivePatcherUtil.applyDelta(
                    baseFile.apkFile,
                    deltaFile,
                    tempOutputFile,
                    isDeltaGzipped = true
                )
                val outputDigest = tempOutputFile.digest("SHA-256")
                val expectedDigest = expectedFile.apkFile.digest("SHA-256")
                if (!outputDigest.contentEquals(expectedDigest)) {
                    throw AppRepoException.InvalidRepoState(
                        "delta application did not produce expected target file " +
                                "(old: $baseFile, delta: $deltaFile, " +
                                "expected: ${expectedFile.apkFile})"
                    )
                }
            } catch (e: Throwable) {
                println(
                    "${baseFile.packageName}: error occurred when trying to validate deltas" +
                            "(old: $baseFile, delta: $deltaFile, " +
                            "expected: ${expectedFile.apkFile})"
                )
                throw e
            }
        }
    }

    private sealed interface DeltaGenerationRequest {
        @JvmInline
        value class ForPackage(val pkg: String) : DeltaGenerationRequest
        object StartPrinting : DeltaGenerationRequest
    }

    /**
     * A Mutex to control asynchronous printing so that print messages don't leak into the editor when the user is
     * prompted to edit some text such as release notes.
     */
    private val asyncPrintMutex = Mutex()

    private sealed interface PrintMessageType {
        object ShowDeltaProgress : PrintMessageType
        class NewPackage(val pkg: String, val numberOfDeltas: Int) : PrintMessageType
        @JvmInline
        value class DeltaFinished(val pkg: String) : PrintMessageType
        object ProgressPrint : PrintMessageType
        @JvmInline
        value class NewLine(val string: String) : PrintMessageType
    }

    private fun CoroutineScope.createDeltaGenerationActor(
        signingPrivateKey: PKCS8PrivateKeyFile
    ): SendChannel<DeltaGenerationRequest> = actor(capacity = Channel.UNLIMITED) {
        val failedDeltaAppsMutex = Mutex()
        val failedDeltaApps = ArrayList<AppDir>()
        val anyDeltasGenerated = AtomicBoolean(false)

        val printMessageChannel = Channel<PrintMessageType>(capacity = Channel.UNLIMITED)
        val printMessageJob = launch(start = CoroutineStart.LAZY) {
            var showDeltaProgress = false
            var lastProgressMessage = ""
            var totalNumberOfDeltasToGenerate = 0L
            var numberOfDeltasGenerated = 0L
            val packageToDeltasLeftMap = sortedMapOf<String, Int>()
            fun printProgress(carriageReturn: Boolean) {
                if (!showDeltaProgress) {
                    lastProgressMessage = ""
                    return
                }

                val stringToPrint = if (totalNumberOfDeltasToGenerate > 0) {
                    val percentage = DecimalFormat.getPercentInstance().format(
                        numberOfDeltasGenerated / totalNumberOfDeltasToGenerate.toDouble()
                    )
                    "generating delta $numberOfDeltasGenerated of $totalNumberOfDeltasToGenerate ($percentage)" +
                            " ${packageToDeltasLeftMap.keys}"
                } else {
                    ""
                }
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

            for (printMessage in printMessageChannel) {
                asyncPrintMutex.withLock {
                    when (printMessage) {
                        is PrintMessageType.ProgressPrint -> printProgress(true)
                        is PrintMessageType.NewLine -> {
                            val extraSpaceNeeded = lastProgressMessage.length - printMessage.string.length
                            if (extraSpaceNeeded > 0) {
                                println('\r' + printMessage.string
                                        + " ".repeat(extraSpaceNeeded)
                                        + "\b".repeat(extraSpaceNeeded))
                            } else {
                                println('\r' + printMessage.string)
                            }
                            printProgress(false)
                        }
                        is PrintMessageType.DeltaFinished -> {
                            numberOfDeltasGenerated++

                            val numDeltasLeftForPackage = packageToDeltasLeftMap[printMessage.pkg] ?: return@withLock
                            if (numDeltasLeftForPackage - 1 <= 0) {
                                packageToDeltasLeftMap.remove(printMessage.pkg)
                            } else {
                                packageToDeltasLeftMap[printMessage.pkg] = numDeltasLeftForPackage - 1
                            }
                            printProgress(true)
                        }
                        is PrintMessageType.NewPackage -> {
                            totalNumberOfDeltasToGenerate += printMessage.numberOfDeltas
                            packageToDeltasLeftMap[printMessage.pkg] = printMessage.numberOfDeltas
                            printProgress(true)
                        }
                        is PrintMessageType.ShowDeltaProgress -> {
                            showDeltaProgress = true
                            printProgress(true)
                        }
                    }
                }
            }
            println()
        }

        coroutineScope {
            for (request in channel) {
                if (request is DeltaGenerationRequest.ForPackage) {
                    val appDir = fileManager.getDirForApp(request.pkg)
                    launch {
                        try {
                            val deltaAvailableVersions: List<AppMetadata.DeltaInfo> =
                                regenerateDeltas(appDir, printMessageChannel = printMessageChannel)
                            if (deltaAvailableVersions.isNotEmpty()) {
                                anyDeltasGenerated.set(true)
                            }

                            // Update the metadata with the updated delta info
                            AppMetadata.getMetadataFromDiskForPackage(appDir.packageName, fileManager)
                                .copy(deltaInfo = deltaAvailableVersions.toSet())
                                .writeToDiskAndSign(signingPrivateKey, openSSLInvoker, fileManager)
                            printMessageChannel.trySend(PrintMessageType.NewLine(
                                "updated metadata for ${appDir.packageName} with delta information"
                            ))
                        } catch (e: Throwable) {
                            printMessageChannel.trySend(PrintMessageType.NewLine(
                                "error during delta generation for ${appDir.packageName}"
                            ))
                            e.printStackTrace()
                            failedDeltaAppsMutex.withLock { failedDeltaApps.add(appDir) }
                        }
                    }
                } else {
                    check(request is DeltaGenerationRequest.StartPrinting)
                    printMessageChannel.send(PrintMessageType.ShowDeltaProgress)
                    printMessageJob.start()
                }
            }
        }
        printMessageChannel.close()
        printMessageJob.join()

        if (anyDeltasGenerated.get()) {
            if (failedDeltaApps.isEmpty()) {
                println("delta generation successful")
            } else {
                println("delta generation complete, but some failed to generate: ${failedDeltaApps.map { it.packageName }}")
            }
        }
    }

    /**
     * Inserts APKs from the provided [apkFilePaths]. The APKs will be first validated (ensure that they
     * verify with apksigner and that the contents of their manifests can be parsed).
     *
     * @throws AppRepoException
     * @throws IOException
     */
    override suspend fun insertApks(
        apkFilePaths: Collection<File>,
        signingPrivateKey: PKCS8PrivateKeyFile,
        promptForReleaseNotes: Boolean
    ): Unit = withContext(repoDispatcher) {
        validatePublicKeyInRepo(signingPrivateKey)

        println("parsing ${apkFilePaths.size} APKs")
        // If there are multiple versions of the same package passed into the command line, we insert all of those
        // APKs together.
        val packageApkGroup: List<PackageApkGroup.AscendingOrder>
        val timeTaken = measureTimeMillis {
            packageApkGroup = try {
                PackageApkGroup.fromFilesAscending(
                    apkFilePaths = apkFilePaths,
                    aaptInvoker = aaptInvoker,
                    apkSignerInvoker = apkSignerInvoker
                )
            } catch (e: IOException) {
                throw AppRepoException.AppDetailParseFailed("error: unable to to get Android app details", e)
            }
        }
        println("took $timeTaken ms to parse APKs")
        println("found the following packages: ${packageApkGroup.map { it.packageName }}")

        val timestampForMetadata = UnixTimestamp.now()

        coroutineScope {
            val groupIdCheckChannel: SendChannel<AppMetadata> = actor(capacity = Channel.UNLIMITED) {
                val groupIdToInsertedPackageMap = hashMapOf<String, MutableSet<String>>()
                for (appMetadata in channel) {
                    appMetadata.groupId?.let { groupId ->
                        val setOfPackagesForThisGroup: MutableSet<String>? = groupIdToInsertedPackageMap[groupId]
                        setOfPackagesForThisGroup?.add(appMetadata.packageName)
                            ?: run { groupIdToInsertedPackageMap[groupId] = hashSetOf(appMetadata.packageName) }
                    }
                }
                if (groupIdToInsertedPackageMap.isEmpty()) {
                    return@actor
                }

                println("checking groups of inserted packages")
                val allGroupsInRepo = AppMetadata.getAllGroupsAndTheirPackages(fileManager, groupIdToInsertedPackageMap.keys)
                launch {
                    val difference = groupIdToInsertedPackageMap.keys subtract allGroupsInRepo.keys
                    if (difference.isNotEmpty()) {
                        println("warning: repo doesn't have any packages for these groupIds $difference")
                    }
                }

                groupIdToInsertedPackageMap.forEach { (groupId, insertedPackages) ->
                    val allPackagesForThisGroup: Set<String>? = allGroupsInRepo[groupId]
                    if (allPackagesForThisGroup == null) {
                        println("warning: repo doesn't have any packages for groupId: $groupId")
                        return@forEach
                    }
                    println("inserted these packages for groupId $groupId: $insertedPackages")
                    val packagesInGroupNotInsertedInThisSession = allPackagesForThisGroup subtract insertedPackages
                    if (packagesInGroupNotInsertedInThisSession.isNotEmpty()) {
                        println("warning: for groupId $groupId, these packages were inserted: $insertedPackages")
                        println(" but these packages in the same group didn't get an update: $packagesInGroupNotInsertedInThisSession")
                    }
                }
            }
            val deltaGenChannel: SendChannel<DeltaGenerationRequest> = createDeltaGenerationActor(signingPrivateKey)
            try {
                packageApkGroup.forEach { apkInsertionGroup ->
                    val nowInsertingString = "Now inserting ${apkInsertionGroup.packageName}"
                    val versionCodeString = "Version codes: ${apkInsertionGroup.sortedApks.map { it.versionCode.code }}"
                    val width = max(versionCodeString.length, nowInsertingString.length)
                    println(
                        """
                        ${"=".repeat(width)}
                        $nowInsertingString
                        $versionCodeString
                        ${"=".repeat(width)}
                    """.trimIndent()
                    )

                    try {
                        insertApkGroupForSinglePackage(
                            apksToInsert = apkInsertionGroup,
                            signingPrivateKey = signingPrivateKey,
                            timestampForMetadata = timestampForMetadata,
                            promptForReleaseNotes = promptForReleaseNotes,
                            groupIdCheckChannel
                        )
                        deltaGenChannel.send(DeltaGenerationRequest.ForPackage(apkInsertionGroup.packageName))
                    } catch (e: AppRepoException.MoreRecentVersionInRepo) {
                        println("warning: skipping insertion of ${apkInsertionGroup.packageName}:")
                        println(e.message)
                    }
                }

                println()
                println("refreshing app index")
                AppRepoIndex.constructFromRepoFilesOnDisk(fileManager, timestampForMetadata)
                    .writeToDiskAndSign(
                        privateKey = signingPrivateKey,
                        openSSLInvoker = openSSLInvoker,
                        fileManager = fileManager
                    )
                println("wrote new app index at ${fileManager.appIndex}")
            } finally {
                deltaGenChannel.send(DeltaGenerationRequest.StartPrinting)
                deltaGenChannel.close()
                groupIdCheckChannel.close()
            }
        }

        BulkAppMetadata.createFromDisk(fileManager, timestampForMetadata)
            .writeToDiskAndSign(fileManager, openSSLInvoker, signingPrivateKey)
        println("refreshed bulk metadata file")
    }

    /**
     * @throws IOException if validation of the public key failed (due to I/O error, public key mismatch, openssl
     * failing, etc
     * @throws AppRepoException.RepoSigningKeyMismatch
     */
    private fun validatePublicKeyInRepo(signingPrivateKey: PKCS8PrivateKeyFile) {
        val publicKey: PEMPublicKey = try {
            openSSLInvoker.getPublicKey(signingPrivateKey)
        } catch (e: IOException) {
            throw IOException("openssl failed to generate public key", e)
        }
        val publicKeyInRepo = fileManager.publicSigningKeyPem
        if (!publicKeyInRepo.exists()) {
            publicKeyInRepo.writeText(publicKey.pubKey)
        } else {
            val existingPublicKeyPem = PEMPublicKey(publicKeyInRepo.readText())
            if (existingPublicKeyPem != publicKey) {
                throw AppRepoException.RepoSigningKeyMismatch(
                    "the key passed to command (${signingPrivateKey.file.absolutePath}) differs from the one " +
                            "used before (${publicKeyInRepo.absolutePath})"
                )
            }
        }
    }

    fun readAppMetadataFromDisk(packageName: String): AppMetadata? = try {
        AppMetadata.getMetadataFromDiskForPackage(packageName, fileManager)
    } catch (e: IOException) {
        if (e is FileNotFoundException || e.cause is FileNotFoundException) {
            null
        } else {
            throw e
        }
    }

    /**
     * Inserts one or more APKs for a single package.
     */
    private suspend fun insertApkGroupForSinglePackage(
        apksToInsert: PackageApkGroup.AscendingOrder,
        signingPrivateKey: PKCS8PrivateKeyFile,
        timestampForMetadata: UnixTimestamp,
        promptForReleaseNotes: Boolean,
        groupIdCheckChannel: SendChannel<AppMetadata>
    ): Unit = withContext(repoDispatcher) {
        val appDir = fileManager.getDirForApp(apksToInsert.packageName)

        // Validate or create the directory for the package / app.
        if (appDir.dir.exists()) {
            val currentAppMetadata = readAppMetadataFromDisk(apksToInsert.packageName)
                ?: throw AppRepoException.InvalidRepoState("app directories are present but missing metadata file")
            println("${apksToInsert.packageName} is in repo.")

            val smallestVersionCodeApk = apksToInsert.sortedApks.first()
            if (smallestVersionCodeApk.versionCode <= currentAppMetadata.latestVersionCode) {
                throw AppRepoException.MoreRecentVersionInRepo(
                    "trying to insert ${smallestVersionCodeApk.packageName} with version code " +
                            "${smallestVersionCodeApk.versionCode.code} when the " +
                            "repo has latest version ${currentAppMetadata.latestVersionCode.code}"
                )
            }
            println("previous version in repo: ${currentAppMetadata.latestVersionCode.code}")
        } else {
            println("${apksToInsert.packageName} is not in the repo. Creating new directory and metadata")
            if (!appDir.dir.mkdirs()) {
                throw AppRepoException.InsertFailed("failed to create directory ${appDir.dir.absolutePath}")
            }
        }

        val sortedPreviousApks = PackageApkGroup.fromDir(appDir, aaptInvoker, apkSignerInvoker, ascendingOrder = true)
        validateApkSigningCertChain(newApks = apksToInsert.sortedApks, currentApks = sortedPreviousApks.sortedApks)

        val maxVersionApk = apksToInsert.sortedApks.last()
        val releaseNotes: String? = if (promptForReleaseNotes) {
            promptUserForReleaseNotes(maxVersionApk.asRight())?.takeIf { it.isNotBlank() }
        } else {
            null
        }

        apksToInsert.sortedApks.forEach { apkToInsert ->
            val newApkFile = File(appDir.dir, "${apkToInsert.versionCode.code}.apk")
            apkToInsert.apkFile.copyTo(newApkFile)
            println("copied ${apkToInsert.apkFile.absolutePath} to ${newApkFile.absolutePath}")
        }

        try {
            val currentAppMetadata = readAppMetadataFromDisk(apksToInsert.packageName)
            val newAppMetadata = AppMetadata(
                packageName = maxVersionApk.packageName,
                groupId = currentAppMetadata?.groupId,
                label = maxVersionApk.label,
                latestVersionCode = maxVersionApk.versionCode,
                latestVersionName = maxVersionApk.versionName,
                sha256Checksum = Base64String.fromBytes(
                    maxVersionApk.apkFile.digest(MessageDigest.getInstance("SHA-256"))
                ),
                // This will be filled in later.
                // Deltas are generated asynchronously
                deltaInfo = emptySet(),
                lastUpdateTimestamp = timestampForMetadata,
                releaseNotes = releaseNotes
            )
            println("metadata updated: $newAppMetadata")

            newAppMetadata.writeToDiskAndSign(
                privateKey = signingPrivateKey,
                openSSLInvoker = openSSLInvoker,
                fileManager = fileManager
            )
            groupIdCheckChannel.send(newAppMetadata)
        } catch (e: IOException) {
            throw IOException("failed to write metadata", e)
        }

        val appIconFile = fileManager.getAppIconFile(apksToInsert.packageName)
        val didLauncherIconExtractSucceed = try {
            aaptInvoker.getApplicationIconFromApk(
                apkFile = maxVersionApk.apkFile,
                minimumDensity = AAPT2Invoker.Density.MEDIUM,
                outputIconFile = appIconFile
            )
        } catch (e: IOException) {
            false
        }
        if (!didLauncherIconExtractSucceed) {
            println("warning: unable to extract launcher icon for ${maxVersionApk.apkFile}")
        } else {
            println("launcher icon extracted")
        }
    }

    /**
     * @throws IOException if unable to delete deltas
     */
    private fun deleteAllDeltas(appDir: AppDir) {
        appDir.listDeltaFilesUnsorted().forEach { oldDelta ->
            println("deleting outdated delta ${oldDelta.name}")
            if (!oldDelta.delete()) {
                throw IOException("failed to delete $oldDelta")
            }
        }
    }

    /**
     * Validates that the intersection of all signing certificates for all the APKs in [newApks] and [currentApks]
     * is nonempty.
     *
     * @throws AppRepoException.ApkSigningCertMismatch
     */
    private fun validateApkSigningCertChain(
        newApks: Iterable<AndroidApk>?,
        currentApks: Iterable<AndroidApk>
    ) {
        var currentSetIntersection: Set<HexString>? = null
        currentApks.asSequence()
            .apply { newApks?.let { plus(newApks) } }
            .forEach { currentApk ->
                currentSetIntersection = currentSetIntersection
                    ?.let { currentApk.certificates intersect it }
                    ?: currentApk.certificates.toSet()

                // TODO: verify using same or similar way as frameworks/base or apksigner
                if (currentSetIntersection!!.isEmpty()) {
                    throw AppRepoException.ApkSigningCertMismatch(
                        "some apks don't have the same signing certificates\ndumping details: $currentApks"
                    )
                }
            }
    }

    /**
     * Generates multiple deltas with the APK with the highest version code as the target and the top
     * [maxPreviousVersions] APKs as the bases.
     *
     * @return The version codes for which a delta is available to patching to create the highest version code APK
     * @throws IOException if an I/O error occurs
     */
    private suspend fun regenerateDeltas(
        appDir: AppDir,
        maxPreviousVersions: Int = DEFAULT_MAX_PREVIOUS_VERSION_DELTAS,
        printMessageChannel: SendChannel<PrintMessageType>?
    ): List<AppMetadata.DeltaInfo> = withContext(repoDispatcher) {
        deleteAllDeltas(appDir)
        val apks = PackageApkGroup.fromDir(appDir, aaptInvoker, apkSignerInvoker, ascendingOrder = false)
        if (apks.size <= 1) {
            return@withContext emptyList()
        }
        check(apks is PackageApkGroup.DescendingOrder)

        val newestApk = apks.sortedApks.first()
        val numberOfDeltasToGenerate = min(apks.size - 1, maxPreviousVersions)
        printMessageChannel?.trySend(PrintMessageType.NewPackage(apks.packageName, numberOfDeltasToGenerate))
        yield()

        var deltaGenTime = 0L
        // Drop the first element, because that's the most recent one and hence the target
        apks.sortedApks.drop(1)
            .take(numberOfDeltasToGenerate)
            .mapTo(ArrayList(numberOfDeltasToGenerate)) { previousApk ->
                async {
                    yield()
                    deltaGenerationSemaphore.withPermit {
                        val outputDeltaFile = fileManager.getDeltaFileForApp(
                            apks.packageName,
                            previousApk.versionCode,
                            newestApk.versionCode
                        )
                        printMessageChannel?.trySend(PrintMessageType.ProgressPrint)
                        deltaGenTime += measureTimeMillis {
                            ArchivePatcherUtil.generateDelta(
                                previousApk.apkFile,
                                newestApk.apkFile,
                                outputDeltaFile,
                                outputGzip = true
                            )
                        }
                        val digest = outputDeltaFile.digest("SHA-256")
                        printMessageChannel?.apply {
                            trySend(PrintMessageType.NewLine(
                                " generated delta ${outputDeltaFile.name} (${appDir.packageName}) is " +
                                        "${outputDeltaFile.length().toDouble() / (0x1 shl 20)} MiB"
                            ))
                            trySend(PrintMessageType.DeltaFinished(apks.packageName))
                        }

                        return@async AppMetadata.DeltaInfo(
                            previousApk.versionCode,
                            Base64String.fromBytes(digest)
                        )
                    }
                }
            }
            .awaitAll()
            .also { deltaAvailableVersions ->
                printMessageChannel?.trySend(PrintMessageType.NewLine(
                    "took $deltaGenTime ms to generate ${deltaAvailableVersions.size} deltas " +
                            "for ${appDir.packageName}. " +
                            "Versions with deltas available: " +
                            "${deltaAvailableVersions.map { it.versionCode.code }}"
                ))
            }
    }

    private fun processGroupId(groupId: String?) = groupId?.trim()?.lowercase()

    override suspend fun setGroupForPackages(
        groupId: String,
        packages: List<String>,
        signingPrivateKey: PKCS8PrivateKeyFile,
        createNewGroupIfNotExists: Boolean
    ) {
        val allGroups: SortedSet<String?> = AppMetadata.getAllAppMetadataFromDisk(fileManager).asSequence()
            .filter { it.groupId != null }
            .mapTo(sortedSetOf()) { it.groupId }

        val processedGroupId = processGroupId(groupId)
        if (processedGroupId !in allGroups) {
            if (createNewGroupIfNotExists) {
                println("creating new group $groupId")
            } else {
                val groupInfoString = if (allGroups.isEmpty()) {
                    "There are no available groups"
                } else {
                    "Available groups: $allGroups"
                }
                throw AppRepoException.GroupDoesntExist(
                    """groupId $processedGroupId does not exist in the repository.
                        $groupInfoString
                        Use --create (-c) instead of --add (-a) to create this group with these packages
                    """.trimIndent()
                )
            }
        }

        setGroupForPackagesInternal(groupId, packages, signingPrivateKey)
    }

    override suspend fun removeGroupFromPackages(packages: List<String>, signingPrivateKey: PKCS8PrivateKeyFile) {
        setGroupForPackagesInternal(groupId = null, packages, signingPrivateKey)
    }

    override suspend fun deleteGroup(groupId: String, signingPrivateKey: PKCS8PrivateKeyFile) {
        val processedGroupId = processGroupId(groupId)

        val packagesForThisGroup = AppMetadata.getAllAppMetadataFromDisk(fileManager).asSequence()
            .filter { it.groupId == processedGroupId }
            .mapTo(arrayListOf()) { it.packageName }
        if (packagesForThisGroup.isEmpty()) {
            println("group doesn't exist")
            println("all groups:")
            printAllGroups()
            return
        }

        removeGroupFromPackages(packagesForThisGroup, signingPrivateKey)
        println("removed groupId $processedGroupId from ${packagesForThisGroup.size} groups: $packagesForThisGroup")
    }

    override suspend fun printAllPackages() {
        AppMetadata.getAllAppMetadataFromDisk(fileManager)
            .forEach { println(it.packageName) }
    }

    override fun doesPackageExist(pkg: String): Boolean {
        return try {
            AppMetadata.getMetadataFromDiskForPackage(pkg, fileManager)
            true
        } catch (e: IOException) {
            false
        }
    }

    override fun getMetadataForPackage(pkg: String): AppMetadata {
        return AppMetadata.getMetadataFromDiskForPackage(pkg, fileManager)
    }

    override suspend fun editReleaseNotesForPackage(
        pkg: String,
        delete: Boolean,
        signingPrivateKey: PKCS8PrivateKeyFile
    ) {
        validatePublicKeyInRepo(signingPrivateKey)

        val metadata = try {
             AppMetadata.getMetadataFromDiskForPackage(pkg, fileManager)
        } catch (e: IOException) {
            throw AppRepoException.EditFailed("package $pkg doesn't exist")
        }

        val newReleaseNotes: String? = if (!delete) {
            promptUserForReleaseNotes(metadata.asLeft())
                .also { newReleaseNotes ->
                    if (newReleaseNotes == null) {
                        if (metadata.releaseNotes == null) {
                            println("error: no release notes specified")
                        } else {
                            println("error: no changes to release notes")
                        }
                        return
                    }
                }
        } else {
            null
        }

        val newTimestamp = UnixTimestamp.now()
        AppMetadata.getMetadataFromDiskForPackage(pkg, fileManager)
            .copy(
                releaseNotes = newReleaseNotes,
                lastUpdateTimestamp = newTimestamp
            )
            .writeToDiskAndSign(signingPrivateKey, openSSLInvoker, fileManager)
        println("updated release notes for $pkg")
        println("regenerating repo index")
        AppRepoIndex.constructFromRepoFilesOnDisk(fileManager, newTimestamp)
            .writeToDiskAndSign(signingPrivateKey, openSSLInvoker, fileManager)
        println("regenerating bulk app metadata")
        BulkAppMetadata.createFromDisk(fileManager, newTimestamp)
            .writeToDiskAndSign(fileManager, openSSLInvoker, signingPrivateKey)
    }

    private suspend fun promptUserForReleaseNotes(
        appInfo: Either<AppMetadata, AndroidApk>
    ): String? {
        val textToEdit = buildString {
            val packageAndVersionLine = when (appInfo) {
                is Either.Left -> {
                    val metadata: AppMetadata = appInfo.value
                    "$EDITOR_IGNORE_PREFIX ${metadata.label} (${metadata.packageName}), " +
                            "version ${metadata.latestVersionName} (versionCode ${metadata.latestVersionCode.code})."
                }
                is Either.Right -> {
                    val androidApk: AndroidApk = appInfo.value
                    "$EDITOR_IGNORE_PREFIX ${androidApk.label} (${androidApk.packageName}), " +
                            "version ${androidApk.versionName} (versionCode ${androidApk.versionCode.code})."
                }
            }

            if (appInfo is Either.Left && appInfo.value.releaseNotes != null) {
                val metadata: AppMetadata = appInfo.value
                val releaseNotes: String = metadata.releaseNotes!!

                append(releaseNotes)
                if (!releaseNotes.endsWith('\n')) appendLine()
                appendLine("$EDITOR_IGNORE_PREFIX Editing existing release notes for")
                appendLine(packageAndVersionLine)
                appendLine(
                    "$EDITOR_IGNORE_PREFIX Last edited: ${
                        ZonedDateTime.ofInstant(
                            Instant.ofEpochSecond(metadata.lastUpdateTimestamp.seconds),
                            ZoneId.systemDefault()
                        ).format(DateTimeFormatter.RFC_1123_DATE_TIME)
                    }"
                )
            } else {
                appendLine()
                appendLine("$EDITOR_IGNORE_PREFIX Enter new release notes for")
                appendLine(packageAndVersionLine)
            }
            appendLine("$EDITOR_IGNORE_PREFIX Lines or sections starting with $EDITOR_IGNORE_PREFIX are ignored.")
            append("$EDITOR_IGNORE_PREFIX Text can be plaintext or HTML.")
        }

        return asyncPrintMutex.withLock {
            TermUi.editText(
                text = textToEdit,
                requireSave = true
            )?.replace(Regex("$EDITOR_IGNORE_PREFIX[^\n]*\n"), "")
        }
    }

    override suspend fun printAllGroups() {
        AppMetadata.getAllGroupsAndTheirPackages(fileManager, groupsToSelect = null)
            .ifEmpty { null }
            ?.let { println(it) }
            ?: println("no groups")
    }

    private suspend fun setGroupForPackagesInternal(
        groupId: String?,
        packages: List<String>,
        signingPrivateKey: PKCS8PrivateKeyFile
    ) {
        validatePublicKeyInRepo(signingPrivateKey)
        val groupIdToUse = groupId?.let {
            require(groupId.isNotBlank())
            processGroupId(it)
        }

        val packageToMetadataMap: Map<String, AppMetadata> =
            AppMetadata.getAllAppMetadataFromDisk(fileManager).associateByTo(sortedMapOf()) { it.packageName }

        val distinctInputPackages = packages.distinct()

        distinctInputPackages.forEach { pkg ->
            require(pkg in packageToMetadataMap.keys) { "$pkg is not in the repo" }
        }

        val timestampForMetadata = UnixTimestamp.now()
        coroutineScope {
            distinctInputPackages.forEach { pkg ->
                launch {
                    packageToMetadataMap[pkg]!!
                        .copy(
                            groupId = groupIdToUse,
                            lastUpdateTimestamp = timestampForMetadata
                        )
                        .writeToDiskAndSign(
                            signingPrivateKey,
                            openSSLInvoker,
                            fileManager
                        )
                }
            }
        }

        println("regenerating repo index")
        AppRepoIndex.constructFromRepoFilesOnDisk(fileManager, timestampForMetadata)
            .writeToDiskAndSign(signingPrivateKey, openSSLInvoker, fileManager)
        println("regenerating bulk app metadata")
        BulkAppMetadata.createFromDisk(fileManager, timestampForMetadata)
            .writeToDiskAndSign(fileManager, openSSLInvoker, signingPrivateKey)

        groupId
            ?.let { println("packages were successfully set to the group $groupIdToUse") }
            ?: println("successfully removed groups from the given packages")
    }
}

sealed class AppRepoException : Exception {
    constructor() : super()
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable) : super(message, cause)
    constructor(cause: Throwable) : super(cause)

    class EditFailed : AppRepoException {
        constructor(message: String) : super(message)
    }
    class MoreRecentVersionInRepo(message: String): AppRepoException(message)
    class InsertFailed : AppRepoException {
        constructor() : super()
        constructor(message: String) : super(message)
        constructor(message: String, cause: Throwable) : super(message, cause)
        constructor(cause: Throwable) : super(cause)
    }
    class GroupDoesntExist : AppRepoException {
        constructor(message: String) : super(message)
    }
    class ApkSigningCertMismatch : AppRepoException {
        constructor(message: String) : super(message)
    }
    class RepoSigningKeyMismatch : AppRepoException {
        constructor(message: String) : super(message)
    }
    class InvalidRepoState : AppRepoException {
        constructor(message: String) : super(message)
        constructor(message: String, cause: Throwable) : super(message, cause)
    }
    class AppDetailParseFailed : AppRepoException {
        constructor(message: String, cause: Throwable) : super(message, cause)
    }
}
