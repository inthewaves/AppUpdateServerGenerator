package org.grapheneos.appupdateservergenerator.files

import java.io.File
import java.io.IOException
import java.nio.file.Path

class FileManager @Throws(IOException::class) constructor(
    /**
     * Root directory for all data, including the live server data and old app data.
     * By default, it's set to the user's working directory
     */
    val dataRootDirectory: File = File(Path.of("").toAbsolutePath().toFile(), REPO_ROOT_DIRNAME)
) {
    companion object {
        private const val REPO_ROOT_DIRNAME = "app_repo_data"
        private const val STANDALONE_APP_DATA_DIRNAME = "apps"
    }
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

    val latestAppVersionIndex = File(appDirectory, "latest-index.txt")

    fun getDirForApp(pkg: String) = File(appDirectory, pkg)

    fun getLatestAppVersionInfoMetadata(pkg: String) = File(getDirForApp(pkg), "latest.txt")
}
