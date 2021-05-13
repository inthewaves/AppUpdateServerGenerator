package org.grapheneos.appupdateservergenerator.repo

import kotlinx.coroutines.*
import org.grapheneos.appupdateservergenerator.api.AppMetadata
import org.grapheneos.appupdateservergenerator.api.AppVersionIndex
import org.grapheneos.appupdateservergenerator.apkparsing.AAPT2Invoker
import org.grapheneos.appupdateservergenerator.apkparsing.ApkSignerInvoker
import org.grapheneos.appupdateservergenerator.crypto.OpenSSLInvoker
import org.grapheneos.appupdateservergenerator.crypto.PEMPublicKey
import org.grapheneos.appupdateservergenerator.crypto.PKCS8PrivateKeyFile
import org.grapheneos.appupdateservergenerator.files.FileManager
import org.grapheneos.appupdateservergenerator.model.*
import org.grapheneos.appupdateservergenerator.util.ArchivePatcherUtil
import org.grapheneos.appupdateservergenerator.util.digest
import java.io.File
import java.io.FileFilter
import java.io.IOException
import java.lang.Integer.min
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.zip.ZipFile
import kotlin.math.max
import kotlin.system.measureTimeMillis

/**
 * A command to insert a given APK into the repository. This will handle patch generation and metadata refreshing.
 *
 * If it is not an update to an existing app tracked in the repository, then a new directory is created for the app.
 */
class AppRepoManager(
    private val fileManager: FileManager,
    private val aaptInvoker: AAPT2Invoker,
    private val apkSignerInvoker: ApkSignerInvoker,
    private val openSSLInvoker: OpenSSLInvoker,
    private val signingPrivateKey: PKCS8PrivateKeyFile,
) {
    companion object {
        private const val DEFAULT_MAX_PREVIOUS_VERSION_DELTAS = 5
        private const val DELTA_FILE_FORMAT = "delta-%d-to-%d.gz"
        private val APK_REGEX = Regex("""^[0-9]*\.apk$""")
        private val DELTA_REGEX = Regex(
            DELTA_FILE_FORMAT
                .replace("%d", "[0-9]*")
                .replace(".", """\.""")
        )
    }

    private val executorService: ExecutorService = Executors.newFixedThreadPool(
        min(Runtime.getRuntime().availableProcessors() + 2, 8)
    )
    private val repoDispatcher = executorService.asCoroutineDispatcher()

    /**
     * Inserts APKs from the provided [apkFilePaths]. The APKs will be first validated (ensure that they
     * verify with apksigner.
     * 
     * @throws AppRepoException
     * @throws IOException
     */
    suspend fun insertApksFromStringPaths(apkFilePaths: Collection<String>): Unit = withContext(repoDispatcher) {
        validatePublicKeyInRepo()

        println("parsing ${apkFilePaths.size} APKs")
        val apkGroupsByPackage: Map<String, List<AndroidApk>>
        val timeTaken = measureTimeMillis {
            apkGroupsByPackage = try {
                coroutineScope {
                    apkFilePaths.asSequence()
                        .map { apkFilePathString ->
                            val apkFile = File(apkFilePathString)
                            if (!apkFile.exists() || !apkFile.canRead()) {
                                throw IOException("unable to read APK file $apkFilePathString")
                            }

                            async(repoDispatcher) {
                                AndroidApk.verifyApkSignatureAndBuildFromApkFile(apkFile, aaptInvoker, apkSignerInvoker)
                            }
                        }
                        .groupBy(keySelector = { it.await().packageName }, valueTransform = { it.await() })
                }
            } catch (e: IOException) {
                throw AppRepoException.AppDetailParseFailed("error: unable to to get Android app details", e)
            }
        }
        println("took $timeTaken ms to parse APKs")

        println("found the following packages: ${apkGroupsByPackage.keys}")

        val timestampForMetadata = UnixTimestamp.now()
        apkGroupsByPackage.forEach { (packageName, apks) ->
            val sortedApksForThisPackage = apks.sortedBy { it.versionCode }

            val nowInsertingString = "Now inserting $packageName"
            val versionCodeString = "Version codes: ${sortedApksForThisPackage.map { it.versionCode.code }}"
            val width = max(versionCodeString.length, nowInsertingString.length)
            println(
                """
                ${"=".repeat(width)}
                $nowInsertingString
                $versionCodeString
                ${"=".repeat(width)}
                """.trimIndent()
            )

            sortedApksForThisPackage.forEachIndexed { index, apkInfo ->
                insertApk(
                    apkToInsert = apkInfo,
                    signingPrivateKey = signingPrivateKey,
                    // only regenerate deltas when at the latest apk
                    shouldGenerateDelta = index >= sortedApksForThisPackage.size - 1,
                    timestampForMetadata = timestampForMetadata
                )
            }

            val maxVersionApk = sortedApksForThisPackage.last()
            val appIconFile = fileManager.getAppIconFile(maxVersionApk.packageName)

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
            }

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

    private suspend fun insertApk(
        apkToInsert: AndroidApk,
        signingPrivateKey: PKCS8PrivateKeyFile,
        shouldGenerateDelta: Boolean,
        timestampForMetadata: UnixTimestamp
    ): Unit = withContext(repoDispatcher) {
        println("Inserting ${apkToInsert.apkFile.name}, with details $apkToInsert")

        val appDir = fileManager.getDirForApp(apkToInsert.packageName)
        validateOrCreateDirectories(appDir, apkToInsert)

        val previousApks = getPreviousApks(appDir, apkToInsert.apkFile)
        validateApkSigningCertChain(apkToInsert, previousApks)

        val newApkFile = File(appDir, "${apkToInsert.versionCode.code}.apk")
        apkToInsert.apkFile.copyTo(newApkFile)
        println("copied ${apkToInsert.apkFile.absolutePath} to ${newApkFile.absolutePath}")

        val deltaAvailableVersions: List<VersionCode>
        val timeTaken = measureTimeMillis {
            deltaAvailableVersions = if (shouldGenerateDelta) {
                println()
                deleteAllDeltas(appDir)
                generateDeltas(appDir, apkToInsert, previousApks)
            } else {
                emptyList()
            }
        }
        println("took $timeTaken ms to generate ${deltaAvailableVersions.size} deltas")

        try {
            println()
            val newAppMetadata = AppMetadata(
                packageName = apkToInsert.packageName,
                label = apkToInsert.label,
                latestVersionCode = apkToInsert.versionCode,
                latestVersionName = apkToInsert.versionName,
                sha256Checksum = Base64String.fromBytes(newApkFile.digest(MessageDigest.getInstance("SHA-256"))),
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

        println("")
    }

    /**
     * @throws IOException if unable to delete deltas
     */
    private fun deleteAllDeltas(appDir: File) {
        appDir.listFiles(FileFilter { it.name.matches(DELTA_REGEX) })?.forEach { oldDelta ->
            println("deleting outdated delta ${oldDelta.name}")
            if (!oldDelta.delete()) {
                throw IOException("failed to delete $oldDelta")
            }
        }
    }

    /**
     * Gets a sorted list of the previous APK files in the [appDir], mapping them to [AndroidApk] instances.
     * The list will be sorted in ascending order.
     *
     * @throws IOException if an I/O error occurs
     */
    private suspend fun getPreviousApks(appDir: File, newApkFile: File): List<AndroidApk> =
        withContext(repoDispatcher) {
            appDir
                .listFiles(
                    FileFilter {
                        it.isFile && APK_REGEX.matches(it.name) &&
                                it.nameWithoutExtension != newApkFile.nameWithoutExtension &&
                                isZipFile(it)
                    }
                )
                ?.map {
                    async { AndroidApk.verifyApkSignatureAndBuildFromApkFile(it, aaptInvoker, apkSignerInvoker) }
                }
                ?.awaitAll()
                ?.sortedBy { it.versionCode }
                ?: throw IOException("failed to get previous apks: listFiles returned null")
        }

    private fun isZipFile(file: File): Boolean = try {
        ZipFile(file).close()
        true
    } catch (e: IOException) {
        false
    }

    private fun validateOrCreateDirectories(
        appDir: File,
        infoOfApkToInsert: AndroidApk
    ) {
        if (appDir.exists()) {
            println("${infoOfApkToInsert.packageName} is in repo.")
            val latestAppMetadata = try {
                AppMetadata.getMetadataFromDiskForPackage(infoOfApkToInsert.packageName, fileManager)
            } catch (e: IOException) {
                throw AppRepoException.InvalidRepoState(
                    "missing metadata file despite the app directory being present",
                    e
                )
            }
            // The first line contains the signature.
            if (infoOfApkToInsert.versionCode <= latestAppMetadata.latestVersionCode) {
                throw AppRepoException.InsertFailed(
                    "trying to insert ${infoOfApkToInsert.packageName} with version code " +
                            "${infoOfApkToInsert.versionCode.code} when the " + 
                            "repo has latest version ${latestAppMetadata.latestVersionCode.code}"
                )
            }
            println("previous version in repo: ${latestAppMetadata.latestVersionCode.code}")
        } else {
            println("${infoOfApkToInsert.packageName} is not in the repo. Creating new directory and metadata")
            if (!appDir.mkdirs()) {
                throw AppRepoException.InsertFailed("failed to create directory ${appDir.absolutePath}")
            }
        }
    }

    /**
     * @throws AppRepoException.ApkSigningCertMismatch
     */
    private fun validateApkSigningCertChain(
        infoOfApkToInsert: AndroidApk,
        previousApks: List<AndroidApk>
    ) {
        var currentSetIntersection: Set<HexString> = infoOfApkToInsert.certificates.toSet()
        previousApks.forEach { previousApkInfo ->
            currentSetIntersection = currentSetIntersection intersect previousApkInfo.certificates
            // TODO: verify using same or similar way as frameworks/base
            if (currentSetIntersection.isEmpty()) {
                throw AppRepoException.ApkSigningCertMismatch(
                    "some apks don't have the same signing certificates\n" +
                            "dumping details: this apk: $infoOfApkToInsert\n" +
                            "previous apks: $previousApks"
                )
            }
        }
    }

    /**
     * Generates multiple deltas with the [newApk] as the target and all the [previousApks] as the base to generate
     * a delta from.
     *
     * @return The version codes for which a delta is available to patching to create the [newApk]
     * @throws IOException if an I/O error occurs
     */
    private suspend fun generateDeltas(
        appDir: File,
        newApk: AndroidApk,
        previousApks: List<AndroidApk>,
        maxPreviousVersions: Int = DEFAULT_MAX_PREVIOUS_VERSION_DELTAS
    ): List<VersionCode> = withContext(repoDispatcher) {
        val numberOfDeltasToGenerate = min(previousApks.size, maxPreviousVersions)
        
        previousApks.asSequence()
            .take(numberOfDeltasToGenerate)
            .mapTo(ArrayList(numberOfDeltasToGenerate)) { previousApk ->
                async {
                    val previousVersionApkFile = previousApk.apkFile

                    val previousApkInfo =
                        AndroidApk.verifyApkSignatureAndBuildFromApkFile(previousVersionApkFile, aaptInvoker, apkSignerInvoker)
                    val deltaName =
                        DELTA_FILE_FORMAT.format(previousApkInfo.versionCode.code, newApk.versionCode.code)
                    println("generating delta: $deltaName")

                    val outputDeltaFile = File(appDir, deltaName)
                    ArchivePatcherUtil.generateDelta(
                        previousVersionApkFile,
                        newApk.apkFile,
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
