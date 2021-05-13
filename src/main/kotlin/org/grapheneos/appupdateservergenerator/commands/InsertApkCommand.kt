package org.grapheneos.appupdateservergenerator.commands

import kotlinx.cli.*
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
import java.util.zip.ZipFile
import kotlin.math.max
import kotlin.system.exitProcess
import kotlin.system.measureTimeMillis
import java.util.concurrent.Executors

import java.util.concurrent.ExecutorService
import kotlin.random.Random

/**
 * A command to insert a given APK into the repository. This will handle patch generation and metadata refreshing.
 *
 * If it is not an update to an existing app tracked in the repository, then a new directory is created for the app.
 */
@OptIn(ExperimentalCli::class)
class InsertApkCommand : Subcommand("insert-apk", "Inserts an APK into the local repository") {
    companion object {
        private const val DEFAULT_MAX_PREVIOUS_VERSION_DELTAS = 5
        private const val DELTA_FILE_FORMAT = "delta-%d-to-%d.gz"
        private val APK_REGEX = Regex("""[0-9]*\.apk""")
        private val DELTA_REGEX = Regex(
            DELTA_FILE_FORMAT
                .replace("%d", "[0-9]*")
                .replace(".", """\.""")
        )
    }

    private val keyFile: String by option(
        ArgType.String,
        description = "A decrypted key in PKCS8 format used to sign the metadata",
        fullName = "key-file",
        shortName = "k"
    ).required()
    private val userSpecifiedRepoDirectory: String? by option(
        ArgType.String,
        description = "The directory to use for the app repo. Defaults to working directory.",
        fullName = "repo-directory",
        shortName = "d"
    )
    private val apkFilePaths: List<String> by argument(
        ArgType.String,
        description = "The APK file(s) to insert into the repository",
        fullName = "apk-file"
    ).vararg()

    private val fileManager = try {
        userSpecifiedRepoDirectory?.let { FileManager(File(it)) } ?: FileManager()
    } catch (e: IOException) {
        println("failed to create root dir $userSpecifiedRepoDirectory")
        e.printStackTrace()
        exitProcess(1)
    }
    private val aaptInvoker = AAPT2Invoker()
    private val apkSignerInvoker = ApkSignerInvoker()
    private val openSSLInvoker = OpenSSLInvoker()

    private val executorService: ExecutorService = Executors.newFixedThreadPool(
        min(Runtime.getRuntime().availableProcessors() + 2, 8)
    )
    private val repoDispatcher = executorService.asCoroutineDispatcher()

    override fun execute() = runBlocking {  
        try {
            executeInternal()
        } finally {
            executorService.shutdown()
        }
    }

    private suspend fun executeInternal(): Unit = withContext(repoDispatcher) {
        if (!aaptInvoker.isExecutablePresent()) {
            println("unable to locate aapt2 at ${aaptInvoker.executablePath}; please add it to your PATH variable")
            exitProcess(1)
        }
        if (!apkSignerInvoker.isExecutablePresent()) {
            println("unable to locate apksigner at ${aaptInvoker.executablePath}; please add it to your PATH variable")
            exitProcess(1)
        }
        if (!openSSLInvoker.isExecutablePresent()) {
            println("unable to locate openssl at ${openSSLInvoker.executablePath}")
            exitProcess(1)
        }

        val signingPrivateKey: PKCS8PrivateKeyFile = try {
            parsePrivateKeyAndValidateDiskPublicKey()
        } catch (e: IOException) {
            println("error: ${e.message}")
            e.printStackTrace()
            exitProcess(1)
        }
        println("parsing ${apkFilePaths.size} APKs")

        val apkGroupsByPackage: Map<String, List<AndroidApk>>
        val timeTaken = measureTimeMillis {
            apkGroupsByPackage = try {
                coroutineScope {
                    apkFilePaths.asSequence()
                        .map { apkFilePathString ->
                            val apkFile = File(apkFilePathString)
                            if (!apkFile.exists() || !apkFile.canRead()) {
                                println("unable to read APK file $apkFilePathString")
                                exitProcess(1)
                            }

                            async(repoDispatcher) {
                                AndroidApk.verifyCertsAndBuildFromApkFile(apkFile, aaptInvoker, apkSignerInvoker)
                            }
                        }
                        .groupBy(keySelector = { it.await().packageName }, valueTransform = { it.await() })
                }
            } catch (e: IOException) {
                println("error: unable to to get Android app details")
                e.printStackTrace()
                exitProcess(1)
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
                    infoOfApkToInsert = apkInfo,
                    signingPrivateKey = signingPrivateKey,
                    // only regenerate deltas when at the latest apk
                    regenerateDeltas = index >= sortedApksForThisPackage.size - 1,
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
        println("wrote new app version at ${fileManager.latestAppVersionIndex}")
    }


    /**
     * @throws IOException if an I/O error occurs or parsing the key fails.
     */
    private fun parsePrivateKeyAndValidateDiskPublicKey(): PKCS8PrivateKeyFile {
        val signingPrivateKey: PKCS8PrivateKeyFile = try {
            openSSLInvoker.getKeyWithType(File(keyFile))
        } catch (e: IOException) {
            throw IOException("failed to parse key type from provided key file: $e", e)
        }
        println("input signing key is of type $signingPrivateKey")

        val publicKey: PEMPublicKey = try {
            openSSLInvoker.getPublicKey(signingPrivateKey)
        } catch (e: IOException) {
            throw IOException("failed to generate public key", e)
        }
        val publicKeyInRepo = fileManager.publicSigningKeyPem
        if (!publicKeyInRepo.exists()) {
            publicKeyInRepo.writeText(publicKey.pubKey)
        } else {
            val existingPublicKeyPem = PEMPublicKey(publicKeyInRepo.readText())
            if (existingPublicKeyPem != publicKey) {
                throw IOException(
                    "the key passed to command (${signingPrivateKey.file.absolutePath}) differs from the one " +
                            "used before (${publicKeyInRepo.absolutePath})"
                )
            }
        }
        return signingPrivateKey
    }

    private suspend fun insertApk(
        infoOfApkToInsert: AndroidApk,
        signingPrivateKey: PKCS8PrivateKeyFile,
        regenerateDeltas: Boolean,
        timestampForMetadata: UnixTimestamp
    ): Unit = withContext(repoDispatcher) {
        println("Inserting ${infoOfApkToInsert.apkFile.name}, with details $infoOfApkToInsert")

        val appDir = fileManager.getDirForApp(infoOfApkToInsert.packageName)
        validateOrCreateDirectories(appDir, infoOfApkToInsert)

        val previousApks = getPreviousApks(appDir, infoOfApkToInsert.apkFile)
        if (!validateApkSigningCertChain(infoOfApkToInsert, previousApks)) {
            println("some apks don't have the same signing certificates\n")
            println("dumping details: this apk: $infoOfApkToInsert\n")
            println("previous apks: $previousApks")
            exitProcess(1)
        }

        val newApkFile = File(appDir, "${infoOfApkToInsert.versionCode.code}.apk")
        infoOfApkToInsert.apkFile.copyTo(newApkFile)
        println("copied ${infoOfApkToInsert.apkFile.absolutePath} to ${newApkFile.absolutePath}")

        val deltaAvailableVersions: List<VersionCode>

        val timeTaken = measureTimeMillis {
            deltaAvailableVersions = if (regenerateDeltas) {
                println()
                deleteAllDeltas(appDir)
                generateDeltas(appDir, infoOfApkToInsert, previousApks)
            } else {
                emptyList()
            }
        }
        println("it took $timeTaken ms to generate ${deltaAvailableVersions.size} deltas")

        try {
            println()
            val newAppMetadata = AppMetadata(
                packageName = infoOfApkToInsert.packageName,
                label = infoOfApkToInsert.label,
                latestVersionCode = infoOfApkToInsert.versionCode,
                latestVersionName = infoOfApkToInsert.versionName,
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
            println("failed to write metadata: ${e.message}")
            e.printStackTrace()
            exitProcess(1)
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
                        it.isFile &&
                                it.name.matches(APK_REGEX) &&
                                it.nameWithoutExtension != newApkFile.nameWithoutExtension &&
                                isZipFile(it)
                    }
                )
                ?.map {
                    async { AndroidApk.verifyCertsAndBuildFromApkFile(it, aaptInvoker, apkSignerInvoker) }
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
                println("error: missing metadata file despite the app directory being present")
                println(" got IOException: ${e.message}")
                e.printStackTrace()
                exitProcess(1)
            }
            // The first line contains the signature.
            if (infoOfApkToInsert.versionCode <= latestAppMetadata.latestVersionCode) {
                println(
                    "error: trying to insert an APK with version code ${infoOfApkToInsert.versionCode.code} when the " +
                            "repo has latest version ${latestAppMetadata.latestVersionCode.code}"
                )
                exitProcess(1)
            }
            println("previous version in repo: ${latestAppMetadata.latestVersionCode.code}")
        } else {
            println("${infoOfApkToInsert.packageName} is not in the repo. Creating new directory and metadata")
            if (!appDir.mkdirs()) {
                println("failed to create directory ${appDir.absolutePath}")
                exitProcess(1)
            }
        }
    }

    private fun validateApkSigningCertChain(
        infoOfApkToInsert: AndroidApk,
        previousApks: List<AndroidApk>
    ): Boolean {
        var currentSetIntersection: Set<HexString> = infoOfApkToInsert.certificates.toSet()
        previousApks.forEach { previousApkInfo ->
            currentSetIntersection = currentSetIntersection intersect previousApkInfo.certificates
            // TODO: verify using same or similar way as frameworks/base
            if (currentSetIntersection.isEmpty()) {
                return false
            }
        }
        return true
    }

    /**
     * Generates multiple deltas with the [newApk] as the target
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
                        AndroidApk.verifyCertsAndBuildFromApkFile(previousVersionApkFile, aaptInvoker, apkSignerInvoker)
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
