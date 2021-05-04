package model

import kotlinx.serialization.Serializable
import java.util.Base64

@Serializable
@JvmInline
value class Base64String(val s: String) {
    val bytes: ByteArray get() = Base64.getUrlDecoder().decode(s)

    companion object {
        fun fromBytes(bytes: ByteArray) = Base64String(Base64.getUrlEncoder().encodeToString(bytes))
    }
}