package org.grapheneos.appupdateservergenerator.api

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.grapheneos.appupdateservergenerator.crypto.OpenSSLInvoker
import org.grapheneos.appupdateservergenerator.crypto.PKCS8PrivateKeyFile
import org.grapheneos.appupdateservergenerator.crypto.SignatureHeaderInputStream
import org.grapheneos.appupdateservergenerator.db.App
import org.grapheneos.appupdateservergenerator.db.AppRelease
import org.grapheneos.appupdateservergenerator.db.DeltaInfo
import org.grapheneos.appupdateservergenerator.files.FileManager
import org.grapheneos.appupdateservergenerator.model.Base64String
import org.grapheneos.appupdateservergenerator.model.GroupId
import org.grapheneos.appupdateservergenerator.model.PackageName
import org.grapheneos.appupdateservergenerator.model.UnixTimestamp
import org.grapheneos.appupdateservergenerator.model.VersionCode
import java.io.FileFilter
import java.io.IOException
import java.util.SortedSet

/**
 * The metadata for an app that will be parsed by the app.
 */
@Serializable
data class AppMetadata(
    @SerialName("package")
    val packageName: PackageName,
    val groupId: GroupId? = null,
    val label: String,
    val iconSignature: Base64String? = null,
    val lastUpdateTimestamp: UnixTimestamp,
    val releases: Set<ReleaseInfo>
) {
    @Serializable
    data class ReleaseInfo(
        val versionCode: VersionCode,
        val versionName: String,
        val minSdkVersion: Int,
        val releaseTimestamp: UnixTimestamp,
        /** A checksum for the APK. */
        val apkSha256: Base64String,
        /**
         * A sha256 checksum of the APK Signature Scheme v4 signature.
         * If this is null, the APK wasn't signed using the v4 scheme; otherwise, clients should expect to find the
         * .apk.idsig file.
         */
        val v4SigSha256: Base64String? = null,
        /** Set containing previous releases that have a delta available for this version. */
        val deltaInfo: Set<DeltaInfo>? = null,
        /**
         * Optional release notes for this release. This can be in Markdown (flavor configured in
         * [org.grapheneos.appupdateservergenerator.api.MarkdownProcessor]) or HTML. Contents will be compressed
         * during metadata generation, but contents in the database are left as is.
         */
        val releaseNotes: String? = null
    ) : Comparable<ReleaseInfo> {
        override fun compareTo(other: ReleaseInfo): Int = versionCode.compareTo(other.versionCode)
    }

    @Serializable
    data class DeltaInfo(val baseVersionCode: VersionCode, val sha256Checksum: Base64String) : Comparable<DeltaInfo> {
        override fun compareTo(other: DeltaInfo) = baseVersionCode.compareTo(other.baseVersionCode)
    }

    fun latestRelease(): ReleaseInfo = if (releases is SortedSet<ReleaseInfo> && releases.comparator() == null) {
        releases.last()
    } else {
        releases.maxByOrNull { it.versionCode }!!
    }

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
        fileManager.getLatestAppMetadata(pkg = packageName).bufferedWriter().use { writer ->
            writer.append(SignatureHeaderInputStream.createSignatureHeaderWithLineFeed(signature))
            writer.append(latestAppVersionInfoJson)
            writer.flush()
        }
    }

    companion object {
        val packageComparator = compareBy<AppMetadata> { it.packageName }

        /**
         * Reads the metadata in the [pkg]'s app directory.
         * @throws IOException if an I/O error occurs or the metadata is of an invalid format
         */
        fun getMetadataFromDiskForPackage(pkg: PackageName, fileManager: FileManager): AppMetadata =
            try {
                Json.decodeFromString(fileManager.getLatestAppMetadata(pkg).useLines { it.last() })
            } catch (e: SerializationException) {
                throw IOException(e)
            }

        /**
         * Reads all app metadata from the app / package directories. This will ignore directories that don't contain
         * valid app info.
         *
         * @throws IOException if an I/O error occurs.
         */
        suspend fun getAllAppMetadataFromDisk(fileManager: FileManager): SortedSet<AppMetadata> = coroutineScope {
            fileManager.appDirectory.listFiles(FileFilter { it.isDirectory })
                ?.mapTo(ArrayList()) { dirForApp ->
                    async {
                        try {
                            getMetadataFromDiskForPackage(PackageName(dirForApp.name), fileManager)
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

fun App.toSerializableModel(releases: Set<AppMetadata.ReleaseInfo>) = AppMetadata(
    packageName = packageName,
    groupId = groupId,
    label = label,
    iconSignature = iconSignature,
    lastUpdateTimestamp = lastUpdateTimestamp,
    releases = releases
)

fun AppRelease.toSerializableModel(deltaInfo: Set<AppMetadata.DeltaInfo>) = AppMetadata.ReleaseInfo(
    versionCode = versionCode,
    versionName = versionName,
    minSdkVersion = minSdkVersion,
    releaseTimestamp = releaseTimestamp,
    apkSha256 = apkSha256,
    v4SigSha256 = v4SigSha256,
    deltaInfo = deltaInfo.ifEmpty { null },
    releaseNotes = releaseNotes?.takeIf { it.isNotBlank() }?.let(MarkdownProcessor::markdownToCompressedHtml)
)

fun DeltaInfo.toSerializableModel() = AppMetadata.DeltaInfo(baseVersion, sha256Checksum)