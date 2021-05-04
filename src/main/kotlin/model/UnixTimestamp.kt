package model

import kotlinx.serialization.Serializable
import java.util.concurrent.TimeUnit
import kotlin.time.milliseconds

@Serializable
@JvmInline
value class UnixTimestamp(val seconds: Long): Comparable<UnixTimestamp> {
    init {
        require(seconds >= 0L)
    }

    override fun compareTo(other: UnixTimestamp): Int = seconds.compareTo(other.seconds)

    companion object {
        fun now() = UnixTimestamp(TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()))
    }
}