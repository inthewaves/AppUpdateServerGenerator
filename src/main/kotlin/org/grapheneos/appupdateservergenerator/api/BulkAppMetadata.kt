package org.grapheneos.appupdateservergenerator.api

import org.grapheneos.appupdateservergenerator.crypto.OpenSSLInvoker
import org.grapheneos.appupdateservergenerator.crypto.PKCS8PrivateKeyFile
import org.grapheneos.appupdateservergenerator.files.FileManager
import org.grapheneos.appupdateservergenerator.model.UnixTimestamp
import java.io.IOException
import java.util.*

/**
 * Represents a file where every (non-signature and timestamp) line is a JSON string of [AppMetadata]
 * for every app in the repo. Intended to be used for fresh installs or force refreshes to not require
 * the app the make tons of network requests, as it is more efficient to do everything in one network
 * request.
 */
data class BulkAppMetadata(
    val lastUpdateTimestamp: UnixTimestamp,
    val allAppMetadata: SortedSet<AppMetadata>
) {
    /**
     * @throws IOException if an I/O error occurs or the metadata is of an invalid format
     */
    fun writeToDiskAndSign(
        fileManager: FileManager,
        openSSLInvoker: OpenSSLInvoker,
        privateKey: PKCS8PrivateKeyFile
    ) {
        val bulkAppMetadataFle = fileManager.latestAppMetadataBulk
        bulkAppMetadataFle.bufferedWriter().use { writer ->
            writer.appendLine(lastUpdateTimestamp.seconds.toString())
            allAppMetadata.forEach { writer.appendLine(it.writeToString()) }
        }
        openSSLInvoker.signFileAndPrependSignatureToFile(privateKey, bulkAppMetadataFle)
    }

    companion object {
        suspend fun createFromDisk(
            fileManager: FileManager,
            timestamp: UnixTimestamp
        ) = BulkAppMetadata(timestamp, AppMetadata.getAllAppMetadataFromDisk(fileManager))
    }
}
