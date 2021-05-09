package org.grapheneos.appupdateservergenerator.api

import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import org.grapheneos.appupdateservergenerator.model.Base64String
import org.grapheneos.appupdateservergenerator.model.UnixTimestamp
import org.grapheneos.appupdateservergenerator.model.VersionCode
import org.grapheneos.appupdateservergenerator.util.FileManager
import org.grapheneos.appupdateservergenerator.util.invoker.OpenSSLInvoker
import java.io.IOException

@Serializable
data class LatestAppVersionInfo(
    @SerialName("package")
    val packageName: String,
    val latestVersionCode: VersionCode,
    val sha256Checksum: Base64String,
    /**
     * The versions that have a delta available
     */
    val deltaAvailableVersions: List<VersionCode>,
    val lastUpdateTimestamp: UnixTimestamp,
) {
    fun writeToDiskAndSign(key: OpenSSLInvoker.Key, openSSLInvoker: OpenSSLInvoker, fileManager: FileManager) {
        val latestAppVersionInfoJson = Json.encodeToString(this)
        val signature = openSSLInvoker.signString(key, latestAppVersionInfoJson)
        fileManager.getLatestAppVersionInfoMetadata(pkg = packageName).bufferedWriter().use { writer ->
            writer.appendLine(signature.s)
            writer.append(latestAppVersionInfoJson)
            writer.flush()
        }
    }

    companion object {
        @Throws(IOException::class)
        fun getInfoFromDiskForPackage(pkg: String, fileManager: FileManager): LatestAppVersionInfo =
            try {
                Json.decodeFromString(fileManager.getLatestAppVersionInfoMetadata(pkg).useLines { it.last() })
            } catch (e: SerializationException) {
                throw IOException(e)
            }

    }
}