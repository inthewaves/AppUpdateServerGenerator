package org.grapheneos.appupdateservergenerator.util

/**
 * Logical implication. This is equivalent to `!this || other`.
 */
@Suppress("NOTHING_TO_INLINE")
inline infix fun Boolean.implies(other: Boolean) = !this || other