package api

import kotlinx.serialization.Serializable
import model.Base64String
import model.UnixTimestamp
import model.VersionCode

@Serializable
data class LatestAppVersionInfo(
    val latestVersionCode: VersionCode,
    val sha256Checksum: Base64String,
    /**
     * The versions that have a delta available
     */
    val deltaAvailableVersions: List<VersionCode>,
    val lastUpdateTimestamp: UnixTimestamp,
)
