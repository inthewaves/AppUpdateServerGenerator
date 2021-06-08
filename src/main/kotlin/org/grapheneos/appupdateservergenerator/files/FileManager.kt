package org.grapheneos.appupdateservergenerator.files

import org.grapheneos.appupdateservergenerator.model.PackageName
import org.grapheneos.appupdateservergenerator.model.VersionCode
import java.io.File
import java.io.IOException
import java.nio.file.Path

/**
 * Manages the locations of the files for the app repository.
 * @throws IOException if unable to create or access the [dataRootDirectory] or [appDirectory].
 * @throws SecurityException if unable to create [dataRootDirectory] and [appDirectory] because a [SecurityManager]
 * denied access.
 */
class FileManager constructor(
    /**
     * Root directory for all data, including the live server data and old app data.
     * By default, it's set to the user's working directory
     */
    val dataRootDirectory: File = File(Path.of("").toAbsolutePath().toFile(), REPO_ROOT_DIRNAME)
) {
    companion object {
        const val APP_REPO_INDEX_FILENAME = "latest-index.txt"
        const val BULK_METADATA_FILENAME = "latest-bulk-metadata.txt"
        const val APP_METADATA_FILENAME = "latest.txt"
        /**
         * The name of the icon file for clients to download when they don't have an app installed.
         * Note: App icons can be PNG, JPEG, GIF, WebP. XML (vector drawables) are not supported.
         */
        const val APP_ICON_FILENAME = "ic_launcher"

        private const val REPO_ROOT_DIRNAME = "app_repo_data"
        private const val STANDALONE_APP_DATA_DIRNAME = "apps"
        private const val DELTA_FILE_FORMAT = "delta-%d-to-%d.gz"
        val DELTA_REGEX = Regex("^delta-([0-9]*)-to-([0-9]*).gz$")
        val APK_REGEX = Regex("""^[0-9]*\.apk$""")
    }

    /**
     * @throws IOException if unable to create the [dirToWrite]
     * @throws SecurityException if unable to create the [dirToWrite] because a [SecurityManager] denied access.
     */
    private fun attemptToCreateDirIfNotExists(dirToWrite: File) {
        dirToWrite.apply {
            if (!exists()) {
                if (!mkdir()) {
                    throw IOException("unable to make directory")
                }
            }
        }
    }

    /**
     * Directory for the live app data to be pushed to the static file server.
     * Contains directories for all supported applications and the (signed) metadata of all the latest versions.
     */
    val appDirectory = File(dataRootDirectory, STANDALONE_APP_DATA_DIRNAME)

    val systemTempDir: File by lazy { TempFile.create("FileManager_systemTempDir").useFile { it.parentFile } }

    init {
        attemptToCreateDirIfNotExists(dataRootDirectory)
        attemptToCreateDirIfNotExists(appDirectory)
    }

    val databaseFile = File(dataRootDirectory, "apprepo.db")

    /**
     * Stores the public key used to sign the metadata in the repo. This is stored here for checks during local repo
     * generation / validation; it is **not** meant to be consumed by the apps.
     */
    val publicSigningKeyPem = File(dataRootDirectory, "public-signing-key.pem")

    val appIndex = File(appDirectory, APP_REPO_INDEX_FILENAME)

    val bulkAppMetadata = File(appDirectory, BULK_METADATA_FILENAME)

    fun getVersionedApk(packageName: PackageName, versionCode: VersionCode) =
        File(getDirForApp(packageName).dir, "${versionCode.code}.apk")

    /**
     * The file location for an APK Signing Scheme v4 signature.
     */
    fun getVersionedApkV4Signature(packageName: PackageName, versionCode: VersionCode) =
        File(getDirForApp(packageName).dir, "${versionCode.code}.apk.idsig")

    fun getDirForApp(pkg: PackageName) = AppDir(File(appDirectory, pkg.pkg))

    fun getLatestAppMetadata(pkg: PackageName) = File(getDirForApp(pkg).dir, APP_METADATA_FILENAME)

    fun getDeltaFileForApp(pkg: PackageName, previousVersion: VersionCode, newVersion: VersionCode) =
        File(getDirForApp(pkg).dir, DELTA_FILE_FORMAT.format(previousVersion.code, newVersion.code))

    fun getAppIconFile(pkg: PackageName) = File(getDirForApp(pkg).dir, APP_ICON_FILENAME)
}
