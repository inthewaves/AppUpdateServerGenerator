package org.grapheneos.appupdateservergenerator.model

import kotlinx.serialization.Serializable
import java.util.Base64

@Serializable
@JvmInline
value class Base64String(val s: String) {
    val bytes: ByteArray get() = Base64.getDecoder().decode(s)
    init {
        // make sure that this is actually a base64 string
        require(Base64.getDecoder().decode(s) != null)
    }

    companion object {
        /**
         * Encodes the given bytes into a [Base64String].
         */
        fun encodeFromBytes(bytes: ByteArray) = Base64String(Base64.getEncoder().encodeToString(bytes))
    }
}

fun ByteArray.toBase64String() = Base64String.encodeFromBytes(this)

@Serializable
@JvmInline
value class HexString(val hex: String) {
    init {
        require(hexRegex.matchEntire(hex) != null) { "input string doesn't match hex pattern ^[0-9a-f]*\$" }
    }

    companion object {
        private val hexRegex = Regex("^[0-9a-f]*$")
    }
}
