package org.grapheneos.appupdateservergenerator.commands

import kotlinx.cli.*
import kotlinx.coroutines.runBlocking
import org.grapheneos.appupdateservergenerator.api.AppMetadata
import org.grapheneos.appupdateservergenerator.api.AppVersionIndex
import org.grapheneos.appupdateservergenerator.model.AndroidApk
import org.grapheneos.appupdateservergenerator.model.Base64String
import org.grapheneos.appupdateservergenerator.model.UnixTimestamp
import org.grapheneos.appupdateservergenerator.model.VersionCode
import org.grapheneos.appupdateservergenerator.util.ArchivePatcherUtil
import org.grapheneos.appupdateservergenerator.util.FileManager
import org.grapheneos.appupdateservergenerator.util.digest
import org.grapheneos.appupdateservergenerator.util.invoker.AAPT2Invoker
import org.grapheneos.appupdateservergenerator.util.invoker.ApkSignerInvoker
import org.grapheneos.appupdateservergenerator.util.invoker.OpenSSLInvoker
import java.io.File
import java.io.FileFilter
import java.io.IOException
import java.lang.Integer.min
import java.security.MessageDigest
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlin.math.max
import kotlin.system.exitProcess

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
        exitProcess(1)
    }
    private val aaptInvoker = AAPT2Invoker()
    private val apkSignerInvoker = ApkSignerInvoker()
    private val openSSLInvoker = OpenSSLInvoker()

    override fun execute() = runBlocking {
        if (!aaptInvoker.isExecutablePresent()) {
            println("unable to locate aapt at ${aaptInvoker.executablePath}; please add it to your PATH variable")
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

        val signingKey: OpenSSLInvoker.Key = try {
            openSSLInvoker.getKeyWithType(File(keyFile))
        } catch (e: IOException) {
            println("failed to parse key type from provided key file: $e")
            exitProcess(1)
        }
        println("input signing key is of type $signingKey")

        println("parsing ${apkFilePaths.size} APKs")

        val apkGroupsByPackage = apkFilePaths.asSequence()
            .map { apkFilePathString ->
                val apkFile = File(apkFilePathString)
                if (!apkFile.exists() || !apkFile.canRead()) {
                    println("unable to read APK file $apkFilePathString")
                    exitProcess(1)
                }

                val infoOfApkToInsert: AndroidApk = try {
                    AndroidApk.buildFromApkFile(apkFile, aaptInvoker, apkSignerInvoker)
                } catch (e: IOException) {
                    println("unable to to get Android app details for ${apkFile.path}: ${e.message}")
                    exitProcess(1)
                }

                infoOfApkToInsert
            }
            .groupBy { it.packageName }

        println("found the following packages: ${apkGroupsByPackage.keys}")

        apkGroupsByPackage.forEach { (packageName, apks) ->
            val apksForThisPackage = apks.sortedBy { it.versionCode }

            val nowInsertingString = "Now inserting $packageName"
            val versionCodeString = "Version codes: ${apksForThisPackage.map { it.versionCode.code }}"
            val width = max(versionCodeString.length, nowInsertingString.length)
            println("""
                ${"=".repeat(width)}
                $nowInsertingString
                $versionCodeString
                ${"=".repeat(width)}
            """.trimIndent())

            apksForThisPackage.forEachIndexed { index, apkInfo ->
                // only regenerate deltas when at the latest apk
                insertApk(apkInfo, signingKey, regenerateDeltas = index >= apksForThisPackage.size - 1)
            }
            println()
        }

        val index = AppVersionIndex.create(fileManager)
        println("new app version index: $index")
        index.writeToDiskAndSign(
            key = signingKey,
            openSSLInvoker = openSSLInvoker,
            fileManager = fileManager
        )
        println("wrote new app version at ${fileManager.latestAppVersionIndex}")
    }

    private fun insertApk(infoOfApkToInsert: AndroidApk, signingKey: OpenSSLInvoker.Key, regenerateDeltas: Boolean) {
        println("Inserting ${infoOfApkToInsert.apkFile.name}, with details $infoOfApkToInsert")

        val appDir = fileManager.getDirForApp(infoOfApkToInsert.packageName)
        validateOrCreateDirectories(appDir, infoOfApkToInsert)

        val previousApksMap = getPreviousApks(appDir, infoOfApkToInsert.apkFile)
        if (!doPreviousApksHaveSameSigningCerts(infoOfApkToInsert, previousApksMap)) {
            println("some apks don't have the same signing certificates")
            println("dumping details: this apk: $infoOfApkToInsert")
            println("previous apks: $previousApksMap")
            exitProcess(1)
        }

        val newApkFile = File(appDir, "${infoOfApkToInsert.versionCode.code}.apk")
        infoOfApkToInsert.apkFile.copyTo(newApkFile)
        println("copied ${infoOfApkToInsert.apkFile.absolutePath} to ${newApkFile.absolutePath}")

        val deltaAvailableVersions: List<VersionCode> = if (regenerateDeltas) {
            println()
            deleteAllDeltas(appDir)
            generateDeltas(appDir, newApkFile, infoOfApkToInsert, previousApksMap)
        } else {
            emptyList()
        }

        try {
            println()
            val newAppMetadata = AppMetadata(
                packageName = infoOfApkToInsert.packageName,
                latestVersionCode = infoOfApkToInsert.versionCode,
                sha256Checksum = Base64String.fromBytes(newApkFile.digest(MessageDigest.getInstance("SHA-256"))),
                deltaAvailableVersions = deltaAvailableVersions,
                lastUpdateTimestamp = UnixTimestamp.now()
            )
            newAppMetadata.writeToDiskAndSign(
                key = signingKey,
                openSSLInvoker = openSSLInvoker,
                fileManager = fileManager
            )
            println("metadata updated: $newAppMetadata")
        } catch (e: IOException) {
            println("failed to write metadata: ${e.message}")
            exitProcess(1)
        }

        println("")
    }

    @Throws(IOException::class)
    private fun deleteAllDeltas(appDir: File) {
        appDir.listFiles(FileFilter { it.name.matches(DELTA_REGEX) })?.forEach { oldDelta ->
            println("deleting outdated delta ${oldDelta.name}")
            if (!oldDelta.delete()) {
                throw IOException("failed to delete $oldDelta")
            }
        }
    }

    @Throws(IOException::class)
    private fun getPreviousApks(appDir: File, newApkFile: File): List<AndroidApk> =
        appDir
            .listFiles(
                FileFilter {
                    it.isFile && it.name.matches(APK_REGEX) &&
                            it.nameWithoutExtension != newApkFile.nameWithoutExtension && isZipFile(it)
                }
            )
            ?.sortedWith { a, b -> -a.nameWithoutExtension.toInt().compareTo(b.nameWithoutExtension.toInt()) }
            ?.map { AndroidApk.buildFromApkFile(it, aaptInvoker, apkSignerInvoker) }
            ?: throw IOException("failed to get previous apks")

    @Throws(IOException::class)
    private fun isZipFile(file: File): Boolean = try {
        ZipFile(file)
        true
    } catch (e: ZipException) {
        false
    }

    private fun validateOrCreateDirectories(
        appDir: File,
        infoOfApkToInsert: AndroidApk
    ) {
        if (appDir.exists()) {
            println("${infoOfApkToInsert.packageName} is in repo.")
            val latestAppMetadata = try {
                AppMetadata.getInfoFromDiskForPackage(infoOfApkToInsert.packageName, fileManager)
            } catch (e: IOException) {
                println("error: missing metadata file despite the app directory being present")
                println(" got IOException: ${e.message}")
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

    private fun doPreviousApksHaveSameSigningCerts(
        infoOfApkToInsert: AndroidApk,
        previousApks: List<AndroidApk>
    ): Boolean {
        previousApks.forEach { previousApkInfo ->
            // TODO: verify using same or similar way as frameworks/base
            if (previousApkInfo.certificates.intersect(infoOfApkToInsert.certificates).isEmpty()) {
                return false
            }
        }
        return true
    }

    @Throws(IOException::class)
    private fun generateDeltas(
        appDir: File,
        newApkFile: File,
        newApkInfo: AndroidApk,
        previousApks: List<AndroidApk>,
        maxPreviousVersions: Int = DEFAULT_MAX_PREVIOUS_VERSION_DELTAS
    ): List<VersionCode> {
        val numberOfDeltasToGenerate = min(previousApks.size, maxPreviousVersions)
        return previousApks.asSequence()
            .take(numberOfDeltasToGenerate)
            .mapTo(ArrayList(numberOfDeltasToGenerate)) { previousApk ->
                val previousVersionApkFile = previousApk.apkFile
                
                val previousApkInfo = AndroidApk.buildFromApkFile(previousVersionApkFile, aaptInvoker, apkSignerInvoker)
                val deltaName = DELTA_FILE_FORMAT.format(previousApkInfo.versionCode.code, newApkInfo.versionCode.code)
                println("generating delta: $deltaName")

                val outputDeltaFile = File(appDir, deltaName)
                ArchivePatcherUtil.generateDelta(
                    previousVersionApkFile,
                    newApkFile,
                    outputDeltaFile,
                    outputGzip = true
                )

                println(" generated delta ${outputDeltaFile.name} is " +
                        "${outputDeltaFile.length().toDouble() / (0x1 shl 20)} MiB")

                previousApkInfo.versionCode
            }
    }
}