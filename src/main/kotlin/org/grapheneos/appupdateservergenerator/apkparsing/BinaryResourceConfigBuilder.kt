package org.grapheneos.appupdateservergenerator.apkparsing

import com.google.devrel.gmscore.tools.apk.arsc.BinaryResourceConfiguration
import org.grapheneos.appupdateservergenerator.model.Density

/**
 * A class creating [com.google.devrel.gmscore.tools.apk.arsc.BinaryResourceConfiguration]s
 * Use [toBinaryResConfig] and [copy] to convert a builder object to such a thing.
 */
data class BinaryResourceConfigBuilder(
    /**
     * Determined from experimental testing.
     */
    val size: Int = 64,
    val mcc: Int = 0,
    val mnc: Int = 0,
    val language: ByteArray = ByteArray(2),
    val region: ByteArray = ByteArray(2),
    val orientation: Int = 0,
    val touchscreen: Int = 0,
    val density: Int = 0,
    val keyboard: Int = 0,
    val navigation: Int = 0,
    val inputFlags: Int = 0,
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
    val sdkVersion: Int = 0,
    val minorVersion: Int = 0,
    val screenLayout: Int = 0,
    val uiMode: Int = 0,
    val smallestScreenWidthDp: Int = 0,
    val screenWidthDp: Int = 0,
    val screenHeightDp: Int = 0,
    /**
     * The ISO-15924 short name for the script corresponding to this
     * configuration. (eg. Hant, Latn, etc.). Interpreted in conjunction with
     * the locale field. A ByteArray of 4 bytes.
     *
     * https://android.googlesource.com/platform/frameworks/base/+/9489b44d47372be1cd19dd0e5e8da61c25380de6/libs/androidfw/include/androidfw/ResourceTypes.h#1161
     */
    val localeScript: ByteArray = ByteArray(4),
    val localeVariant: ByteArray = ByteArray(8),
    val screenLayout2: Int = 0,
    val unknown: ByteArray = ByteArray(0),
) {
    init {
        require(language.size == 2)
        require(region.size == 2)
        require(localeScript.size == 4)
        require(localeVariant.size == 8)
    }

    companion object {
        /**
         * Creates a default configuration used to retrieve resources.
         *
         * These defaults are from
         * https://android.googlesource.com/platform/frameworks/base/+/e2ddd9d277876ee33e8526a792d0bc9538de6dfc/tools/aapt2/dump/DumpManifest.cpp#325
         */
        fun createDummyConfig() = BinaryResourceConfigBuilder(
            orientation = 0x01, // portrait
            density = Density.MEDIUM.approximateDpi,
            sdkVersion = 10000,
            screenWidthDp = 320,
            screenHeightDp = 480,
            smallestScreenWidthDp = 320,
            screenLayout = 0x02 // SCREENSIZE_NORMAL
        )

        private val constructor by lazy {
            BinaryResourceConfiguration::class.java.declaredConstructors
                .also {
                    if (it.size != 1) {
                        println("Warning: BinaryResourceConfiguration has more than one constructor --- API change?")
                    }

                }
                .first()
                .also {
                    it.isAccessible = true
                    if (it.parameters.size != 24) {
                        println("Warning: BinaryResourceConfiguration constructor change parameters --- API change?")
                    }
                }
        }
    }

    fun toBinaryResConfig(): BinaryResourceConfiguration {

        /*
        private BinaryResourceConfiguration(int size,
                                      int mcc,
                                      int mnc,
                                      byte[] language,
                                      byte[] region,
                                      int orientation,
                                      int touchscreen,
                                      int density,
                                      int keyboard,
                                      int navigation,
                                      int inputFlags,
                                      int screenWidth,
                                      int screenHeight,
                                      int sdkVersion,
                                      int minorVersion,
                                      int screenLayout,
                                      int uiMode,
                                      int smallestScreenWidthDp,
                                      int screenWidthDp,
                                      int screenHeightDp,
                                      byte[] localeScript,
                                      byte[] localeVariant,
                                      int screenLayout2,
                                      byte[] unknown) {
         */

        constructor.isAccessible = true
        return constructor.newInstance(
            size,
            mcc,
            mnc,
            language,
            region,
            orientation,
            touchscreen,
            density,
            keyboard,
            navigation,
            inputFlags,
            screenWidth,
            screenHeight,
            sdkVersion,
            minorVersion,
            screenLayout,
            uiMode,
            smallestScreenWidthDp,
            screenWidthDp,
            screenHeightDp,
            localeScript,
            localeVariant,
            screenLayout2,
            unknown
        ) as BinaryResourceConfiguration
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BinaryResourceConfigBuilder

        if (size != other.size) return false
        if (mcc != other.mcc) return false
        if (mnc != other.mnc) return false
        if (!language.contentEquals(other.language)) return false
        if (!region.contentEquals(other.region)) return false
        if (orientation != other.orientation) return false
        if (touchscreen != other.touchscreen) return false
        if (density != other.density) return false
        if (keyboard != other.keyboard) return false
        if (navigation != other.navigation) return false
        if (inputFlags != other.inputFlags) return false
        if (screenWidth != other.screenWidth) return false
        if (screenHeight != other.screenHeight) return false
        if (sdkVersion != other.sdkVersion) return false
        if (minorVersion != other.minorVersion) return false
        if (screenLayout != other.screenLayout) return false
        if (uiMode != other.uiMode) return false
        if (smallestScreenWidthDp != other.smallestScreenWidthDp) return false
        if (screenWidthDp != other.screenWidthDp) return false
        if (screenHeightDp != other.screenHeightDp) return false
        if (!localeScript.contentEquals(other.localeScript)) return false
        if (!localeVariant.contentEquals(other.localeVariant)) return false
        if (screenLayout2 != other.screenLayout2) return false
        if (!unknown.contentEquals(other.unknown)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = size
        result = 31 * result + mcc
        result = 31 * result + mnc
        result = 31 * result + language.contentHashCode()
        result = 31 * result + region.contentHashCode()
        result = 31 * result + orientation
        result = 31 * result + touchscreen
        result = 31 * result + density
        result = 31 * result + keyboard
        result = 31 * result + navigation
        result = 31 * result + inputFlags
        result = 31 * result + screenWidth
        result = 31 * result + screenHeight
        result = 31 * result + sdkVersion
        result = 31 * result + minorVersion
        result = 31 * result + screenLayout
        result = 31 * result + uiMode
        result = 31 * result + smallestScreenWidthDp
        result = 31 * result + screenWidthDp
        result = 31 * result + screenHeightDp
        result = 31 * result + localeScript.contentHashCode()
        result = 31 * result + localeVariant.contentHashCode()
        result = 31 * result + screenLayout2
        result = 31 * result + unknown.contentHashCode()
        return result
    }
}