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
import org.grapheneos.appupdateservergenerator.db.App
import org.grapheneos.appupdateservergenerator.db.AppDao
import org.grapheneos.appupdateservergenerator.db.AppRelease
import org.grapheneos.appupdateservergenerator.db.Database
import org.grapheneos.appupdateservergenerator.db.DbWrapper
import org.grapheneos.appupdateservergenerator.db.DeltaInfo
import org.grapheneos.appupdateservergenerator.db.DeltaInfoDao
import org.grapheneos.appupdateservergenerator.db.GroupDao
import org.grapheneos.appupdateservergenerator.files.AppDir
import org.grapheneos.appupdateservergenerator.files.FileManager
import org.grapheneos.appupdateservergenerator.files.TempFile
import org.grapheneos.appupdateservergenerator.model.AndroidApk
import org.grapheneos.appupdateservergenerator.model.Base64String
import org.grapheneos.appupdateservergenerator.model.GroupId
import org.grapheneos.appupdateservergenerator.model.HexString
import org.grapheneos.appupdateservergenerator.model.PackageApkGroup
import org.grapheneos.appupdateservergenerator.model.PackageName
import org.grapheneos.appupdateservergenerator.model.UnixTimestamp
import org.grapheneos.appupdateservergenerator.model.VersionCode
import org.grapheneos.appupdateservergenerator.model.toBase64String
import org.grapheneos.appupdateservergenerator.util.ArchivePatcherUtil
import org.grapheneos.appupdateservergenerator.util.Either
import org.grapheneos.appupdateservergenerator.util.asEitherLeft
import org.grapheneos.appupdateservergenerator.util.asEitherRight
import org.grapheneos.appupdateservergenerator.util.digest
import org.grapheneos.appupdateservergenerator.util.symmetricDifference
import java.io.File
import java.io.FileFilter
import java.io.IOException
import java.lang.Integer.min
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.text.DecimalFormat
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.TreeSet
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
        groupId: GroupId,
        packages: List<PackageName>,
        signingPrivateKey: PKCS8PrivateKeyFile,
        createNewGroupIfNotExists: Boolean
    )

    /**
     * Removes the group IDs from the given [packages]
     */
    suspend fun removeGroupFromPackages(packages: List<PackageName>, signingPrivateKey: PKCS8PrivateKeyFile)

    /**
     * Deletes the [groupId] from the repository.
     */
    suspend fun deleteGroup(groupId: GroupId, signingPrivateKey: PKCS8PrivateKeyFile)

    suspend fun printAllPackages()

    suspend fun printAllGroups()

    fun doesPackageExist(pkg: PackageName): Boolean

    fun getMetadataForPackage(pkg: PackageName): AppMetadata?

    suspend fun editReleaseNotesForPackage(
        pkg: PackageName,
        versionCode: VersionCode? = null,
        delete: Boolean,
        signingPrivateKey: PKCS8PrivateKeyFile
    )

    suspend fun resignMetadataForPackage(pkg: PackageName, signingPrivateKey: PKCS8PrivateKeyFile)
}

fun AppRepoManager(
    fileManager: FileManager,
    aaptInvoker: AAPT2Invoker,
    openSSLInvoker: OpenSSLInvoker
): AppRepoManager = AppRepoManagerImpl(fileManager, aaptInvoker, openSSLInvoker)

/**
 * The implementation of [AppRepoManager].
 * @see AppRepoManager
 */
@ObsoleteCoroutinesApi
private class AppRepoManagerImpl(
    private val fileManager: FileManager,
    private val aaptInvoker: AAPT2Invoker,
    private val openSSLInvoker: OpenSSLInvoker
): AppRepoManager {
    companion object {
        private const val DEFAULT_MAX_PREVIOUS_VERSION_DELTAS = 5
        private const val MAX_CONCURRENT_DELTA_GENERATION = 3
        private const val MAX_CONCURRENT_DELTA_APPLIES_FOR_VERIFICATION = 4

        private const val EDITOR_IGNORE_PREFIX = "//##:"
        private val deltaSizeDecimalFormat = DecimalFormat("#.###")
    }

    val deltaGenerationSemaphore = Semaphore(permits = MAX_CONCURRENT_DELTA_GENERATION)
    private val executorService: ExecutorService = Executors.newFixedThreadPool(64)
    private val repoDispatcher = executorService.asCoroutineDispatcher()

    private val database: Database = DbWrapper.getDbInstance(fileManager)
    private val appDao = AppDao(database)
    private val groupDao = GroupDao(database)
    private val deltaInfoDao = DeltaInfoDao(database, fileManager)

    private val staticFilesManager = MetadataFileManager(database, appDao, fileManager, openSSLInvoker)

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
                first = packagesFromExistingIndex,
                missingFromFirstError = "app directory has packages not included in the index",
                second = packagesFromAllMetadataOnDisk,
                missingFromSecondError = "index contains packages for which there are no directories for"
            )
        }

        val bulkAppMetadata = BulkAppMetadata.readFromExistingFile(fileManager).allAppMetadata
        if (bulkAppMetadata != metadataFromDirs) {
            throwExceptionWithMissingIntersectionElements(
                first = bulkAppMetadata.map { it.packageName},
                missingFromFirstError = "bulk app metadata file doesn't include packages",
                second = metadataFromDirs.map { it.packageName },
                missingFromSecondError = "missing latest.txt metadata for packages included in bulk app metadata file"
            )
        }

        check(packageDirs.size == metadataFromDirs.size)
        println("validating details, APKs, and delta files of " +
                "${packageDirs.size} ${if (packageDirs.size == 1) "app" else "apps"}")
        val appDirAndMetadata = packageDirs.zip(metadataFromDirs) { a, b ->
            check(a.name == b.packageName.pkg)
            a to b
        }
        coroutineScope {
            appDirAndMetadata.forEach { (appDir, metadata) ->
                launch { validateAppDir(AppDir(appDir), metadata, publicKey, existingIndex) }
            }
        }

        println("repo successfully validated")
    }

    private fun <T> throwExceptionWithMissingIntersectionElements(
        first: Iterable<T>,
        missingFromFirstError: T,
        second: Iterable<T>,
        missingFromSecondError: T
    ): Nothing {
        val errorMessage = buildString {
            val intersection = first intersect second
            (first subtract intersection)
                .takeIf { it.isNotEmpty() }
                ?.let { appendLine("$missingFromSecondError: $it") }
            (second subtract intersection)
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
        val apks = appDir.listApkFilesUnsorted().sortedBy { it.nameWithoutExtension.toInt() }
        if (apks.isEmpty()) {
            throw AppRepoException.InvalidRepoState("$pkg: app directory with no APKs in it")
        }

        val latestVersionCodeFromFileName: Int = apks.last().nameWithoutExtension.toInt()
        if (metadata.latestRelease().versionCode.code != latestVersionCodeFromFileName) {
            throw AppRepoException.InvalidRepoState(
                "$pkg: metadata latestVersionCode (${metadata.latestRelease().versionCode.code}) " +
                        "> apk version from file name (${apks.last()})"
            )
        }
        if (metadata.latestRelease().versionCode != metadata.latestRelease().versionCode) {
            throw AppRepoException.InvalidRepoState(
                "$pkg: metadata latestVersionCode (${metadata.latestRelease().versionCode.code}) " +
                        "doesn't match the actual most recent release's version code in the releases array"
            )
        }

        val latestApk = AndroidApk.verifyApkSignatureAndBuildFromApkFile(apks.last(), aaptInvoker)
        if (metadata.latestRelease().versionCode != latestApk.versionCode) {
            throw AppRepoException.InvalidRepoState(
                "$pkg: latest APK versionCode in manifest mismatches with filename"
            )
        }
        val latestApkDigest = Base64String.fromBytes(
            latestApk.apkFile.digest(MessageDigest.getInstance("SHA-256"))
        )
        val latestRelease = metadata.latestRelease()
        if (latestRelease.sha256Checksum != latestApkDigest) {
            throw AppRepoException.InvalidRepoState(
                "$pkg: sha256 checksum from latest apk file doesn't match the checksum in the metadata"
            )
        }
        val iconFile = fileManager.getAppIconFile(latestApk.packageName)
            val icon = aaptInvoker.getApplicationIconFromApk(
                latestApk.apkFile,
                AAPT2Invoker.Density.MEDIUM
            )
            if (icon != null) {
                val iconFileBytes = iconFile.readBytes()
                if (!iconFileBytes.contentEquals(icon)) {
                    throw AppRepoException.InvalidRepoState(
                        "$pkg: icon from $latestApk doesn't match current icon in repo $iconFile"
                    )
                }
            } else if (iconFile.exists()) {
                println("warning: $pkg has icon $iconFile but extraction of icon failed")
            }


        val versionsToDeltaMap: Map<Pair<VersionCode, VersionCode>, File> = appDir.listDeltaFilesUnsortedMappedToVersion()
        val baseDeltaVersionsFromDirFiles: List<VersionCode> = versionsToDeltaMap
            .map { (versionPair, file)  ->
                val (baseVersion, targetVersion) = versionPair
                if (targetVersion != metadata.latestRelease().versionCode) {
                    throw AppRepoException.InvalidRepoState(
                        "$pkg: delta file ${file.name} doesn't target latest version ${metadata.latestRelease().versionCode}"
                    )
                }

                baseVersion
            }
            .sorted()
        val baseDeltaVersionsToFileMapFromMetadata: Map<VersionCode, AppMetadata.DeltaInfo> =
            latestRelease.deltaInfo.associateBy { it.baseVersionCode }

        if (baseDeltaVersionsToFileMapFromMetadata.keys.sorted() != baseDeltaVersionsFromDirFiles) {
            val problemVersions = (baseDeltaVersionsToFileMapFromMetadata.keys symmetricDifference
                    baseDeltaVersionsFromDirFiles)
                .map { it.code }

            throw AppRepoException.InvalidRepoState(
                "$pkg: mismatch between delta files in repo and delta files in metadata for these base versions: " +
                        "$problemVersions"
            )
        }

        val versionCodesFromApksInDir = apks.mapTo(sortedSetOf()) { VersionCode(it.nameWithoutExtension.toInt()) }
        // check if there are deltas that have missing base APKs
        val baseDeltaVersionsFromMetadata = baseDeltaVersionsToFileMapFromMetadata.mapTo(sortedSetOf()) { it.key }
        if (baseDeltaVersionsFromMetadata.isNotEmpty()) {
            val baseVersionCodesFromApksInDir = versionCodesFromApksInDir
                .subSet(baseDeltaVersionsFromMetadata.first(), metadata.latestRelease().versionCode)

            if (baseDeltaVersionsFromMetadata != baseVersionCodesFromApksInDir) {
                throwExceptionWithMissingIntersectionElements(
                    first = baseDeltaVersionsFromMetadata,
                    missingFromFirstError = "$pkg: dir has some APKs within delta generation range not included in metadata ",
                    second = baseVersionCodesFromApksInDir,
                    missingFromSecondError = "$pkg: these APKs are missing from the dir despite there being deltas available"
                )
            }
        }

        /** Accepts a triple of the form (baseFile, deltaFile, expectedFile) */
        val deltaVerificationChannel = actor<Triple<AndroidApk, File, AndroidApk>> {
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

        val releasesInMetadata: Set<AppMetadata.ReleaseInfo> = metadata.releases
        val versionCodesFromReleasesInMetadata = releasesInMetadata.mapTo(sortedSetOf()) { it.versionCode }
        if (versionCodesFromApksInDir != versionCodesFromReleasesInMetadata) {
            throwExceptionWithMissingIntersectionElements(
                first = versionCodesFromApksInDir,
                missingFromFirstError = "$pkg: dir has some APKs not in the metadata releases",
                second = versionCodesFromReleasesInMetadata,
                missingFromSecondError = "$pkg: metadata has releases that don't have APKs in the dir"
            )
        }

        val parsedApks: List<AndroidApk> = coroutineScope {
            apks.map { apkFile ->
                async {
                    val parsedApk = AndroidApk.verifyApkSignatureAndBuildFromApkFile(apkFile, aaptInvoker)
                    if (parsedApk.packageName != metadata.packageName) {
                        throw AppRepoException.InvalidRepoState(
                            "$pkg: mismatch between metadata package and package from manifest ($apkFile)"
                        )
                    }

                    val releaseInfoForThisVersion = metadata.releases.find { it.versionCode == parsedApk.versionCode }
                        ?: throw AppRepoException.InvalidRepoState(
                            "$pkg: can't find release for APK version ${parsedApk.versionCode}"
                        )

                    if (parsedApk.versionCode.code != apkFile.nameWithoutExtension.toInt()) {
                        throw AppRepoException.InvalidRepoState(
                            "$pkg: mismatch between filename version code and version from manifest ($apkFile)"
                        )
                    }
                    if (parsedApk.versionCode != releaseInfoForThisVersion.versionCode) {
                        throw AppRepoException.InvalidRepoState(
                            "$pkg: mismatch between metadata release version and version from manifest ($apkFile)"
                        )
                    }
                    if (parsedApk.versionName != releaseInfoForThisVersion.versionName) {
                        throw AppRepoException.InvalidRepoState(
                            "$pkg: mismatch between metadata release version name and version from manifest ($apkFile)"
                        )
                    }
                    if (parsedApk.versionCode > metadata.latestRelease().versionCode) {
                        throw AppRepoException.InvalidRepoState(
                            "$pkg: APK ($apkFile) exceeds the latest version code from metadata"
                        )
                    }
                    if (parsedApk.apkFile.digest("SHA-256").toBase64String() !=
                        releaseInfoForThisVersion.sha256Checksum
                    ) {
                        throw AppRepoException.InvalidRepoState(
                            "$pkg: APK ($apkFile) sha256 checksum mismatches the release's checksum"
                        )
                    }

                    if (parsedApk.versionCode in baseDeltaVersionsToFileMapFromMetadata.keys) {
                        val deltaFile = fileManager.getDeltaFileForApp(
                            metadata.packageName,
                            parsedApk.versionCode,
                            metadata.latestRelease().versionCode
                        )
                        if (!deltaFile.exists()) {
                            throw AppRepoException.InvalidRepoState(
                                "$pkg: missing a delta file from ${parsedApk.versionCode} " +
                                        "to ${metadata.latestRelease().versionCode}"
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

                        deltaVerificationChannel.send(Triple(parsedApk, deltaFile, latestApk))
                    }

                    parsedApk
                }
            }.awaitAll()
        }
        deltaVerificationChannel.close()
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
        value class ForPackage(val pkg: PackageName) : DeltaGenerationRequest
        object StartPrinting : DeltaGenerationRequest
    }

    /**
     * A Mutex to control asynchronous printing so that print messages don't leak into the editor when the user is
     * prompted to edit some text such as release notes.
     */
    private val asyncPrintMutex = Mutex()

    private sealed interface PrintMessageType {
        object ShowDeltaProgress : PrintMessageType
        class NewPackage(val pkg: PackageName, val numberOfDeltas: Int) : PrintMessageType
        @JvmInline
        value class DeltaFinished(val pkg: PackageName) : PrintMessageType
        object ProgressPrint : PrintMessageType
        @JvmInline
        value class NewLine(val string: String) : PrintMessageType
        @JvmInline
        value class NewLines(val strings: Array<String>) : PrintMessageType
    }

    private fun CoroutineScope.createDeltaGenerationActor(): SendChannel<DeltaGenerationRequest> =
        actor(capacity = Channel.UNLIMITED) {
            val failedDeltaAppsMutex = Mutex()
            val failedDeltaApps = ArrayList<AppDir>()
            val anyDeltasGenerated = AtomicBoolean(false)

            val printMessageChannel = Channel<PrintMessageType>(capacity = Channel.UNLIMITED)
            val printMessageJob = launch(start = CoroutineStart.LAZY) {
                var showDeltaProgress = false
                var lastProgressMessage = ""
                var totalNumberOfDeltasToGenerate = 0L
                var numberOfDeltasGenerated = 0L
                val packageToDeltasLeftMap = sortedMapOf<PackageName, Int>()
                fun printProgress(carriageReturn: Boolean) {
                    if (!showDeltaProgress) {
                        lastProgressMessage = ""
                        return
                    }

                    val stringToPrint = if (totalNumberOfDeltasToGenerate > 0) {
                        val percentage = DecimalFormat.getPercentInstance().format(
                            numberOfDeltasGenerated / totalNumberOfDeltasToGenerate.toDouble()
                        )
                        "generated delta $numberOfDeltasGenerated of $totalNumberOfDeltasToGenerate ($percentage)" +
                                (if (packageToDeltasLeftMap.keys.isNotEmpty()) {
                                    " --- packages left: ${packageToDeltasLeftMap.keys}"
                                } else {
                                    ""
                                })
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
                fun printNewLine(printMessage: String) {
                    val extraSpaceNeeded = lastProgressMessage.length - printMessage.length
                    if (extraSpaceNeeded > 0) {
                        println('\r' + printMessage
                                + " ".repeat(extraSpaceNeeded)
                                + "\b".repeat(extraSpaceNeeded))
                    } else {
                        println('\r' + printMessage)
                    }
                    printProgress(false)
                }

                for (printMessage in printMessageChannel) {
                    asyncPrintMutex.withLock {
                        when (printMessage) {
                            is PrintMessageType.ProgressPrint -> printProgress(true)
                            is PrintMessageType.NewLines -> printMessage.strings.forEach { printNewLine(it) }
                            is PrintMessageType.NewLine -> printNewLine(printMessage.string)
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
                                val deltaInfo: Set<DeltaInfo> = regenerateDeltas(
                                    appDir,
                                    printMessageChannel = printMessageChannel
                                )
                                if (deltaInfo.isNotEmpty()) {
                                    anyDeltasGenerated.set(true)
                                }
                                deltaInfoDao.insertDeltaInfos(deltaInfo)
                                printMessageChannel.trySend(PrintMessageType.NewLine(
                                    "updated metadata for ${appDir.packageName} with delta information"
                                ))
                            } catch (e: Throwable) {
                                printMessageChannel.trySend(PrintMessageType.NewLines(
                                    arrayOf(
                                        "error during delta generation for ${appDir.packageName}}",
                                        e.stackTraceToString()
                                    )
                                ))

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
        val packageApkGroups: List<PackageApkGroup.AscendingOrder>
        val timeTaken = measureTimeMillis {
            packageApkGroups = try {
                PackageApkGroup.fromFilesAscending(
                    apkFilePaths = apkFilePaths,
                    aaptInvoker = aaptInvoker
                )
            } catch (e: IOException) {
                throw AppRepoException.AppDetailParseFailed(
                    "error: unable to get Android app details",
                    e
                )
            }
        }
        println("took $timeTaken ms to parse APKs")
        println("found the following packages: ${packageApkGroups.map { it.packageName }}")

        val timestampForMetadata = UnixTimestamp.now()

        var numAppsNotInserted = 0L
        coroutineScope {
            val groupIdCheckChannel: SendChannel<PackageApkGroup> = actor(capacity = Channel.UNLIMITED) {
                val groupIdToInsertedPackageMap = hashMapOf<GroupId, MutableSet<AndroidApk>>()
                for (packageApkGroup in channel) {
                    val latestApk = packageApkGroup.highestVersionApk!!
                    val app = appDao.getApp(packageApkGroup.packageName)!!
                    app.groupId?.let { groupId ->
                        val setOfPackagesForThisGroup: MutableSet<AndroidApk>? = groupIdToInsertedPackageMap[groupId]
                        setOfPackagesForThisGroup
                            ?.add(latestApk)
                            ?: run { groupIdToInsertedPackageMap[groupId] = hashSetOf(latestApk) }
                    }
                }
                if (groupIdToInsertedPackageMap.isEmpty()) {
                    return@actor
                }

                println("checking groups of inserted packages")
                groupIdToInsertedPackageMap.forEach { (groupId, insertedPackages) ->
                    val appsInGroupNotUpdatedInThisSession = appDao.getAppsInGroupButExcludingApps(
                        groupId,
                        insertedPackages.map { it.packageName }
                    )
                    println("inserted these packages for groupId $groupId: $insertedPackages")
                    if (appsInGroupNotUpdatedInThisSession.isNotEmpty()) {
                        print("warning: for groupId $groupId, these packages were inserted: ")
                        println(insertedPackages.map { "${it.label} (${it.packageName})" })
                        print("         but these packages in the same group didn't get an update: ")
                        println(appsInGroupNotUpdatedInThisSession.map { "${it.label} (${it.packageName})" })
                    }
                }
            }
            val deltaGenChannel: SendChannel<DeltaGenerationRequest> = createDeltaGenerationActor()
            try {
                var numApksInserted = 0L
                packageApkGroups.forEach { apkInsertionGroup ->
                    val nowInsertingString = "Now inserting ${apkInsertionGroup.highestVersionApk!!.label} " +
                            "(${apkInsertionGroup.packageName})"
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
                            timestampForMetadata = timestampForMetadata,
                            promptForReleaseNotes = promptForReleaseNotes,
                        )
                        deltaGenChannel.send(DeltaGenerationRequest.ForPackage(apkInsertionGroup.packageName))
                        groupIdCheckChannel.send(apkInsertionGroup)
                        numApksInserted += apkInsertionGroup.size
                    } catch (e: AppRepoException.MoreRecentVersionInRepo) {
                        println("warning: skipping insertion of ${apkInsertionGroup.packageName}:")
                        println(e.message)
                        numAppsNotInserted++
                    }
                }

                println()
                println("finished inserting ${packageApkGroups.size - numAppsNotInserted} apps ($numApksInserted APKs)")
            } finally {
                deltaGenChannel.send(DeltaGenerationRequest.StartPrinting)
                deltaGenChannel.close()
                groupIdCheckChannel.close()
            }
        }

        if (numAppsNotInserted != packageApkGroups.size.toLong()) {
            staticFilesManager.regenerateMetadataAndIcons(signingPrivateKey, timestampForMetadata)
        } else {
            println("not regenerating metadata and icons, because nothing was changed")
        }
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

    override fun getMetadataForPackage(pkg: PackageName): AppMetadata? = appDao.getSerializableAppMetadata(pkg)

    /**
     * Inserts one or more APKs for a single package.
     */
    private suspend fun insertApkGroupForSinglePackage(
        apksToInsert: PackageApkGroup.AscendingOrder,
        timestampForMetadata: UnixTimestamp,
        promptForReleaseNotes: Boolean,
    ): Unit = withContext(repoDispatcher) {
        if (apksToInsert.sortedApks.isEmpty()) {
            return@withContext
        }
        val maxVersionApk = apksToInsert.highestVersionApk!!
        val appDir = fileManager.getDirForApp(apksToInsert.packageName)
        if (!appDir.dir.exists()) {
            if (!appDir.dir.mkdirs()) {
                throw AppRepoException.InsertFailed("can't make directory $appDir")
            }
        }

        val latestRelease: AppRelease? = appDao.getLatestRelease(apksToInsert.packageName)
        if (latestRelease == null) {
            println("${maxVersionApk.label} (${apksToInsert.packageName}) is a new app")
        } else {
            val smallestVersionCodeApk = apksToInsert.lowestVersionApk!!
            if (smallestVersionCodeApk.versionCode <= latestRelease.versionCode) {
                throw AppRepoException.MoreRecentVersionInRepo(
                    "trying to insert ${smallestVersionCodeApk.packageName} with version code " +
                            "${smallestVersionCodeApk.versionCode.code} when the " +
                            "repo has latest version ${latestRelease.versionName} " +
                            "(versionCode ${latestRelease.versionCode.code}"
                )
            }
            println("previous version in repo: ${latestRelease.versionName} " +
                    "(versionCode ${latestRelease.versionCode.code}")
        }

        val sortedPreviousApks = PackageApkGroup.fromDir(appDir, aaptInvoker, ascendingOrder = true)
        validateApkSigningCertChain(newApks = apksToInsert.sortedApks, currentApks = sortedPreviousApks.sortedApks)

        val releaseNotesForMostRecentVersion: String? = if (promptForReleaseNotes) {
            promptUserForReleaseNotes(maxVersionApk.asEitherRight())
                ?.takeIf { it.isNotBlank() }
        } else {
            null
        }

        val icon = aaptInvoker.getApplicationIconFromApk(
            apkFile = maxVersionApk.apkFile,
            minimumDensity = AAPT2Invoker.Density.MEDIUM
        )
        if (icon == null) {
            println("warning: unable to extract icon for ${maxVersionApk.packageName}, ${maxVersionApk.versionCode}")
        }

        appDao.upsertApks(appDir, apksToInsert, icon, releaseNotesForMostRecentVersion, timestampForMetadata)
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
        var currentSetIntersection: Set<X509Certificate>? = null
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
    ): Set<DeltaInfo> = withContext(repoDispatcher) {
        deltaInfoDao.deleteDeltasForApp(appDir.packageName)

        val apks = PackageApkGroup.fromDir(appDir, aaptInvoker, ascendingOrder = false)
        if (apks.size <= 1) {
            return@withContext emptySet<DeltaInfo>()
        }
        check(apks is PackageApkGroup.DescendingOrder)

        val newestApk = apks.highestVersionApk!!
        val numberOfDeltasToGenerate = min(apks.size - 1, maxPreviousVersions)
        printMessageChannel?.trySend(PrintMessageType.NewPackage(apks.packageName, numberOfDeltasToGenerate))
        yield()

        var deltaGenTime = 0L
        // Drop the first element, because that's the most recent one and hence the target
        apks.sortedApks.drop(1)
            .take(numberOfDeltasToGenerate)
            .map { previousApk ->
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
                            val formattedSizeInMib = deltaSizeDecimalFormat.format(
                                outputDeltaFile.length().toDouble() / (0x1 shl 20)
                            )
                            trySend(PrintMessageType.NewLine(
                                " generated delta ${outputDeltaFile.name} (${appDir.packageName}) is " +
                                        "$formattedSizeInMib MiB --- took $deltaGenTime ms"
                            ))
                            trySend(PrintMessageType.DeltaFinished(apks.packageName))
                        }

                        return@async DeltaInfo(
                            previousApk.packageName,
                            previousApk.versionCode,
                            newestApk.versionCode,
                            Base64String.fromBytes(digest)
                        )
                    }
                }
            }
            .mapTo(TreeSet { o1, o2 -> o1.baseVersion.compareTo(o2.baseVersion) }) { it.await() }
            .also { deltaAvailableVersions ->
                printMessageChannel?.trySend(PrintMessageType.NewLine(
                    "generated ${deltaAvailableVersions.size} deltas for ${appDir.packageName}. " +
                            "Versions with deltas available: ${deltaAvailableVersions.map { it.baseVersion }}"
                ))
            }
    }

    override suspend fun setGroupForPackages(
        groupId: GroupId,
        packages: List<PackageName>,
        signingPrivateKey: PKCS8PrivateKeyFile,
        createNewGroupIfNotExists: Boolean
    ) {
        setGroupForPackagesInternal(groupId, packages, signingPrivateKey, createNewGroupIfNotExists)
    }

    override suspend fun removeGroupFromPackages(packages: List<PackageName>, signingPrivateKey: PKCS8PrivateKeyFile) {
        setGroupForPackagesInternal(groupId = null, packages, signingPrivateKey, false)
    }

    override suspend fun deleteGroup(groupId: GroupId, signingPrivateKey: PKCS8PrivateKeyFile) {
        val timestamp = UnixTimestamp.now()
        val packagesForThisGroup = appDao.getAppLabelsInGroup(groupId)
        println("removed groupId $groupId from ${packagesForThisGroup.size} groups: $packagesForThisGroup")
        staticFilesManager.regenerateMetadataAndIcons(signingPrivateKey, timestamp)
    }

    override suspend fun printAllPackages() {
        print(buildString {
            appDao.forEachAppName { appendLine(it) }
        })
    }

    override fun doesPackageExist(pkg: PackageName): Boolean {
        return appDao.doesAppExist(pkg)
    }

    override suspend fun editReleaseNotesForPackage(
        pkg: PackageName,
        versionCode: VersionCode?,
        delete: Boolean,
        signingPrivateKey: PKCS8PrivateKeyFile
    ) {
        validatePublicKeyInRepo(signingPrivateKey)

        val appToReleasePair = database.transactionWithResult<Pair<App, AppRelease>> {
            val app = appDao.getApp(pkg) ?: throw AppRepoException.EditFailed("$pkg: unable to find $pkg")

            val release = if (versionCode == null) {
                appDao.getLatestRelease(pkg)
            } else {
                appDao.getRelease(pkg, versionCode)
            } ?: throw AppRepoException.EditFailed("$pkg: unable to find release $versionCode")

            app to release
        }

        val newReleaseNotes: String? = if (!delete) {
            promptUserForReleaseNotes(appToReleasePair.asEitherLeft())
                .also { newReleaseNotes ->
                    if (newReleaseNotes == null) {
                        throw AppRepoException.EditFailed("no release notes specified")
                    }
                }
        } else {
            null
        }

        val newTimestamp = UnixTimestamp.now()
        appDao.updateReleaseNotes(pkg, appToReleasePair.second.versionCode, newReleaseNotes, newTimestamp)

        staticFilesManager.regenerateMetadataAndIcons(signingPrivateKey, newTimestamp)
    }

    override suspend fun resignMetadataForPackage(pkg: PackageName, signingPrivateKey: PKCS8PrivateKeyFile) {
        validatePublicKeyInRepo(signingPrivateKey)

        getMetadataForPackage(pkg)
            ?.writeToDiskAndSign(signingPrivateKey, openSSLInvoker, fileManager)
            ?: throw AppRepoException.EditFailed("$pkg doesn't exist in repo")
    }

    private suspend fun promptUserForReleaseNotes(
        appInfo: Either<Pair<App, AppRelease>, AndroidApk>
    ): String? {
        val textToEdit = buildString {
            val existingReleaseNotes: String?
            val label: String
            val packageName: PackageName
            val versionName: String
            val versionCode: VersionCode
            when (appInfo) {
                is Either.Left -> {
                    val (app, releaseToEdit) = appInfo.value
                    label = app.label
                    packageName = app.packageName
                    versionName = releaseToEdit.versionName
                    versionCode = releaseToEdit.versionCode
                    existingReleaseNotes = releaseToEdit.releaseNotes
                }
                is Either.Right -> {
                    val androidApk: AndroidApk = appInfo.value
                    label = androidApk.label
                    packageName = androidApk.packageName
                    versionName = androidApk.versionName
                    versionCode = androidApk.versionCode
                    existingReleaseNotes = null
                }
            }

            val pkgAndVersion = "$EDITOR_IGNORE_PREFIX $label ($packageName), version $versionName (${versionCode.code})."
            if (appInfo is Either.Left && existingReleaseNotes != null) {
                val (app, _) = appInfo.value
                append(existingReleaseNotes)
                if (!existingReleaseNotes.trimEnd().endsWith('\n')) appendLine()
                appendLine("$EDITOR_IGNORE_PREFIX Editing existing release notes for")
                appendLine(pkgAndVersion)
                appendLine(
                    "$EDITOR_IGNORE_PREFIX Last edited: ${
                        ZonedDateTime.ofInstant(
                            Instant.ofEpochSecond(app.lastUpdateTimestamp.seconds),
                            ZoneId.systemDefault()
                        ).format(DateTimeFormatter.RFC_1123_DATE_TIME)
                    }"
                )
            } else {
                appendLine()
                appendLine("$EDITOR_IGNORE_PREFIX Enter new release notes for")
                appendLine(pkgAndVersion)
            }
            appendLine("$EDITOR_IGNORE_PREFIX Lines or sections starting with $EDITOR_IGNORE_PREFIX are ignored.")
            append("$EDITOR_IGNORE_PREFIX Text can be plaintext or HTML.")
        }

        return asyncPrintMutex.withLock {
            TermUi.editText(
                text = textToEdit,
                requireSave = true
            )?.replace(Regex("$EDITOR_IGNORE_PREFIX[^\n]*\n"), "")
                ?.trimEnd('\n')
        }
    }

    override suspend fun printAllGroups() {
        println(groupDao.getGroupToAppMap())
    }

    private suspend fun setGroupForPackagesInternal(
        groupId: GroupId?,
        packages: List<PackageName>,
        signingPrivateKey: PKCS8PrivateKeyFile,
        createNewGroupIfNotExists: Boolean
    ) {
        validatePublicKeyInRepo(signingPrivateKey)

        val distinctInputPackages = packages.distinct()
        distinctInputPackages.forEach { pkg ->
            if (!appDao.doesAppExist(pkg)) {
                throw AppRepoException.EditFailed("package $pkg does not exist in the repo")
            }
        }

        val timestampForMetadata = UnixTimestamp.now()
        if (groupId != null && !groupDao.doesGroupExist(groupId)) {
            val allGroups = groupDao.getGroupToAppMap()
            if (createNewGroupIfNotExists) {
                println("creating new group $groupId")
                groupDao.createGroupWithPackages(groupId, distinctInputPackages, timestampForMetadata)
            } else {
                val groupInfoString = if (allGroups.isEmpty()) {
                    "There are no available groups"
                } else {
                    "Available groups: $allGroups"
                }
                throw AppRepoException.GroupDoesntExist(
                    """groupId $groupId does not exist in the repository.
                    $groupInfoString
                    Use --create (-c) instead of --add (-a) to create this group with these packages
                """.trimIndent()
                )
            }
        } else {
            appDao.setGroupForPackages(groupId, distinctInputPackages, timestampForMetadata)
        }

        staticFilesManager.regenerateMetadataAndIcons(signingPrivateKey, timestampForMetadata)
        groupId
            ?.let { println("packages were successfully set to the group $groupId") }
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
