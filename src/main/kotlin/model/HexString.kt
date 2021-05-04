package model

import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class HexString(val s: String)