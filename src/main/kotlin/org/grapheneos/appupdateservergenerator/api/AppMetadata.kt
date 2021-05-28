package org.grapheneos.appupdateservergenerator.api

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.grapheneos.appupdateservergenerator.crypto.OpenSSLInvoker
import org.grapheneos.appupdateservergenerator.crypto.PKCS8PrivateKeyFile
import org.grapheneos.appupdateservergenerator.files.FileManager
import org.grapheneos.appupdateservergenerator.model.AndroidApk
import org.grapheneos.appupdateservergenerator.model.Base64String
import org.grapheneos.appupdateservergenerator.model.UnixTimestamp
import org.grapheneos.appupdateservergenerator.model.VersionCode
import org.grapheneos.appupdateservergenerator.model.toBase64String
import org.grapheneos.appupdateservergenerator.serialization.ToolJson
import org.grapheneos.appupdateservergenerator.util.digest
import java.io.FileFilter
import java.io.IOException
import java.util.SortedMap
import java.util.SortedSet
import java.util.TreeSet

/**
 * The metadata for an app that will be parsed by the app.
 */
@Serializable
data class AppMetadata(
    @SerialName("package")
    val packageName: String,
    val groupId: String?,
    val label: String,
    val lastUpdateTimestamp: UnixTimestamp,
    @Contextual
    val releases: TreeSet<ReleaseInfo>
) {
    @Serializable
    data class DeltaInfo(val baseVersionCode: VersionCode, val sha256Checksum: Base64String) : Comparable<DeltaInfo> {
        override fun compareTo(other: DeltaInfo) = baseVersionCode.compareTo(other.baseVersionCode)
    }

    @Serializable
    data class ReleaseInfo(
        val versionCode: VersionCode,
        val versionName: String,
        val minSdkVersion: Int,
        val releaseTimestamp: UnixTimestamp,
        /** A checksum for the APK. */
        val sha256Checksum: Base64String,
        /** A set of [DeltaInfo] for previous [VersionCode]s that have a delta available for this version */
        @Contextual
        val deltaInfo: TreeSet<DeltaInfo>,
        val releaseNotes: String?
    ) : Comparable<ReleaseInfo> {
        override fun compareTo(other: ReleaseInfo): Int = versionCode.compareTo(other.versionCode)
        companion object {
            fun fromApk(apk: AndroidApk, releaseTimestamp: UnixTimestamp, releaseNotes: String?) = ReleaseInfo(
                versionName = apk.versionName,
                versionCode = apk.versionCode,
                minSdkVersion = apk.minSdkVersion,
                releaseTimestamp = releaseTimestamp,
                sha256Checksum = apk.apkFile.digest("SHA-256").toBase64String(),
                deltaInfo = sortedSetOf(),
                releaseNotes = releaseNotes
            )
        }
    }

    fun updateLatestReleaseWithDeltaInfo(newDeltaInfo: SortedSet<DeltaInfo>) {
        latestRelease().deltaInfo.apply {
            clear()
            addAll(newDeltaInfo)
        }
    }

    fun latestRelease(): ReleaseInfo = releases.last()

    fun writeToString() = try {
        ToolJson.json.encodeToString(this)
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
                ToolJson.json.decodeFromString(fileManager.getLatestAppMetadata(pkg).useLines { it.last() })
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

        /**
         * Gets a map of groupIds to the packages that are tagged with the groupId.
         *
         * If [groupsToSelect] is not null, the keys in the returned map will only contain [groupsToSelect].
         */
        suspend fun getAllGroupsAndTheirPackages(
            fileManager: FileManager,
            groupsToSelect: Set<String>?
        ): SortedMap<String, Set<String>> {
            @Suppress("UNCHECKED_CAST")
            return getAllAppMetadataFromDisk(fileManager).asSequence()
                .filter {
                    if (it.groupId == null) return@filter false
                    if (groupsToSelect == null) return@filter true
                    it.groupId in groupsToSelect
                }
                .groupingBy { it.groupId!! }
                .foldTo(
                    destination = sortedMapOf(),
                    initialValueSelector = { _, _ -> hashSetOf() },
                    operation = { _, accumulator: HashSet<String>, element ->
                        accumulator.apply { add(element.packageName) }
                    }
                )
            as SortedMap<String, Set<String>>
        }
    }
}
