package org.grapheneos.appupdateservergenerator.api

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.coroutineScope
import org.grapheneos.appupdateservergenerator.api.AppRepoIndex.Companion.createLine
import org.grapheneos.appupdateservergenerator.api.AppRepoIndex.Companion.regenerateIndexFromChannel
import org.grapheneos.appupdateservergenerator.crypto.OpenSSLInvoker
import org.grapheneos.appupdateservergenerator.crypto.PKCS8PrivateKeyFile
import org.grapheneos.appupdateservergenerator.crypto.SignatureHeaderInputStream
import org.grapheneos.appupdateservergenerator.files.FileManager
import org.grapheneos.appupdateservergenerator.model.PackageName
import org.grapheneos.appupdateservergenerator.model.UnixTimestamp
import org.grapheneos.appupdateservergenerator.model.VersionCode
import org.grapheneos.appupdateservergenerator.repo.StaticFileManager
import java.io.IOException
import java.util.SortedMap

/**
 * The index of all the apps in the repo.
 *
 * Format of the plaintext file (as specified by the [createLine] function):
 *
 *  - line 1: a header containing a signature of the below contents (see
 *   [SignatureHeaderInputStream.createSignatureHeaderWithLineFeed])
 *  - line 2: the [repoUpdateTimestamp], i.e., the last time the index was last updated and all app metadata was
 *    regenerated
 *  - line 3+: a line for each app in the repo of the form
 *     ```
 *     packageName latestVersionCode lastUpdateTimestamp
 *     ```
 *
 *    where lastUpdateTimestamp is the last time the app's metadata was last updated or the latest release date.
 *
 * When clients check for updates, they should store the `latestVersionCode` and `lastUpdateTimestamp` and use that to
 * validate the [AppMetadata] that they download for apps that have been updated in order to prevent the client from
 * installing an older version of an app.
 *
 * Implementation note: An instance of this class is not created for writing the index. Index updates are done in a
 * streaming fashion via [StaticFileManager.regenerateMetadataAndIcons], which uses [regenerateIndexFromChannel].
 */
data class AppRepoIndex private constructor(
    val repoUpdateTimestamp: UnixTimestamp,
    val packageToVersionMap: SortedMap<PackageName, Pair<VersionCode, UnixTimestamp>>
) {
    companion object {
        /**
         * Format for the metadata file lines for each app.
         * The format is
         *
         *     packageName versionCode lastUpdateTimestamp
         *
         * The [versionCode] written may be 0 if the app has no releases (not expected to happen)
         */
        private fun createLine(
            packageName: PackageName,
            versionCode: VersionCode?,
            lastUpdateTimestamp: UnixTimestamp
        ) = "${packageName.pkg} ${versionCode?.code ?: 0} ${lastUpdateTimestamp.seconds}"

        /**
         * Regenerates the app repo index by using the [AppMetadata] being fed through the [writerBlock]'s [SendChannel].
         * The caller should makes sure that the [AppMetadata] sent through the channel contains all the apps in the
         * repository.
         */
        suspend fun regenerateIndexFromChannel(
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
                        writer.appendLine(
                            createLine(
                                metadata.packageName,
                                metadata.latestReleaseOrNull()?.versionCode,
                                metadata.lastUpdateTimestamp
                            )
                        )
                    }
                }
                openSSLInvoker.signFileAndPrependSignatureToFile(privateKey, appIndex)
            }

            try {
                writerBlock(outerChannel)
            } finally {
                // idempotent operation
                outerChannel.close()
            }
        }

        private fun parseLine(line: String): Pair<PackageName, Pair<VersionCode, UnixTimestamp>>? {
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
                            val (packageName, versionCodeAndTimestamp) = parseLine(line)
                                ?: throw IOException("failed to parse line $line")
                            sortedMap[packageName] = versionCodeAndTimestamp
                        }
                    }
            }
            return timestamp?.let { AppRepoIndex(it, sortedMap) } ?: throw IOException("missing timestamp")
        }
    }
}
