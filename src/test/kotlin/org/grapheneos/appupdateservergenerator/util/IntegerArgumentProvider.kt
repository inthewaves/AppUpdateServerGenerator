package org.grapheneos.appupdateservergenerator.util

import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.ArgumentsProvider
import java.util.stream.Stream

internal class IntegerArgumentProvider : ArgumentsProvider {
    companion object {
        private const val MAX_ARGS_PER_PARTITION = 25
    }
    
    override fun provideArguments(context: ExtensionContext?): Stream<out Arguments> {
        val nonNegativeLower = Stream.iterate(Arguments.of(0)) {
            val previous = it.get()[0] as Int
            Arguments.of(previous + 1)
        }.limit(MAX_ARGS_PER_PARTITION.toLong())

        val positiveMiddle = Stream.iterate(Arguments.of(Integer.MAX_VALUE / 2 - MAX_ARGS_PER_PARTITION / 4)) {
            val previous = it.get()[0] as Int
            Arguments.of(previous + 1)
        }.limit(MAX_ARGS_PER_PARTITION.toLong())

        val positiveUpper = Stream.iterate(Arguments.of(Integer.MAX_VALUE)) {
            val previous = it.get()[0] as Int
            Arguments.of(previous - 1)
        }.limit(MAX_ARGS_PER_PARTITION.toLong())

        val nonNegativeValues = Stream.concat(Stream.concat(nonNegativeLower, positiveMiddle), positiveUpper)

        val negativeSmallest = Stream.iterate(Arguments.of(-1)) {
            val previous = it.get()[0] as Int
            Arguments.of(previous - 1)
        }.limit(MAX_ARGS_PER_PARTITION.toLong())

        val negativeMiddle = Stream.iterate(Arguments.of(Integer.MIN_VALUE / 2 + MAX_ARGS_PER_PARTITION / 4)) {
            val previous = it.get()[0] as Int
            Arguments.of(previous - 1)
        }.limit(MAX_ARGS_PER_PARTITION.toLong())

        val negativeLargest = Stream.iterate(Arguments.of(Integer.MIN_VALUE)) {
            val previous = it.get()[0] as Int
            Arguments.of(previous + 1)
        }.limit(MAX_ARGS_PER_PARTITION.toLong())

        val negativeValues = Stream.concat(Stream.concat(negativeSmallest, negativeMiddle), negativeLargest)

        return Stream.concat(nonNegativeValues, negativeValues)
    }

}