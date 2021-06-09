package org.grapheneos.appupdateservergenerator.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class VersionCodeTest {
    @Test
    fun testVersionCodeComposition() {
        val middleValue = Int.MAX_VALUE / 2
        val firstInterval = 1..100
        val middleInterval = (middleValue - 50)..(middleValue + 50)
        val endInterval = (Int.MAX_VALUE - 100)..(Int.MAX_VALUE)

        val testInterval = firstInterval union middleInterval union endInterval
        for (versionMinor in testInterval) {
            for (versionMajor in testInterval) {
                val versionCode = VersionCode.fromMajorMinor(versionMajor, versionMinor)
                assertEquals(versionMajor, versionCode.versionCodeMajor)
                assertEquals(versionMinor, versionCode.versionCodeMinor)
            }
        }
    }
}