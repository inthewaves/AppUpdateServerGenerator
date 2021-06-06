package org.grapheneos.appupdateservergenerator.api

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.coroutineScope
import org.grapheneos.appupdateservergenerator.crypto.OpenSSLInvoker
import org.grapheneos.appupdateservergenerator.crypto.PKCS8PrivateKeyFile
import org.grapheneos.appupdateservergenerator.crypto.SignatureHeaderInputStream
import org.grapheneos.appupdateservergenerator.files.FileManager
import org.grapheneos.appupdateservergenerator.model.PackageName
import org.grapheneos.appupdateservergenerator.model.UnixTimestamp
import org.grapheneos.appupdateservergenerator.model.VersionCode
import java.io.IOException
import java.util.SortedMap
import java.util.TreeMap

/**
 * The index of all the apps in the repo.
 *
 * Format:
 * - line 1: a header containing a signature of the below contents (see
 *   [SignatureHeaderInputStream.createSignatureHeaderWithLineFeed])
 * - line 2: a timestamp of when the index was last updated (i.e., the last time the `add` command was run)
 * - line 3+: a line for each app in the repo of the form
 *     ```
 *     packageName latestVersionCode lastUpdateTimestamp
 *     ```
 *     where lastUpdateTimestamp is the last time the app's metadata was last updated or the latest release date.
 */
data class AppRepoIndex private constructor(
    val timestamp: UnixTimestamp,
    val packageToVersionMap: SortedMap<PackageName, Pair<VersionCode, UnixTimestamp>>
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

    companion object {
        /**
         * Format for the metadata file lines for each app.
         * The format is
         *
         *     packageName versionCode lastUpdateTimestamp
         */
        private fun createLine(
            packageName: PackageName,
            versionCode: VersionCode,
            lastUpdateTimestamp: UnixTimestamp
        ) = "${packageName.pkg} ${versionCode.code} ${lastUpdateTimestamp.seconds}"

        suspend fun writeFromChannel(
            updateTimestamp: UnixTimestamp,
            fileManager: FileManager,
            openSSLInvoker: OpenSSLInvoker,
            privateKey: PKCS8PrivateKeyFile,
            writerBlock: suspend (channel: SendChannel<AppMetadata>) -> Unit
        ): Unit = coroutineScope {
            val outerChannel = actor<AppMetadata>(capacity = Channel.UNLIMITED) {
                val appIndex = fileManager.appIndex.apply { delete() }
                appIndex.bufferedWriter().use { writer ->
                    writer.appendLine(updateTimestamp.seconds.toString())
                    for (metadata in channel) {
                        writer.appendLine(createLine(
                            metadata.packageName,
                            metadata.latestRelease().versionCode,
                            metadata.lastUpdateTimestamp
                        ))
                    }
                }
                openSSLInvoker.signFileAndPrependSignatureToFile(privateKey, appIndex)
            }

            try {
                writerBlock(outerChannel)
            } finally {
                outerChannel.close()
            }
        }

        private fun createFromLine(line: String): Pair<PackageName, Pair<VersionCode, UnixTimestamp>>? {
            val split = line.split(' ')
            return if (split.size == 3) {
                PackageName(split[0]) to (VersionCode(split[1].toLong()) to UnixTimestamp(split[2].toLong()))
            } else {
                null
            }
        }

        fun readFromExistingIndexFile(fileManager: FileManager): AppRepoIndex {
            val sortedMap = sortedMapOf<PackageName, Pair<VersionCode, UnixTimestamp>>()
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
