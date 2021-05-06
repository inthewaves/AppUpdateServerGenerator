package commands

import api.AppVersionIndex
import api.LatestAppVersionInfo
import kotlinx.cli.ArgType
import kotlinx.cli.ExperimentalCli
import kotlinx.cli.Subcommand
import kotlinx.cli.vararg
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import model.AndroidApk
import model.Base64String
import model.UnixTimestamp
import model.VersionCode
import util.ArchivePatcherUtil
import util.FileManager
import util.digest
import util.invoker.AaptInvoker
import util.invoker.ApkSignerInvoker
import java.io.File
import java.io.FileFilter
import java.io.IOException
import java.lang.Integer.min
import java.security.MessageDigest
import java.util.zip.ZipException
import java.util.zip.ZipFile
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
    private val aaptInvoker = AaptInvoker()
    private val apkSignerInvoker = ApkSignerInvoker()

    override fun execute() = runBlocking {
        if (!aaptInvoker.isExecutablePresent()) {
            println("unable to locate aapt at ${aaptInvoker.aaptPath}; please add it to your PATH variable")
            exitProcess(1)
        }

        if (!apkSignerInvoker.isExecutablePresent()) {
            println("unable to locate apksigner at ${aaptInvoker.aaptPath}; please add it to your PATH variable")
            exitProcess(1)
        }

        val packageGroups: Map<String, List<Pair<File, AndroidApk>>> = apkFilePaths
            .map { apkFilePathString ->
                val apkFile = File(apkFilePathString)
                if (!apkFile.exists() || !apkFile.canRead()) {
                    println("unable to read APK file $apkFilePathString")
                    exitProcess(1)
                }

                val infoOfApkToInsert = try {
                    AndroidApk.buildFromApkFile(apkFile, aaptInvoker, apkSignerInvoker)
                } catch (e: IOException) {
                    println("unable to to get Android app details for ${apkFile.path}: ${e.message}")
                    exitProcess(1)
                }

                apkFile to infoOfApkToInsert
            }
            .groupBy { it.second.packageName }
        packageGroups.forEach { groupEntry ->
            val apksForThisPackage = groupEntry.value.sortedBy { it.second.versionCode }
            println("apksForThisPackage (${groupEntry.key}): ${apksForThisPackage.map { it.second.versionCode }}")
            apksForThisPackage.forEachIndexed { index, (file, apkInfo) ->
                // only regenerate deltas when at the latest apk
                insertApk(file, apkInfo, regenerateDeltas = index >= apksForThisPackage.size - 1)
            }
        }

        val index = AppVersionIndex.create(fileManager)
        println("index: $index")
        index.writeToFile(fileManager.appIndex)
    }

    private fun insertApk(apkFile: File, infoOfApkToInsert: AndroidApk, regenerateDeltas: Boolean) {
        println("App details for ${apkFile.name}: $infoOfApkToInsert")

        val appDir = fileManager.getDirForApp(infoOfApkToInsert.packageName)
        val latestAppMetadata = fileManager.getLatestAppVersionInfoMetadata(infoOfApkToInsert.packageName)
        validateOrCreateDirectories(appDir, infoOfApkToInsert, latestAppMetadata)

        val previousApksMap = getPreviousApkFileToInfoMap(appDir, apkFile)
        if (!doPreviousApksHaveSameSigningCerts(infoOfApkToInsert, previousApksMap)) {
            println("some apks don't have the same signing certificates")
            exitProcess(1)
        }

        val newApkFile = File(appDir, "${infoOfApkToInsert.versionCode.code}.apk")
        apkFile.copyTo(newApkFile)
        println("copied ${apkFile.absolutePath} to ${newApkFile.absolutePath}")

        val deltaAvailableVersions: List<VersionCode> = if (regenerateDeltas) {
            deleteAllDeltas(appDir)
            generateDeltas(appDir, newApkFile, infoOfApkToInsert, previousApksMap)
        } else {
            emptyList()
        }

        try {
            val newAppMetadata = LatestAppVersionInfo(
                latestVersionCode = infoOfApkToInsert.versionCode,
                sha256Checksum = Base64String.fromBytes(newApkFile.digest(MessageDigest.getInstance("SHA-256"))),
                deltaAvailableVersions = deltaAvailableVersions,
                lastUpdateTimestamp = UnixTimestamp.now()
            )
            latestAppMetadata.writeText(Json.encodeToString(newAppMetadata))
            println("metadata updated: $newAppMetadata")
        } catch (e: IOException) {
            println("failed to write metadata: ${e.message}")
            exitProcess(1)
        }
        println("")
    }

    private fun deleteAllDeltas(appDir: File) {
        appDir.listFiles(FileFilter { it.name.matches(DELTA_REGEX) })?.forEach { oldDelta ->
            println("deleting outdated delta ${oldDelta.name}")
            if (!oldDelta.delete()) {
                throw IOException("failed to delete $oldDelta")
            }
        }
    }

    private fun getPreviousApkFileToInfoMap(appDir: File, newApkFile: File): Map<File, AndroidApk> {
        val previousApks: List<File> = appDir.listFiles(
            FileFilter {
                it.isFile && it.name.matches(APK_REGEX) && it.nameWithoutExtension != newApkFile.nameWithoutExtension
                        && isZipFile(it)
            }
        )?.asSequence()
            ?.sortedWith { a, b -> -a.nameWithoutExtension.toInt().compareTo(b.nameWithoutExtension.toInt()) }
            ?.toList()
            ?: throw IOException("failed to get previous apks")

        return if (previousApks.isEmpty()) {
            emptyMap()
        } else {
            previousApks.associateWith { AndroidApk.buildFromApkFile(it, aaptInvoker, apkSignerInvoker) }
        }
    }

    @Throws(IOException::class)
    private fun isZipFile(file: File): Boolean = try {
        ZipFile(file)
        true
    } catch (e: ZipException) {
        false
    }

    private fun validateOrCreateDirectories(
        appDir: File,
        infoOfApkToInsert: AndroidApk,
        latestAppMetadata: File
    ) {
        if (appDir.exists()) {
            println("${infoOfApkToInsert.packageName} is in repo.")
            if (!latestAppMetadata.exists()) {
                println("error: missing metadata file despite the app directory being present")
                exitProcess(1)
            }
            val latestAppInfo: LatestAppVersionInfo = Json.decodeFromString(latestAppMetadata.readText())
            if (infoOfApkToInsert.versionCode <= latestAppInfo.latestVersionCode) {
                println(
                    "error: trying to insert an APK with version code ${infoOfApkToInsert.versionCode.code} when the " +
                            "repo has latest version ${latestAppInfo.latestVersionCode.code}"
                )
                exitProcess(1)
            }
            println("previous version in repo: ${latestAppInfo.latestVersionCode.code}")
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
        previousApks: Map<File, AndroidApk>
    ): Boolean {
        previousApks.values.forEach { previousApkInfo ->
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
        previousApks: Map<File, AndroidApk>,
        maxPreviousVersions: Int = DEFAULT_MAX_PREVIOUS_VERSION_DELTAS
    ): List<VersionCode> {
        val versionCodes = ArrayList<VersionCode>(min(previousApks.size, maxPreviousVersions))
        var numDeltas = 0
        previousApks.iterator()
            .forEach { entry ->
                val previousVersionApkFile = entry.key
                if (numDeltas >= maxPreviousVersions) {
                    return versionCodes
                }

                val previousApkInfo = AndroidApk.buildFromApkFile(previousVersionApkFile, aaptInvoker, apkSignerInvoker)
                val deltaName = DELTA_FILE_FORMAT.format(previousApkInfo.versionCode.code, newApkInfo.versionCode.code)
                println("generating delta: $deltaName")
                ArchivePatcherUtil.generateDelta(
                    previousVersionApkFile,
                    newApkFile,
                    File(appDir, deltaName),
                    outputGzip = true
                )

                versionCodes.add(previousApkInfo.versionCode)
                numDeltas++
            }
        return versionCodes
    }
}