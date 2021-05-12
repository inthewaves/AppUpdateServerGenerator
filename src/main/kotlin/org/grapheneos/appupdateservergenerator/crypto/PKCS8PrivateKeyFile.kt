package org.grapheneos.appupdateservergenerator.crypto

import java.io.File

sealed class PKCS8PrivateKeyFile(open val file: File) {
    data class RSA(override val file: File) : PKCS8PrivateKeyFile(file)
    data class EC(override val file: File) : PKCS8PrivateKeyFile(file)
    companion object {
        /**
         * Creates a [PKCS8PrivateKeyFile] from an algorithm identifier line. Algorithm identifiers are from the
         * X.509 standard.
         *
         * The only supported algorithms are "rsaEncryption" and "id-ecPublicKey"
         *
         * @throws IllegalArgumentException if an unsupported [algorithmIdentifierLine] is given.
         */
        fun fromAlgorithmLine(file: File, algorithmIdentifierLine: String) =
            when {
                algorithmIdentifierLine.startsWith("rsaEncryption") -> RSA(file)
                algorithmIdentifierLine.startsWith("id-ecPublicKey") -> EC(file)
                else -> throw IllegalArgumentException(
                    "algorithm $algorithmIdentifierLine is not supported --- only RSA and EC are supported"
                )
            }
    }
}