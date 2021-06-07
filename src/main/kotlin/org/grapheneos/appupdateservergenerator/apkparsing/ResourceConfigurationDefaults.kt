package org.grapheneos.appupdateservergenerator.apkparsing

import org.grapheneos.appupdateservergenerator.model.Density

/**
 * These defaults are from
 * https://android.googlesource.com/platform/frameworks/base/+/e2ddd9d277876ee33e8526a792d0bc9538de6dfc/tools/aapt2/dump/DumpManifest.cpp#213
 */
object ResourceConfigurationDefaults {
    private const val DEFAULT_ORIENTATION = 0x01 // portrait
    private val defaultDensity = Density.MEDIUM // mdpi
    /** https://android.googlesource.com/platform/frameworks/base/+/e2ddd9d277876ee33e8526a792d0bc9538de6dfc/tools/aapt2/dump/DumpManifest.cpp#94 */
    private const val DEFAULT_SDK_VERSION = 10000
    private const val DEFAULT_SCREEN_WIDTH_DP = 320
    private const val DEFAULT_SCREEN_HEIGHT_DP = 480
    private const val DEFAULT_SMALLEST_SCREEN_WIDTH_DP = 320
    /**
     * Screen size: value indicating the screen is at least
     * approximately 320x470 dp units, corresponding to the
     * <a href="/guide/topics/resources/providing-resources.html#ScreenSizeQualifier">normal</a>
     * resource qualifier.
     *
     * https://cs.android.com/android/platform/superproject/+/master:frameworks/native/include/android/configuration.h;drc=master;bpv=1;bpt=1;l=239?gsn=ACONFIGURATION_SCREENSIZE_NORMAL&gs=kythe%3A%2F%2Fandroid.googlesource.com%2Fplatform%2Fsuperproject%3Flang%3Dc%252B%252B%3Fpath%3Dframeworks%2Fnative%2Finclude%2Fandroid%2Fconfiguration.h%23AaawhOgA9PHrHUOa4AL-MdeI5aa_e54IIPKhTDtvF3w
     */
    private const val DEFAULT_SCREEN_LAYOUT = 0x02
}