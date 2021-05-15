package org.grapheneos.appupdateservergenerator.api

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import org.grapheneos.appupdateservergenerator.crypto.OpenSSLInvoker
import org.grapheneos.appupdateservergenerator.crypto.PKCS8PrivateKeyFile
import org.grapheneos.appupdateservergenerator.files.FileManager
import org.grapheneos.appupdateservergenerator.model.Base64String
import org.grapheneos.appupdateservergenerator.model.UnixTimestamp
import org.grapheneos.appupdateservergenerator.model.VersionCode
import java.io.FileFilter
import java.io.IOException
import java.util.*

/**
 * The metadata for an app that will be parsed by the app.
 */
@Serializable
data class AppMetadata(
    @SerialName("package")
    val packageName: String,
    val label: String,
    val latestVersionCode: VersionCode,
    val latestVersionName: String,
    val sha256Checksum: Base64String,
    /**
     * The versions that have a delta available
     */
    val deltaAvailableVersions: Set<VersionCode>,
    val lastUpdateTimestamp: UnixTimestamp,
) {
    fun writeToString() = try {
        Json.encodeToString(this)
    } catch (e: SerializationException) {
        throw IOException(e)
    }

    /**
     * @throws IOException if an I/O error occurs or the metadata is of an invalid format
     */
    fun writeToDiskAndSign(privateKey: PKCS8PrivateKeyFile, openSSLInvoker: OpenSSLInvoker, fileManager: FileManager) {
        val latestAppVersionInfoJson = writeToString()
        val signature = openSSLInvoker.signString(privateKey, latestAppVersionInfoJson)
        fileManager.getLatestAppVersionInfoMetadata(pkg = packageName).bufferedWriter().use { writer ->
            writer.appendLine(signature.s)
            writer.append(latestAppVersionInfoJson)
            writer.flush()
        }
    }

    companion object {
        val packageComparator = Comparator<AppMetadata> { o1, o2 -> o1.packageName.compareTo(o2.packageName) }

        /**
         * Reads the metadata in the [pkg]'s app directory.
         * @throws IOException if an I/O error occurs or the metadata is of an invalid format
         */
        fun getMetadataFromDiskForPackage(pkg: String, fileManager: FileManager): AppMetadata =
            try {
                Json.decodeFromString(fileManager.getLatestAppVersionInfoMetadata(pkg).useLines { it.last() })
            } catch (e: SerializationException) {
                throw IOException(e)
            }

        /**
         * @throws IOException if an I/O error occurs.
         */
        suspend fun getAllAppMetadataFromDisk(fileManager: FileManager): SortedSet<AppMetadata> = coroutineScope {
            fileManager.appDirectory.listFiles(FileFilter { it.isDirectory })
                ?.mapTo(ArrayList()) { dirForApp ->
                    async {
                        try {
                            getMetadataFromDiskForPackage(dirForApp.name, fileManager)
                        } catch (e: IOException) {
                            // Ignore directories that fail
                            null
                        }
                    }
                }
                ?.awaitAll()
                ?.filterNotNull()
                ?.toSortedSet(packageComparator)
                ?: throw IOException("unable to get all app metadata from disk")
        }
    }
}
