package org.grapheneos.appupdateservergenerator.model

import kotlinx.serialization.Serializable
import java.util.*

@Serializable
@JvmInline
value class Base64String(val s: String) {
    val bytes: ByteArray get() = Base64.getDecoder().decode(s)

    companion object {
        /**
         * Encodes the given bytes into a [Base64String].
         */
        fun fromBytes(bytes: ByteArray) = Base64String(Base64.getEncoder().encodeToString(bytes))
    }
}

@Serializable
@JvmInline
value class HexString(val hex: String)
