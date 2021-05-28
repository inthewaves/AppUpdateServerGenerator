package org.grapheneos.appupdateservergenerator.api

import kotlinx.coroutines.coroutineScope
import org.grapheneos.appupdateservergenerator.crypto.OpenSSLInvoker
import org.grapheneos.appupdateservergenerator.crypto.PKCS8PrivateKeyFile
import org.grapheneos.appupdateservergenerator.files.FileManager
import org.grapheneos.appupdateservergenerator.model.UnixTimestamp
import org.grapheneos.appupdateservergenerator.model.VersionCode
import java.io.IOException
import java.util.SortedMap
import java.util.TreeMap

/**
 * The index of all the apps in the repo.
 *
 * Format:
 * - line 1: base64-encoded signature of the below contents
 * - line 2: a timestamp of when the index was last updated (i.e., the last time the insert-apk command was run)
 * - line 3+: a line for each app in the repo of the form
 *     ```
 *     packageName versionCode lastUpdateTimestamp
 *     ```
 *     where lastUpdateTimestamp is when the app's metadata was last updated.
 */
data class AppRepoIndex private constructor(
    val timestamp: UnixTimestamp,
    val packageToVersionMap: SortedMap<String, Pair<VersionCode, UnixTimestamp>>
) {
    /**
     * Writes this [AppRepoIndex] to the disk and then signs the file using the [privateKey] and [openSSLInvoker].
     *
     * @throws IOException if an I/O error occurs.
     */
    fun writeToDiskAndSign(privateKey: PKCS8PrivateKeyFile, openSSLInvoker: OpenSSLInvoker, fileManager: FileManager) {
        val latestAppVersionIndex = fileManager.appIndex
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
     *     packageName versionCode lastUpdateTimestamp
     */
    private fun createLine(
        packageName: String,
        versionCode: VersionCode,
        lastUpdateTimestamp: UnixTimestamp
    ) = "$packageName ${versionCode.code} ${lastUpdateTimestamp.seconds}"

    companion object {
        private fun createFromLine(line: String): Pair<String, Pair<VersionCode, UnixTimestamp>>? {
            val split = line.split(' ')
            return if (split.size == 3) {
                split[0] to (VersionCode(split[1].toInt()) to UnixTimestamp(split[2].toLong()))
            } else {
                null
            }
        }

        fun readFromExistingIndexFile(fileManager: FileManager): AppRepoIndex {
            val sortedMap = sortedMapOf<String, Pair<VersionCode, UnixTimestamp>>()
            var timestamp: UnixTimestamp? = null
            fileManager.appIndex.useLines { lineSequence ->
                // drop the signature line
                lineSequence.drop(1)
                    .forEachIndexed { index, line ->
                        if (index == 0) {
                            timestamp = UnixTimestamp(line.toLong())
                        } else {
                            val (packageName, versionCodeAndTimestamp) = createFromLine(line)
                                ?: throw IOException("failed to parse line $line")
                            sortedMap[packageName] = versionCodeAndTimestamp
                        }
                    }
            }
            return timestamp?.let { AppRepoIndex(it, sortedMap) } ?: throw IOException("missing timestamp")
        }

        /**
         * Creates a new [AppRepoIndex] instance from the repo files on disk.
         *
         * @throws IOException if an I/O error occurs.
         */
        suspend fun constructFromRepoFilesOnDisk(
            fileManager: FileManager,
            timestamp: UnixTimestamp
        ): AppRepoIndex = coroutineScope {
            // sort by keys
            val map = AppMetadata.getAllAppMetadataFromDisk(fileManager)
                .associateTo(TreeMap()) { it.packageName to (it.latestRelease().versionCode to it.lastUpdateTimestamp) }
            AppRepoIndex(timestamp, map)
        }
    }
}
