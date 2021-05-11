package org.grapheneos.appupdateservergenerator.api

import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import org.grapheneos.appupdateservergenerator.crypto.OpenSSLInvoker
import org.grapheneos.appupdateservergenerator.crypto.PrivateKeyFile
import org.grapheneos.appupdateservergenerator.files.FileManager
import org.grapheneos.appupdateservergenerator.model.Base64String
import org.grapheneos.appupdateservergenerator.model.UnixTimestamp
import org.grapheneos.appupdateservergenerator.model.VersionCode
import java.io.IOException

@Serializable
data class AppMetadata(
    @SerialName("package")
    val packageName: String,
    val latestVersionCode: VersionCode,
    val sha256Checksum: Base64String,
    /**
     * The versions that have a delta available
     */
    val deltaAvailableVersions: Set<VersionCode>,
    val lastUpdateTimestamp: UnixTimestamp,
) {
    constructor(
        packageName: String,
        latestVersionCode: VersionCode,
        sha256Checksum: Base64String,
        deltaAvailableVersions: List<VersionCode>,
        lastUpdateTimestamp: UnixTimestamp
    ) : this(packageName, latestVersionCode, sha256Checksum, deltaAvailableVersions.toSet(), lastUpdateTimestamp)

    fun writeToDiskAndSign(privateKey: PrivateKeyFile, openSSLInvoker: OpenSSLInvoker, fileManager: FileManager) {
        val latestAppVersionInfoJson = Json.encodeToString(this)
        val signature = openSSLInvoker.signString(privateKey, latestAppVersionInfoJson)
        fileManager.getLatestAppVersionInfoMetadata(pkg = packageName).bufferedWriter().use { writer ->
            writer.appendLine(signature.s)
            writer.append(latestAppVersionInfoJson)
            writer.flush()
        }
    }

    companion object {
        @Throws(IOException::class)
        fun getInfoFromDiskForPackage(pkg: String, fileManager: FileManager): AppMetadata =
            try {
                Json.decodeFromString(fileManager.getLatestAppVersionInfoMetadata(pkg).useLines { it.last() })
            } catch (e: SerializationException) {
                throw IOException(e)
            }

    }
}
