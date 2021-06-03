package org.grapheneos.appupdateservergenerator.model

import com.android.apksig.ApkVerifier
import com.android.apksig.internal.util.AndroidSdkVersion
import java.io.EOFException
import java.io.File
import java.io.IOException

sealed interface ApkVerifyResult  {
    val apkFile: File
    val result: ApkVerifier.Result
    val isVerified: Boolean get() = result.isVerified

    /** Represents verifications where a APK Signature Scheme v4 signature is present. */
    interface V4 : ApkVerifyResult {
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
            return "V4Impl(apkFile=$apkFile, " +
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
        fun verifyApk(apkFile: File): ApkVerifyResult {
            val apkV4Signature: File? = File(apkFile.parentFile, "${apkFile.name}.idsig")
                .takeIf { it.exists() && it.isFile }

            val verifyResult = try {
                ApkVerifier.Builder(apkFile)
                    .apply {
                        setMinCheckedPlatformVersion(AndroidSdkVersion.R)
                        apkV4Signature?.let { setV4SignatureFile(it) }
                    }
                    .build()
                    .verify()
            } catch (e: EOFException) {
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