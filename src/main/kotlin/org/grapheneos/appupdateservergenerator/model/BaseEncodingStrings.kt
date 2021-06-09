package org.grapheneos.appupdateservergenerator.model

import kotlinx.serialization.Serializable
import org.bouncycastle.util.encoders.HexEncoder
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Locale

@Serializable
@JvmInline
value class Base64String private constructor(val s: String) {
    val bytes: ByteArray get() = Base64.getDecoder().decode(s)

    companion object {
        /**
         * Encodes the given bytes into a [Base64String].
         */
        fun encodeFromBytes(bytes: ByteArray) = Base64String(Base64.getEncoder().encodeToString(bytes))

        fun fromBase64(base64String: String): Base64String {
            require(Base64.getDecoder().decode(base64String) != null)
            return Base64String(base64String)
        }
    }
}

fun ByteArray.encodeToBase64String() = Base64String.encodeFromBytes(this)

@Serializable
@JvmInline
value class HexString private constructor(val hex: String) {
    init {
        require(hexRegex.matchEntire(hex) != null) { "input string $hex doesn't match hex pattern ^[0-9a-f]*\$" }
    }

    companion object {
        private val hexRegex = Regex("^[0-9a-f]*$")
        private val hexEncoder by lazy { HexEncoder() }

        fun fromHex(string: String) = HexString(string.lowercase(Locale.ENGLISH))

        fun encodeFromBytes(bytes: ByteArray): HexString {
            // Hex encodes each byte into two hex characters
            val bos = ByteArrayOutputStream(bytes.size * 2)
            hexEncoder.encode(bytes, 0, bytes.size, bos)
            return HexString(bos.toByteArray().toString(Charsets.UTF_8).lowercase(Locale.ENGLISH))
        }
    }
}

fun ByteArray.encodeToHexString() = HexString.encodeFromBytes(this)
