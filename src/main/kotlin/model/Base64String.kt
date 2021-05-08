package model

import kotlinx.serialization.Serializable
import java.util.Base64

@Serializable
@JvmInline
value class Base64String(val s: String) {
    val bytes: ByteArray get() = Base64.getDecoder().decode(s)

    companion object {
        fun fromBytes(bytes: ByteArray) = Base64String(Base64.getEncoder().encodeToString(bytes))
    }
}