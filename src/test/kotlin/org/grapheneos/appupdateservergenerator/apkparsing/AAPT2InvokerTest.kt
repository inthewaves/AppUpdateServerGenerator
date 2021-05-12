package org.grapheneos.appupdateservergenerator.apkparsing

import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.ArgumentsProvider
import org.junit.jupiter.params.provider.ArgumentsSource
import java.util.stream.Stream

internal class AAPT2InvokerTest {
    class PathToDensityArgProvider : ArgumentsProvider {
        override fun provideArguments(context: ExtensionContext?): Stream<Arguments> = Stream.of(
            Arguments.of("res/drawable/notification_tile_bg.xml", AAPT2Invoker.Density.DEFAULT),
            Arguments.of("res/drawable-ldpi/notification_tile_bg.xml", AAPT2Invoker.Density.LOW),
            Arguments.of("res/drawable-mdpi-v4/notification_bg_normal.9.png", AAPT2Invoker.Density.MEDIUM),
            Arguments.of("res/drawable-hdpi-v4/notification_bg_normal.9.png", AAPT2Invoker.Density.HIGH),
            Arguments.of("res/drawable-xhdpi-v4/notification_bg_normal.9.png", AAPT2Invoker.Density.XHIGH),
            Arguments.of("res/drawable-ldrtl-xxxhdpi/abc_spinner_mtrl_am_alpha.9.png", AAPT2Invoker.Density.XXXHIGH),
            Arguments.of("res/drawable-anydpi/fingerprint_dialog_error.xml", AAPT2Invoker.Density.ANY),
        )
    }
    @ParameterizedTest(name = "parse [{0}] expecting density {1}")
    @ArgumentsSource(PathToDensityArgProvider::class)
    fun testDensityPathParsing(path: String, expectedDensity: AAPT2Invoker.Density) {
        assert(expectedDensity == AAPT2Invoker.Density.fromPathToDensity(path))
    }
}