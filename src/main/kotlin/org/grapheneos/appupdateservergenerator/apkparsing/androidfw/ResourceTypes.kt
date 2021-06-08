package org.grapheneos.appupdateservergenerator.apkparsing.androidfw

/**
 * Transliterated from
 * https://android.googlesource.com/platform/frameworks/base/+/c6c226327debf1f3fcbd71e2bbee792118364ee5/libs/androidfw/ResourceTypes.cpp
 */

import com.google.devrel.gmscore.tools.apk.arsc.BinaryResourceConfiguration
import org.grapheneos.appupdateservergenerator.model.Density
import org.grapheneos.appupdateservergenerator.util.byteArrayOfChars
import org.grapheneos.appupdateservergenerator.util.byteArrayOfInts


/** The minimum size in bytes that this configuration must be to contain screen config info.  */
private const val SCREEN_CONFIG_MIN_SIZE = 32

/** The minimum size in bytes that this configuration must be to contain screen dp info.  */
private const val SCREEN_DP_MIN_SIZE = 36

/** The minimum size in bytes that this configuration must be to contain locale info.  */
private const val LOCALE_MIN_SIZE = 48

/** The minimum size in bytes that this config must be to contain the screenConfig extension.  */
private const val SCREEN_CONFIG_EXTENSION_MIN_SIZE = 52

// Codes for specially handled languages and regions
private val kEnglish: ByteArray by lazy { byteArrayOfChars('e', 'n') } // packed version of "en"
private val kUnitedStates: ByteArray by lazy { byteArrayOfChars('U', 'S') }   // packed version of "US"
private val kFilipino: ByteArray by lazy { byteArrayOfInts(0xAD, 0x05) }  // packed version of "fil": '\xAD', '\x05'
private val kTagalog: ByteArray by lazy { byteArrayOfChars('t', 'l') } // packed version of "tl"


// screenLayout bits for screen size class.
private const val MASK_SCREENSIZE = 0x0f
// screenLayout bits for layout direction.
private const val MASK_LAYOUTDIR = 0xC0
// screenLayout bits for wide/long screen variation.
private const val MASK_SCREENLONG = 0x30
// screenLayout2 bits for round/notround.
private const val MASK_SCREENROUND = 0x03

private const val MASK_UI_MODE_TYPE = 0x0F

private const val MASK_UI_MODE_NIGHT = 0x30

private const val MASK_KEYSHIDDEN = 0x0003

private const val MASK_NAVHIDDEN = 0x000c

// https://android.googlesource.com/platform/frameworks/native/+/08d2f47df3be13b5b1a33e86d1ebb5081e5e7e29/include/android/configuration.h

/** Screen size: not specified. */
private const val SCREENSIZE_ANY = 0x00
/**
 * Screen size: value indicating the screen is at least
 * approximately 320x426 dp units, corresponding to the
 * <a href="/guide/topics/resources/providing-resources.html#ScreenSizeQualifier">small</a>
 * resource qualifier.
 */
private const val SCREENSIZE_SMALL = 0x01
/**
 * Screen size: value indicating the screen is at least
 * approximately 320x470 dp units, corresponding to the
 * <a href="/guide/topics/resources/providing-resources.html#ScreenSizeQualifier">normal</a>
 * resource qualifier.
 */
private const val SCREENSIZE_NORMAL = 0x02


/* https://cs.android.com/android/platform/superproject/+/master:frameworks/native/include/android/configuration.h;drc=master;l=188 */

/** Keyboard availability: not specified. */
private const val KEYSHIDDEN_ANY = 0x0000
/**
 * Keyboard availability: value corresponding to the
 * <a href="/guide/topics/resources/providing-resources.html#KeyboardAvailQualifier">keysexposed</a>
 * resource qualifier.
 */
private const val KEYSHIDDEN_NO = 0x0001
/**
 * Keyboard availability: value corresponding to the
 * <a href="/guide/topics/resources/providing-resources.html#KeyboardAvailQualifier">keyshidden</a>
 * resource qualifier.
 */
private const val KEYSHIDDEN_YES = 0x0002
/**
 * Keyboard availability: value corresponding to the
 * <a href="/guide/topics/resources/providing-resources.html#KeyboardAvailQualifier">keyssoft</a>
 * resource qualifier.
 */
private const val KEYSHIDDEN_SOFT = 0x0003

private fun areIdentical(code1: ByteArray, code2: ByteArray): Boolean {
    require(code1.size == 2 && code2.size == 2)
    return code1[0] == code2[0] && code1[1] == code2[1]
}

private fun langsAreEquivalent(lang1: ByteArray, lang2: ByteArray): Boolean {
    require(lang1.size == 2 && lang2.size == 2)

    return areIdentical(lang1, lang2) ||
            (areIdentical(lang1, kTagalog) && areIdentical(lang2, kFilipino)) ||
            (areIdentical(lang1, kFilipino) && areIdentical(lang2, kTagalog))
}

/**
 * https://android.googlesource.com/platform/frameworks/base/+/9489b44d47372be1cd19dd0e5e8da61c25380de6/libs/androidfw/ResourceTypes.cpp#2780
 */
fun BinaryResourceConfiguration.match(settings: BinaryResourceConfiguration): Boolean {
    if (imsi() != 0U) {
        if (mcc() != 0 && mcc() != settings.mcc()) {
            return false
        }
        if (mnc() != 0 && mnc() != settings.mcc()) {
            return false
        }
    }

    if (locale() != 0U) {
        // Don't consider country and variants when deciding matches.
        // (Theoretically, the variant can also affect the script. For
        // example, "ar-alalc97" probably implies the Latin script, but since
        // CLDR doesn't support getting likely scripts for that, we'll assume
        // the variant doesn't change the script.)
        //
        // If two configs differ only in their country and variant,
        // they can be weeded out in the isMoreSpecificThan test.
        if (!langsAreEquivalent(language(), settings.language())) {
            return false;
        }

        // For backward compatibility and supporting private-use locales, we
        // fall back to old behavior if we couldn't determine the script for
        // either of the desired locale or the provided locale. But if we could determine
        // the scripts, they should be the same for the locales to match.
        var countriesMustMatch = false
        val computedScript = ByteArray(4)
        var script: ByteArray? = null
        if (settings.localeScript()[0] == 0.toByte()) {
            countriesMustMatch = true
        } else {
            if (localeScript()[0] == 0.toByte()) { // TL note: TODO: add localeScriptWasComputed property check here
                LocaleData.localeDataComputeScript(computedScript, language(), region())
                if (computedScript[0] == 0.toByte()) { // we could not compute the script
                    countriesMustMatch = true
                } else {
                    script = computedScript
                }
            } else { // script was provided, so just use it
                script = localeScript()
            }
        }

        if (countriesMustMatch) {
            if (region()[0] != 0.toByte() && !areIdentical(region(), settings.region())) {
                return false
            }
        } else {
            if (!settings.localeScript().contentEquals(script)) {
                return false
            }
        }
    }

    if (screenConfig() != 0U) {
        if (screenLayoutDirection() != 0 && screenLayoutDirection() != settings.screenLayoutDirection()) {
            return false
        }

        // Any screen sizes for larger screens than the setting do not
        // match.
        if (screenLayoutSize() != 0 && screenLayoutSize() > settings.screenLayoutSize()) {
            return false
        }

        if (screenLayoutLong() != 0 && screenLayoutLong() != settings.screenLayoutLong()) {
            return false
        }

        if (uiModeType() != 0 && uiModeType() != settings.uiModeType()) {
            return false
        }

        if (uiModeNight() != 0 && uiModeNight() != settings.uiModeNight()) {
            return false
        }

        if (smallestScreenWidthDp() != 0 && smallestScreenWidthDp() > settings.smallestScreenWidthDp()) {
            return false
        }
    }

    if (screenLayout2() != 0) {
        if (screenLayoutRound() != 0 && screenLayoutRound() != settings.screenLayoutRound()) {
            return false
        }
        // TL note: The current implementation of BinaryResourceConfiguration doesn't support HDR and wide color gamut,
        // as it is explicitly treated as padding by the implementation
    }

    if (screenHeightDp() != 0 || screenWidthDp() != 0) {
        if (screenWidthDp() != 0 && screenWidthDp() > settings.screenWidthDp()) {
            return false
        }
        if (screenHeightDp() != 0 && screenHeightDp() > settings.screenHeightDp()) {
            return false
        }
    }

    if (screenType() != 0U) {
        if (orientation() != 0 && orientation() != settings.orientation()) {
            return false
        }
        // density always matches - we can scale it. See isBetterThan
        if (touchscreen() != 0 && touchscreen() != settings.touchscreen()) {
            return false
        }
    }

    if (inputFlags() != 0) {
        if (keyboardHidden() != 0 && keyboardHidden() != settings.keyboardHidden()) {
            // For compatibility, we count a request for KEYSHIDDEN_NO as also
            // matching the more recent KEYSHIDDEN_SOFT.  Basically
            // KEYSHIDDEN_NO means there is some kind of keyboard available.
            if (keyboardHidden() != KEYSHIDDEN_NO || settings.keyboardHidden() != KEYSHIDDEN_SOFT) {
                return false
            }
        }
        if (navigationHidden() != 0 && navigationHidden() != settings.navigationHidden()) {
            return false
        }
        if (keyboard() != 0 && keyboard() != settings.keyboard()) {
            return false
        }
        if (navigation() != 0 && navigation() != settings.navigation()) {
            return false
        }
    }
    if (screenSize() != 0U) {
        if (screenWidth() != 0 && screenWidth() != settings.screenWidth()) {
            return false
        }
        if (screenHeight() != 0 && screenHeight() != settings.screenHeight()) {
            return false
        }
    }

    if (version() != 0U) {
        if (sdkVersion() != 0 && sdkVersion() > settings.sdkVersion()) {
            return false
        }
        if (minorVersion() != 0 && minorVersion() != settings.minorVersion()) {
            return false
        }
    }
    return true
}

/**
 * https://cs.android.com/android/platform/superproject/+/master:frameworks/base/libs/androidfw/ResourceTypes.cpp;drc=master;l=2517
 */
fun BinaryResourceConfiguration.isBetterThan(
    o: BinaryResourceConfiguration,
    requested: BinaryResourceConfiguration?
): Boolean {
    if (requested != null) {
        if ((imsi() or o.imsi()) != 0u) { // if (imsi || o.imsi)
            if ((mcc() != o.mcc()) && requested.mcc() != 0) {
                return mcc() != 0 // return (mcc);
            }
            if ((mnc() != o.mnc()) && requested.mnc() != 0) {
                return mnc() != 0 // return (mnc);
            }
        }

        if (isLocaleBetterThan(o, requested)) {
            return true
        }

        if ((screenLayout() or o.screenLayout()) != 0) {
            if (
                (screenLayout() xor o.screenLayout()) and MASK_LAYOUTDIR != 0
                && (requested.screenLayout() and MASK_LAYOUTDIR) != 0
            ) {
                return screenLayoutDirection() > o.screenLayoutDirection()
            }
        }

        if ((smallestScreenWidthDp() or o.smallestScreenWidthDp()) != 0) {
            // The configuration closest to the actual size is best.
            // We assume that larger configs have already been filtered
            // out at this point.  That means we just want the largest one.
            if (smallestScreenWidthDp() != o.smallestScreenWidthDp()) {
                return smallestScreenWidthDp() > o.smallestScreenWidthDp()
            }
        }

        if ((screenSizeDp() or o.screenSizeDp()) != 0U) {
            // "Better" is based on the sum of the difference between both
            // width and height from the requested dimensions.  We are
            // assuming the invalid configs (with smaller dimens) have
            // already been filtered.  Note that if a particular dimension
            // is unspecified, we will end up with a large value (the
            // difference between 0 and the requested dimension), which is
            // good since we will prefer a config that has specified a
            // dimension value.
            var myDelta = 0
            var otherDelta = 0
            if (requested.screenWidthDp() != 0) {
                myDelta += requested.screenWidthDp() - screenWidthDp()
                otherDelta += requested.screenWidthDp() - o.screenWidthDp()
            }
            if (requested.screenHeightDp() != 0) {
                myDelta += requested.screenHeightDp() - screenHeightDp()
                otherDelta += requested.screenHeightDp() - o.screenHeightDp()
            }
            if (myDelta != otherDelta) {
                return myDelta < otherDelta
            }
        }

        if ((screenLayout() or o.screenLayout()) != 0) {
            if (
                ((screenLayout() xor o.screenLayout()) and MASK_SCREENSIZE) != 0
                && (requested.screenLayout() and MASK_SCREENSIZE) != 0
            ) {
                // A little backwards compatibility here: undefined is
                // considered equivalent to normal.  But only if the
                // requested size is at least normal; otherwise, small
                // is better than the default.
                val mySL = screenLayoutSize()
                val oSL = o.screenLayoutSize()
                var fixedMySL = mySL
                var fixedOSL = oSL
                if (requested.screenLayoutSize() >= SCREENSIZE_NORMAL) {
                    if (fixedMySL == 0) fixedMySL = SCREENSIZE_NORMAL
                    if (fixedOSL == 0) fixedOSL = SCREENSIZE_NORMAL
                }
                // For screen size, the best match is the one that is
                // closest to the requested screen size, but not over
                // (the not over part is dealt with in match() below).
                if (fixedMySL == fixedOSL) {
                    // If the two are the same, but 'this' is actually
                    // undefined, then the other is really a better match.
                    if (mySL == 0) return false;
                    return true;
                }
                if (fixedMySL != fixedOSL) {
                    return fixedMySL > fixedOSL;
                }
            }
            if (
                (screenLayout() xor o.screenLayout()) and MASK_SCREENLONG != 0
                && requested.screenLayout() and MASK_SCREENLONG != 0
            ) {
                return screenLayoutLong() != 0
            }
        }

        if ((screenLayout2() or o.screenLayout2()) != 0) {
            if (
                ((screenLayout2() xor o.screenLayout2()) and MASK_SCREENROUND) != 0
                && (requested.screenLayout2() and MASK_SCREENROUND) != 0
            ) {
                return screenLayoutRound() != 0
            }
        }

        /*
        TODO: add colorMode
        if (colorMode || o.colorMode) {
            if (((colorMode^o.colorMode) & MASK_WIDE_COLOR_GAMUT) != 0 &&
                    (requested->colorMode & MASK_WIDE_COLOR_GAMUT)) {
                return colorMode & MASK_WIDE_COLOR_GAMUT;
            }
            if (((colorMode^o.colorMode) & MASK_HDR) != 0 &&
                    (requested->colorMode & MASK_HDR)) {
                return colorMode & MASK_HDR;
            }
        }
         */

        if ((orientation() != o.orientation()) && (requested.orientation() != 0)) {
            return orientation() != 0
        }

        if ((uiMode() or o.uiMode()) != 0) {
            if (
                ((uiMode() xor o.uiMode()) and MASK_UI_MODE_TYPE) != 0
                && (requested.uiMode() and MASK_UI_MODE_TYPE) != 0
            ) {
                return uiModeType() != 0
            }

            if (
                ((uiMode() xor o.uiMode()) and MASK_UI_MODE_NIGHT) != 0
                && (requested.uiMode() and MASK_UI_MODE_NIGHT) != 0
            ) {
                return uiModeNight() != 0
            }
        }

        if ((screenType() or o.screenType()) != 0U) {
            if (density() != o.density()) {
                // Use the system default density (DENSITY_MEDIUM, 160dpi) if none specified.
                val thisDensity = if (density() != 0) density() else Density.MEDIUM.approximateDpi
                val otherDensity = if (o.density() != 0) o.density() else Density.MEDIUM.approximateDpi

                // We always prefer DENSITY_ANY over scaling a density bucket.
                if (thisDensity == Density.ANY.approximateDpi) {
                    return true
                } else if (otherDensity == Density.ANY.approximateDpi) {
                    return false
                }

                var requestedDensity = requested.density()
                if (requestedDensity == 0 || requestedDensity == Density.ANY.approximateDpi) {
                    requestedDensity = Density.MEDIUM.approximateDpi
                }

                // DENSITY_ANY is now dealt with. We should look to
                // pick a density bucket and potentially scale it.
                // Any density is potentially useful
                // because the system will scale it.  Scaling down
                // is generally better than scaling up.
                var h = thisDensity
                var l = otherDensity
                var bImBigger = true
                if (l > h) {
                    val t = h
                    h = l
                    l = t
                    bImBigger = false
                }

                if (requestedDensity >= h) {
                    // requested value higher than both l and h, give h
                    return bImBigger
                }
                if (l >= requestedDensity) {
                    // requested value lower than both l and h, give l
                    return !bImBigger
                }
                // saying that scaling down is 2x better than up
                return if ((2 * l - requestedDensity) * h > requestedDensity * requestedDensity) {
                    !bImBigger
                } else {
                    bImBigger
                }
            }

            if ((touchscreen() != o.touchscreen()) && (requested.touchscreen() != 0)) {
                return touchscreen() != 0
            }
        }

        if ((inputFlags() or o.inputFlags()) != 0) {
            val keysHidden = keyboardHidden()
            val oKeysHidden = o.keyboardHidden()
            if (keysHidden != oKeysHidden) {
                val reqKeysHidden = requested.keyboardHidden()
                if (reqKeysHidden != 0) {

                    if (keysHidden == 0) return false;
                    if (oKeysHidden == 0) return true;
                    // For compatibility, we count KEYSHIDDEN_NO as being
                    // the same as KEYSHIDDEN_SOFT.  Here we disambiguate
                    // these by making an exact match more specific.
                    if (reqKeysHidden == keysHidden) return true;
                    if (reqKeysHidden == oKeysHidden) return false;
                }
            }

            val navHidden = navigationHidden()
            val oNavHidden = o.navigationHidden()
            if (navHidden != oNavHidden) {
                val reqNavHidden = requested.navigationHidden()
                if (reqNavHidden != 0) {

                    if (navHidden == 0) return false;
                    if (oNavHidden == 0) return true;
                }
            }

            if (keyboard() != o.keyboard() && requested.keyboard() != 0) {
                return keyboard() != 0
            }

            if (navigation() != o.navigation() && requested.navigation() != 0) {
                return navigation() != 0
            }
        }

        if ((screenSize() or o.screenSize()) != 0U) {
            // "Better" is based on the sum of the difference between both
            // width and height from the requested dimensions.  We are
            // assuming the invalid configs (with smaller sizes) have
            // already been filtered.  Note that if a particular dimension
            // is unspecified, we will end up with a large value (the
            // difference between 0 and the requested dimension), which is
            // good since we will prefer a config that has specified a
            // size value.
            var myDelta = 0
            var otherDelta = 0
            if (requested.screenWidth() != 0) {
                myDelta += requested.screenWidth() - screenWidth()
                otherDelta = requested.screenWidth() - o.screenWidth()
            }
            if (requested.screenHeight() != 0) {
                myDelta += requested.screenHeight() - screenHeight()
                otherDelta = requested.screenHeight() - o.screenHeight()
            }
            if (myDelta != otherDelta) {
                return myDelta < otherDelta
            }
        }

        if ((version() or o.version()) != 0U) {
            if ((sdkVersion() != o.sdkVersion()) && requested.sdkVersion() != 0) {
                return sdkVersion() > o.sdkVersion()
            }

            if (minorVersion() != o.minorVersion() && requested.minorVersion() != 0) {
                return minorVersion() != 0
            }
        }

        return false
    }
    return isMoreSpecificThan(o)
}

/**
 * https://cs.android.com/android/platform/superproject/+/master:frameworks/base/libs/androidfw/ResourceTypes.cpp;drc=master;l=2219
 */
fun BinaryResourceConfiguration.getImportanceScoreOfLocale(): Int {
    return (if (localeVariant()[0] != 0.toByte()) 4 else 0) +
            (if (localeScript()[0] != 0.toByte()) 2 else 0)
        /* TODO: add !localeScriptWasComputed */
        /* TODO: add localeNumberingSystem */
}

fun BinaryResourceConfiguration.isLocaleMoreSpecificThan(o: BinaryResourceConfiguration): Int {
    if (locale() or o.locale() != 0U) {
        if (language()[0] != o.language()[0]) {
            if (language()[0] == 0.toByte()) return -1
            if (o.language()[0] == 0.toByte()) return 1
        }

        if (region()[0] != o.region()[0]) {
            if (region()[0] == 0.toByte()) return -1
            if (o.region()[0] == 0.toByte()) return 1
        }
    }

    return getImportanceScoreOfLocale() - o.getImportanceScoreOfLocale()
}

fun BinaryResourceConfiguration.isMoreSpecificThan(o: BinaryResourceConfiguration): Boolean {
    // The order of the following tests defines the importance of one
    // configuration parameter over another.  Those tests first are more
    // important, trumping any values in those following them.
    if ((imsi() or o.imsi()) != 0U) {
        if (mcc() != o.mcc()) {
            if (mcc() == 0) return false
            if (o.mcc() == 0) return true
        }

        if (mnc() != o.mnc()) {
            if (mnc() == 0) return false
            if (o.mnc() == 0) return true
        }
    }

    if (locale() or o.locale() != 0U) {
        val diff = isLocaleMoreSpecificThan(o)
        if (diff < 0) {
            return false
        }

        if (diff > 0) {
            return true
        }
    }

    if (screenLayout() or o.screenLayout() != 0) {
        if ((screenLayout() xor o.screenLayout()) and MASK_LAYOUTDIR != 0) {
            if (screenLayoutDirection() == 0) return false
            if (o.screenLayoutDirection() == 0) return false
        }
    }

    if (smallestScreenWidthDp() or o.smallestScreenWidthDp() != 0) {
        if (smallestScreenWidthDp() != o.smallestScreenWidthDp()) {
            if (smallestScreenWidthDp() == 0) return false
            if (o.smallestScreenWidthDp() == 0) return true
        }
    }

    if (screenSizeDp() or o.screenSizeDp() != 0U) {
        if (screenWidthDp() != o.screenWidthDp()) {
            if (screenWidthDp() == 0) return false
            if (o.screenWidthDp() == 0) return true
        }
        if (screenHeightDp() != o.screenHeightDp()) {
            if (screenHeightDp() == 0) return false
            if (o.screenHeightDp() == 0) return true
        }
    }

    if (screenLayout() or o.screenLayout() != 0) {
        if ((screenLayout() xor o.screenLayout()) and MASK_SCREENSIZE != 0) {
            if (screenLayoutSize() == 0) return false
            if (o.screenLayoutSize() == 0) return true
        }
        if ((screenLayout() xor o.screenLayout()) and MASK_SCREENLONG != 0) {
            if (screenLayoutLong() == 0) return false
            if (o.screenLayoutLong() == 0) return true
        }
    }

    if (screenLayout2() or o.screenLayout2() != 0) {
        if ((screenLayout2() xor o.screenLayout2()) and MASK_SCREENROUND != 0) {
            if (screenLayoutRound() == 0) return false
            if (o.screenLayoutRound() == 0) return true
        }
    }

    /*
    if (colorMode || o.colorMode) {
        if (((colorMode^o.colorMode) & MASK_HDR) != 0) {
            if (!(colorMode & MASK_HDR)) return false;
            if (!(o.colorMode & MASK_HDR)) return true;
        }
        if (((colorMode^o.colorMode) & MASK_WIDE_COLOR_GAMUT) != 0) {
            if (!(colorMode & MASK_WIDE_COLOR_GAMUT)) return false;
            if (!(o.colorMode & MASK_WIDE_COLOR_GAMUT)) return true;
        }
    }
     */

    if (orientation() != o.orientation()) {
        if (orientation() == 0) return false
        if (o.orientation() == 0) return true
    }

    if (uiMode() or o.uiMode() != 0) {
        if ((uiMode() xor o.uiMode()) and MASK_UI_MODE_TYPE != 0) {
            if (uiModeType() == 0) return false
            if (o.uiModeType() == 0) return true
        }
        if ((uiMode() xor o.uiMode()) and MASK_UI_MODE_NIGHT != 0) {
            if (uiModeNight() == 0) return false
            if (o.uiModeNight() == 0) return true
        }
    }

    // density is never 'more specific'
    // as the default just equals 160

    if (touchscreen() != o.touchscreen()) {
        if (touchscreen() == 0) return false
        if (o.touchscreen() == 0) return true
    }

    if (inputFlags() or o.inputFlags() != 0) {
        if ((inputFlags() xor o.inputFlags()) and MASK_KEYSHIDDEN != 0) {
            if (keyboardHidden() == 0) return false
            if (o.keyboardHidden() == 0) return true
        }

        if ((inputFlags() xor o.inputFlags()) and MASK_NAVHIDDEN != 0) {
            if (navigationHidden() == 0) return false
            if (o.navigationHidden() == 0) return true
        }

        if (keyboard() != o.keyboard()) {
            if (keyboard() == 0) return false
            if (o.keyboard() == 0) return true
        }

        if (navigation() != o.navigation()) {
            if (navigation() == 0) return false
            if (o.navigation() == 0) return true
        }
    }

    if (screenSize() or o.screenSize() != 0U) {
        if (screenWidth() != o.screenWidth()) {
            if (screenWidth() == 0) return false
            if (o.screenWidth() == 0) return true
        }

        if (screenHeight() != o.screenHeight()) {
            if (screenHeight() == 0) return false
            if (o.screenHeight() == 0) return true
        }
    }

    if (version() or o.version() != 0U) {
        if (sdkVersion() != o.sdkVersion()) {
            if (sdkVersion() == 0) return false
            if (o.sdkVersion() == 0) return true
        }

        if (minorVersion() != o.minorVersion()) {
            if (minorVersion() == 0) return false
            if (o.minorVersion() == 0) return true
        }
    }
    return false
}



/*
union {
    struct {
        // This field can take three different forms:
        // - \0\0 means "any".
        //
        // - Two 7 bit ascii values interpreted as ISO-639-1 language
        //   codes ('fr', 'en' etc. etc.). The high bit for both bytes is
        //   zero.
        //
        // - A single 16 bit little endian packed value representing an
        //   ISO-639-2 3 letter language code. This will be of the form:
        //
        //   {1, t, t, t, t, t, s, s, s, s, s, f, f, f, f, f}
        //
        //   bit[0, 4] = first letter of the language code
        //   bit[5, 9] = second letter of the language code
        //   bit[10, 14] = third letter of the language code.
        //   bit[15] = 1 always
        //
        // For backwards compatibility, languages that have unambiguous
        // two letter codes are represented in that format.
        //
        // The layout is always bigendian irrespective of the runtime
        // architecture.
        char language[2];

        // This field can take three different forms:
        // - \0\0 means "any".
        //
        // - Two 7 bit ascii values interpreted as 2 letter region
        //   codes ('US', 'GB' etc.). The high bit for both bytes is zero.
        //
        // - An UN M.49 3 digit region code. For simplicity, these are packed
        //   in the same manner as the language codes, though we should need
        //   only 10 bits to represent them, instead of the 15.
        //
        // The layout is always bigendian irrespective of the runtime
        // architecture.
        char country[2];
    };
    uint32_t locale;
};
 */
fun BinaryResourceConfiguration.locale(): UInt = LocaleData.packLocale(language(), region())

fun BinaryResourceConfiguration.imsi(): UInt = ((mcc().toUInt() and 0xFFFFU) shl 16) or (mnc().toUInt() and 0xFFFFU)

fun BinaryResourceConfiguration.screenSizeDp(): UInt =
    ((screenWidthDp().toUInt() and 0xFFFFU) shl 16) or (screenHeightDp().toUInt() and 0xFFFFU)

/**
 * Corresponds to the uint32_t `screenConfig` variable in
 * [libandroidfw](https://android.googlesource.com/platform/frameworks/base/+/c6c226327debf1f3fcbd71e2bbee792118364ee5/libs/androidfw/include/androidfw/ResourceTypes.h#1147)
 */
fun BinaryResourceConfiguration.screenConfig(): UInt =
    ((screenLayout().toUInt() and 0xFFU) shl 24) or
            ((uiMode().toUInt() and 0xFFU) shl 16) or
            (smallestScreenWidthDp().toUInt() and 0xFFFFU)

fun BinaryResourceConfiguration.screenType(): UInt =
    ((orientation().toUInt() and 0xFFU) shl 24) or
            ((touchscreen().toUInt() and 0xFFU) shl 16) or
            (density().toUInt() and 0xFFFFU)

fun BinaryResourceConfiguration.screenSize(): UInt =
    ((screenWidth().toUInt() and 0xFFFFU) shl 16) or (screenHeight().toUInt() and 0xFFFFU)

fun BinaryResourceConfiguration.version(): UInt =
    ((sdkVersion().toUInt() and 0xFFFFU) shl 16) or (minorVersion().toUInt() and 0xFFFFU)

fun BinaryResourceConfiguration.isLocaleBetterThan(
    o: BinaryResourceConfiguration,
    requested: BinaryResourceConfiguration
): Boolean {
    if (requested.locale() == 0u) {
        // The request doesn't have a locale, so no resource is better
        // than the other.
        return false
    }

    if (locale() == 0u && o.locale() == 0u) {
        // The locale part of both resources is empty, so none is better
        // than the other.
        return false;
    }

    // Non-matching locales have been filtered out, so both resources
    // match the requested locale.
    //
    // Because of the locale-related checks in match() and the checks, we know
    // that:
    // 1) The resource languages are either empty or match the request;
    // and
    // 2) If the request's script is known, the resource scripts are either
    //    unknown or match the request.
    if (!langsAreEquivalent(language(), o.language())) {
        // The languages of the two resources are not equivalent. If we are
        // here, we can only assume that the two resources matched the request
        // because one doesn't have a language and the other has a matching
        // language.
        //
        // We consider the one that has the language specified a better match.
        //
        // The exception is that we consider no-language resources a better match
        // for US English and similar locales than locales that are a descendant
        // of Internatinal English (en-001), since no-language resources are
        // where the US English resource have traditionally lived for most apps.
        if (areIdentical(requested.language(), kEnglish)) {
            if (areIdentical(requested.region(), kUnitedStates)) {
                // For US English itself, we consider a no-locale resource a
                // better match if the other resource has a country other than
                // US specified.
                return if (language()[0] != 0.toByte()) {
                    region()[0] == 0.toByte() || areIdentical(region(), kUnitedStates)
                } else {
                    !(o.region()[0] == 0.toByte() || areIdentical(o.region(), kUnitedStates));
                }
            } else if (LocaleData.localeDataIsCloseToUsEnglish(requested.region())) {
                return if (language()[0] == 0.toByte()) {
                    LocaleData.localeDataIsCloseToUsEnglish(region())
                } else {
                    LocaleData.localeDataIsCloseToUsEnglish(o.region())
                }
            }
        }
        return language()[0] != 0.toByte()
    }

    // If we are here, both the resources have an equivalent non-empty language
    // to the request.
    //
    // Because the languages are equivalent, computeScript() always returns a
    // non-empty script for languages it knows about, and we have passed the
    // script checks in match(), the scripts are either all unknown or are all
    // the same. So we can't gain anything by checking the scripts. We need to
    // check the region and variant.

    // See if any of the regions is better than the other.
    val regionComparison = LocaleData.localeDataCompareRegions(
        region(),
        o.region(),
        requested.language(),
        requested.localeScript(),
        requested.region()
    )
    if (regionComparison != 0) {
        return regionComparison > 0
    }

    // The regions are the same. Try the variant.
    val localeMatches: Boolean = localeVariant().contentEquals(requested.localeVariant())
    val otherMatches: Boolean = o.localeVariant().contentEquals(requested.localeVariant())
    if (localeMatches != otherMatches) {
        return localeMatches
    }

    // The variants are the same, try numbering system. TODO: Figure out what localeNumberingSystem is
    /*
    const bool localeNumsysMatches = strncmp(localeNumberingSystem,
        requested->localeNumberingSystem,
    sizeof(localeNumberingSystem)) == 0;
    const bool otherNumsysMatches = strncmp(o.localeNumberingSystem,
        requested->localeNumberingSystem,
    sizeof(localeNumberingSystem)) == 0;
    if (localeNumsysMatches != otherNumsysMatches) {
        return localeNumsysMatches;
    }

     */

    // Finally, the languages, although equivalent, may still be different
    // (like for Tagalog and Filipino). Identical is better than just
    // equivalent.
    if (areIdentical(language(), requested.language()) && !areIdentical(o.language(), requested.language())) {
        return true
    }

    return false
}
