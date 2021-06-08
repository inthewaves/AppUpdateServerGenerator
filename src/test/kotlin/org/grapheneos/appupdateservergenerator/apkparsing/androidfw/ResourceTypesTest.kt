package org.grapheneos.appupdateservergenerator.apkparsing.androidfw

import com.google.devrel.gmscore.tools.apk.arsc.BinaryResourceConfiguration
import org.grapheneos.appupdateservergenerator.apkparsing.BinaryResourceConfigBuilder
import org.grapheneos.appupdateservergenerator.model.Density
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

// https://cs.android.com/android/platform/superproject/+/master:frameworks/base/libs/androidfw/include/androidfw/ResourceTypes.h;drc=master;l=1111
private const val SCREENLONG_YES = 0x2 shl 4 /* SCREENLONG_YES = ACONFIGURATION_SCREENLONG_YES << SHIFT_SCREENLONG, */
private const val SCREENROUND_YES = 0x2

/**
 * Derived from tests in
 * [frameworks/base/libs/androidfw](https://android.googlesource.com/platform/frameworks/base/+/c6c226327debf1f3fcbd71e2bbee792118364ee5/libs/androidfw/tests/Config_test.cpp)
 */
internal class ResourceTypesTest {
    private fun selectBest(
        target: BinaryResourceConfiguration,
        configs: Collection<BinaryResourceConfiguration>
    ): BinaryResourceConfiguration {
        var bestConfig = BinaryResourceConfigBuilder().toBinaryResConfig()
        for (thisConfig in configs) {
            if (!thisConfig.match(target)) {
                continue
            }

            if (thisConfig.isBetterThan(bestConfig, target)) {
                bestConfig = thisConfig
            }
        }
        return bestConfig
    }

    private fun buildDensityConfig(density: Int) = BinaryResourceConfigBuilder(
        density = density,
        sdkVersion = 4
    ).toBinaryResConfig()

    @Test
    fun testSelectBestDensity() {
        val deviceConfig = BinaryResourceConfigBuilder(density = Density.XHIGH.approximateDpi, sdkVersion = 21)
            .toBinaryResConfig()

        val configs = mutableListOf<BinaryResourceConfiguration>()
        var expectedBest = buildDensityConfig(Density.HIGH.approximateDpi)
        configs.add(expectedBest)
        assertEquals(expectedBest, selectBest(deviceConfig, configs))

        expectedBest = buildDensityConfig(Density.XXHIGH.approximateDpi)
        configs.add(expectedBest)
        assertEquals(expectedBest, selectBest(deviceConfig, configs))

        expectedBest = buildDensityConfig(Density.XXHIGH.approximateDpi - 20)
        configs.add(expectedBest)
        assertEquals(expectedBest, selectBest(deviceConfig, configs))

        expectedBest = buildDensityConfig(Density.XHIGH.approximateDpi + 20)
        configs.add(expectedBest)
        assertEquals(expectedBest, selectBest(deviceConfig, configs))

        expectedBest = buildDensityConfig(Density.XHIGH.approximateDpi)
        configs.add(expectedBest)
        assertEquals(expectedBest, selectBest(deviceConfig, configs))

        expectedBest = buildDensityConfig(Density.XHIGH.approximateDpi)
        expectedBest.withSdkVersion(21)
        configs.add(expectedBest)
        assertEquals(expectedBest, selectBest(deviceConfig, configs))
    }

    @Test
    fun testSelectBestDensityWhenNoneSpecified() {
        val deviceConfig = BinaryResourceConfigBuilder(sdkVersion = 21)
            .toBinaryResConfig()

        val configs = mutableListOf<BinaryResourceConfiguration>(buildDensityConfig(Density.HIGH.approximateDpi))
        var expectedBest = buildDensityConfig(Density.MEDIUM.approximateDpi)
        configs.add(expectedBest)
        assertEquals(expectedBest, selectBest(deviceConfig, configs))

        expectedBest = buildDensityConfig(Density.ANY.approximateDpi)
        configs.add(expectedBest)
        assertEquals(expectedBest, selectBest(deviceConfig, configs))
    }

    @Test
    fun testShouldMatchRoundQualifier() {
        val deviceConfig = BinaryResourceConfigBuilder().toBinaryResConfig()
        val roundConfig = BinaryResourceConfigBuilder(screenLayout2 = 0x2 /*ACONFIGURATION_SCREENROUND_YES*/)
        assertFalse(roundConfig.toBinaryResConfig().match(deviceConfig))

        val roundDeviceConfig = BinaryResourceConfigBuilder(screenLayout2 = 0x2 /*ACONFIGURATION_SCREENROUND_YES*/)
            .toBinaryResConfig()
        assert(roundConfig.toBinaryResConfig().match(roundDeviceConfig))

        val notRoundDeviceConfig = BinaryResourceConfigBuilder(screenLayout2 = 0x1 /*ACONFIGURATION_SCREENROUND_NO*/)
            .toBinaryResConfig()
        assertFalse(roundConfig.toBinaryResConfig().match(notRoundDeviceConfig))

        val notRoundAppConfig = BinaryResourceConfigBuilder(screenLayout2 = 0x1 /*ACONFIGURATION_SCREENROUND_NO*/)
            .toBinaryResConfig()
        assert(notRoundAppConfig.match(notRoundDeviceConfig))
    }

    @Test
    fun testRoundIsMoreSpecific() {
        val deviceConfig = BinaryResourceConfigBuilder(
            screenLayout2 = SCREENROUND_YES /*ACONFIGURATION_SCREENROUND_YES*/,
            screenLayout = SCREENLONG_YES
        ).toBinaryResConfig()

        val targetConfigA = BinaryResourceConfigBuilder()
        val targetConfigB = targetConfigA.copy(screenLayout = SCREENLONG_YES)
        val targetConfigC = targetConfigB.copy(screenLayout2 = SCREENROUND_YES)

        assert(targetConfigC.screenLayout2 != 0)

        assert(targetConfigB.toBinaryResConfig().isBetterThan(targetConfigA.toBinaryResConfig(), deviceConfig))
        assert(targetConfigC.toBinaryResConfig().isBetterThan(targetConfigB.toBinaryResConfig(), deviceConfig))
    }
}