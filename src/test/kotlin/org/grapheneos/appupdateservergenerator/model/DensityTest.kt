package org.grapheneos.appupdateservergenerator.model

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.ArgumentsProvider
import org.junit.jupiter.params.provider.ArgumentsSource
import java.util.stream.Stream
import kotlin.test.assertEquals

internal class DensityTest {
    class PathToDensityArgProvider : ArgumentsProvider {
        override fun provideArguments(context: ExtensionContext?): Stream<Arguments> = Stream.of(
            Arguments.of("res/drawable/notification_tile_bg.xml", Density.DEFAULT),
            Arguments.of("res/drawable-ldpi/notification_tile_bg.xml", Density.LOW),
            Arguments.of("res/drawable-mdpi-v4/notification_bg_normal.9.png", Density.MEDIUM),
            Arguments.of("res/drawable-hdpi-v4/notification_bg_normal.9.png", Density.HIGH),
            Arguments.of("res/drawable-xhdpi-v4/notification_bg_normal.9.png", Density.XHIGH),
            Arguments.of("res/drawable-ldrtl-xxxhdpi/abc_spinner_mtrl_am_alpha.9.png", Density.XXXHIGH),
            Arguments.of("res/drawable-anydpi/fingerprint_dialog_error.xml", Density.ANY),
        )
    }
    @ParameterizedTest(name = "parse [{0}] expecting density {1}")
    @ArgumentsSource(PathToDensityArgProvider::class)
    fun testDensityPathParsing(path: String, expectedDensity: Density) {
        assertEquals(expectedDensity, Density.fromPath(path))
    }

    @Test
    fun testDensityOrder() {
        assert(Density.DEFAULT < Density.LOW)
        assert(Density.LOW < Density.MEDIUM)
        assert(Density.MEDIUM < Density.TV)
        assert(Density.TV < Density.HIGH)
        assert(Density.HIGH < Density.XHIGH)
        assert(Density.XHIGH < Density.XXHIGH)
        assert(Density.XXHIGH < Density.XXXHIGH)
        assert(Density.XXXHIGH < Density.ANY)
    }
}