package org.grapheneos.appupdateservergenerator.repo

import com.github.ajalt.clikt.output.TermUi
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.grapheneos.appupdateservergenerator.api.AppMetadata
import org.grapheneos.appupdateservergenerator.api.AppRepoIndex
import org.grapheneos.appupdateservergenerator.api.BulkAppMetadata
import org.grapheneos.appupdateservergenerator.crypto.OpenSSLInvoker
import org.grapheneos.appupdateservergenerator.crypto.PEMPublicKey
import org.grapheneos.appupdateservergenerator.crypto.PKCS8PrivateKeyFile
import org.grapheneos.appupdateservergenerator.db.App
import org.grapheneos.appupdateservergenerator.db.AppDao
import org.grapheneos.appupdateservergenerator.db.AppRelease
import org.grapheneos.appupdateservergenerator.db.DbWrapper
import org.grapheneos.appupdateservergenerator.files.AppDir
import org.grapheneos.appupdateservergenerator.files.FileManager
import org.grapheneos.appupdateservergenerator.files.TempFile
import org.grapheneos.appupdateservergenerator.model.AndroidApk
import org.grapheneos.appupdateservergenerator.model.Base64String
import org.grapheneos.appupdateservergenerator.model.Density
import org.grapheneos.appupdateservergenerator.model.HexString
import org.grapheneos.appupdateservergenerator.model.PackageApkGroup
import org.grapheneos.appupdateservergenerator.model.PackageName
import org.grapheneos.appupdateservergenerator.model.UnixTimestamp
import org.grapheneos.appupdateservergenerator.model.VersionCode
import org.grapheneos.appupdateservergenerator.model.encodeToBase64String
import org.grapheneos.appupdateservergenerator.model.encodeToHexString
import org.grapheneos.appupdateservergenerator.util.ArchivePatcherUtil
import org.grapheneos.appupdateservergenerator.util.Either
import org.grapheneos.appupdateservergenerator.util.asEitherLeft
import org.grapheneos.appupdateservergenerator.util.asEitherRight
import org.grapheneos.appupdateservergenerator.util.digest
import org.grapheneos.appupdateservergenerator.util.symmetricDifference
import java.io.File
import java.io.FileFilter
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.SortedSet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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

    suspend fun printAllPackages()

    suspend fun doesPackageExist(pkg: PackageName): Boolean

    suspend fun getLatestRelease(pkg: PackageName): AppRelease?

    /**
     * Edits the release notes for the given [pkg] and then regenerates all metadata, singing it using the
     * [signingPrivateKey].
     *
     * If [versionCode] is null, it will edit the most recent release; otherwise, it will edit release notes for that
     * particular release.
     */
    suspend fun editReleaseNotesForPackage(
        pkg: PackageName,
        versionCode: VersionCode? = null,
        delete: Boolean,
        signingPrivateKey: PKCS8PrivateKeyFile
    )
}

fun AppRepoManager(
    fileManager: FileManager,
    openSSLInvoker: OpenSSLInvoker,
    numJobs: Int
): AppRepoManager = AppRepoManagerImpl(fileManager, openSSLInvoker, numJobs)

/**
 * The implementation of [AppRepoManager].
 * @see AppRepoManager
 */
@ObsoleteCoroutinesApi
private class AppRepoManagerImpl(
    private val fileManager: FileManager,
    private val openSSLInvoker: OpenSSLInvoker,
    numJobs: Int
): AppRepoManager {
    companion object {
        private const val HTML_COMMENT_FORMAT = "<!--_ %s -->"
        private const val MAX_CONCURRENT_DELTA_APPLIES_FOR_VERIFICATION = 4
    }

    private val executorService: ExecutorService = Executors.newFixedThreadPool(numJobs)
    private val repoDispatcher = executorService.asCoroutineDispatcher()

    private val dbWrapper: DbWrapper = DbWrapper.getInstance(fileManager)
    private val appDao = AppDao(fileManager)

    private val staticFilesManager = StaticFileManager(appDao, fileManager, openSSLInvoker)
    private val deltaGenerationManager = DeltaGenerationManager(fileManager)

    /**
     * @see AppRepoManager.validateRepo
     */
    override suspend fun validateRepo(): Unit = withContext(repoDispatcher) {
        // TODO: rewrite this
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
        val packagesFromDirectoryListing = packageDirs.mapTo(sortedSetOf()) { PackageName(it.name) }
        val packagesFromAllMetadataOnDisk = metadataFromDirs.mapTo(sortedSetOf()) { it.packageName }
        if (packagesFromDirectoryListing != packagesFromAllMetadataOnDisk) {
            val problemDirectories = packagesFromDirectoryListing symmetricDifference packagesFromAllMetadataOnDisk
            throw AppRepoException.InvalidRepoState(
                "some directories in ${fileManager.appDirectory} are not valid app directories: $problemDirectories"
            )
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

    private val deltaApplicationSemaphore by lazy { Semaphore(MAX_CONCURRENT_DELTA_APPLIES_FOR_VERIFICATION) }

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
        if (metadata.lastUpdateTimestamp > existingIndex.repoUpdateTimestamp) {
            throw AppRepoException.InvalidRepoState(
                "$pkg: metadata timestamp (${metadata.lastUpdateTimestamp}) " +
                        "> index timestamp (${existingIndex.repoUpdateTimestamp})"
            )
        }
        val apks = appDir.listApkFilesUnsorted().sortedBy { it.nameWithoutExtension.toInt() }
        if (apks.isEmpty()) {
            throw AppRepoException.InvalidRepoState("$pkg: app directory with no APKs in it")
        }

        val latestVersionCodeFromFileName: Long = apks.last().nameWithoutExtension.toLong()

        val latestRelease = metadata.latestReleaseOrNull()
            ?: throw AppRepoException.InvalidRepoState("no releases for ${metadata.packageName}")

        if (latestRelease.versionCode.code != latestVersionCodeFromFileName) {
            throw AppRepoException.InvalidRepoState(
                "$pkg: metadata latestVersionCode (${latestRelease.versionCode.code}) " +
                        "> apk version from file name (${apks.last()})"
            )
        }
        if (latestRelease.versionCode != latestRelease.versionCode) {
            throw AppRepoException.InvalidRepoState(
                "$pkg: metadata latestVersionCode (${latestRelease.versionCode.code}) " +
                        "doesn't match the actual most recent release's version code in the releases array"
            )
        }

        val latestApk = AndroidApk.buildFromApkAndVerifySignature(apks.last())
        if (latestRelease.versionCode != latestApk.versionCode) {
            throw AppRepoException.InvalidRepoState(
                "$pkg: latest APK versionCode in manifest mismatches with filename"
            )
        }
        val latestApkDigest = Base64String.encodeFromBytes(
            latestApk.apkFile.digest(MessageDigest.getInstance("SHA-256"))
        )

        if (latestRelease.apkSha256 != latestApkDigest) {
            throw AppRepoException.InvalidRepoState(
                "$pkg: sha256 checksum from latest apk file doesn't match the checksum in the metadata"
            )
        }
        val iconFile = fileManager.getAppIconFile(latestApk.packageName)
        val icon = try {
            latestApk.getIcon(Density.HIGH)
        } catch (e: IOException) {
            null
        }
        if (icon != null) {
            val iconFileBytes = iconFile.readBytes()
            if (!iconFileBytes.contentEquals(icon.bytes)) {
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
                if (targetVersion != latestRelease.versionCode) {
                    throw AppRepoException.InvalidRepoState(
                        "$pkg: delta file ${file.name} doesn't target latest version ${latestRelease.versionCode}"
                    )
                }

                baseVersion
            }
            .sorted()
        val baseDeltaVersionsToFileMapFromMetadata: Map<VersionCode, AppMetadata.ReleaseInfo.DeltaInfo> =
            latestRelease.deltaInfo?.associateBy { it.baseVersionCode } ?: emptyMap()

        if (baseDeltaVersionsToFileMapFromMetadata.keys.sorted() != baseDeltaVersionsFromDirFiles) {
            val problemVersions = (baseDeltaVersionsToFileMapFromMetadata.keys symmetricDifference
                    baseDeltaVersionsFromDirFiles)
                .map { it.code }

            throw AppRepoException.InvalidRepoState(
                "$pkg: mismatch between delta files in repo and delta files in metadata for these base versions: " +
                        "$problemVersions"
            )
        }

        val versionCodesFromApksInDir = apks.mapTo(sortedSetOf()) { VersionCode(it.nameWithoutExtension.toLong()) }
        // check if there are deltas that have missing base APKs
        val baseDeltaVersionsFromMetadata = baseDeltaVersionsToFileMapFromMetadata.mapTo(sortedSetOf()) { it.key }
        if (baseDeltaVersionsFromMetadata.isNotEmpty()) {
            val baseVersionCodesFromApksInDir = versionCodesFromApksInDir
                .subSet(baseDeltaVersionsFromMetadata.first(), latestRelease.versionCode)

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

        val parsedApks: SortedSet<AndroidApk> = coroutineScope {
            apks.map { apkFile ->
                async {
                    val parsedApk = AndroidApk.buildFromApkAndVerifySignature(apkFile)
                    if (parsedApk.packageName != metadata.packageName) {
                        throw AppRepoException.InvalidRepoState(
                            "$pkg: mismatch between metadata package and package from manifest ($apkFile)"
                        )
                    }

                    val releaseInfoForThisVersion = metadata.releases.find { it.versionCode == parsedApk.versionCode }
                        ?: throw AppRepoException.InvalidRepoState(
                            "$pkg: can't find release for APK version ${parsedApk.versionCode}"
                        )

                    if (parsedApk.versionCode.code != apkFile.nameWithoutExtension.toLong()) {
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
                    if (parsedApk.versionCode > latestRelease.versionCode) {
                        throw AppRepoException.InvalidRepoState(
                            "$pkg: APK ($apkFile) exceeds the latest version code from metadata"
                        )
                    }
                    if (parsedApk.apkFile.digest("SHA-256").encodeToBase64String() !=
                        releaseInfoForThisVersion.apkSha256
                    ) {
                        throw AppRepoException.InvalidRepoState(
                            "$pkg: APK ($apkFile) sha256 checksum mismatches the release's checksum"
                        )
                    }

                    if (parsedApk.versionCode in baseDeltaVersionsToFileMapFromMetadata.keys) {
                        val deltaFile = fileManager.getDeltaFileForApp(
                            metadata.packageName,
                            parsedApk.versionCode,
                            latestRelease.versionCode
                        )
                        if (!deltaFile.exists()) {
                            throw AppRepoException.InvalidRepoState(
                                "$pkg: missing a delta file from ${parsedApk.versionCode} " +
                                        "to ${latestRelease.versionCode}"
                            )
                        }
                        val deltaInfo = baseDeltaVersionsToFileMapFromMetadata[parsedApk.versionCode]
                            ?: error("impossible")
                        val deltaDigest = Base64String.encodeFromBytes(deltaFile.digest("SHA-256"))
                        if (deltaDigest != deltaInfo.sha256) {
                            throw AppRepoException.InvalidRepoState(
                                "$pkg: delta file $deltaFile checksum doesn't match metadata checksum for $deltaInfo"
                            )
                        }

                        deltaVerificationChannel.send(Triple(parsedApk, deltaFile, latestApk))
                    }

                    parsedApk
                }
            }.awaitAll()
                .toSortedSet(AndroidApk.descendingVersionCodeComparator)
        }
        deltaVerificationChannel.close()
        validateApkSigningCertChain(parsedApks)
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

    sealed interface VerificationMessage {
        @JvmInline
        value class Error(val message: String) : VerificationMessage
        @JvmInline
        value class Warning(val message: String) : VerificationMessage
    }

    suspend fun Channel<VerificationMessage>.sendError(message: String) = send(VerificationMessage.Error(message))

    suspend fun Channel<VerificationMessage>.sendWarning(message: String) = send(VerificationMessage.Warning(message))

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
        val packageApkGroupsToInsert: List<PackageApkGroup.AscendingOrder>
        val timeTaken = measureTimeMillis {
            packageApkGroupsToInsert = PackageApkGroup.createListFromFilesAscending(apkFilePaths)
        }
        println("took $timeTaken ms to parse APKs")
        println("found the following packages: ${packageApkGroupsToInsert.map { it.packageName }}")

        validateInputApks(packageApkGroupsToInsert)

        val timestampForMetadata = UnixTimestamp.now()

        var numAppsNotInserted = 0L
        coroutineScope {
            // Actors are coroutines, so after this block is done, this
            // coroutine scope waits until the delta generation actor is finished.
            val deltaGenChannel: SendChannel<DeltaGenerationManager.GenerationRequest> = deltaGenerationManager.run {
                launchDeltaGenerationActor()
            }
            try {
                var numApksInserted = 0L

                // Run this entire insertion as a transaction so that failures are rolled back.
                dbWrapper.transaction { db ->
                    val newDirectories by lazy { mutableListOf<AppDir>() }
                    afterRollback {
                        println("rolling back due to error: deleting new dirs for ${newDirectories.map { it.packageName }}")
                        newDirectories.forEach { it.dir.deleteRecursively() }
                    }

                    for (apkGroupToInsert in packageApkGroupsToInsert) {
                        if (apkGroupToInsert.sortedApks.isEmpty()) {
                            // Should not happen.
                            error("trying to insert APKs with no packages")
                        }

                        val nowInsertingString = "Now inserting ${apkGroupToInsert.highestVersionApk!!.label} " +
                                "(${apkGroupToInsert.packageName})"
                        val versionCodeString = "Version codes: ${apkGroupToInsert.sortedApks.map { it.versionCode.code }}"
                        val width = max(versionCodeString.length, nowInsertingString.length)
                        val equalSigns = "=".repeat(width)
                        println(
                            """
                                $equalSigns
                                $nowInsertingString
                                $versionCodeString
                                $equalSigns
                            """.trimIndent()
                        )

                        val maxVersionApk = apkGroupToInsert.highestVersionApk!!
                        val appDir = fileManager.getDirForApp(apkGroupToInsert.packageName)
                        if (!appDir.dir.exists()) {
                            if (!appDir.dir.mkdirs()) {
                                throw AppRepoException.InsertFailed("can't make directory $appDir")
                            } else {
                                newDirectories.add(appDir)
                            }
                        }

                        val latestRelease: AppRelease? = appDao.getLatestRelease(db, apkGroupToInsert.packageName)
                        if (latestRelease == null) {
                            println("${maxVersionApk.label} (${apkGroupToInsert.packageName}) is a new app")
                        } else {
                            val smallestVersionCodeApk = apkGroupToInsert.lowestVersionApk!!
                            if (smallestVersionCodeApk.versionCode <= latestRelease.versionCode) {
                                println(
                                    "warning: skipping insertion of ${apkGroupToInsert.packageName}: " +
                                            "trying to insert ${smallestVersionCodeApk.packageName} with version code " +
                                            "${smallestVersionCodeApk.versionCode.code} when the " +
                                            "repo has latest version ${latestRelease.versionName} " +
                                            "(versionCode ${latestRelease.versionCode.code})"
                                )
                                numAppsNotInserted++
                                continue
                            }
                            println(
                                "previous version in repo: ${latestRelease.versionName} " +
                                        "(versionCode ${latestRelease.versionCode.code})"
                            )
                        }

                        val sortedPreviousApks = runBlocking(repoDispatcher) {
                            PackageApkGroup.fromDir(appDir, ascendingOrder = true)
                        }
                        validateApkSigningCertChain(apkGroupToInsert.sortedApks union sortedPreviousApks.sortedApks)

                        val releaseNotesForMostRecentVersion: String? = if (promptForReleaseNotes) {
                            runBlocking(repoDispatcher) {
                                promptUserForReleaseNotes(maxVersionApk.asEitherRight())
                                    ?.takeIf { it.isNotBlank() }
                            }
                        } else {
                            null
                        }

                        // This will handle copying the APKs.
                        appDao.upsertApks(db, apkGroupToInsert, releaseNotesForMostRecentVersion, timestampForMetadata)

                        deltaGenChannel.trySend(
                            DeltaGenerationManager.GenerationRequest.ForPackage(apkGroupToInsert.packageName)
                        ).onFailure {
                            // Channel.UNLIMITED is being used. Not going to be dealing with buffers.
                            // Only reason this would happen is if the delta gen actor failed entirely.
                            println("warning: unable to generate deltas for ${apkGroupToInsert.packageName}")
                        }
                        numApksInserted += apkGroupToInsert.size
                    }
                }

                println()
                println("finished inserting ${packageApkGroupsToInsert.size - numAppsNotInserted} apps ($numApksInserted APKs)")
            } finally {
                dbWrapper.executeWalCheckpointTruncate()
                deltaGenChannel.send(DeltaGenerationManager.GenerationRequest.StartPrinting)
                deltaGenChannel.close()
            }
            // Execution might stop here until delta generation finishes.
        }
        // Delta generation is finished
        dbWrapper.executeWalCheckpointTruncate()

        if (numAppsNotInserted != packageApkGroupsToInsert.size.toLong()) {
            staticFilesManager.regenerateMetadataAndIcons(signingPrivateKey, timestampForMetadata)
        } else {
            println("not regenerating metadata and icons, because nothing was changed")
        }
    }

    private suspend fun validateInputApks(packageApkGroupsToInsert: List<PackageApkGroup.AscendingOrder>) {
        val errorsAndWarnings = Channel<VerificationMessage>(capacity = Channel.UNLIMITED)

        /**
         * Finds an [AndroidApk] in the app repo. If [versionCode] is null, it will try to find any APK
         * for the given [pkg]; otherwise, it will try to find an APK for a specific version and returns null if
         * it can't find an APK with the version.
         */
        suspend fun findApkInRepo(pkg: PackageName, versionCode: VersionCode?): AndroidApk? {
            val apksInRepo: PackageApkGroup =
                try {
                    PackageApkGroup.fromDirDescending(fileManager.getDirForApp(pkg))
                } catch (e: IllegalArgumentException) {
                    return null
                } catch (e: IOException) {
                    // might be thrown if the app doesn't exist in the repo
                    return null
                }

            return if (versionCode != null) {
                apksInRepo.sortedApks.find { it.versionCode == versionCode }
            } else {
                apksInRepo.highestVersionApk
            }
        }

        /**
         * Finds an [AndroidApk] in the command-line arguments. If [versionCode] is null, it will try to find any
         * APK for the given [pkg]; otherwise, it will try to find an APK for a specific version and returns null if
         * it can't find an APK with the version.
         */
        fun findApkInGivenPackagesToInsert(pkg: PackageName, versionCode: VersionCode?): AndroidApk? {
            return packageApkGroupsToInsert.find { it.packageName == pkg }
                ?.let { apkGroup ->
                    if (versionCode != null) {
                        apkGroup.sortedApks.find { it.versionCode == versionCode }
                    } else {
                        apkGroup.highestVersionApk
                    }
                }
        }

        coroutineScope {
            packageApkGroupsToInsert.forEach { apkGroup ->
                launch {
                    apkGroup.sortedApks.forEach { apk ->
                        if (apk.debuggable) {
                            errorsAndWarnings.sendError(
                                "${apk.apkFile} is marked as debuggable, which is not allowed for security reasons. " +
                                        "The Android platform loosens security checks for such APKs."
                            )
                        }

                        // Verify that the static libraries are present in the repo, because static libraries are
                        // strictly required for an app to run.
                        apk.usesStaticLibraries.forEach useStaticLibraries@{ staticLibrary ->
                            println(
                                "verifying ${apk.packageName} (versionCode ${apk.versionCode.code})'s " +
                                        "dependency on $staticLibrary'"
                            )
                            val staticLibraryApk: AndroidApk =
                                findApkInRepo(staticLibrary.name, staticLibrary.version)
                                    ?: findApkInGivenPackagesToInsert(staticLibrary.name, staticLibrary.version)
                                    ?: errorsAndWarnings.send(
                                        VerificationMessage.Error(
                                            "trying to insert ${apk.packageName} ${apk.versionCode}, " +
                                                    "and it depends on $staticLibrary; however, " +
                                                    "the static library cannot be found in either " +
                                                    "the supplied APKs or in the existing repo files"
                                        )
                                    ).let {
                                        return@useStaticLibraries
                                    }

                            val certDigestFromStaticLibApk: Set<HexString> = staticLibraryApk.verifyResult.result
                                .signerCertificates
                                .asSequence()
                                .map { it.encoded.digest("SHA-256").encodeToHexString() }
                                .toSet()

                            if (certDigestFromStaticLibApk != staticLibrary.certDigests.toHashSet()) {
                                errorsAndWarnings.sendError(
                                    "trying to insert ${apk.packageName} (versionCode ${apk.versionCode.code}), " +
                                            "and it depends on $staticLibrary; however, " +
                                            "the static library has certificate digests ${staticLibrary.certDigests} " +
                                            "but the located static library in the app / parameters has certificate " +
                                            "digests $certDigestFromStaticLibApk"
                                )
                            }
                        }

                        // Verify that the uses-library tag is satisfied.
                        apk.usesLibraries.forEach { library ->
                            if (library.required) {
                                println(
                                    "verifying ${apk.packageName} (versionCode ${apk.versionCode.code})'s " +
                                            "hard dependency on $library'"
                                )
                            } else {
                                println(
                                    "verifying ${apk.packageName} (versionCode ${apk.versionCode.code})'s " +
                                            "soft dependency on $library'"
                                )
                            }

                            val libraryApk: AndroidApk? = findApkInRepo(library.name, null)
                                ?: findApkInGivenPackagesToInsert(library.name, null)
                            if (libraryApk == null) {
                                if (library.required) {
                                    errorsAndWarnings.sendError(
                                        "trying to insert ${apk.packageName} ${apk.versionCode}, " +
                                                "and it depends on $library; however, " +
                                                "the library cannot be found in either " +
                                                "the supplied APKs or in the existing repo files"
                                    )
                                } else {
                                    errorsAndWarnings.sendWarning(
                                        "${apk.packageName} (versionCode ${apk.versionCode.code}) has a " +
                                                "uses-library dependency on $library, but was unable to find the " +
                                                "library in either the repository or in the given packages for " +
                                                "insertion"
                                    )
                                }
                            }
                        }

                        apk.usesPackages.forEach usesPackagesCheck@{ packageDep ->
                            println(
                                "verifying ${apk.packageName} (versionCode ${apk.versionCode.code})'s " +
                                        "uses-package dependency on $packageDep'"
                            )

                            val apksFromRepo: SortedSet<AndroidApk>? = try {
                                PackageApkGroup.fromDirDescending(fileManager.getDirForApp(packageDep.name)).sortedApks
                            } catch (e: IllegalArgumentException) {
                                null
                            } catch (e: IOException) {
                                // might be thrown if the app doesn't exist in the repo
                                null
                            }
                            val apksFromArguments: SortedSet<AndroidApk>? = packageApkGroupsToInsert
                                .find { it.packageName == packageDep.name }
                                ?.sortedApks

                            var sequence = emptySequence<AndroidApk>()
                            if (apksFromRepo != null) {
                                sequence += apksFromRepo.asSequence()
                            }
                            if (apksFromArguments != null) {
                                sequence += apksFromArguments.asSequence()
                            }
                            val packageDepCertsSet: Set<HexString>? = packageDep.certDigests?.toSet()
                            val areThereAnyApksThatSatisfyPackageDependency: Boolean =
                                sequence
                                    .map { apk ->
                                        val version = apk.versionCode
                                        val certDigests = apk.verifyResult.result.signerCertificates
                                            .asSequence()
                                            .map { it.encoded.digest("SHA-256").encodeToHexString() }
                                            .toSet()
                                        version to certDigests
                                    }
                                    .filter { (versionFromApk, certsFromApk) ->
                                        // Only filter for optional qualifiers that are present.
                                        if (packageDep.minimumVersion != null) {
                                            if (versionFromApk < packageDep.minimumVersion) return@filter false
                                        }
                                        if (packageDepCertsSet != null) {
                                            if (packageDepCertsSet != certsFromApk) return@filter false
                                        }
                                        true
                                    }
                                    .any()

                            if (!areThereAnyApksThatSatisfyPackageDependency) {
                                errorsAndWarnings.sendWarning(
                                    "${apk.packageName} (versionCode ${apk.versionCode.code}) has a " +
                                            "uses-package dependency on $packageDep, but was unable to find a " +
                                            "suitable package in either the repository or in the given packages " +
                                            "for insertion"
                                )
                            }
                        }
                    }
                }
            }
        }
        errorsAndWarnings.close()
        val warnings = mutableListOf<VerificationMessage.Warning>()
        val errors = mutableListOf<VerificationMessage.Error>()
        errorsAndWarnings.consumeEach {
            if (it is VerificationMessage.Warning) warnings.add(it) else errors.add(it as VerificationMessage.Error)
        }
        warnings.forEach { println("warning: ${it.message}") }
        errors.forEach { println("error: ${it.message}") }
        if (errors.isNotEmpty()) {
            throw AppRepoException.InsertFailed(
                "${errors.size} ${if (errors.size == 1) "error" else "errors"} encountered when inserting"
            )
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

    override suspend fun getLatestRelease(pkg: PackageName): AppRelease? = dbWrapper.useDatabase{ db ->
        appDao.getLatestRelease(db, pkg)
    }

    /**
     * Validates that the collection of [apks] have a valid certificate chain (i.e., installing all of the [apks] in
     * successive order starting from the lowest version code would not result in an error on Android).
     *
     * @throws AppRepoException.ApkSigningCertMismatch if there is a missing certificate
     */
    private fun validateApkSigningCertChain(apks: Collection<AndroidApk>) {
        if (apks.isEmpty()) {
            return
        }

        val apksSortedAscendingOrder: SortedSet<AndroidApk> =
            if (apks is SortedSet<AndroidApk> && apks.comparator() == AndroidApk.ascendingVersionCodeComparator) {
                apks
            } else {
                apks.toSortedSet(AndroidApk.ascendingVersionCodeComparator)
            }

        var previousApk: AndroidApk? = null
        // Determine if the older package's signing certs are a subset of the packages in the next newer version.
        // See https://android.googlesource.com/platform/frameworks/base/+/6c942b9dc9bbd3d607c8601907ffa4a5c7a4a606/services/core/java/com/android/server/pm/KeySetManagerService.java#365
        // https://android.googlesource.com/platform/frameworks/base/+/6c942b9dc9bbd3d607c8601907ffa4a5c7a4a606/services/core/java/com/android/server/pm/PackageManagerServiceUtils.java#619
        // https://android.googlesource.com/platform/frameworks/base/+/6c942b9dc9bbd3d607c8601907ffa4a5c7a4a606/core/java/android/content/pm/PackageParser.java#6270
        apksSortedAscendingOrder.forEach { currentApk ->
            previousApk?.let {
                val previousCertificates = it.verifyResult.result.signerCertificates
                val currentCertificates = currentApk.verifyResult.result.signerCertificates
                if (!currentCertificates.containsAll(previousCertificates)) {
                    throw AppRepoException.ApkSigningCertMismatch(
                        "failed to verify signing cert chain\n" +
                                "dumping details: $apksSortedAscendingOrder"
                    )
                }
            }
            previousApk = currentApk
        }
    }

    override suspend fun printAllPackages() {
        dbWrapper.useDatabase { db ->
            print(buildString {
                appDao.forEachAppName(db) { appendLine(it) }
            })
        }
    }

    override suspend fun doesPackageExist(pkg: PackageName): Boolean {
        return dbWrapper.useDatabase { db -> appDao.doesAppExist(db, pkg) }
    }

    override suspend fun editReleaseNotesForPackage(
        pkg: PackageName,
        versionCode: VersionCode?,
        delete: Boolean,
        signingPrivateKey: PKCS8PrivateKeyFile
    ) {
        validatePublicKeyInRepo(signingPrivateKey)

        val appToReleasePair = dbWrapper.transactionWithResult<Pair<App, AppRelease>> { db ->
            val app = appDao.getApp(db, pkg) ?: throw AppRepoException.EditFailed("$pkg: unable to find $pkg")

            val release = if (versionCode == null) {
                appDao.getLatestRelease(db, pkg)
            } else {
                appDao.getRelease(db, pkg, versionCode)
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
        dbWrapper.useDatabase { db ->
            appDao.updateReleaseNotes(db, pkg, appToReleasePair.second.versionCode, newReleaseNotes, newTimestamp)
        }

        staticFilesManager.regenerateMetadataAndIcons(signingPrivateKey, newTimestamp)
    }

    private suspend fun promptUserForReleaseNotes(
        appInfo: Either<Pair<App, AppRelease>, AndroidApk>
    ): String? {
        val textToEdit = buildString {
            val existingReleaseNotes: String?
            val label: String?
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
            val infoString = if (label != null) {
                "$label ($packageName), version $versionName (${versionCode.code})."
            } else {
                "$packageName (no label), version $versionName (${versionCode.code})."
            }
            val pkgAndVersion = HTML_COMMENT_FORMAT.format(infoString)
            if (appInfo is Either.Left && existingReleaseNotes != null) {
                val (app, _) = appInfo.value
                append(existingReleaseNotes)
                if (!existingReleaseNotes.trimEnd().endsWith('\n')) appendLine()
                appendLine(HTML_COMMENT_FORMAT.format("Editing existing release notes for"))
                appendLine(pkgAndVersion)
                appendLine(HTML_COMMENT_FORMAT.format(
                    "Last edited: ${
                        ZonedDateTime.ofInstant(
                            Instant.ofEpochSecond(app.lastUpdateTimestamp.seconds),
                            ZoneId.systemDefault()
                        ).format(DateTimeFormatter.RFC_1123_DATE_TIME)
                    }"
                ))
            } else {
                appendLine()
                appendLine(HTML_COMMENT_FORMAT.format("Enter new release notes for"))
                appendLine(pkgAndVersion)
            }
            append(HTML_COMMENT_FORMAT.format("You can use GitHub Flavored Markdown / HTML."))
        }

        return deltaGenerationManager.asyncPrintMutex.withLock {
            TermUi.editText(
                text = textToEdit,
                requireSave = true
            )?.replace(Regex("<!--_? [^\n]* -->\n"), "")
                ?.trimEnd('\n')
                ?.takeIf { it.isNotBlank() }
        }
    }
}
