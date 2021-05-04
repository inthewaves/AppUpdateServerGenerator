package model

import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class VersionCode(val code: Int): Comparable<VersionCode> {
    init {
        require(code >= 0)
    }

    override fun compareTo(other: VersionCode): Int = code.compareTo(other.code)
}
