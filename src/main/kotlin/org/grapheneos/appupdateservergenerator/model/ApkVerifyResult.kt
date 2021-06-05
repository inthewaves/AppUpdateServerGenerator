package org.grapheneos.appupdateservergenerator.model

import com.android.apksig.ApkVerifier
import com.android.apksig.internal.util.AndroidSdkVersion
import org.grapheneos.appupdateservergenerator.model.ApkVerifyResult.V3AndBelow
import org.grapheneos.appupdateservergenerator.model.ApkVerifyResult.V4
import java.io.File
import java.io.IOException

/**
 * An interface meant to enforce type safety on APK signature verification results. Use [ApkVerifyResult.verifyApk] to
 * verify an APK and create an instance of this interface.
 *
 * There are two known inheritors: [V4], which represents an APK verification attempt with a APK Signing Scheme v4
 * signature (`.apk.idsig`), and [V3AndBelow], which represents all other APK Signing Schemes.
 */
sealed interface ApkVerifyResult {
    /** The APK file that was input during verification */
    val apkFile: File
    /** The verification result from [ApkVerifier], containing information such as certificates of the signers. */
    val result: ApkVerifier.Result
    /** Whether the APK's signatures are verified. */
    val isVerified: Boolean get() = result.isVerified

    /** Represents verifications where a APK Signature Scheme v4 signature is present. */
    interface V4 : ApkVerifyResult {
        /** The APK Signature Scheme v4 signature file used during verification */
        val v4SignatureFile: File
    }

    private class V4Impl(
        override val apkFile: File,
        override val result: ApkVerifier.Result,
        override val v4SignatureFile: File
    ) : V4 {
        init {
            require(v4SignatureFile.exists() && v4SignatureFile.isFile) {
                "v4 signature file $v4SignatureFile should be a file"
            }
            if (result.isVerified) {
                require(result.isVerifiedUsingV4Scheme)
            }
        }

        override fun toString(): String {
            return "V4(apkFile=$apkFile, " +
                    "isVerifiedUsingV4Scheme=${result.isVerifiedUsingV4Scheme}, " +
                    "v4SignatureFile=$v4SignatureFile)"
        }
    }

    /**
     * Represents verifications where APK Signature Scheme v3 and below signatures were present, and
     * a APK Signature Scheme v4 signature is not present.
     */
    interface V3AndBelow : ApkVerifyResult

    private class V3AndBelowImpl(override val apkFile: File, override val result: ApkVerifier.Result) : V3AndBelow {
        init {
            if (result.isVerified) {
                require(!result.isVerifiedUsingV4Scheme) {
                    "apk $apkFile is verified with v4 scheme, but instance is ${V3AndBelow::class.java.simpleName}"
                }
            }
        }

        override fun toString(): String {
            return "V3AndBelow(apkFile=$apkFile, " +
                    "isVerifiedUsingV1Scheme=${result.isVerifiedUsingV1Scheme}, " +
                    "isVerifiedUsingV2Scheme=${result.isVerifiedUsingV2Scheme}, " +
                    "isVerifiedUsingV3Scheme=${result.isVerifiedUsingV3Scheme}" +
                    ")"
        }
    }

    companion object {
        /**
         * Verifies the given [apkFile] against its signatures.
         *
         * @return an [ApkVerifyResult]. There are two known inheritors: [V4], which represents an APK verification
         * attempt with a APK Signing Scheme v4 signature (`.apk.idsig`), and [V3AndBelow], which represents all other
         * APK Signing Schemes.
         *
         * @see [V4]
         * @see [V3AndBelow]
         */
        fun verifyApk(apkFile: File): ApkVerifyResult {
            val apkV4Signature: File? = File(apkFile.parentFile, "${apkFile.name}.idsig")
                .takeIf { it.exists() && it.canRead() && it.isFile }

            val verifyResult = try {
                ApkVerifier.Builder(apkFile)
                    .apply {
                        setMinCheckedPlatformVersion(AndroidSdkVersion.R)
                        apkV4Signature?.let { setV4SignatureFile(it) }
                    }
                    .build()
                    .verify()
            } catch (e: IOException) {
                throw if (
                    apkV4Signature != null &&
                    e.stackTraceToString().contains("com.android.apksig.internal.apk.v4.")
                ) {
                    IOException("v4 signature $apkV4Signature might be invalid", e)
                } else {
                    e
                }
            }

            return if (apkV4Signature == null) {
                V3AndBelowImpl(apkFile, verifyResult)
            } else {
                V4Impl(apkFile, verifyResult, apkV4Signature)
            }
        }
    }
}