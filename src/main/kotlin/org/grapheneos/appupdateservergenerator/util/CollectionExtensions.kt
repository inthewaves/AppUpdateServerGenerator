package org.grapheneos.appupdateservergenerator.util

infix fun <T> Iterable<T>.symmetricDifference(other: Iterable<T>): Set<T> =
    (this union other) subtract (this intersect other)