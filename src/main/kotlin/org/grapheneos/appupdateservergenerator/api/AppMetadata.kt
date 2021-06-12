package org.grapheneos.appupdateservergenerator.api

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Transient
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.grapheneos.appupdateservergenerator.crypto.OpenSSLInvoker
import org.grapheneos.appupdateservergenerator.crypto.PKCS8PrivateKeyFile
import org.grapheneos.appupdateservergenerator.crypto.SignatureHeaderInputStream
import org.grapheneos.appupdateservergenerator.db.App
import org.grapheneos.appupdateservergenerator.db.AppRelease
import org.grapheneos.appupdateservergenerator.files.FileManager
import org.grapheneos.appupdateservergenerator.model.AndroidApk
import org.grapheneos.appupdateservergenerator.model.ApkVerifyResult
import org.grapheneos.appupdateservergenerator.model.Base64String
import org.grapheneos.appupdateservergenerator.model.HexString
import org.grapheneos.appupdateservergenerator.model.PackageName
import org.grapheneos.appupdateservergenerator.model.UnixTimestamp
import org.grapheneos.appupdateservergenerator.model.VersionCode
import org.grapheneos.appupdateservergenerator.model.encodeToBase64String
import org.grapheneos.appupdateservergenerator.model.encodeToHexString
import org.grapheneos.appupdateservergenerator.util.digest
import org.grapheneos.appupdateservergenerator.util.isUsingNaturalOrdering
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
    val label: String?,
    val iconSha256: Base64String?,
    /** The last time this app has been updated. */
    val lastUpdateTimestamp: UnixTimestamp,
    val releases: Set<ReleaseInfo>
) {
    /**
     * Represents a specific release of the app. This corresponds to an APK of the app and is derived from the database
     * model class [AppRelease]. Use the extension function [AppRelease.toSerializableModelAndVerify] to create an instance
     * of this class.
     */
    @Serializable
    data class ReleaseInfo private constructor(
        /**
         * The associated [AndroidApk]. This is not serialized (@[Transient]).
         * If this is null, then this [ReleaseInfo] instance was created from deserialization.
         */
        @Transient
        val apkFile: AndroidApk? = null,
        /**
         * The size of the associated [AndroidApk].
         * If this is null, then this [ReleaseInfo] instance was created from deserialization.
         */
        val apkSize: Long? = null,
        val versionCode: VersionCode,
        val versionName: String,
        val minSdkVersion: Int,
        val releaseTimestamp: UnixTimestamp,
        /**
         * Hex-encoded sha256 digests of the signing certificates. Clients can use this to determine if an installed
         * version is compatible with a release on the server via a subset check (i.e., check if the digests of the
         * certificates of the locally installed version is a subset of the server metadata digests)
         */
        val certDigests: List<HexString>,
        /** The base64-encoded sha256 checksum for the APK. */
        val apkSha256: Base64String,
        /**
         * The base64-encoded sha256 checksum of the APK Signature Scheme v4 signature.
         * If this is null, the APK wasn't signed using the v4 scheme; otherwise, clients should expect to find the
         * .apk.idsig file next to the APK.
         */
        val v4SigSha256: Base64String? = null,
        /**
         * If this is non null, then this apk is providing itself
         * as a static shared library for other applications to use. Any app can declare such
         * a library and there can be only one static shared library per package. These libraries
         * are updatable, multiple versions can be installed at the same time, and an app links
         * against a specific version simulating static linking while allowing code sharing.
         *
         * @see AndroidApk.StaticLibrary
         */
        val staticLibrary: AndroidApk.StaticLibrary? = null,
        /**
         * `uses-libraries` specifies a shared library that this package requires to be linked against.
         * Specifying this flag tells the system to include this library's code in your class loader.
         *
         * This appears as a child tag of the AndroidManifest `application` tag.
         *
         * [Source declaration](https://android.googlesource.com/platform/frameworks/base/+/cc0a3a9bef94bd2ad7061a17f0a3297be5d7f270/core/res/res/values/attrs_manifest.xml#2182)
         *
         * @see AndroidApk.UsesLibrary
         */
        val usesLibraries: List<AndroidApk.UsesLibrary>? = null,
        /**
         * Specifies a vendor-provided shared native library that the application must be linked against. This element tells
         * the system to make the native library accessible for the package.
         *
         * NDK libraries are by default accessible and therefore don't require the <uses-native-library> tag.
         *
         * Non-NDK native shared libraries that are provided by silicon vendors or device manufacturers are not accessible
         * by default if the app is targeting Android 12 or higher. The libraries are accessible only when they are
         * explicitly requested using the `<uses-native-library>` tag.
         *
         * If the app is targeting Android 11 or lower, the `<uses-native-library>` tag is not required. In that case, any
         * native shared library is accessible regardless of whether it is an NDK library.
         *
         * This element also affects the installation of the application on a particular device: If this element is present
         * and its `android:required` attribute is set to true, the PackageManager framework won't let the user install the
         * application unless the library is present on the user's device.
         *
         * [Android Developers documentation](https://developer.android.com/guide/topics/manifest/uses-native-library-element)
         *
         * @see AndroidApk.UsesNativeLibrary
         */
        val usesNativeLibraries: List<AndroidApk.UsesNativeLibrary>? = null,
        /**
         * Specifies a shared **static** library that this package requires to be statically
         * linked against. Specifying this tag tells the system to include this library's code
         * in your class loader. Depending on a static shared library is equivalent to statically
         * linking with the library at build time while it offers apps to share code defined in such
         * libraries. Hence, static libraries are strictly required.
         *
         * On devices running O MR1 or higher, if the library is signed with multiple
         * signing certificates you must to specify the SHA-256 hashes of the additional
         * certificates via adding `additional-certificate` tags.
         *
         * This appears as a child tag of the AndroidManifest `application` tag.
         *
         * [Source declaration](https://android.googlesource.com/platform/frameworks/base/+/cc0a3a9bef94bd2ad7061a17f0a3297be5d7f270/core/res/res/values/attrs_manifest.xml#2209)
         *
         * @see AndroidApk.UsesStaticLibrary
         */
        val usesStaticLibraries: List<AndroidApk.UsesStaticLibrary>? = null,
        /**
         * Specifies some kind of dependency on another package. It does not have any impact
         * on the app's execution on the device, but provides information about dependencies
         * it has on other packages that need to be satisfied for it to run correctly. That is,
         * this is primarily for installers to know what other apps need to be installed along
         * with this one.
         *
         * @see AndroidApk.UsesPackage
         */
        val usesPackages: List<AndroidApk.UsesPackage>? = null,
        /** Set containing previous releases that have a delta available for this version. */
        val deltaInfo: Set<DeltaInfo>? = null,
        /**
         * Optional release notes for this release. This can be in Markdown (flavor configured in
         * [org.grapheneos.appupdateservergenerator.api.MarkdownProcessor]) or HTML. Contents will be compressed
         * during metadata generation, but contents in the database are left as is.
         */
        val releaseNotes: String? = null
    ) : Comparable<ReleaseInfo> {
        constructor(
            apkFile: AndroidApk,
            versionCode: VersionCode,
            versionName: String,
            minSdkVersion: Int,
            releaseTimestamp: UnixTimestamp,
            deltaInfo: Set<DeltaInfo>,
            releaseNotes: String?
        ) : this(
            apkFile = apkFile,
            apkSize = apkFile.apkFile.length(),
            versionCode = versionCode,
            versionName = versionName,
            minSdkVersion = minSdkVersion,
            releaseTimestamp = releaseTimestamp,
            certDigests = apkFile.verifyResult.result.signerCertificates.map {
                it.encoded.digest("SHA-256").encodeToHexString()
            },
            apkSha256 = apkFile.apkFile.digest("SHA-256").encodeToBase64String(),
            v4SigSha256 = (apkFile.verifyResult as? ApkVerifyResult.V4)
                ?.v4SignatureFile
                ?.digest("SHA-256")
                ?.encodeToBase64String(),
            staticLibrary = apkFile.staticLibrary,
            usesLibraries = apkFile.usesLibraries.ifEmpty { null },
            usesNativeLibraries = apkFile.usesNativeLibraries.ifEmpty { null },
            usesStaticLibraries = apkFile.usesStaticLibraries.ifEmpty { null },
            usesPackages = apkFile.usesPackages.ifEmpty { null },
            deltaInfo = deltaInfo.ifEmpty { null },
            releaseNotes = releaseNotes?.ifBlank { null }?.let(MarkdownProcessor::markdownToCompressedHtml)
        )


        override fun compareTo(other: ReleaseInfo): Int = versionCode.compareTo(other.versionCode)

        /**
         * Represents a delta file for an older release of an app that targets the latest version of the app. This
         * corresponds to the database model [org.grapheneos.appupdateservergenerator.db.DeltaInfo].
         *
         * @property baseVersionCode The base version code.
         * @property sha256 The sha256 checksum of the delta file, encoded in base64.
         */
        @Serializable
        data class DeltaInfo(
            val baseVersionCode: VersionCode,
            val size: Long,
            val sha256: Base64String
        ) : Comparable<DeltaInfo> {
            override fun compareTo(other: DeltaInfo) = baseVersionCode.compareTo(other.baseVersionCode)
        }
    }

    /**
     * Returns the latest [ReleaseInfo], or null if [releases] is empty.
     */
    fun latestReleaseOrNull(): ReleaseInfo? =
        if (releases.isNotEmpty()) {
            latestRelease()
        } else {
            null
        }

    /**
     * Returns the latest [ReleaseInfo] or throws there are no releases
     *
     * @throws NoSuchElementException if [releases] is empty
     */
    fun latestRelease(): ReleaseInfo =
        if (releases is SortedSet<ReleaseInfo> && releases.isUsingNaturalOrdering) {
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
    icon: AndroidApk.AppIcon?,
    releases: Set<AppMetadata.ReleaseInfo>,
) = AppMetadata(
    packageName = packageName,
    repoIndexTimestamp = repositoryIndexTimestamp,
    label = label,
    iconSha256 = icon?.bytes?.digest("SHA-256")?.encodeToBase64String(),
    lastUpdateTimestamp = lastUpdateTimestamp,
    releases = releases
)

/**
 * Verifies this [AppRelease] database model that the APK details match and then creates a serializable [AppRelease]
 * instance. A warning will be printed if the [org.grapheneos.appupdateservergenerator.db.AppRelease] details in the
 * database doesn't match the details from the APK on disk. This also handles parsing of Markdown release notes into
 * HTMl and then compressing the HTML via the [MarkdownProcessor].
 *
 * TODO: Evaluate whether we should cut down on redundant data stored in the database if we can just derive it from
 *  the APK on disk again.
 */
fun AppRelease.toSerializableModelAndVerify(
    deltaInfo: Set<AppMetadata.ReleaseInfo.DeltaInfo>,
    fileManager: FileManager
): AppMetadata.ReleaseInfo {
    // This verifies the APK details on disk.
    val apk = AndroidApk.buildFromApkAndVerifySignature(fileManager.getVersionedApk(packageName, versionCode))
    val appReleaseFromApk = apk.toAppReleaseDbModel(releaseTimestamp, releaseNotes)
    if (this != appReleaseFromApk) {
        println(
            "warning: for $packageName, app release info in the database for $versionCode doesn't match the one " +
                    "generated from ${apk.apkFile} "
        )
    }

    return AppMetadata.ReleaseInfo(
        apkFile = apk,
        versionCode = versionCode,
        versionName = versionName,
        minSdkVersion = minSdkVersion,
        releaseTimestamp = releaseTimestamp,
        deltaInfo = deltaInfo,
        releaseNotes = releaseNotes
    )
}

fun AndroidApk.toAppReleaseDbModel(releaseTimestamp: UnixTimestamp, releaseNotes: String?) = AppRelease(
    packageName = packageName,
    versionName = versionName,
    versionCode = versionCode,
    minSdkVersion = minSdkVersion,
    releaseTimestamp = releaseTimestamp,
    apkSha256 = apkFile.digest("SHA-256").encodeToBase64String(),
    v4SigSha256 = (verifyResult as? ApkVerifyResult.V4)?.v4SignatureFile
        ?.digest("SHA-256")
        ?.encodeToBase64String(),
    staticLibraryName = staticLibrary?.name,
    staticLibraryVersion = staticLibrary?.version,
    releaseNotes = releaseNotes
)
