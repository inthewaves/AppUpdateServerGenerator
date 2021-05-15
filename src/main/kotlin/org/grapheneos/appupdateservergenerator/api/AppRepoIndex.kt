package org.grapheneos.appupdateservergenerator.api

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.grapheneos.appupdateservergenerator.crypto.OpenSSLInvoker
import org.grapheneos.appupdateservergenerator.crypto.PKCS8PrivateKeyFile
import org.grapheneos.appupdateservergenerator.files.FileManager
import org.grapheneos.appupdateservergenerator.model.UnixTimestamp
import org.grapheneos.appupdateservergenerator.model.VersionCode
import java.io.IOException
import java.util.*

/**
 * The index of all the apps in the repo.
 *
 * Format:
 * - line 1: base64-encoded signature of the below contents
 * - line 2: a timestamp of when the index was last updated (i.e., the last time the insert-apk command was run)
 * - line 3+: a line for each app in the repo of the form
 *     ```
 *     packageName:versionCode:lastUpdateTimestamp
 *     ```
 *     where lastUpdateTimestamp is when the app's metadata was last updated.
 */
data class AppRepoIndex constructor(
    val timestamp: UnixTimestamp,
    val packageToVersionMap: SortedMap<String, Pair<VersionCode, UnixTimestamp>>
) {
    /**
     * Writes this [AppRepoIndex] to the disk and then signs the file using the [privateKey] and [openSSLInvoker].
     *
     * @throws IOException if an I/O error occurs.
     */
    fun writeToDiskAndSign(privateKey: PKCS8PrivateKeyFile, openSSLInvoker: OpenSSLInvoker, fileManager: FileManager) {
        val latestAppVersionIndex = fileManager.latestAppVersionIndex
        latestAppVersionIndex.bufferedWriter().use { writer ->
            writer.appendLine(timestamp.seconds.toString())
            packageToVersionMap.forEach { (packageName, versionCodeTimestampPair) ->
                val (versionCode, timestamp) = versionCodeTimestampPair
                writer.appendLine(createLine(packageName, versionCode, timestamp))
            }
        }
        openSSLInvoker.signFileAndPrependSignatureToFile(privateKey, latestAppVersionIndex)
    }

    /**
     * Format for the metadata file lines for each app.
     * The format is
     *
     *     packageName:versionCode:lastUpdateTimestamp
     */
    private fun createLine(
        packageName: String,
        versionCode: VersionCode,
        lastUpdateTimestamp: UnixTimestamp
    ) = "$packageName:${versionCode.code}:${lastUpdateTimestamp.seconds}"

    companion object {
        /**
         * Creates a new [AppRepoIndex] instance from the repo files on disk .
         *
         * @throws IOException if an I/O error occurs.
         */
        suspend fun create(fileManager: FileManager, timestamp: UnixTimestamp): AppRepoIndex = coroutineScope {
            val map = fileManager.appDirectory.listFiles()
                ?.filter { it.isDirectory }
                ?.map { dirForApp ->
                    async {
                        try {
                            AppMetadata.getMetadataFromDiskForPackage(dirForApp.name, fileManager)
                        } catch (e: IOException) {
                            null
                        }
                    }
                }
                ?.awaitAll()
                ?.filterNotNull()
                // sort by keys
                ?.associateTo(TreeMap()) { it.packageName to (it.latestVersionCode to it.lastUpdateTimestamp) }
                ?: throw IOException("failed to get files from app directory")
            AppRepoIndex(timestamp, map)
        }
    }
}
