package org.grapheneos.appupdateservergenerator.api

import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import org.grapheneos.appupdateservergenerator.crypto.OpenSSLInvoker
import org.grapheneos.appupdateservergenerator.crypto.PKCS8PrivateKeyFile
import org.grapheneos.appupdateservergenerator.files.FileManager
import org.grapheneos.appupdateservergenerator.model.Base64String
import org.grapheneos.appupdateservergenerator.model.UnixTimestamp
import org.grapheneos.appupdateservergenerator.model.VersionCode
import java.io.IOException

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
    constructor(
        packageName: String,
        label: String,
        latestVersionCode: VersionCode,
        latestVersionName: String,
        sha256Checksum: Base64String,
        deltaAvailableVersions: List<VersionCode>,
        lastUpdateTimestamp: UnixTimestamp
    ) : this(
        packageName = packageName,
        label = label,
        latestVersionCode = latestVersionCode,
        latestVersionName = latestVersionName,
        sha256Checksum = sha256Checksum,
        deltaAvailableVersions = deltaAvailableVersions.toSet(),
        lastUpdateTimestamp = lastUpdateTimestamp
    )

    fun writeToDiskAndSign(privateKey: PKCS8PrivateKeyFile, openSSLInvoker: OpenSSLInvoker, fileManager: FileManager) {
        val latestAppVersionInfoJson = Json.encodeToString(this)
        val signature = openSSLInvoker.signString(privateKey, latestAppVersionInfoJson)
        fileManager.getLatestAppVersionInfoMetadata(pkg = packageName).bufferedWriter().use { writer ->
            writer.appendLine(signature.s)
            writer.append(latestAppVersionInfoJson)
            writer.flush()
        }
    }

    companion object {
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

    }
}
