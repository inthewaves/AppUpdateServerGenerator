package api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import model.Base64String
import model.UnixTimestamp
import model.VersionCode
import util.FileManager
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
