package org.grapheneos.appupdateservergenerator.model

import com.android.apksig.ApkSigner
import com.android.apksig.apk.ApkFormatException
import com.android.apksig.apk.ApkUtils
import com.android.apksig.internal.apk.AndroidBinXmlParser
import com.google.devrel.gmscore.tools.apk.arsc.BinaryResourceFile
import com.google.devrel.gmscore.tools.apk.arsc.BinaryResourceIdentifier
import com.google.devrel.gmscore.tools.apk.arsc.BinaryResourceValue
import com.google.devrel.gmscore.tools.apk.arsc.ResourceTableChunk
import kotlinx.serialization.Serializable
import org.grapheneos.appupdateservergenerator.apkparsing.BinaryResAndConfig
import org.grapheneos.appupdateservergenerator.apkparsing.BinaryResourceConfigBuilder
import org.grapheneos.appupdateservergenerator.apkparsing.ParsingPackageUtils
import org.grapheneos.appupdateservergenerator.apkparsing.XmlUtils
import org.grapheneos.appupdateservergenerator.apkparsing.resolveReference
import org.grapheneos.appupdateservergenerator.apkparsing.resolveString
import org.grapheneos.appupdateservergenerator.model.AndroidApk.Companion.buildFromApkAndVerifySignature
import org.grapheneos.appupdateservergenerator.model.AndroidApk.StaticLibrary
import org.grapheneos.appupdateservergenerator.model.AndroidApk.UsesLibrary
import org.grapheneos.appupdateservergenerator.model.AndroidApk.UsesNativeLibrary
import org.grapheneos.appupdateservergenerator.model.AndroidApk.UsesPackage
import org.grapheneos.appupdateservergenerator.model.AndroidApk.UsesStaticLibrary
import org.grapheneos.appupdateservergenerator.util.digest
import org.grapheneos.appupdateservergenerator.util.implies
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.zip.ZipFile

/**
 * Encapsulates data from the [apkFile] that was taken from the [apkFile]'s manifest and signing
 * certificate info. Use [buildFromApkAndVerifySignature] to build an instance.
 */
data class AndroidApk private constructor(
    val apkFile: File,
    val label: String?,
    val resourceTableChunk: ResourceTableChunk,
    val packageName: PackageName,
    val versionCode: VersionCode,
    val versionName: String,
    val minSdkVersion: Int,
    /**
     * Whether the APK is marked as debuggable (`android:debuggable="true"` in its `AndroidManifest.xml`).
     *
     * It is dangerous to sign debuggable APKs with production/release keys because Android
     * platform loosens security checks for such APKs. For example, arbitrary unauthorized code
     * may be executed in the context of such an app by anybody with ADB shell access.
     *
     * Debuggable APKs should never be inserted into the app repo.
     *
     * @see ApkSigner.Builder.setDebuggableApkPermitted
     */
    val debuggable: Boolean,
    /**
     * Represents a `static-library` tag in the manifest. This is optional; it will be non-null iff the app's manifest
     * declares a valid `static-library` tag.
     *
     * @see StaticLibrary
     */
    val staticLibrary: StaticLibrary?,
    /**
     * `uses-library` specifies a shared library that this package requires to be linked against.
     * Specifying this flag tells the system to include this library's code in your class loader.
     *
     * This appears as a child tag of the AndroidManifest `application` tag.
     *
     * - [Source declaration](https://android.googlesource.com/platform/frameworks/base/+/cc0a3a9bef94bd2ad7061a17f0a3297be5d7f270/core/res/res/values/attrs_manifest.xml#2182)
     * - [Android Developers documentation on uses-library](https://developer.android.com/guide/topics/manifest/uses-library-element)
     *
     * @see UsesLibrary
     */
    val usesLibraries: List<UsesLibrary>,
    /**
     * @see UsesNativeLibrary
     */
    val usesNativeLibraries: List<UsesNativeLibrary>,
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
     * @see UsesStaticLibrary
     */
    val usesStaticLibraries: List<UsesStaticLibrary>,
    /**
     * Specifies some kind of dependency on another package. It does not have any impact
     * on the app's execution on the device, but provides information about dependencies
     * it has on other packages that need to be satisfied for it to run correctly. That is,
     * this is primarily for installers to know what other apps need to be installed along
     * with this one.
     *
     * @see UsesPackage
     */
    val usesPackages: List<UsesPackage>,
    val verifyResult: ApkVerifyResult,
) {
    val signingCertSha256Digests: Set<HexString> by lazy {
        verifyResult.result.signerCertificates
            .mapTo(mutableSetOf()) {
                it.encoded.digest("SHA-256").encodeToHexString()
            }
    }


    /**
     * The `static-library` tag declares that this apk is providing itself
     * as a static shared library for other applications to use. Any app can declare such
     * a library and there can be only one static shared library per package. These libraries
     * are updatable, multiple versions can be installed at the same time, and an app links
     * against a specific version simulating static linking while allowing code sharing.
     * Other apks can link to it with the `uses-static-library`)
     * tag.
     *
     * @see UsesStaticLibrary
     */
    @Serializable
    data class StaticLibrary(
        val name: String,
        val version: VersionCode
    ) {
        data class Builder(
            var name: String? = null,
            var version: Int? = null,
            var versionMajor: Int = 0,
        ) {
            fun buildIfPossible(): StaticLibrary? {
                return if (name != null && version != null) {
                    StaticLibrary(name!!, VersionCode.fromMajorMinor(versionMajor, version!!))
                } else {
                    null
                }
            }
        }
    }

    /**
     * `uses-libraries` specifies a shared library that thi spackage requires to be linked against.
     * Specifying this flag tells the Android system to include this library's code in the app's
     * class loader.
     *
     * This appears as a child tag of the AndroidManifest `application` tag.
     *
     * [Source declaration](https://android.googlesource.com/platform/frameworks/base/+/cc0a3a9bef94bd2ad7061a17f0a3297be5d7f270/core/res/res/values/attrs_manifest.xml#2182)
     */
    @Serializable
    data class UsesLibrary(
        /** Required name of the library you use. */
        val name: PackageName,
        /**
         * Boolean value that indicates whether the application requires the library specified by android:name:
         *
         *  - `true`: The application does not function without this library. The system will not allow the application
         *    on a device that does not have the library.
         *  - `false`: The application can use the library if present, but is designed to function without it if necessary.
         *    The system will allow the application to be installed, even if the library is not present. If you use
         *    `false`, you are responsible for checking at runtime that the library is available.
         *
         *    To check for a library, you can use reflection to determine if a particular class is available.
         *
         * The default is `true`.
         */
        val required: Boolean = true
    ) {
        data class Builder(
            var name: String? = null,
            var required: Boolean = true // default is true
        ) {
            fun buildIfPossible(): UsesLibrary? {
                return if (name != null) {
                    UsesLibrary(PackageName(name!!), required)
                } else {
                    null
                }
            }
        }
    }

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
     * This appears as a child tag of the AndroidManifest `application` tag.
     *
     * [Android Developers documentation](https://developer.android.com/guide/topics/manifest/uses-native-library-element)
     */
    @Serializable
    data class UsesNativeLibrary(
        /**
         * The name of the library file. See
         * [Adding additional native libraries](https://source.android.com/devices/tech/config/namespaces_libraries#adding-additional-native-libraries)
         * */
        val name: String,
        /**
         * Boolean value that indicates whether the application requires the library specified by `android:name`:
         *
         * - `true`: The application does not function without this library. The system will not allow the application
         * on a device that does not have the library.
         * - `false`: The application can use the library if present, but is designed to function without it if
         * necessary. The system will allow the application to be installed, even if the library is not present. If you
         * use "false", you are responsible for gracefully handling the absence of the library.
         *
         * The default is `true`.
         */
        val required: Boolean = true
    ) {
        data class Builder(
            var name: String? = null,
            var required: Boolean = true // defaults to true
        ) {
            fun buildIfPossible(): UsesNativeLibrary? {
                return if (name != null) {
                    UsesNativeLibrary(name!!, required)
                } else {
                    null
                }
            }
        }
    }

    interface CertDigestBuilder {
        var certDigests: MutableSet<HexString>?
        /**
         * @throws ApkFormatException
         */
        fun addCertDigest(digest: String) {
            // ":" delimiters are allowed in the SHA declaration as this is the format
            // emitted by the certtool making it easy for developers to copy/paste.
            // https://android.googlesource.com/platform/frameworks/base/+/1c0577193b6060ecea4d516a732db12d1b99e297/core/java/android/content/pm/parsing/ParsingPackageUtils.java#2133
            val certSha256Digest = try {
                // Note: fromHex handles lowercasing for us.
                HexString.fromHex(digest.replace(":", ""))
            } catch (e: IllegalArgumentException) {
                throw ApkFormatException("Bad uses-static-library declaration: bad certDigest $digest", e)
            }

            if (certDigests == null) {
                certDigests = mutableSetOf(certSha256Digest)
            } else {
                certDigests!!.add(certSha256Digest)
            }
        }
    }

    /**
     * The `uses-static-library` specifies a shared <strong>static</strong>
     * library that this package requires to be statically linked against. Specifying
     * this tag tells the system to include this library's code in your class loader.
     * Depending on a static shared library is equivalent to statically linking with
     * the library at build time while it offers apps to share code defined in such
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
     * @see StaticLibrary
     */
    @Serializable
    data class UsesStaticLibrary(
        /** Required name of the library you use. */
        val name: String,
        /** Specify which version of the shared library should be statically linked */
        val version: VersionCode,
        /** The SHA-256 digest(s) of the library signing certificate(s). */
        val certDigests: Set<HexString>
    ) {
        data class Builder(
            var name: String? = null,
            var version: VersionCode? = null,
            override var certDigests: MutableSet<HexString>? = null
        ) : CertDigestBuilder {
            fun buildIfPossible(): UsesStaticLibrary? {
                return if (name != null && version != null && certDigests != null) {
                    UsesStaticLibrary(name!!, version!!, certDigests!!)
                } else {
                    null
                }
            }
        }
    }

    /**
     * The `uses-package` specifies some kind of dependency on another
     * package. It does not have any impact on the app's execution on the device,
     * but provides information about dependencies it has on other packages that need
     * to be satisfied for it to run correctly. That is, this is primarily for
     * installers to know what other apps need to be installed along with this one.
     *
     * This appears as a child tag of the AndroidManifest `application` tag.
     *
     * [Source declaration](https://android.googlesource.com/platform/frameworks/base/+/cc0a3a9bef94bd2ad7061a17f0a3297be5d7f270/core/res/res/values/attrs_manifest.xml#2238)
     */
    @Serializable
    data class UsesPackage(
        /**
         * Required type of association with the package, for example "android.package.ad_service"
         * if it provides an advertising service.  This should use the standard scoped naming
         * convention as used for other things such as package names, based on the Java naming
         * convention.
         */
        val packageType: String,
        /** Required name of the package being used */
        val name: PackageName,
        /** Optional minimum version of the package that satisfies the dependency. */
        val minimumVersion: VersionCode?,
        /** Optional SHA-256 digest(s) of the package signing certificate(s). */
        val certDigests: Set<HexString>?
    ) {
        data class Builder(
            var packageType: String? = null,
            var name: PackageName? = null,
            var version: Int? = null,
            var versionMajor: Int? = null,
            override var certDigests: MutableSet<HexString>? = null
        ) : CertDigestBuilder {
            fun buildIfPossible(): UsesPackage? {
                return if (packageType != null && name != null) {
                    val versionCode = if (version != null) {
                        if (versionMajor != null) {
                            VersionCode.fromMajorMinor(versionMajor!!, version!!)
                        } else {
                            VersionCode(version!!.toLong())
                        }
                    } else {
                        null
                    }

                    UsesPackage(packageType!!, name!!, versionCode, certDigests)
                } else {
                    null
                }
            }
        }
    }

    class AppIcon(val pkg: PackageName, val bytes: ByteArray, val density: Density)

    private var _icon: AppIcon? = null

    /**
     * Attempts to get the application icon from the APK. This assumes the APK has an icon, and it will throw if it
     * cannot get the icon.
     *
     * @throws IOException if the icon cannot be retrieved or an I/O error occurs
     */
    @Synchronized
    fun getIcon(minimumDensity: Density): AppIcon {
        if (_icon != null && _icon?.density!! >= minimumDensity) return _icon!!

        ZipFile(apkFile).use { apkZipFile ->
            val manifestBytes = apkZipFile.getInputStream(apkZipFile.getEntry("AndroidManifest.xml")).use {
                it.readBytes()
            }
            val iconResId = try {
                getIconResIdInBinaryAndroidManifest(ByteBuffer.wrap(manifestBytes))
            } catch (e: ApkFormatException) {
                throw IOException("unable to find icon resource ID", e)
            } ?: throw IOException("unable to find icon resource ID")

            val icon: BinaryResAndConfig = resourceTableChunk.resolveReference(
                iconResId,
                config = BinaryResourceConfigBuilder
                    .createDummyConfig()
                    .copy(density = minimumDensity.approximateDpi)
                    .toBinaryResConfig(),
                // Specifically eliminate vector drawables. Maybe just filter out anydpi?
                extraConfigFilter = { value, _  ->
                    if (value.type() == BinaryResourceValue.Type.REFERENCE) {
                        // Let references pass through to be resolved again.
                        return@resolveReference true
                    }
                    if (value.type() != BinaryResourceValue.Type.STRING) return@resolveReference false
                    val path = stringPool.getString(value.data())
                    // Note: This also checks if the reference actually points to a file in the APK we can extract.
                    val entry = apkZipFile.getEntry(path) ?: return@resolveReference false
                    val iconBytes = apkZipFile.getInputStream(entry).use { it.readBytes() }
                    // Attempt to parse the icon as a binary XML. Alternatively, add a dependency on
                    // com.android.tools.apkparser:apkanalyzer if not already there, and then use
                    // BinaryXmlParser.decodeXml(null, iconBytes) to get the XML output. Maybe that can be used
                    // in the future to convert adaptive icons / vector drawables to SVGs
                    try {
                        BinaryResourceFile(iconBytes)
                    } catch (e: Throwable) {
                        // An error means this is likely not a vector drawable / adaptive icon.
                        return@resolveReference true
                    }
                    // No errors means it parsed correctly as a binary XML. All signs point towards this being a
                    // vector drawable / adaptive icon. We don't support those at this time.
                    return@resolveReference false
                }
            ) ?: throw IOException("unable to find icon resource ID")
            System.gc()

            if (icon.value.type() != BinaryResourceValue.Type.STRING) {
                throw IOException("icon resource ID isn't a string")
            }
            val iconPath = try {
                resourceTableChunk.stringPool.getString(icon.value.data())
            } catch (e: IndexOutOfBoundsException) {
                throw IOException("can't find path in string pool", e)
            }
            val iconEntry = apkZipFile.getEntry(iconPath) ?: throw IOException("unable to find icon path")

            val iconBytes = apkZipFile.getInputStream(iconEntry).use { it.readBytes() }
            return AppIcon(packageName, iconBytes, Density.fromDpi(icon.config.density()) ?: minimumDensity)
                .also {
                    // Cache the icon.
                    _icon = it
                }
        }
    }

    /**
     * Returns whether `this` [AndroidApk] satisfies the given [usesStaticLibrary] requirement.
     */
    fun isMatchingUsesStaticLibrary(usesStaticLibrary: UsesStaticLibrary): Boolean {
        if (staticLibrary == null) return false
        if (staticLibrary.name != usesStaticLibrary.name || staticLibrary.version != usesStaticLibrary.version) {
            return false
        }

        return signingCertSha256Digests == usesStaticLibrary.certDigests
    }

    data class Builder(
        val apkFile: File,
        var label: String? = null,
        var resourceTableChunk: ResourceTableChunk? = null,
        var packageName: PackageName? = null,
        var versionCode: VersionCode? = null,
        var versionName: String? = null,
        var minSdkVersion: Int? = null,
        var debuggable: Boolean? = null,
        var staticLibrary: StaticLibrary? = null,
        var usesLibraries: List<UsesLibrary>? = null,
        var usesNativeLibraries: List<UsesNativeLibrary>? = null,
        var usesStaticLibraries: List<UsesStaticLibrary>? = null,
        var usesPackages: List<UsesPackage>? = null,
        var verifyResult: ApkVerifyResult? = null,
    ) {
        private val isBuildable: Boolean
            get() = resourceTableChunk != null &&
                    packageName != null &&
                    versionCode != null &&
                    versionName != null &&
                    minSdkVersion != null &&
                    debuggable != null &&
                    usesLibraries != null &&
                    usesNativeLibraries != null &&
                    usesStaticLibraries != null &&
                    usesPackages != null &&
                    verifyResult != null

        fun buildIfAllPresent(): AndroidApk? {
            return if (isBuildable) {
                AndroidApk(
                    apkFile = apkFile,
                    label = label,
                    resourceTableChunk = resourceTableChunk!!,
                    packageName = packageName!!,
                    versionCode = versionCode!!,
                    versionName = versionName!!,
                    minSdkVersion = minSdkVersion!!,
                    debuggable = debuggable!!,
                    staticLibrary = staticLibrary /* optional */,
                    usesLibraries = usesLibraries!!,
                    usesNativeLibraries = usesNativeLibraries!!,
                    usesStaticLibraries = usesStaticLibraries!!,
                    usesPackages = usesPackages!!,
                    verifyResult = verifyResult!!
                )
            } else {
                null
            }
        }
    }

    companion object {
        /*
        Values from
        https://android.googlesource.com/platform/frameworks/base/+/7b02279e0ab3000f1d353992e9059db5d5b3cbfd/core/res/res/values/public.xml
        */

        /** Android resource ID of the `android:label` attribute in AndroidManifest.xml. */
        private const val LABEL_ATTR_ID = 0x01010001
        /** Android resource ID of the `android:name` attribute in AndroidManifest.xml. */
        private const val NAME_ATTR_ID = 0x01010003
        /** Android resource ID of the `android:version` attribute in AndroidManifest.xml. */
        private const val VERSION_ATTR_ID = 0x01010519
        /** Android resource ID of the `android:versionMajor` attribute in AndroidManifest.xml. */
        private const val VERSION_MAJOR_ATTR_ID = 0x01010577
        /** Android resource ID of the `android:certDigest` attribute in AndroidManifest.xml. */
        private const val CERT_DIGEST_ATTR_ID = 0x01010548
        /** Android resource ID of the `android:required` attribute in AndroidManifest.xml. */
        private const val REQUIRED_ATTR_ID = 0x0101028e
        /** Android resource ID of the `android:packageType` attribute in AndroidManifest.xml. */
        private const val PACKAGE_TYPE_ATTR_ID = 0x01010587
        /** Android resource ID of the `android:sharedUserId` attribute in AndroidManifest.xml. */
        private const val SHARED_USER_ID_ATTR_ID = 0x0101000b

        /** Android resource ID of the `android:versionName` attribute in AndroidManifest.xml. */
        private const val VERSION_NAME_ATTR = 0x0101021c
        /** Android resource ID of the `android:icon` attribute in AndroidManifest.xml. */
        private const val ICON_ATTR_ID = 0x01010002

        val ascendingVersionCodeComparator = compareBy<AndroidApk> { it.versionCode }
        val descendingVersionCodeComparator = compareByDescending<AndroidApk> { it.versionCode }

        /**
         * Builds an [AndroidApk] instance from the given [apkFile]. The input [apkFile] will be stored as the property
         * [AndroidApk.apkFile]. The `apksig` library will be used on the APK to verify its signature.
         *
         * Only APKs that pass signature verification will be created.
         *
         * @throws IOException if an I/O error occurs, or the APK can't be parsed or the APK failed to verify
         */
        fun buildFromApkAndVerifySignature(apkFile: File): AndroidApk {
            val builder = Builder(apkFile)

            val resourceTableChunk: ResourceTableChunk
            val androidManifestBytes: ByteBuffer
            ZipFile(apkFile).use { zipFile ->
                val androidManifestEntry = zipFile.getEntry("AndroidManifest.xml")
                    ?: throw IOException("unable to find AndroidManifest.xml for $apkFile")
                androidManifestBytes = zipFile.getInputStream(androidManifestEntry).use { ByteBuffer.wrap(it.readBytes()) }

                val resourceEntry = zipFile.getEntry("resources.arsc")
                    ?: throw IOException("unable to find resources.arsc for $apkFile")
                resourceTableChunk = zipFile.getInputStream(resourceEntry)
                    .use { BinaryResourceFile.fromInputStream(it) }
                    .chunks
                    .firstOrNull()
                    ?.let { it as? ResourceTableChunk }
                    ?: throw IOException("can't find ResourceTableChunk in resources.arsc for $apkFile")
            }
            builder.resourceTableChunk = resourceTableChunk

            androidManifestBytes.rewind()
            try {
                builder.versionName = getVersionNameFromBinaryAndroidManifest(androidManifestBytes, resourceTableChunk)
                androidManifestBytes.rewind()
                builder.label = getLabelInBinaryAndroidManifest(androidManifestBytes, resourceTableChunk)
                androidManifestBytes.rewind()

                // Use ApkUtils from the apksig library, since it handles edge cases such as missing elements
                // and resolving codenames for minSdkVersion.
                // This might be slower than just parsing the APK once, but it's more correct and results in less
                // code to maintain.
                builder.packageName = PackageName(ApkUtils.getPackageNameFromBinaryAndroidManifest(androidManifestBytes))
                androidManifestBytes.rewind()
                builder.versionCode = VersionCode(ApkUtils.getLongVersionCodeFromBinaryAndroidManifest(androidManifestBytes))
                androidManifestBytes.rewind()
                builder.minSdkVersion = ApkUtils.getMinSdkVersionFromBinaryAndroidManifest(androidManifestBytes)
                androidManifestBytes.rewind()

                // This can throw ApkFormatException if there is a reference to a resource for the debuggable attribute.
                // Comment from the apksig library:
                //
                // > References to resources are not supported on purpose. The
                // > reason is that the resolved value depends on the resource
                // > configuration (e.g, MNC/MCC, locale, screen density) used
                // > at resolution time. As a result, the same APK may appear as
                // > debuggable in one situation and as non-debuggable in another
                // > situation. Such APKs may put users at risk.
                builder.debuggable = ApkUtils.getDebuggableFromBinaryAndroidManifest(androidManifestBytes)
                androidManifestBytes.rewind()

                val dependencies = getDependenciesAndExtraInfoInBinaryAndroidManifest(androidManifestBytes)
                builder.usesStaticLibraries = dependencies.usesStaticLibs
                builder.usesLibraries = dependencies.libs
                builder.usesPackages = dependencies.packageDeps
                builder.usesNativeLibraries = dependencies.usesNativeLibs
                builder.staticLibrary = dependencies.staticLibrary
            } catch (e: ApkFormatException) {
                throw IOException("failed to parse APK details: ${e.message}", e)
            } catch (e: NullPointerException) {
                throw IOException("failed to parse APK details: ${e.message}", e)
            }

            val verifyResult = ApkVerifyResult.verifyApk(apkFile)
            if (!verifyResult.isVerified) {
                throw IOException("APK signature verification failed with errors: ${verifyResult.result.allErrors}")
            }
            builder.verifyResult = verifyResult

            return builder.buildIfAllPresent() ?: throw IOException("failed to build: $builder")
        }

        /**
         * Finds the element with the given [elementName] at the expected [depth] in the given AndroidManifest.xml from
         * the [binaryAndroidManifest], and then if the element is found, invokes the [elementHandler] with parser as
         * the receiver and returns its return value. Otherwise, null is returned. This function is meant to parse
         * only a single element inside of the manifest.
         *
         * If [mustHaveEmptyNamespace] is true, the element must have an empty namespace to check. Otherwise, it can
         * match if the namespace is empty is not empty.
         *
         * @throws AndroidBinXmlParser.XmlParserException
         */
        private inline fun <T> parseForElement(
            binaryAndroidManifest: ByteBuffer,
            elementName: String,
            depth: Int,
            mustHaveEmptyNamespace: Boolean,
            elementHandler: AndroidBinXmlParser.() -> T?
        ): T? {
            val parser = AndroidBinXmlParser(binaryAndroidManifest.rewind())
            var eventType = parser.eventType
            while (eventType != AndroidBinXmlParser.EVENT_END_DOCUMENT) {
                if (eventType == AndroidBinXmlParser.EVENT_START_ELEMENT) {
                    if (parser.depth == depth &&
                        parser.name == elementName &&
                        (mustHaveEmptyNamespace implies parser.namespace.isEmpty())
                    ) {
                        return parser.run(elementHandler)
                    }
                }
                eventType = parser.next()
            }
            // No element found
            return null
        }

        /**
         * Sets up the parser to parse for the inner elements with the target [elementNames]. The [elementHandler] will
         * receive the element name for the handler to determine which element is currently being parsed.
         *
         * This should only be called when at the start of an element and wanting to parse the inner elements.
         */
        private inline fun AndroidBinXmlParser.parseForInnerElements(
            vararg elementNames: String,
            targetDepth: Int,
            mustHaveEmptyNamespace: Boolean,
            elementHandler: AndroidBinXmlParser.(elementName: String) -> Unit
        ) {
            check(eventType == AndroidBinXmlParser.EVENT_START_ELEMENT) {
                "parseForInnerElements called when not at the start of an outer element."
            }

            val elementNamesToCheck = elementNames.toHashSet()
            val outerDepth = depth
            var eventType = next()
            while (
                eventType != AndroidBinXmlParser.EVENT_END_DOCUMENT &&
                (eventType != AndroidBinXmlParser.EVENT_END_ELEMENT || depth > outerDepth)
            ) {
                if (eventType == AndroidBinXmlParser.EVENT_END_ELEMENT) {
                    eventType = next()
                    continue
                }

                if (
                    eventType == AndroidBinXmlParser.EVENT_START_ELEMENT &&
                    name in elementNamesToCheck &&
                    depth == targetDepth &&
                    (mustHaveEmptyNamespace implies namespace.isEmpty())
                ) {
                    elementHandler(name)
                }
                eventType = next()
            }
        }

        /**
         * To be called when the parser is in an element with the [attributeIndex]. The [resourceTable] is used to
         * resolve resource references.
         *
         * @throws ApkFormatException
         * @throws IOException
         */
        private fun AndroidBinXmlParser.getOrResolveAttributeStringValue(
            attributeIndex: Int,
            resourceTable: ResourceTableChunk
        ): String? = when (val type = getAttributeValueType(attributeIndex)) {
            AndroidBinXmlParser.VALUE_TYPE_STRING -> {
                getAttributeStringValue(attributeIndex)
            }
            AndroidBinXmlParser.VALUE_TYPE_REFERENCE -> {
                resourceTable.resolveString(BinaryResourceIdentifier.create(getAttributeIntValue(attributeIndex)))
            }
            else -> throw ApkFormatException(
                "expected versionName attribute to be of string or reference, but got $type"
            )
        }

        /**
         * Gets the version name from the application element in AndroidManifest.xml. The [resourceTable] will be
         * used to resolve any resource references.
         *
         * @throws ApkFormatException
         * @throws IOException
         */
        private fun getVersionNameFromBinaryAndroidManifest(
            androidManifestContents: ByteBuffer,
            resourceTable: ResourceTableChunk
        ): String? = try {
            // IMPLEMENTATION NOTE: Version name is declared as the "versionName" attribute of the top-level
            // manifest element. This can be a reference or a string resource
            // (source: https://developer.android.com/studio/publish/versioning#appversioning)
            parseForElement(
                androidManifestContents,
                elementName = "manifest",
                depth = 1,
                mustHaveEmptyNamespace = true
            ) {
                for (i in 0 until attributeCount) {
                    if (getAttributeNameResourceId(i) == VERSION_NAME_ATTR) {
                        return getOrResolveAttributeStringValue(i, resourceTable)
                    }
                }
                return null
            }
        } catch (e: AndroidBinXmlParser.XmlParserException) {
            throw ApkFormatException(
                "Unable to determine APK version name: malformed binary resource: "
                        + ApkUtils.ANDROID_MANIFEST_ZIP_ENTRY_NAME,
                e
            )
        }

        /**
         * Gets a string from the application element in AndroidManifest.xml. The [stringTypeChunk] will be used to
         * resolve any resource references.
         *
         * @throws ApkFormatException
         * @throws IOException
         */
        private fun getLabelInBinaryAndroidManifest(
            androidManifestContents: ByteBuffer,
            resourceTable: ResourceTableChunk
        ): String? = try {
            parseForElement(
                androidManifestContents,
                elementName = "application",
                depth = 2,
                mustHaveEmptyNamespace = true
            ) {
                for (i in 0 until attributeCount) {
                    if (getAttributeNameResourceId(i) == LABEL_ATTR_ID) {
                         return getOrResolveAttributeStringValue(i, resourceTable)
                    }
                }
                return null
            }
        } catch (e: AndroidBinXmlParser.XmlParserException) {
            throw ApkFormatException(
                "Unable to get label from APK: malformed binary resource: " +
                        ApkUtils.ANDROID_MANIFEST_ZIP_ENTRY_NAME,
                e
            )
        }

        private data class DependenciesAndExtraInfo(
            val usesStaticLibs: List<UsesStaticLibrary>,
            val libs: List<UsesLibrary>,
            val packageDeps: List<UsesPackage>,
            val usesNativeLibs: List<UsesNativeLibrary>,
            val staticLibrary: StaticLibrary?
        )

        private fun getDependenciesAndExtraInfoInBinaryAndroidManifest(
            androidManifestContents: ByteBuffer
        ): DependenciesAndExtraInfo {
            try {
                var staticLibrary: StaticLibrary? = null
                val usesStaticLibs = mutableListOf<UsesStaticLibrary>()
                val usesLibs = mutableListOf<UsesLibrary>()
                val usesPackage = mutableListOf<UsesPackage>()
                val usesNativeLibraries = mutableListOf<UsesNativeLibrary>()

                parseForElement(
                    androidManifestContents,
                    elementName = "manifest",
                    depth = 1,
                    mustHaveEmptyNamespace = true
                ) {

                    // Parse the sharedUserId so that we can check it if there's a static library.
                    var sharedUserId: String? = null
                    for (i in 0 until attributeCount) {
                        if (getAttributeNameResourceId(i) == SHARED_USER_ID_ATTR_ID) {
                            val parsedSharedUserId =
                                if (getAttributeValueType(i) == AndroidBinXmlParser.VALUE_TYPE_STRING) {
                                    getAttributeStringValue(i).ifEmpty { null }
                                } else {
                                    null
                                }
                            if (parsedSharedUserId != null) {
                                // Note: skipping a check that the package name is not "android"
                                // https://android.googlesource.com/platform/frameworks/base/+/7b02279e0ab3000f1d353992e9059db5d5b3cbfd/core/java/android/content/pm/parsing/ParsingPackageUtils.java#863
                                val nameResult: Result<Unit?> = ParsingPackageUtils.validateName(
                                    name = parsedSharedUserId,
                                    requireSeparator = true,
                                    requireFilename = true
                                )
                                if (nameResult.isFailure) {
                                    throw ApkFormatException(
                                        "<manifest> specified bad sharedUserId name \"$parsedSharedUserId\": " +
                                                "${nameResult.exceptionOrNull()!!.message}"
                                    )
                                }
                                sharedUserId = parsedSharedUserId
                            }
                        }
                    }

                    parseForInnerElements(
                        "application",
                        targetDepth = 2,
                        mustHaveEmptyNamespace = true
                    ) {
                        parseForInnerElements(
                            "static-library",
                            "uses-static-library",
                            "uses-native-library",
                            "uses-library",
                            "uses-package",
                            targetDepth = 3,
                            mustHaveEmptyNamespace = true
                        ) { elementName ->
                            when (elementName) {
                                "static-library" -> {
                                    if (sharedUserId != null) {
                                        throw ApkFormatException("sharedUserId not allowed in static shared library")
                                    } else if (staticLibrary != null) {
                                        throw ApkFormatException("multiple static-shared libs for package")
                                    }

                                    val staticLibraryBuilder = StaticLibrary.Builder()

                                    for (i in 0 until attributeCount) {
                                        when (val attrId = getAttributeNameResourceId(i)) {
                                            NAME_ATTR_ID -> when (val type = getAttributeValueType(i)) {
                                                // Note: don't allow this value to be a reference to a resource
                                                // that may change.
                                                // https://android.googlesource.com/platform/frameworks/base/+/7b02279e0ab3000f1d353992e9059db5d5b3cbfd/core/java/android/content/pm/parsing/ParsingPackageUtils.java#2049
                                                AndroidBinXmlParser.VALUE_TYPE_STRING -> {
                                                    staticLibraryBuilder.name = getAttributeStringValue(i)
                                                }
                                                else -> {
                                                    throw ApkFormatException(
                                                        "Bad static-library declaration: package name isn't a string" +
                                                                "but is of type $type"
                                                    )
                                                }
                                            }
                                            VERSION_ATTR_ID, VERSION_MAJOR_ATTR_ID -> {
                                                val parsedVersion: Int =
                                                    if (getAttributeValueType(i) == AndroidBinXmlParser.VALUE_TYPE_STRING) {
                                                        XmlUtils.convertValueToInt(
                                                            getAttributeStringValue(i),
                                                            // Let version major default to 0
                                                            // https://android.googlesource.com/platform/frameworks/base/+/7b02279e0ab3000f1d353992e9059db5d5b3cbfd/core/java/android/content/pm/parsing/ParsingPackageUtils.java#2055
                                                            if (attrId == VERSION_MAJOR_ATTR_ID) 0 else -1
                                                        )
                                                    } else {
                                                        getAttributeIntValue(i)
                                                    }
                                                if (parsedVersion < 0) {
                                                    throw ApkFormatException(
                                                        "Bad static-library declaration version: $parsedVersion"
                                                    )
                                                }
                                                if (attrId == VERSION_ATTR_ID) {
                                                    staticLibraryBuilder.version = parsedVersion
                                                } else { // VERSION_MAJOR_ATTR_ID
                                                    staticLibraryBuilder.versionMajor = parsedVersion
                                                }
                                            }
                                        }
                                    }

                                    staticLibrary = staticLibraryBuilder.buildIfPossible()
                                        ?: throw ApkFormatException("Bad static-library declaration: $staticLibraryBuilder")
                                }
                                "uses-static-library" -> {
                                    val staticLibraryBuilder = UsesStaticLibrary.Builder()
                                    for (i in 0 until attributeCount) {
                                        when (getAttributeNameResourceId(i)) {
                                            NAME_ATTR_ID -> when (val type = getAttributeValueType(i)) {
                                                AndroidBinXmlParser.VALUE_TYPE_STRING -> {
                                                    val libraryName = getAttributeStringValue(i)

                                                    // Can depend only on one version of the same library
                                                    if (usesStaticLibs.find { it.name == libraryName } != null) {
                                                        throw ApkFormatException(
                                                            "Depending on multiple versions of static library $libraryName"
                                                        )
                                                    }

                                                    staticLibraryBuilder.name = libraryName
                                                }
                                                AndroidBinXmlParser.VALUE_TYPE_REFERENCE -> {
                                                    // Note: don't allow this value to be a reference to a resource that may change.
                                                    // https://android.googlesource.com/platform/frameworks/base/+/cc0a3a9bef94bd2ad7061a17f0a3297be5d7f270/core/java/android/content/pm/PackageParser.java#2678
                                                    throw ApkFormatException(
                                                        "Bad uses-static-library declaration name: " +
                                                                "references for package name are not allowed"
                                                    )
                                                }
                                                else -> {
                                                    throw ApkFormatException(
                                                        "Bad uses-static-library declaration: package name isn't a string" +
                                                                "but is of type $type"
                                                    )
                                                }
                                            }
                                            VERSION_ATTR_ID -> {
                                                val version: Int =
                                                    if (getAttributeValueType(i) == AndroidBinXmlParser.VALUE_TYPE_STRING) {
                                                        XmlUtils.convertValueToInt(getAttributeStringValue(i), -1)
                                                    } else {
                                                        getAttributeIntValue(i)
                                                    }
                                                // Since the app cannot run without a static lib - fail if malformed
                                                // https://android.googlesource.com/platform/frameworks/base/+/cc0a3a9bef94bd2ad7061a17f0a3297be5d7f270/core/java/android/content/pm/PackageParser.java#2688
                                                if (version < 0) {
                                                    throw ApkFormatException(
                                                        "Bad uses-static-library declaration version: $version"
                                                    )
                                                }
                                                staticLibraryBuilder.version = VersionCode.fromMinor(version)
                                            }
                                            CERT_DIGEST_ATTR_ID -> {
                                                val type = getAttributeValueType(i)
                                                if (type != AndroidBinXmlParser.VALUE_TYPE_STRING) {
                                                    throw ApkFormatException(
                                                        "Bad uses-static-library declaration: bad type for certDigest: " +
                                                                "expected string but is of type $type"
                                                    )
                                                }
                                                staticLibraryBuilder.addCertDigest(getAttributeStringValue(i))
                                            }
                                        }
                                    }

                                    parseForInnerElements(
                                        "additional-certificate",
                                        targetDepth = depth + 1,
                                        mustHaveEmptyNamespace = true
                                    ) {
                                        for (i in 0 until attributeCount) {
                                            if (getAttributeNameResourceId(i) == CERT_DIGEST_ATTR_ID) {
                                                val type = getAttributeValueType(i)
                                                if (type != AndroidBinXmlParser.VALUE_TYPE_STRING) {
                                                    throw ApkFormatException(
                                                        "Bad uses-static-library declaration: bad type for certDigest: " +
                                                                "expected string but is of type $type"
                                                    )
                                                }
                                                staticLibraryBuilder.addCertDigest(getAttributeStringValue(i))
                                            }
                                        }
                                    }

                                    val staticLib = staticLibraryBuilder.buildIfPossible() ?: throw ApkFormatException(
                                        "Bad uses-static-library declaration: missing attributes. $staticLibraryBuilder"
                                    )
                                    usesStaticLibs.add(staticLib)
                                }
                                "uses-native-library" -> {
                                    val nativeLibBuilder = UsesNativeLibrary.Builder()
                                    for (i in 0 until attributeCount) {
                                        when (getAttributeNameResourceId(i)) {
                                            NAME_ATTR_ID -> {
                                                when (val type = getAttributeValueType(i)) {
                                                    AndroidBinXmlParser.VALUE_TYPE_STRING -> {
                                                        nativeLibBuilder.name = getAttributeStringValue(i)
                                                    }
                                                    AndroidBinXmlParser.VALUE_TYPE_REFERENCE -> {
                                                        // Note: don't allow this value to be a reference to a resource that may change.
                                                        // https://android.googlesource.com/platform/frameworks/base/+/cc0a3a9bef94bd2ad7061a17f0a3297be5d7f270/core/java/android/content/pm/PackageParser.java#2678
                                                        throw ApkFormatException(
                                                            "Bad uses-native-library declaration name: " +
                                                                    "references for package name are not allowed"
                                                        )
                                                    }
                                                    else -> {
                                                        throw ApkFormatException(
                                                            "Bad uses-native-library declaration: package name isn't a string" +
                                                                    "but is of type $type"
                                                        )
                                                    }
                                                }
                                            }
                                            REQUIRED_ATTR_ID -> {
                                                when (val type = getAttributeValueType(i)) {
                                                    AndroidBinXmlParser.VALUE_TYPE_BOOLEAN,
                                                    AndroidBinXmlParser.VALUE_TYPE_STRING,
                                                    AndroidBinXmlParser.VALUE_TYPE_INT -> {
                                                        val value = getAttributeStringValue(i);
                                                        nativeLibBuilder.required = "true" == value ||
                                                                "TRUE" == value ||
                                                                "1" == value
                                                    }
                                                    else -> {
                                                        throw ApkFormatException(
                                                            "Bad uses-native-library declaration: " +
                                                                    "bad required attribute --- had type $type"
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    val nativeLib = nativeLibBuilder.buildIfPossible() ?: throw ApkFormatException(
                                        "Bad uses-native-library declaration: missing attributes. $nativeLibBuilder"
                                    )
                                    usesNativeLibraries.add(nativeLib)
                                }
                                "uses-library" -> {
                                    val libraryBuilder = UsesLibrary.Builder()
                                    for (i in 0 until attributeCount) {
                                        when (getAttributeNameResourceId(i)) {
                                            NAME_ATTR_ID -> {
                                                when (val type = getAttributeValueType(i)) {
                                                    AndroidBinXmlParser.VALUE_TYPE_STRING -> {
                                                        libraryBuilder.name = getAttributeStringValue(i)
                                                    }
                                                    AndroidBinXmlParser.VALUE_TYPE_REFERENCE -> {
                                                        // Note: don't allow this value to be a reference to a resource that may change.
                                                        // https://android.googlesource.com/platform/frameworks/base/+/cc0a3a9bef94bd2ad7061a17f0a3297be5d7f270/core/java/android/content/pm/PackageParser.java#2678
                                                        throw ApkFormatException(
                                                            "Bad uses-static-library declaration name: " +
                                                                    "references for package name are not allowed"
                                                        )
                                                    }
                                                    else -> {
                                                        throw ApkFormatException(
                                                            "Bad uses-static-library declaration: package name isn't a string" +
                                                                    "but is of type $type"
                                                        )
                                                    }
                                                }
                                            }
                                            REQUIRED_ATTR_ID -> {
                                                when (getAttributeValueType(i)) {
                                                    AndroidBinXmlParser.VALUE_TYPE_BOOLEAN,
                                                    AndroidBinXmlParser.VALUE_TYPE_STRING,
                                                    AndroidBinXmlParser.VALUE_TYPE_INT -> {
                                                        val value = getAttributeStringValue(i);
                                                        libraryBuilder.required = "true" == value ||
                                                                "TRUE" == value ||
                                                                "1" == value
                                                    }
                                                    else -> {
                                                        throw ApkFormatException(
                                                            "Bad uses-library declaration: bad required attribute"
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    val library = libraryBuilder.buildIfPossible() ?: throw ApkFormatException(
                                        "Bad uses-library declaration: missing attributes. $libraryBuilder"
                                    )
                                    usesLibs.add(library)
                                }
                                "uses-package" -> {
                                    val packageDependencyBuilder = UsesPackage.Builder()

                                    for (i in 0 until attributeCount) {
                                        when (val attrId = getAttributeNameResourceId(i)) {
                                            PACKAGE_TYPE_ATTR_ID -> {
                                                // References are not allowed.
                                                if (getAttributeValueType(i) != AndroidBinXmlParser.VALUE_TYPE_STRING) {
                                                    throw ApkFormatException(
                                                        "Bad uses-package declaration: packageType attribute isn't " +
                                                                "a String but is instead ${getAttributeValueType(i)}"
                                                    )
                                                }
                                                packageDependencyBuilder.packageType = getAttributeStringValue(i)
                                            }
                                            NAME_ATTR_ID -> when (val type = getAttributeValueType(i)) {
                                                AndroidBinXmlParser.VALUE_TYPE_STRING -> {
                                                    packageDependencyBuilder.name =
                                                        PackageName(getAttributeStringValue(i))
                                                }
                                                AndroidBinXmlParser.VALUE_TYPE_REFERENCE -> {
                                                    // Note: don't allow this value to be a reference to a resource that may change.
                                                    throw ApkFormatException(
                                                        "Bad uses-package declaration name: " +
                                                                "references for package name are not allowed"
                                                    )
                                                }
                                                else -> {
                                                    throw ApkFormatException(
                                                        "Bad uses-package declaration: package name isn't a string" +
                                                                "but is of type $type"
                                                    )
                                                }
                                            }
                                            VERSION_ATTR_ID, VERSION_MAJOR_ATTR_ID -> {
                                                val parsedVersion: Int =
                                                    if (getAttributeValueType(i) == AndroidBinXmlParser.VALUE_TYPE_STRING) {
                                                        XmlUtils.convertValueToInt(
                                                            getAttributeStringValue(i),
                                                            // Let version major be defaulted to 0.
                                                            if (attrId == VERSION_MAJOR_ATTR_ID) 0 else -1
                                                        )
                                                    } else {
                                                        getAttributeIntValue(i)
                                                    }
                                                // PackageParser doesn't enforce this, but we should anyway
                                                // https://android.googlesource.com/platform/frameworks/base/+/cc0a3a9bef94bd2ad7061a17f0a3297be5d7f270/core/java/android/content/pm/PackageParser.java#3874
                                                if (parsedVersion < 0) {
                                                    throw ApkFormatException("Bad uses-package declaration version: $parsedVersion")
                                                }
                                                if (attrId == VERSION_ATTR_ID) {
                                                    packageDependencyBuilder.version = parsedVersion
                                                } else { // VERSION_MAJOR_ATTR_ID
                                                    packageDependencyBuilder.versionMajor = parsedVersion
                                                }
                                            }
                                            CERT_DIGEST_ATTR_ID -> {
                                                val type = getAttributeValueType(i)
                                                if (type != AndroidBinXmlParser.VALUE_TYPE_STRING) {
                                                    throw ApkFormatException(
                                                        "Bad uses-package declaration: bad type for certDigest: " +
                                                                "expected string but is of type $type"
                                                    )
                                                }
                                                packageDependencyBuilder.addCertDigest(getAttributeStringValue(i))
                                            }
                                        }
                                    }

                                    parseForInnerElements(
                                        "additional-certificate",
                                        targetDepth = depth + 1,
                                        mustHaveEmptyNamespace = true
                                    ) {
                                        for (i in 0 until attributeCount) {
                                            if (getAttributeNameResourceId(i) == CERT_DIGEST_ATTR_ID) {
                                                val type = getAttributeValueType(i)
                                                if (type != AndroidBinXmlParser.VALUE_TYPE_STRING) {
                                                    throw ApkFormatException(
                                                        "Bad uses-package declaration: bad type for certDigest: " +
                                                                "expected string but is of type $type"
                                                    )
                                                }
                                                packageDependencyBuilder.addCertDigest(getAttributeStringValue(i))
                                            }
                                        }
                                    }
                                    val packageDep =
                                        packageDependencyBuilder.buildIfPossible() ?: throw ApkFormatException(
                                            "Bad uses-package declaration: $packageDependencyBuilder"
                                        )
                                    usesPackage.add(packageDep)
                                }
                            }
                        }
                    }

                }
                return DependenciesAndExtraInfo(
                    usesStaticLibs,
                    usesLibs,
                    usesPackage,
                    usesNativeLibraries,
                    staticLibrary
                )
            } catch (e: AndroidBinXmlParser.XmlParserException) {
                throw ApkFormatException(
                    "Unable to get label from APK: malformed binary resource: " +
                            ApkUtils.ANDROID_MANIFEST_ZIP_ENTRY_NAME,
                    e
                )
            }
        }

        /**
         * Gets the icon resource id from the application element in AndroidManifest.xml.
         *
         * @throws ApkFormatException
         * @throws IOException
         */
        private fun getIconResIdInBinaryAndroidManifest(androidManifestContents: ByteBuffer): BinaryResourceIdentifier? =
            try {
                parseForElement(
                    androidManifestContents,
                    elementName = "application",
                    depth = 2,
                    mustHaveEmptyNamespace = true
                ) {
                    for (i in 0 until attributeCount) {
                        if (getAttributeNameResourceId(i) == ICON_ATTR_ID) {
                            return when (val type = getAttributeValueType(i)) {
                                AndroidBinXmlParser.VALUE_TYPE_REFERENCE -> {
                                    BinaryResourceIdentifier.create(getAttributeIntValue(i))
                                }
                                else -> throw ApkFormatException("expected icon attribute to be a reference, but got $type")
                            }
                        }
                    }
                    return null
                }
            } catch (e: AndroidBinXmlParser.XmlParserException) {
                throw ApkFormatException(
                    "Unable to get label from APK: malformed binary resource: " +
                            ApkUtils.ANDROID_MANIFEST_ZIP_ENTRY_NAME,
                    e
                )
            }
    }
}