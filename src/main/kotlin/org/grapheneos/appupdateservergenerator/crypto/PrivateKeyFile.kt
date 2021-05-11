package org.grapheneos.appupdateservergenerator.crypto

import java.io.File

sealed class PrivateKeyFile(val file: File) {
    class RSA(file: File) : PrivateKeyFile(file)
    class EC(file: File) : PrivateKeyFile(file)
    companion object {
        @Throws(IllegalArgumentException::class)
        fun fromAlgorithmLine(file: File, algorithmLine: String) =
            when {
                algorithmLine.startsWith("rsaEncryption") -> RSA(file)
                algorithmLine.startsWith("id-ecPublicKey") -> EC(file)
                else -> throw IllegalArgumentException(
                    "algorithm $algorithmLine is not supported --- only RSA and EC are supported"
                )
            }
    }
}