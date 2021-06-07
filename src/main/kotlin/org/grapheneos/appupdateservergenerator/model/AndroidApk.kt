package org.grapheneos.appupdateservergenerator.model

import com.android.apksig.ApkSigner
import com.android.apksig.apk.ApkFormatException
import com.android.apksig.apk.ApkUtils
import com.android.apksig.internal.apk.AndroidBinXmlParser
import com.google.devrel.gmscore.tools.apk.arsc.BinaryResourceFile
import com.google.devrel.gmscore.tools.apk.arsc.BinaryResourceIdentifier
import com.google.devrel.gmscore.tools.apk.arsc.BinaryResourceValue
import com.google.devrel.gmscore.tools.apk.arsc.ResourceTableChunk
import org.grapheneos.appupdateservergenerator.apkparsing.resolveReference
import org.grapheneos.appupdateservergenerator.apkparsing.resolveString
import org.grapheneos.appupdateservergenerator.util.implies
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.zip.ZipFile

/**
 * Encapsulates data from the [apkFile] that was taken from the [apkFile]'s manifest and signing
 * certificate info.
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
     * @see ApkSigner.Builder.setDebuggableApkPermitted
     */
    val debuggable: Boolean,
    val verifyResult: ApkVerifyResult
) {
    fun getIcon(minimumDensity: Density): ByteArray {
        ZipFile(apkFile).use { apkZipFile ->
            val manifestBytes = apkZipFile.getInputStream(apkZipFile.getEntry("AndroidManifest.xml")).use {
                it.readBytes()
            }

            val iconResId = getIconResIdInBinaryAndroidManifest(ByteBuffer.wrap(manifestBytes))
                ?: throw IOException("unable to find icon resource ID")

            val icon: BinaryResourceValue = resourceTableChunk.resolveReference(
                resId = iconResId,
                // We manage minimum density below, so we use a null configPredicate here.
                configPredicate = null,
                sequenceTransformer = { sequence ->
                    val list = sequence.filter { it.chunkEntry.value() != null }.toList()
                    // println("$packageName $versionCode densities found: ${list.map { "${it.second.density()} with type ${it.first.value().type()}" }}")

                    // First, if this is a reference, find the 0 DPI version.
                    // Else, try to find an icon is just over the minimum density.
                    // Else, try to take the greatest possible icon.
                    val entry =
                        if (list.firstOrNull()?.chunkEntry?.value()?.type() == BinaryResourceValue.Type.REFERENCE) {
                            list.find { it.config.density() == 0 }
                        } else {
                            null
                        } ?: list.asSequence()
                            .filter { it.config.density() >= minimumDensity.approximateDpi }
                            .minByOrNull { it.config.density() }
                        ?: list.maxByOrNull { it.config.density() }

                    return@resolveReference entry?.chunkEntry?.value()
                }
            ) ?: throw IOException("unable to resolve resource ID for ${packageName.pkg}, $versionCode")
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

    class Builder constructor(val apkFile: File) {
        var label: String? = null
        var resourceTableChunk: ResourceTableChunk? = null
        var packageName: PackageName? = null
        var versionCode: VersionCode? = null
        var versionName: String? = null
        var minSdkVersion: Int? = null
        var debuggable: Boolean? = null
        var verifyResult: ApkVerifyResult? = null

        private val isBuildable: Boolean
            get() = label != null &&
                    resourceTableChunk != null &&
                    packageName != null &&
                    versionCode != null &&
                    versionName != null &&
                    minSdkVersion != null &&
                    debuggable != null &&
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
                    verifyResult = verifyResult!!
                )
            } else {
                null
            }
        }

        override fun toString(): String {
            return "Builder(apkFile=$apkFile, label=$label, resourceTableChunk=$resourceTableChunk, " +
                    "packageName=$packageName, versionCode=$versionCode, versionName=$versionName, " +
                    "minSdkVersion=$minSdkVersion, debuggable=$debuggable, verifyResult=$verifyResult)"
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Builder

            if (apkFile != other.apkFile) return false
            if (label != other.label) return false
            if (resourceTableChunk != other.resourceTableChunk) return false
            if (packageName != other.packageName) return false
            if (versionCode != other.versionCode) return false
            if (versionName != other.versionName) return false
            if (minSdkVersion != other.minSdkVersion) return false
            if (debuggable != other.debuggable) return false
            if (verifyResult != other.verifyResult) return false

            return true
        }

        override fun hashCode(): Int {
            var result = apkFile.hashCode()
            result = 31 * result + (label?.hashCode() ?: 0)
            result = 31 * result + (resourceTableChunk?.hashCode() ?: 0)
            result = 31 * result + (packageName?.hashCode() ?: 0)
            result = 31 * result + (versionCode?.hashCode() ?: 0)
            result = 31 * result + (versionName?.hashCode() ?: 0)
            result = 31 * result + (minSdkVersion ?: 0)
            result = 31 * result + (debuggable?.hashCode() ?: 0)
            result = 31 * result + (verifyResult?.hashCode() ?: 0)
            return result
        }
    }


    companion object {
        /** Android resource ID of the `android:label` attribute in AndroidManifest.xml. */
        private const val LABEL_ATTR_ID = 0x01010001
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
         * the receiver. Otherwise, null is returned.
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
            binaryAndroidManifest.rewind()
            val parser = AndroidBinXmlParser(binaryAndroidManifest)
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
         * To be called when the parser is in an element with the [attributeIndex]. The [stringTypeChunk] is used to
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
         * Gets the version name from the application element in AndroidManifest.xml. The [stringTypeChunk] will be
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

        /**
         * Gets a string from the application element in AndroidManifest.xml. The [stringTypeChunk] will be used to
         * resolve any resource references.
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