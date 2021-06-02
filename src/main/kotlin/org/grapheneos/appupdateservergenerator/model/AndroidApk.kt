package org.grapheneos.appupdateservergenerator.model

import com.android.apksig.ApkVerifier
import com.android.apksig.apk.ApkUtils
import com.android.apksig.internal.util.AndroidSdkVersion
import org.grapheneos.appupdateservergenerator.apkparsing.AAPT2Invoker
import org.grapheneos.appupdateservergenerator.db.AppRelease
import org.grapheneos.appupdateservergenerator.util.digest
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.security.cert.X509Certificate
import java.util.zip.ZipFile

/**
 * Encapsulates data from the [apkFile] that was taken from the [apkFile]'s manifest and signing
 * certificate info.
 */
data class AndroidApk private constructor(
    val apkFile: File,
    val label: String,
    val packageName: PackageName,
    val versionCode: VersionCode,
    val versionName: String,
    val minSdkVersion: Int,
    val signatureVerifyResult: ApkVerifier.Result
) {
    fun toAppRelease(releaseTimestamp: UnixTimestamp, releaseNotes: String?) = AppRelease(
        packageName = packageName,
        versionName = versionName,
        versionCode = versionCode,
        minSdkVersion = minSdkVersion,
        releaseTimestamp = releaseTimestamp,
        sha256Checksum = apkFile.digest("SHA-256").toBase64String(),
        releaseNotes = releaseNotes
    )

    class Builder {
        var apkFile: File? = null
        var label: String? = null
        var packageName: PackageName? = null
        var versionCode: VersionCode? = null
        var versionName: String? = null
        var minSdkVersion: Int? = null
        var signatureVerifyResult: ApkVerifier.Result? = null

        private val isBuildable: Boolean
            get() = apkFile != null &&
                    label != null &&
                    packageName != null &&
                    versionCode != null &&
                    versionName != null &&
                    minSdkVersion != null &&
                    signatureVerifyResult != null

        fun buildIfAllPresent(): AndroidApk? {
            return if (isBuildable) {
                AndroidApk(
                    apkFile = apkFile!!,
                    label = label!!,
                    packageName = packageName!!,
                    versionCode = versionCode!!,
                    versionName = versionName!!,
                    minSdkVersion = minSdkVersion!!,
                    signatureVerifyResult = signatureVerifyResult!!
                )
            } else {
                null
            }
        }

        /** Generated by IDEA */
        override fun toString(): String {
            return "Builder(apkFile=$apkFile, label=$label, packageName=$packageName, versionCode=$versionCode, " +
                    "versionName=$versionName, minSdkVersion=$minSdkVersion, signatureVerifyResult=$signatureVerifyResult)"
        }

    }

    companion object {
        val ascendingVersionCodeComparator = compareBy<AndroidApk> { it.versionCode }
        val descendingVersionCodeComparator = compareByDescending<AndroidApk> { it.versionCode }

        /**
         * Builds an [AndroidApk] instance from the given [apkFile]. The input [apkFile] will be stored as the property
         * [AndroidApk.apkFile]. The `apksig` library will be used on the APK to verify the signature of the APK.
         *
         * @throws IOException if an I/O error occurs, or the APK can't be parsed by the [aaptInvoker], or the APK
         * failed to verify
         */
        fun buildFromApkFileAndVerifySig(
            apkFile: File,
            aaptInvoker: AAPT2Invoker
        ): AndroidApk {
            val builder = aaptInvoker.getPartialAndroidAppDetailsAsBuilder(apkFile)
            val androidManifestBytes: ByteBuffer = ZipFile(apkFile).use { zipFile ->
                zipFile.getInputStream(zipFile.getEntry("AndroidManifest.xml")).use { it.readBytes() }
            }.let { ByteBuffer.wrap(it) }
            androidManifestBytes.rewind()
            builder.versionCode = VersionCode(ApkUtils.getLongVersionCodeFromBinaryAndroidManifest(androidManifestBytes))
            androidManifestBytes.rewind()
            builder.minSdkVersion = ApkUtils.getMinSdkVersionFromBinaryAndroidManifest(androidManifestBytes)

            val verifyResult = ApkVerifier.Builder(apkFile)
                .setMinCheckedPlatformVersion(AndroidSdkVersion.R)
                .build()
                .verify()
            if (!verifyResult.isVerified) {
                throw IOException("APK signature verification failed with errors: ${verifyResult.allErrors}")
            }
            builder.signatureVerifyResult = verifyResult

            return builder.buildIfAllPresent() ?: throw IOException("failed to build: $builder")
        }
    }
}
