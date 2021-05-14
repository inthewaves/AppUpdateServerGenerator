package org.grapheneos.appupdateservergenerator.repo

import kotlinx.coroutines.*
import org.grapheneos.appupdateservergenerator.api.AppMetadata
import org.grapheneos.appupdateservergenerator.api.AppVersionIndex
import org.grapheneos.appupdateservergenerator.apkparsing.AAPT2Invoker
import org.grapheneos.appupdateservergenerator.apkparsing.ApkSignerInvoker
import org.grapheneos.appupdateservergenerator.crypto.OpenSSLInvoker
import org.grapheneos.appupdateservergenerator.crypto.PEMPublicKey
import org.grapheneos.appupdateservergenerator.crypto.PKCS8PrivateKeyFile
import org.grapheneos.appupdateservergenerator.files.AppDir
import org.grapheneos.appupdateservergenerator.files.FileManager
import org.grapheneos.appupdateservergenerator.files.FileManager.Companion.APK_REGEX
import org.grapheneos.appupdateservergenerator.files.FileManager.Companion.DELTA_FILE_FORMAT
import org.grapheneos.appupdateservergenerator.files.FileManager.Companion.DELTA_REGEX
import org.grapheneos.appupdateservergenerator.model.*
import org.grapheneos.appupdateservergenerator.util.ArchivePatcherUtil
import org.grapheneos.appupdateservergenerator.util.digest
import java.io.File
import java.io.FileFilter
import java.io.IOException
import java.lang.Integer.min
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.zip.ZipFile
import kotlin.collections.ArrayList
import kotlin.math.max
import kotlin.system.measureTimeMillis

/**
 * A general manager for the app repo.
 * Contains validation, APK insertion, metadata generation and signing
 */
interface AppRepoManager {
    suspend fun insertApksFromStringPaths(apkFilePaths: Collection<String>)
}

fun AppRepoManager(
    fileManager: FileManager,
    aaptInvoker: AAPT2Invoker,
    apkSignerInvoker: ApkSignerInvoker,
    openSSLInvoker: OpenSSLInvoker,
    signingPrivateKey: PKCS8PrivateKeyFile,
): AppRepoManager = AppRepoManagerImpl(fileManager, aaptInvoker, apkSignerInvoker, openSSLInvoker, signingPrivateKey)

/**
 * The implementation of [AppRepoManager].
 * @see AppRepoManager
 */
private class AppRepoManagerImpl(
    private val fileManager: FileManager,
    private val aaptInvoker: AAPT2Invoker,
    private val apkSignerInvoker: ApkSignerInvoker,
    private val openSSLInvoker: OpenSSLInvoker,
    private val signingPrivateKey: PKCS8PrivateKeyFile,
): AppRepoManager {
    companion object {
        private const val DEFAULT_MAX_PREVIOUS_VERSION_DELTAS = 5
    }

    private val executorService: ExecutorService = Executors.newFixedThreadPool(64)
    private val repoDispatcher = executorService.asCoroutineDispatcher()

    /**
     * Inserts APKs from the provided [apkFilePaths]. The APKs will be first validated (ensure that they
     * verify with apksigner and that the contents of their manifests can be parsed).
     * 
     * @throws AppRepoException
     * @throws IOException
     */
    override suspend fun insertApksFromStringPaths(
        apkFilePaths: Collection<String>
    ): Unit = withContext(repoDispatcher) {
        validatePublicKeyInRepo()

        println("parsing ${apkFilePaths.size} APKs")
        // If there are multiple versions of the same package passed into the command line, we insert all of those
        // APKs together.
        val packageApkGroup: List<PackageApkGroup.AscendingOrder>
        val timeTaken = measureTimeMillis {
            packageApkGroup = try {
                PackageApkGroup.fromStringPathsAscending(
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

            insertApkGroupForSinglePackage(
                apksToInsert = apkInsertionGroup,
                signingPrivateKey = signingPrivateKey,
                timestampForMetadata = timestampForMetadata
            )

            println()
        }

        val index = AppVersionIndex.create(fileManager, timestampForMetadata)
        println("new app version index: $index")
        index.writeToDiskAndSign(
            privateKey = signingPrivateKey,
            openSSLInvoker = openSSLInvoker,
            fileManager = fileManager
        )
        println("wrote new app version index at ${fileManager.latestAppVersionIndex}")
    }

    /**
     * @throws IOException if validation of the public key failed (due to I/O error, public key mismatch, openssl
     * failing, etc
     * @throws AppRepoException.RepoSigningKeyMismatch
     */
    private fun validatePublicKeyInRepo() {
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

    /**
     * Inserts multiple APKs for a single package.
     */
    private suspend fun insertApkGroupForSinglePackage(
        apksToInsert: PackageApkGroup,
        signingPrivateKey: PKCS8PrivateKeyFile,
        timestampForMetadata: UnixTimestamp
    ): Unit = withContext(repoDispatcher) {
        val appDir = fileManager.getDirForApp(apksToInsert.packageName)
        validateOrCreateDirectories(appDir, apksToInsert)

        val sortedPreviousApks = PackageApkGroup.fromDir(appDir, aaptInvoker, apkSignerInvoker, ascendingOrder = true)
        validateApkSigningCertChain(newApks = apksToInsert.sortedApks, currentApks = sortedPreviousApks)

        apksToInsert.sortedApks.forEach { apkToInsert ->
            val newApkFile = File(appDir.dir, "${apkToInsert.versionCode.code}.apk")
            apkToInsert.apkFile.copyTo(newApkFile)
            println("copied ${apkToInsert.apkFile.absolutePath} to ${newApkFile.absolutePath}")
        }

        println()
        deleteAllDeltas(appDir)
        val deltaAvailableVersions: List<VersionCode>
        val timeTaken = measureTimeMillis {
            deltaAvailableVersions = generateDeltas(appDir)
        }
        println("took $timeTaken ms to generate ${deltaAvailableVersions.size} deltas")

        val maxVersionApk = apksToInsert.sortedApks.last()
        try {
            println()
            val newAppMetadata = AppMetadata(
                packageName = maxVersionApk.packageName,
                label = maxVersionApk.label,
                latestVersionCode = maxVersionApk.versionCode,
                latestVersionName = maxVersionApk.versionName,
                sha256Checksum = Base64String.fromBytes(
                    maxVersionApk.apkFile.digest(MessageDigest.getInstance("SHA-256"))
                ),
                deltaAvailableVersions = deltaAvailableVersions,
                lastUpdateTimestamp = timestampForMetadata
            )
            newAppMetadata.writeToDiskAndSign(
                privateKey = signingPrivateKey,
                openSSLInvoker = openSSLInvoker,
                fileManager = fileManager
            )
            println("metadata updated: $newAppMetadata")
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

        println()
    }

    /**
     * @throws IOException if unable to delete deltas
     */
    private fun deleteAllDeltas(appDir: AppDir) {
        appDir.dir.listFiles(FileFilter { it.name.matches(DELTA_REGEX) })?.forEach { oldDelta ->
            println("deleting outdated delta ${oldDelta.name}")
            if (!oldDelta.delete()) {
                throw IOException("failed to delete $oldDelta")
            }
        }
    }

    private fun validateOrCreateDirectories(
        appDir: AppDir,
        apksToInsert: PackageApkGroup
    ) {
        if (appDir.dir.exists()) {
            println("${apksToInsert.packageName} is in repo.")
            val latestAppMetadata = try {
                AppMetadata.getMetadataFromDiskForPackage(apksToInsert.packageName, fileManager)
            } catch (e: IOException) {
                throw AppRepoException.InvalidRepoState(
                    "missing metadata file for ${apksToInsert.packageName} despite its app directory being present",
                    e
                )
            }
            val smallestVersionCodeApk = apksToInsert.sortedApks.first()
            if (smallestVersionCodeApk.versionCode <= latestAppMetadata.latestVersionCode) {
                throw AppRepoException.InsertFailed(
                    "trying to insert ${smallestVersionCodeApk.packageName} with version code " +
                            "${smallestVersionCodeApk.versionCode.code} when the " +
                            "repo has latest version ${latestAppMetadata.latestVersionCode.code}"
                )
            }
            println("previous version in repo: ${latestAppMetadata.latestVersionCode.code}")
        } else {
            println("${apksToInsert.packageName} is not in the repo. Creating new directory and metadata")
            if (!appDir.dir.mkdirs()) {
                throw AppRepoException.InsertFailed("failed to create directory ${appDir.dir.absolutePath}")
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
        newApks: SortedSet<AndroidApk>?,
        currentApks: PackageApkGroup
    ) {
        var currentSetIntersection: Set<HexString>? = null
        currentApks.sortedApks.asSequence()
            .apply { newApks?.let { plus(it) } }
            .forEach { currentApk ->
                currentSetIntersection = currentSetIntersection
                    ?.let { currentApk.certificates intersect it }
                    ?: currentApk.certificates.toSet()

                // TODO: verify using same or similar way as frameworks/base
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
    private suspend fun generateDeltas(
        appDir: AppDir,
        maxPreviousVersions: Int = DEFAULT_MAX_PREVIOUS_VERSION_DELTAS
    ): List<VersionCode> = withContext(repoDispatcher) {
        val apks = PackageApkGroup.fromDir(appDir, aaptInvoker, apkSignerInvoker, ascendingOrder = false)
        if (apks.size <= 1) {
            return@withContext emptyList()
        }
        check(apks is PackageApkGroup.DescendingOrder)

        val newestApk = apks.sortedApks.first()
        val numberOfDeltasToGenerate = min(apks.size - 1, maxPreviousVersions)

        // Drop the first element, because that's the most recent one and hence the target
        apks.sortedApks.drop(1)
            .take(numberOfDeltasToGenerate)
            .mapTo(ArrayList(numberOfDeltasToGenerate)) { previousApk ->
                async {
                    val previousVersionApkFile = previousApk.apkFile

                    val previousApkInfo =
                        AndroidApk.verifyApkSignatureAndBuildFromApkFile(previousVersionApkFile, aaptInvoker, apkSignerInvoker)
                    val deltaName =
                        DELTA_FILE_FORMAT.format(previousApkInfo.versionCode.code, newestApk.versionCode.code)
                    println("generating delta: $deltaName")

                    val outputDeltaFile = File(appDir.dir, deltaName)
                    ArchivePatcherUtil.generateDelta(
                        previousVersionApkFile,
                        newestApk.apkFile,
                        outputDeltaFile,
                        outputGzip = true
                    )

                    println(
                        " generated delta ${outputDeltaFile.name} is " +
                                "${outputDeltaFile.length().toDouble() / (0x1 shl 20)} MiB"
                    )

                    previousApkInfo.versionCode
                }
            }
            .awaitAll()
    }
}

sealed class AppRepoException : Exception {
    constructor() : super()
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable) : super(message, cause)
    constructor(cause: Throwable) : super(cause)
    class InsertFailed : AppRepoException {
        constructor() : super()
        constructor(message: String) : super(message)
        constructor(message: String, cause: Throwable) : super(message, cause)
        constructor(cause: Throwable) : super(cause)
    }
    class ApkSigningCertMismatch : AppRepoException {
        constructor(message: String) : super(message)
    }
    class RepoSigningKeyMismatch : AppRepoException {
        constructor(message: String) : super(message)
    }
    class InvalidRepoState : AppRepoException {
        constructor(message: String, cause: Throwable) : super(message)
    }
    class AppDetailParseFailed : AppRepoException {
        constructor(message: String, cause: Throwable) : super(message)
    }
}
