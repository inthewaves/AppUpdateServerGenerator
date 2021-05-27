package org.grapheneos.appupdateservergenerator.util

sealed class Either<L, R> {
    class Left<L, R>(val value: L) : Either<L, R>()
    class Right<L, R>(val value: R) : Either<L, R>()
}

/**
 * Wraps `this` value as an [Either.Left] instance (for types [L] and [R])
 */
fun <L, R> L.asLeft() = Either.Left<L, R>(this)

/**
 * Wraps `this` value as an [Either.Right] instance (for types [L] and [R])
 */
fun <L, R> R.asRight() = Either.Right<L, R>(this)
