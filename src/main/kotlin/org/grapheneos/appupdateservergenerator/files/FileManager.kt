package org.grapheneos.appupdateservergenerator.files

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
        private const val REPO_ROOT_DIRNAME = "app_repo_data"
        private const val STANDALONE_APP_DATA_DIRNAME = "apps"
        const val DELTA_FILE_FORMAT = "delta-%d-to-%d.gz"
        val APK_REGEX = Regex("""^[0-9]*\.apk$""")
        val DELTA_REGEX = Regex(
            DELTA_FILE_FORMAT
                .replace("%d", "[0-9]*")
                .replace(".", """\.""")
        )
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

    init {
        attemptToCreateDirIfNotExists(dataRootDirectory)
        attemptToCreateDirIfNotExists(appDirectory)
    }

    /**
     * Stores the public key used to sign the metadata in the repo. This is stored here for checks during local repo
     * generation / validation; it is **not** meant to be consumed by the apps.
     */
    val publicSigningKeyPem = File(dataRootDirectory, "public-signing-key.pem")

    val appIndex = File(appDirectory, "latest-index.txt")

    val bulkAppMetadata = File(appDirectory, "latest-bulk-metadata.txt")

    fun getDirForApp(pkg: String) = AppDir(File(appDirectory, pkg))

    fun getLatestAppVersionInfoMetadata(pkg: String) = File(getDirForApp(pkg).dir, "latest.txt")

    fun getAppIconFile(pkg: String) = File(getDirForApp(pkg).dir, "ic_launcher.png")
}
