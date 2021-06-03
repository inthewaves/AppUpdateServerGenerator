package org.grapheneos.appupdateservergenerator.repo

import com.github.ajalt.clikt.output.TermUi
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.grapheneos.appupdateservergenerator.api.AppMetadata
import org.grapheneos.appupdateservergenerator.api.AppRepoIndex
import org.grapheneos.appupdateservergenerator.api.BulkAppMetadata
import org.grapheneos.appupdateservergenerator.apkparsing.AAPT2Invoker
import org.grapheneos.appupdateservergenerator.crypto.OpenSSLInvoker
import org.grapheneos.appupdateservergenerator.crypto.PEMPublicKey
import org.grapheneos.appupdateservergenerator.crypto.PKCS8PrivateKeyFile
import org.grapheneos.appupdateservergenerator.db.App
import org.grapheneos.appupdateservergenerator.db.AppDao
import org.grapheneos.appupdateservergenerator.db.AppRelease
import org.grapheneos.appupdateservergenerator.db.Database
import org.grapheneos.appupdateservergenerator.db.DbWrapper
import org.grapheneos.appupdateservergenerator.db.GroupDao
import org.grapheneos.appupdateservergenerator.db.PackageLabelsByGroup
import org.grapheneos.appupdateservergenerator.files.AppDir
import org.grapheneos.appupdateservergenerator.files.FileManager
import org.grapheneos.appupdateservergenerator.files.TempFile
import org.grapheneos.appupdateservergenerator.model.AndroidApk
import org.grapheneos.appupdateservergenerator.model.Base64String
import org.grapheneos.appupdateservergenerator.model.GroupId
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
    openSSLInvoker: OpenSSLInvoker,
    numJobs: Int
): AppRepoManager = AppRepoManagerImpl(fileManager, aaptInvoker, openSSLInvoker, numJobs)

/**
 * The implementation of [AppRepoManager].
 * @see AppRepoManager
 */
@ObsoleteCoroutinesApi
private class AppRepoManagerImpl(
    private val fileManager: FileManager,
    private val aaptInvoker: AAPT2Invoker,
    private val openSSLInvoker: OpenSSLInvoker,
    numJobs: Int
): AppRepoManager {
    companion object {
        private const val EDITOR_IGNORE_PREFIX = "//##:"
        private const val MAX_CONCURRENT_DELTA_APPLIES_FOR_VERIFICATION = 4
    }

    private val executorService: ExecutorService = Executors.newFixedThreadPool(numJobs)
    private val repoDispatcher = executorService.asCoroutineDispatcher()

    private val database: Database = DbWrapper.getDbInstance(fileManager)
    private val appDao = AppDao(database)
    private val groupDao = GroupDao(database)

    private val staticFilesManager = MetadataFileManager(database, appDao, fileManager, openSSLInvoker)
    private val deltaGenerationManager = DeltaGenerationManager(fileManager, database, aaptInvoker)

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

        val latestVersionCodeFromFileName: Long = apks.last().nameWithoutExtension.toLong()
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

        val latestApk = AndroidApk.buildFromApkAndVerifySignature(apks.last(), aaptInvoker)
        if (metadata.latestRelease().versionCode != latestApk.versionCode) {
            throw AppRepoException.InvalidRepoState(
                "$pkg: latest APK versionCode in manifest mismatches with filename"
            )
        }
        val latestApkDigest = Base64String.encodeFromBytes(
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

        val versionCodesFromApksInDir = apks.mapTo(sortedSetOf()) { VersionCode(it.nameWithoutExtension.toLong()) }
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

        val parsedApks: SortedSet<AndroidApk> = coroutineScope {
            apks.map { apkFile ->
                async {
                    val parsedApk = AndroidApk.buildFromApkAndVerifySignature(apkFile, aaptInvoker)
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
                        val deltaDigest = Base64String.encodeFromBytes(deltaFile.digest("SHA-256"))
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
                PackageApkGroup.createListFromFilesAscending(
                    apkFilePaths = apkFilePaths,
                    aaptInvoker = aaptInvoker
                )
            } catch (e: IOException) {
                throw AppRepoException.AppDetailParseFailed("unable to get Android app details", e)
            }
        }
        println("took $timeTaken ms to parse APKs")
        println("found the following packages: ${packageApkGroups.map { it.packageName }}")

        packageApkGroups.forEach { apkGroup ->
            apkGroup.sortedApks.forEach { apk ->
                if (apk.debuggable) {
                    throw AppRepoException.InsertFailed(
                        "${apk.apkFile} is marked as debuggable, which is not allowed for security reasons. " +
                                "The Android platform loosens security checks for such APKs."
                    )
                }
            }
        }

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

            val deltaGenChannel: SendChannel<DeltaGenerationManager.GenerationRequest> =
                deltaGenerationManager.run { launchDeltaGenerationActor() }
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
                        deltaGenChannel.send(DeltaGenerationManager.GenerationRequest.ForPackage(
                            apkInsertionGroup.packageName)
                        )
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
                deltaGenChannel.send(DeltaGenerationManager.GenerationRequest.StartPrinting)
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
                            "(versionCode ${latestRelease.versionCode.code})"
                )
            }
            println("previous version in repo: ${latestRelease.versionName} " +
                    "(versionCode ${latestRelease.versionCode.code})")
        }

        val sortedPreviousApks = PackageApkGroup.fromDir(appDir, aaptInvoker, ascendingOrder = true)
        validateApkSigningCertChain(apksToInsert.sortedApks union sortedPreviousApks.sortedApks)

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
            if (apks !is SortedSet<AndroidApk> || apks.comparator() != AndroidApk.ascendingVersionCodeComparator) {
                apks.toSortedSet(AndroidApk.ascendingVersionCodeComparator)
            } else {
                apks
            }

        var previousApk: AndroidApk? = null
        // Determine if the older package's signing certs are a subset of the packages in the next newer version.
        // See https://android.googlesource.com/platform/frameworks/base/+/6c942b9dc9bbd3d607c8601907ffa4a5c7a4a606/services/core/java/com/android/server/pm/KeySetManagerService.java#365
        // https://android.googlesource.com/platform/frameworks/base/+/6c942b9dc9bbd3d607c8601907ffa4a5c7a4a606/services/core/java/com/android/server/pm/PackageManagerServiceUtils.java#619
        // https://android.googlesource.com/platform/frameworks/base/+/6c942b9dc9bbd3d607c8601907ffa4a5c7a4a606/core/java/android/content/pm/PackageParser.java#6270
        apksSortedAscendingOrder.forEach { currentApk ->
            previousApk?.let {
                val previousCertificates = it.signatureVerifyResult.signerCertificates
                val currentCertificates = currentApk.signatureVerifyResult.signerCertificates
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
        println("removed groupId $groupId from ${packagesForThisGroup.size} apps: ")
        println(packagesForThisGroup.map { "${it.label} (${it.packageName})" })
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

        return deltaGenerationManager.asyncPrintMutex.withLock {
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
            val allGroups: Map<GroupId, Set<PackageLabelsByGroup>> = groupDao.getGroupToAppMap()
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

