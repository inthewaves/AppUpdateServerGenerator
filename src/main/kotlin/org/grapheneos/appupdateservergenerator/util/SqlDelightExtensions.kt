package org.grapheneos.appupdateservergenerator.util

import com.squareup.sqldelight.Query
import com.squareup.sqldelight.db.use

/**
 * Executes the query as a sequence and returns the result of the [sequenceHandler].
 *
 * The [sequenceHandler] uses the database cursor, and then the cursor is closed when the handler is finished.
 * Since the cursor is closed after the handler is finished, the [Sequence] in the [sequenceHandler] should NOT be used
 * outside of the handler.
 *
 * The returned sequence is constrained to be iterated only once.
 */
inline fun <T : Any, R> Query<T>.executeAsSequence(sequenceHandler: (sequence: Sequence<T>) -> R): R {
    execute().use { sqlCursor ->
        val sequence = object : Sequence<T> {
            override fun iterator(): Iterator<T> = object : Iterator<T> {
                var cachedHasNext: Boolean = sqlCursor.next()

                override fun next(): T {
                    if (!cachedHasNext) throw NoSuchElementException()
                    val result: T = mapper(sqlCursor)
                    cachedHasNext = sqlCursor.next()
                    return result
                }

                override fun hasNext(): Boolean {
                    return cachedHasNext
                }
            }
        }.constrainOnce()

        return sequenceHandler(sequence)
    }
}
