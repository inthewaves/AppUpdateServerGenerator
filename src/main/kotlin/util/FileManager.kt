package util

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

    val appLatestVersionIndex = File(appDirectory, "latest.txt")

    fun getDirForApp(pkg: String) = File(appDirectory, pkg)

    fun getLatestAppVersionInfoMetadata(pkg: String) = File(getDirForApp(pkg), "latest.txt")
}
