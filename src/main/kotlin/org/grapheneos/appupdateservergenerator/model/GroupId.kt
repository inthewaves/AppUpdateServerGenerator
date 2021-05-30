package org.grapheneos.appupdateservergenerator.model

import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class GroupId private constructor(val id: String) : Comparable<GroupId> {
    companion object {
        fun of(id: String) = GroupId(id.trim().lowercase())
    }

    override fun toString(): String = id
    override fun compareTo(other: GroupId): Int = id.compareTo(other.id)
}
