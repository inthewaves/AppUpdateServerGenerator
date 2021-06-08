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
import org.grapheneos.appupdateservergenerator.model.AndroidApk
import org.grapheneos.appupdateservergenerator.model.ApkVerifyResult
import org.grapheneos.appupdateservergenerator.model.Base64String
import org.grapheneos.appupdateservergenerator.model.GroupId
import org.grapheneos.appupdateservergenerator.model.PackageName
import org.grapheneos.appupdateservergenerator.model.UnixTimestamp
import org.grapheneos.appupdateservergenerator.model.VersionCode
import org.grapheneos.appupdateservergenerator.model.encodeToBase64String
import org.grapheneos.appupdateservergenerator.util.digest
import java.io.FileFilter
import java.io.IOException
import java.util.SortedSet

/**
 * The metadata for an app that will be parsed by the app. This corresponds to an app and is derived from the
 * database model class [App]. Use the [App.toSerializableModel] extension function to create an instance of this
 * class.
 */
@Serializable
data class AppMetadata(
    @SerialName("package")
    val packageName: PackageName,
    /**
     * The timestamp of the repository index corresponding to the time the repo had an update. This timestamp can be
     * a later time than [lastUpdateTimestamp]. This should be the same as the timestamp in the repository index and
     * bulk metadata.
     *
     * Clients **must** verify this against the repository index timestamp.
     */
    val repoIndexTimestamp: UnixTimestamp,
    /** TODO: Replace with libraries, which parses the `uses-library` and `uses-static-library` tags from the manifest */
    val groupId: GroupId? = null,
    val label: String,
    val iconSha256: Base64String?,
    /** The last time this app has been updated. */
    val lastUpdateTimestamp: UnixTimestamp,
    val releases: Set<ReleaseInfo>
) {
    /**
     * Represents a specific release of the app. This corresponds to an APK of the app and is derived from the database
     * model class [AppRelease]. Use the extension function [AppRelease.toSerializableModel] to create an instance
     * of this class.
     */
    @Serializable
    data class ReleaseInfo(
        val versionCode: VersionCode,
        val versionName: String,
        val minSdkVersion: Int,
        val releaseTimestamp: UnixTimestamp,
        /** The base64-encoded sha256 checksum for the APK. */
        val apkSha256: Base64String,
        /**
         * The base64-encoded sha256 checksum of the APK Signature Scheme v4 signature.
         * If this is null, the APK wasn't signed using the v4 scheme; otherwise, clients should expect to find the
         * .apk.idsig file next to the APK.
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

        /**
         * Represents a delta file for an older release of an app that targets the latest version of the app. This
         * corresponds to the database model [org.grapheneos.appupdateservergenerator.db.DeltaInfo].
         *
         * @property baseVersionCode The base version code.
         * @property sha256 The sha256 checksum of the delta file, encoded in base64.
         */
        @Serializable
        data class DeltaInfo(val baseVersionCode: VersionCode, val sha256: Base64String) : Comparable<DeltaInfo> {
            override fun compareTo(other: DeltaInfo) = baseVersionCode.compareTo(other.baseVersionCode)
        }
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
                ?.map { dirForApp ->
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
                ?.asSequence()
                ?.filterNotNull()
                ?.toSortedSet(packageComparator)
                ?: throw IOException("unable to get all app metadata from disk")
        }
    }
}

fun App.toSerializableModel(
    repositoryIndexTimestamp: UnixTimestamp,
    releases: Set<AppMetadata.ReleaseInfo>
) = AppMetadata(
    packageName = packageName,
    repoIndexTimestamp = repositoryIndexTimestamp,
    groupId = groupId,
    label = label,
    iconSha256 = icon?.digest("SHA-256")?.encodeToBase64String(),
    lastUpdateTimestamp = lastUpdateTimestamp,
    releases = releases
)

fun AppRelease.toSerializableModel(deltaInfo: Set<AppMetadata.ReleaseInfo.DeltaInfo>) = AppMetadata.ReleaseInfo(
    versionCode = versionCode,
    versionName = versionName,
    minSdkVersion = minSdkVersion,
    releaseTimestamp = releaseTimestamp,
    apkSha256 = apkSha256,
    v4SigSha256 = v4SigSha256,
    deltaInfo = deltaInfo.ifEmpty { null },
    releaseNotes = releaseNotes?.takeIf { it.isNotBlank() }?.let(MarkdownProcessor::markdownToCompressedHtml)
)

fun AndroidApk.toAppRelease(releaseTimestamp: UnixTimestamp, releaseNotes: String?) = AppRelease(
    packageName = packageName,
    versionName = versionName,
    versionCode = versionCode,
    minSdkVersion = minSdkVersion,
    releaseTimestamp = releaseTimestamp,
    apkSha256 = apkFile.digest("SHA-256").encodeToBase64String(),
    v4SigSha256 = (verifyResult as? ApkVerifyResult.V4)?.v4SignatureFile
        ?.digest("SHA-256")
        ?.encodeToBase64String(),
    releaseNotes = releaseNotes
)

fun DeltaInfo.toSerializableModel() = AppMetadata.ReleaseInfo.DeltaInfo(baseVersion, sha256Checksum)