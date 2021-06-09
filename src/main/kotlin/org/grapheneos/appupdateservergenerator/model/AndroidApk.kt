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
import org.grapheneos.appupdateservergenerator.apkparsing.BinaryResourceConfigBuilder
import org.grapheneos.appupdateservergenerator.apkparsing.XmlUtils
import org.grapheneos.appupdateservergenerator.apkparsing.resolveReference
import org.grapheneos.appupdateservergenerator.apkparsing.resolveString
import org.grapheneos.appupdateservergenerator.model.AndroidApk.Companion.buildFromApkAndVerifySignature
import org.grapheneos.appupdateservergenerator.model.AndroidApk.Library
import org.grapheneos.appupdateservergenerator.model.AndroidApk.PackageDependency
import org.grapheneos.appupdateservergenerator.model.AndroidApk.StaticLibrary
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
    val label: String,
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
     * `uses-library` specifies a shared library that this package requires to be linked against.
     * Specifying this flag tells the system to include this library's code in your class loader.
     *
     * This appears as a child tag of the AndroidManifest `application` tag.
     *
     * - [Source declaration](https://android.googlesource.com/platform/frameworks/base/+/cc0a3a9bef94bd2ad7061a17f0a3297be5d7f270/core/res/res/values/attrs_manifest.xml#2182)
     * - [Android Developers documentation on uses-library](https://developer.android.com/guide/topics/manifest/uses-library-element)
     *
     * @see Library
     */
    val usesLibraries: List<Library>,
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
     * @see StaticLibrary
     */
    val usesStaticLibraries: List<StaticLibrary>,
    /**
     * Specifies some kind of dependency on another package. It does not have any impact
     * on the app's execution on the device, but provides information about dependencies
     * it has on other packages that need to be satisfied for it to run correctly. That is,
     * this is primarily for installers to know what other apps need to be installed along
     * with this one.
     *
     * @see PackageDependency
     */
    val usesPackages: List<PackageDependency>,
    val verifyResult: ApkVerifyResult,
) {

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
    data class Library(
        /** Required name of the library you use. */
        val name: PackageName,
        /**
         * Specify whether this library is required for the application.
         * The default is true, meaning the application requires the
         * library, and does not want to be installed on devices that
         * don't support it.  If you set this to false, then this will
         * allow the application to be installed even if the library
         * doesn't exist, and you will need to check for its presence
         * dynamically at runtime.
         */
        val required: Boolean
    ) {
        data class Builder(
            var name: String? = null,
            var required: Boolean? = null
        ) {
            fun buildIfPossible(): Library? {
                return if (name != null && required != null) {
                    Library(PackageName(name!!), required!!)
                } else {
                    null
                }
            }
        }
    }

    interface CertDigestBuilder {
        var certDigests: MutableList<HexString>?
        /**
         * @throws ApkFormatException
         */
        fun addCertDigest(digest: String) {
            // ":" delimiters are allowed in the SHA declaration as this is the format
            // emitted by the certtool making it easy for developers to copy/paste.
            // https://android.googlesource.com/platform/frameworks/base/+/1c0577193b6060ecea4d516a732db12d1b99e297/core/java/android/content/pm/parsing/ParsingPackageUtils.java#2133
            val certSha256Digest = try {
                // Note: This handles lowercasing for us.
                HexString.fromHex(digest.replace(":", ""))
            } catch (e: IllegalArgumentException) {
                throw ApkFormatException("Bad uses-static-library declaration: bad certDigest $digest", e)
            }

            if (certDigests == null) {
                certDigests = mutableListOf(certSha256Digest)
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
     */
    @Serializable
    data class StaticLibrary(
        /** Required name of the library you use. */
        val name: PackageName,
        /** Specify which version of the shared library should be statically linked */
        val version: VersionCode,
        /** The SHA-256 digest(s) of the library signing certificate(s). */
        val certDigests: List<HexString>
    ) {
        data class Builder(
            var name: String? = null,
            var version: VersionCode? = null,
            override var certDigests: MutableList<HexString>? = null
        ) : CertDigestBuilder {
            fun buildIfPossible(): StaticLibrary? {
                return if (name != null && version != null && certDigests != null) {
                    StaticLibrary(PackageName(name!!), version!!, certDigests!!)
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
    data class PackageDependency(
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
        val certDigests: List<HexString>?
    ) {
        data class Builder(
            var packageType: String? = null,
            var name: PackageName? = null,
            var version: Int? = null,
            var versionMajor: Int? = null,
            override var certDigests: MutableList<HexString>? = null
        ) : CertDigestBuilder {
            fun buildIfPossible(): PackageDependency? {
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

                    PackageDependency(packageType!!, name!!, versionCode, certDigests)
                } else {
                    null
                }
            }
        }
    }

    fun getIcon(minimumDensity: Density): ByteArray {
        ZipFile(apkFile).use { apkZipFile ->
            val manifestBytes = apkZipFile.getInputStream(apkZipFile.getEntry("AndroidManifest.xml")).use {
                it.readBytes()
            }
            val iconResId = try {
                getIconResIdInBinaryAndroidManifest(ByteBuffer.wrap(manifestBytes))
            } catch (e: ApkFormatException) {
                throw IOException("unable to find icon resource ID", e)
            } ?: throw IOException("unable to find icon resource ID")

            val icon: BinaryResourceValue = resourceTableChunk.resolveReference(
                iconResId,
                config = BinaryResourceConfigBuilder
                    .createDummyConfig()
                    .copy(density = minimumDensity.approximateDpi)
                    .toBinaryResConfig(),
                // Specifically eliminate vector drawables. Maybe just filter out anydpi?
                extraConfigFilter = { _, value ->
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

            if (icon.type() != BinaryResourceValue.Type.STRING) {
                throw IOException("icon resource ID isn't a string")
            }
            val iconPath = try {
                resourceTableChunk.stringPool.getString(icon.data())
            } catch (e: IndexOutOfBoundsException) {
                throw IOException("can't find path in string pool", e)
            }
            val iconEntry = apkZipFile.getEntry(iconPath) ?: throw IOException("unable to find icon path")

            return apkZipFile.getInputStream(iconEntry).use { it.readBytes() }
        }
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
        var usesLibraries: List<Library>? = null,
        var usesStaticLibraries: List<StaticLibrary>? = null,
        var usesPackages: List<PackageDependency>? = null,
        var verifyResult: ApkVerifyResult? = null,
    ) {
        private val isBuildable: Boolean
            get() = label != null &&
                    resourceTableChunk != null &&
                    packageName != null &&
                    usesLibraries != null &&
                    usesStaticLibraries != null &&
                    versionCode != null &&
                    versionName != null &&
                    minSdkVersion != null &&
                    debuggable != null &&
                    usesPackages != null &&
                    verifyResult != null

        fun buildIfAllPresent(): AndroidApk? {
            return if (isBuildable) {
                AndroidApk(
                    apkFile = apkFile,
                    label = label!!,
                    resourceTableChunk = resourceTableChunk!!,
                    packageName = packageName!!,
                    versionCode = versionCode!!,
                    versionName = versionName!!,
                    minSdkVersion = minSdkVersion!!,
                    debuggable = debuggable!!,
                    usesLibraries = usesLibraries!!,
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

                // Use ApkUtils from the apksig library, since it handles edge cases such as missing elements.
                val packageName = PackageName(ApkUtils.getPackageNameFromBinaryAndroidManifest(androidManifestBytes))
                builder.packageName = packageName
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

                val (staticLibraries, libraries, packageDependencies) =
                    getLibrariesAndDependenciesInBinaryAndroidManifest(androidManifestBytes)
                builder.usesStaticLibraries = staticLibraries
                builder.usesLibraries = libraries
                builder.usesPackages = packageDependencies
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
                    name in elementNames &&
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

        private fun getLibrariesAndDependenciesInBinaryAndroidManifest(
            androidManifestContents: ByteBuffer,
        ): Triple<List<StaticLibrary>, List<Library>, List<PackageDependency>> {
            try {
                val staticLibraries = mutableListOf<StaticLibrary>()
                val libraries = mutableListOf<Library>()
                val packageDependencies = mutableListOf<PackageDependency>()

                parseForElement(
                    androidManifestContents,
                    elementName = "application",
                    depth = 2,
                    mustHaveEmptyNamespace = true
                ) {
                    parseForInnerElements(
                        "uses-static-library",
                        "uses-library",
                        "uses-package",
                        targetDepth = 3,
                        mustHaveEmptyNamespace = true
                    ) { elementName ->
                        when (elementName) {
                            "uses-static-library" -> {
                                val staticLibraryBuilder = StaticLibrary.Builder()
                                for (i in 0 until attributeCount) {
                                    when (getAttributeNameResourceId(i)) {
                                        NAME_ATTR_ID -> when (val type = getAttributeValueType(i)) {
                                            AndroidBinXmlParser.VALUE_TYPE_STRING -> {
                                                val libraryName = getAttributeStringValue(i)

                                                // Can depend only on one version of the same library
                                                if (staticLibraries.find { it.name.pkg == libraryName } != null) {
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
                                            val version = when (val type = getAttributeValueType(i)) {
                                                AndroidBinXmlParser.VALUE_TYPE_INT -> getAttributeIntValue(i)
                                                AndroidBinXmlParser.VALUE_TYPE_STRING ->
                                                    XmlUtils.convertValueToInt(getAttributeStringValue(i), -1)
                                                else -> {
                                                    throw ApkFormatException(
                                                        "Bad uses-static-library declaration: version attribute isn't " +
                                                                "numeric but is of type $type"
                                                    )
                                                }
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
                                staticLibraries.add(staticLib)
                            }
                            "uses-library" -> {
                                val libraryBuilder = Library.Builder()
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
                                            when (val type = getAttributeValueType(i)) {
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
                                libraries.add(library)
                            }
                            "uses-package" -> {
                                val packageDependencyBuilder = PackageDependency.Builder()

                                for (i in 0 until attributeCount) {
                                    when (val attrId = getAttributeNameResourceId(i)) {
                                        PACKAGE_TYPE_ATTR_ID -> {
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
                                        VERSION_ATTR_ID, VERSION_MAJOR_ATTR_ID ->  {
                                            val parsedVersion: Int = when (val type = getAttributeValueType(i)) {
                                                AndroidBinXmlParser.VALUE_TYPE_INT -> getAttributeIntValue(i)
                                                AndroidBinXmlParser.VALUE_TYPE_STRING ->
                                                    XmlUtils.convertValueToInt(getAttributeStringValue(i), -1)
                                                else -> {
                                                    val attrName =
                                                        if (attrId == VERSION_ATTR_ID) "version" else "versionMajor"
                                                    throw ApkFormatException(
                                                        "Bad uses-package declaration: $attrName attribute " +
                                                                "isn't integer but is instead of type $type"
                                                    )
                                                }
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
                                val packageDep = packageDependencyBuilder.buildIfPossible() ?: throw ApkFormatException(
                                    "Bad uses-package declaration: $packageDependencyBuilder"
                                )
                                packageDependencies.add(packageDep)
                            }
                        }
                    }
                }

                return Triple(staticLibraries.toList(), libraries.toList(), packageDependencies.toList())
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