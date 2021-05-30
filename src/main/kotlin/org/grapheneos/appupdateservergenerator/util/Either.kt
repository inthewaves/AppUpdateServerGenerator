package org.grapheneos.appupdateservergenerator.util

sealed class Either<L, R> {
    class Left<L, R>(val value: L) : Either<L, R>()
    class Right<L, R>(val value: R) : Either<L, R>()
}

/**
 * Wraps `this` value as an [Either.Left] instance (for types [L] and [R])
 */
fun <L, R> L.asEitherLeft() = Either.Left<L, R>(this)

/**
 * Wraps `this` value as an [Either.Right] instance (for types [L] and [R])
 */
fun <L, R> R.asEitherRight() = Either.Right<L, R>(this)
