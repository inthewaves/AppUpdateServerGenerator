package org.grapheneos.appupdateservergenerator.util

import java.util.SortedSet

infix fun <T> Iterable<T>.symmetricDifference(other: Iterable<T>): Set<T> =
    (this union other) subtract (this intersect other)

val <T : Comparable<T>> SortedSet<T>.isUsingNaturalOrdering: Boolean get() = comparator() == null
