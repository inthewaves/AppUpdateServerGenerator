package org.grapheneos.appupdateservergenerator.apkparsing.androidfw

import org.grapheneos.appupdateservergenerator.util.byteArrayOfChars

/**
 * Transliterated from
 * https://android.googlesource.com/platform/frameworks/base/+/c6c226327debf1f3fcbd71e2bbee792118364ee5/libs/androidfw/LocaleData.cpp
 */
object LocaleData {
    fun localeDataComputeScript(out: ByteArray, language: ByteArray, region: ByteArray) {
        require(out.size == 4)
        if (language[0] == 0.toByte()) {
            out.fill(0)
            return
        }

        var lookupKey = packLocale(language, region)
        var lookupResult = LocaleDataTables.LIKELY_SCRIPTS[lookupKey]
        if (lookupResult == null) {
            // We couldn't find the locale. Let's try without the region
            if (region[0] != 0.toByte()) {
                lookupKey = dropRegion(lookupKey)
                lookupResult = LocaleDataTables.LIKELY_SCRIPTS[lookupKey]
                if (lookupResult != null) {
                    LocaleDataTables.SCRIPT_CODES[lookupResult.toInt()].copyInto(out)
                    return
                }
            }
            // We don't know anything about the locale
            out.fill(0)
            return
        } else {
            // We found the locale
            LocaleDataTables.SCRIPT_CODES[lookupResult.toInt()].copyInto(out)
        }
    }

    /**
     * https://cs.android.com/android/platform/superproject/+/master:frameworks/base/libs/androidfw/LocaleData.cpp;drc=master;l=31
     * Locale is stored as [bigendian](https://android.googlesource.com/platform/frameworks/base/+/1ebfd58606de1bb504063cf8a6de58bdcf1efeaa/libs/androidfw/include/androidfw/ResourceTypes.h#981)
     */
    fun packLocale(language: ByteArray, region: ByteArray): UInt {
        return ((language[0].toUInt() and 0xFFU) shl 24) or ((language[1].toUInt() and 0xFFU) shl 16) or
                ((region[0].toUInt() and 0xFFU) shl 8) or (region[1].toUInt() and 0xFFU)
    }

    val US_SPANISH = 0x65735553U; // es-US
    val MEXICAN_SPANISH = 0x65734D58U; // es-MX
    val LATIN_AMERICAN_SPANISH = 0x6573A424U; // es-419
    fun isSpecialSpanish(language_and_region: UInt): Boolean {
        return language_and_region == US_SPANISH || language_and_region == MEXICAN_SPANISH
    }

    /**
     * https://cs.android.com/android/platform/superproject/+/master:frameworks/base/libs/androidfw/LocaleData.cpp;drc=master;l=135
     */
    fun localeDataCompareRegions(
        leftRegion: ByteArray,
        rightRegion: ByteArray,
        requestedLanguage: ByteArray,
        requestedScript: ByteArray,
        requestedRegion: ByteArray
    ): Int {
        if (leftRegion[0] == rightRegion[0] && leftRegion[1] == rightRegion[1]) {
            return 0
        }

        var left = packLocale(requestedLanguage, leftRegion)
        var right = packLocale(requestedLanguage, rightRegion)
        val request = packLocale(requestedLanguage, requestedRegion)

        // If one and only one of the two locales is a special Spanish locale, we
        // replace it with es-419. We don't do the replacement if the other locale
        // is already es-419, or both locales are special Spanish locales (when
        // es-US is being compared to es-MX).
        val leftIsSpecialSpanish = isSpecialSpanish(left)
        val rightIsSpecialSpanish = isSpecialSpanish(right)
        if (leftIsSpecialSpanish && !rightIsSpecialSpanish && right != LATIN_AMERICAN_SPANISH) {
            left = LATIN_AMERICAN_SPANISH
        } else if (rightIsSpecialSpanish && !leftIsSpecialSpanish && left != LATIN_AMERICAN_SPANISH) {
            right = LATIN_AMERICAN_SPANISH
        }

        val requestAncestors = UIntArray(LocaleDataTables.MAX_PARENT_DEPTH + 1)
        val leftRightIndex = CPointer<Int>(0)
        val leftAndRight = uintArrayOf(left, right)
        val ancestorCount = findAncestors(
            requestAncestors,
            leftRightIndex,
            request,
            requestedScript,
            leftAndRight,
            leftAndRight.size
        )
        if (leftRightIndex.value == 0) { // We saw left earlier
            return 1
        }
        if (leftRightIndex.value == 1) { // We saw right earlier
            return -1
        }

        // If we are here, neither left nor right are an ancestor of the
        // request. This means that all the ancestors have been computed and
        // the last ancestor is just the language by itself. We will use the
        // distance in the parent tree for determining the better match.
        val leftDistance = findDistance(
            left,
            requestedScript,
            requestAncestors,
            ancestorCount
        )
        val rightDistance = findDistance(
            right,
            requestedScript,
            requestAncestors,
            ancestorCount
        )
        if (leftDistance != rightDistance) {
            return rightDistance - leftDistance // smaller distance is better
        }

        // If we are here, left and right are equidistant from the request. We will
        // try and see if any of them is a representative locale.
        val leftIsRepresentative = isRepresentative(left, requestedScript)
        val rightIsRepresentative = isRepresentative(left, requestedScript)
        if (leftIsRepresentative != rightIsRepresentative) {
            fun Boolean.castToIntInC() = if (this) 1 else 0

            return leftIsRepresentative.castToIntInC() - rightIsRepresentative.castToIntInC()
        }

        // We have no way of figuring out which locale is a better match. For
        // the sake of stability, we consider the locale with the lower region
        // code (in dictionary order) better, with two-letter codes before
        // three-digit codes (since two-letter codes are more specific).
        return (right.toULong() - left.toULong()).toInt()
    }

    fun isRepresentative(languageAndRegion: UInt, script: ByteArray): Boolean {
        val packedLocale: ULong = (
                (languageAndRegion.toULong() shl 32) or
                        ((script[0].toULong()) shl 24) or
                        ((script[1].toULong()) shl 16) or
                        ((script[2].toULong()) shl 8) or
                        (script[3].toULong()))
        return packedLocale in LocaleDataTables.REPRESENTATIVE_LOCALES
    }

    /**
     * https://cs.android.com/android/platform/superproject/+/master:frameworks/base/libs/androidfw/LocaleData.cpp;drc=master;l=97
     */
    fun findDistance(supported: UInt, script: ByteArray, requestAncestors: UIntArray, requestAncestorsCount: Int): Int {
        val requestAncestorsIndex = CPointer<Int>(0)
        val supportedAncestorCount = findAncestors(
            null,
            requestAncestorsIndex,
            supported,
            script,
            requestAncestors,
            requestAncestorsCount
        )
        // Since both locales share the same root, there will always be a shared
        // ancestor, so the distance in the parent tree is the sum of the distance
        // of 'supported' to the lowest common ancestor (number of ancestors
        // written for 'supported' minus 1) plus the distance of 'request' to the
        // lowest common ancestor (the index of the ancestor in request_ancestors).
        return supportedAncestorCount + requestAncestorsIndex.value - 1
    }

    val ENGLISH_STOP_LIST = uintArrayOf(
        0x656E0000u, // en
        0x656E8400u, // en-001
    )
    val ENGLISH_CHARS = byteArrayOfChars('e', 'n')
    val LATIN_CHARS = byteArrayOfChars('L', 'a', 't', 'n')
    fun localeDataIsCloseToUsEnglish(region: ByteArray): Boolean {
        val locale: UInt = packLocale(ENGLISH_CHARS, region)
        val stopListIndex = CPointer<Int>(0)
        findAncestors(null, stopListIndex, locale, LATIN_CHARS, ENGLISH_STOP_LIST, 2)
        return stopListIndex.value == 0
    }

    // Find the ancestors of a locale, and fill 'out' with it (assumes out has enough
    // space). If any of the members of stop_list was seen, write it in the
    // output but stop afterwards.
    //
    // This also outputs the index of the last written ancestor in the stop_list
    // to stop_list_index, which will be -1 if it is not found in the stop_list.
    //
    // Returns the number of ancestors written in the output, which is always
    // at least one.
    //
    // (If 'out' is nullptr, we do everything the same way but we simply don't write
    // any results in 'out'.)
    fun findAncestors(
        out: UIntArray?, stopListIndex: CPointer<Int>,
        packedLocale: UInt, script: ByteArray,
        stopList: UIntArray, stopSetLength: Int
    ): Int {
        // TL note: Array indices are Ints, not size_t / long longs, hence stopListIndex is a point to an Int.
        var ancestor: UInt = packedLocale
        var count: Int = 0
        do {
            if (out != null) out[count] = ancestor
            count++
            for (i in 0 until stopSetLength) {
                if (stopList[i] == ancestor) {
                    stopListIndex.value = i
                    return count
                }
            }
            ancestor = findParent(ancestor, script)
        } while (ancestor != LocaleDataTables.PACKED_ROOT)
        stopListIndex.value = -1
        return count
    }

    /**
     * https://cs.android.com/android/platform/superproject/+/master:frameworks/base/libs/androidfw/LocaleData.cpp;drc=master;l=40
     */
    fun hasRegion(packedLocale: UInt) = (packedLocale and 0x0000FFFFu) != 0u
    fun dropRegion(packedLocale: UInt) = packedLocale and 0xFFFF0000u

    /**
     * https://cs.android.com/android/platform/superproject/+/master:frameworks/base/libs/androidfw/LocaleData.cpp;drc=master;l=48
     */
    fun findParent(packedLocale: UInt, script: ByteArray): UInt {
        if (hasRegion(packedLocale)) {
            for (i in 0 until LocaleDataTables.SCRIPT_PARENTS.size) {
                if (script.contentEquals(LocaleDataTables.SCRIPT_PARENTS[i].script)) {
                    val map = LocaleDataTables.SCRIPT_PARENTS[i].map
                    val lookupResult = map[packedLocale]
                    if (lookupResult != null) {
                        return lookupResult
                    }
                    break
                }
            }
            return dropRegion(packedLocale)
        }
        return LocaleDataTables.PACKED_ROOT
    }

    /**
     * Transliterated from
     * https://android.googlesource.com/platform/frameworks/base/+/c6c226327debf1f3fcbd71e2bbee792118364ee5/libs/androidfw/LocaleDataTables.cpp
     */
    private object LocaleDataTables {

        val SCRIPT_CODES by lazy {
            arrayOf<ByteArray>(
                /* 0  */
                byteArrayOfChars('A', 'h', 'o', 'm'),
                /* 1  */
                byteArrayOfChars('A', 'r', 'a', 'b'),
                /* 2  */
                byteArrayOfChars('A', 'r', 'm', 'i'),
                /* 3  */
                byteArrayOfChars('A', 'r', 'm', 'n'),
                /* 4  */
                byteArrayOfChars('A', 'v', 's', 't'),
                /* 5  */
                byteArrayOfChars('B', 'a', 'm', 'u'),
                /* 6  */
                byteArrayOfChars('B', 'a', 's', 's'),
                /* 7  */
                byteArrayOfChars('B', 'e', 'n', 'g'),
                /* 8  */
                byteArrayOfChars('B', 'r', 'a', 'h'),
                /* 9  */
                byteArrayOfChars('C', 'a', 'k', 'm'),
                /* 10 */
                byteArrayOfChars('C', 'a', 'n', 's'),
                /* 11 */
                byteArrayOfChars('C', 'a', 'r', 'i'),
                /* 12 */
                byteArrayOfChars('C', 'h', 'a', 'm'),
                /* 13 */
                byteArrayOfChars('C', 'h', 'e', 'r'),
                /* 14 */
                byteArrayOfChars('C', 'h', 'r', 's'),
                /* 15 */
                byteArrayOfChars('C', 'o', 'p', 't'),
                /* 16 */
                byteArrayOfChars('C', 'p', 'r', 't'),
                /* 17 */
                byteArrayOfChars('C', 'y', 'r', 'l'),
                /* 18 */
                byteArrayOfChars('D', 'e', 'v', 'a'),
                /* 19 */
                byteArrayOfChars('E', 'g', 'y', 'p'),
                /* 20 */
                byteArrayOfChars('E', 't', 'h', 'i'),
                /* 21 */
                byteArrayOfChars('G', 'e', 'o', 'r'),
                /* 22 */
                byteArrayOfChars('G', 'o', 'n', 'g'),
                /* 23 */
                byteArrayOfChars('G', 'o', 'n', 'm'),
                /* 24 */
                byteArrayOfChars('G', 'o', 't', 'h'),
                /* 25 */
                byteArrayOfChars('G', 'r', 'e', 'k'),
                /* 26 */
                byteArrayOfChars('G', 'u', 'j', 'r'),
                /* 27 */
                byteArrayOfChars('G', 'u', 'r', 'u'),
                /* 28 */
                byteArrayOfChars('H', 'a', 'n', 's'),
                /* 29 */
                byteArrayOfChars('H', 'a', 'n', 't'),
                /* 30 */
                byteArrayOfChars('H', 'a', 't', 'r'),
                /* 31 */
                byteArrayOfChars('H', 'e', 'b', 'r'),
                /* 32 */
                byteArrayOfChars('H', 'l', 'u', 'w'),
                /* 33 */
                byteArrayOfChars('H', 'm', 'n', 'g'),
                /* 34 */
                byteArrayOfChars('H', 'm', 'n', 'p'),
                /* 35 */
                byteArrayOfChars('I', 't', 'a', 'l'),
                /* 36 */
                byteArrayOfChars('J', 'p', 'a', 'n'),
                /* 37 */
                byteArrayOfChars('K', 'a', 'l', 'i'),
                /* 38 */
                byteArrayOfChars('K', 'a', 'n', 'a'),
                /* 39 */
                byteArrayOfChars('K', 'h', 'a', 'r'),
                /* 40 */
                byteArrayOfChars('K', 'h', 'm', 'r'),
                /* 41 */
                byteArrayOfChars('K', 'i', 't', 's'),
                /* 42 */
                byteArrayOfChars('K', 'n', 'd', 'a'),
                /* 43 */
                byteArrayOfChars('K', 'o', 'r', 'e'),
                /* 44 */
                byteArrayOfChars('L', 'a', 'n', 'a'),
                /* 45 */
                byteArrayOfChars('L', 'a', 'o', 'o'),
                /* 46 */
                byteArrayOfChars('L', 'a', 't', 'n'),
                /* 47 */
                byteArrayOfChars('L', 'e', 'p', 'c'),
                /* 48 */
                byteArrayOfChars('L', 'i', 'n', 'a'),
                /* 49 */
                byteArrayOfChars('L', 'i', 's', 'u'),
                /* 50 */
                byteArrayOfChars('L', 'y', 'c', 'i'),
                /* 51 */
                byteArrayOfChars('L', 'y', 'd', 'i'),
                /* 52 */
                byteArrayOfChars('M', 'a', 'n', 'd'),
                /* 53 */
                byteArrayOfChars('M', 'a', 'n', 'i'),
                /* 54 */
                byteArrayOfChars('M', 'e', 'r', 'c'),
                /* 55 */
                byteArrayOfChars('M', 'l', 'y', 'm'),
                /* 56 */
                byteArrayOfChars('M', 'o', 'n', 'g'),
                /* 57 */
                byteArrayOfChars('M', 'r', 'o', 'o'),
                /* 58 */
                byteArrayOfChars('M', 'y', 'm', 'r'),
                /* 59 */
                byteArrayOfChars('N', 'a', 'r', 'b'),
                /* 60 */
                byteArrayOfChars('N', 'k', 'o', 'o'),
                /* 61 */
                byteArrayOfChars('N', 's', 'h', 'u'),
                /* 62 */
                byteArrayOfChars('O', 'g', 'a', 'm'),
                /* 63 */
                byteArrayOfChars('O', 'l', 'c', 'k'),
                /* 64 */
                byteArrayOfChars('O', 'r', 'k', 'h'),
                /* 65 */
                byteArrayOfChars('O', 'r', 'y', 'a'),
                /* 66 */
                byteArrayOfChars('O', 's', 'g', 'e'),
                /* 67 */
                byteArrayOfChars('P', 'a', 'u', 'c'),
                /* 68 */
                byteArrayOfChars('P', 'h', 'l', 'i'),
                /* 69 */
                byteArrayOfChars('P', 'h', 'n', 'x'),
                /* 70 */
                byteArrayOfChars('P', 'l', 'r', 'd'),
                /* 71 */
                byteArrayOfChars('P', 'r', 't', 'i'),
                /* 72 */
                byteArrayOfChars('R', 'u', 'n', 'r'),
                /* 73 */
                byteArrayOfChars('S', 'a', 'm', 'r'),
                /* 74 */
                byteArrayOfChars('S', 'a', 'r', 'b'),
                /* 75 */
                byteArrayOfChars('S', 'a', 'u', 'r'),
                /* 76 */
                byteArrayOfChars('S', 'g', 'n', 'w'),
                /* 77 */
                byteArrayOfChars('S', 'i', 'n', 'h'),
                /* 78 */
                byteArrayOfChars('S', 'o', 'g', 'd'),
                /* 79 */
                byteArrayOfChars('S', 'o', 'r', 'a'),
                /* 80 */
                byteArrayOfChars('S', 'o', 'y', 'o'),
                /* 81 */
                byteArrayOfChars('S', 'y', 'r', 'c'),
                /* 82 */
                byteArrayOfChars('T', 'a', 'l', 'e'),
                /* 83 */
                byteArrayOfChars('T', 'a', 'l', 'u'),
                /* 84 */
                byteArrayOfChars('T', 'a', 'm', 'l'),
                /* 85 */
                byteArrayOfChars('T', 'a', 'n', 'g'),
                /* 86 */
                byteArrayOfChars('T', 'a', 'v', 't'),
                /* 87 */
                byteArrayOfChars('T', 'e', 'l', 'u'),
                /* 88 */
                byteArrayOfChars('T', 'f', 'n', 'g'),
                /* 89 */
                byteArrayOfChars('T', 'h', 'a', 'a'),
                /* 90 */
                byteArrayOfChars('T', 'h', 'a', 'i'),
                /* 91 */
                byteArrayOfChars('T', 'i', 'b', 't'),
                /* 92 */
                byteArrayOfChars('U', 'g', 'a', 'r'),
                /* 93 */
                byteArrayOfChars('V', 'a', 'i', 'i'),
                /* 94 */
                byteArrayOfChars('W', 'c', 'h', 'o'),
                /* 95 */
                byteArrayOfChars('X', 'p', 'e', 'o'),
                /* 96 */
                byteArrayOfChars('X', 's', 'u', 'x'),
                /* 97 */
                byteArrayOfChars('Y', 'i', 'i', 'i'),
                /* 98 */
                byteArrayOfChars('~', '~', '~', 'A'),
                /* 99 */
                byteArrayOfChars('~', '~', '~', 'B'),
            )
        }

        val LIKELY_SCRIPTS by lazy {
            mapOf<UInt, UInt>(
                0x61610000u to 46u, // aa -> Latn
                0xA0000000u to 46u, // aai -> Latn
                0xA8000000u to 46u, // aak -> Latn
                0xD0000000u to 46u, // aau -> Latn
                0x61620000u to 17u, // ab -> Cyrl
                0xA0200000u to 46u, // abi -> Latn
                0xC0200000u to 17u, // abq -> Cyrl
                0xC4200000u to 46u, // abr -> Latn
                0xCC200000u to 46u, // abt -> Latn
                0xE0200000u to 46u, // aby -> Latn
                0x8C400000u to 46u, // acd -> Latn
                0x90400000u to 46u, // ace -> Latn
                0x9C400000u to 46u, // ach -> Latn
                0x80600000u to 46u, // ada -> Latn
                0x90600000u to 46u, // ade -> Latn
                0xA4600000u to 46u, // adj -> Latn
                0xBC600000u to 91u, // adp -> Tibt
                0xE0600000u to 17u, // ady -> Cyrl
                0xE4600000u to 46u, // adz -> Latn
                0x61650000u to 4u, // ae -> Avst
                0x84800000u to 1u, // aeb -> Arab
                0xE0800000u to 46u, // aey -> Latn
                0x61660000u to 46u, // af -> Latn
                0x88C00000u to 46u, // agc -> Latn
                0x8CC00000u to 46u, // agd -> Latn
                0x98C00000u to 46u, // agg -> Latn
                0xB0C00000u to 46u, // agm -> Latn
                0xB8C00000u to 46u, // ago -> Latn
                0xC0C00000u to 46u, // agq -> Latn
                0x80E00000u to 46u, // aha -> Latn
                0xACE00000u to 46u, // ahl -> Latn
                0xB8E00000u to 0u, // aho -> Ahom
                0x99200000u to 46u, // ajg -> Latn
                0x616B0000u to 46u, // ak -> Latn
                0xA9400000u to 96u, // akk -> Xsux
                0x81600000u to 46u, // ala -> Latn
                0xA1600000u to 46u, // ali -> Latn
                0xB5600000u to 46u, // aln -> Latn
                0xCD600000u to 17u, // alt -> Cyrl
                0x616D0000u to 20u, // am -> Ethi
                0xB1800000u to 46u, // amm -> Latn
                0xB5800000u to 46u, // amn -> Latn
                0xB9800000u to 46u, // amo -> Latn
                0xBD800000u to 46u, // amp -> Latn
                0x616E0000u to 46u, // an -> Latn
                0x89A00000u to 46u, // anc -> Latn
                0xA9A00000u to 46u, // ank -> Latn
                0xB5A00000u to 46u, // ann -> Latn
                0xE1A00000u to 46u, // any -> Latn
                0xA5C00000u to 46u, // aoj -> Latn
                0xB1C00000u to 46u, // aom -> Latn
                0xE5C00000u to 46u, // aoz -> Latn
                0x89E00000u to 1u, // apc -> Arab
                0x8DE00000u to 1u, // apd -> Arab
                0x91E00000u to 46u, // ape -> Latn
                0xC5E00000u to 46u, // apr -> Latn
                0xC9E00000u to 46u, // aps -> Latn
                0xE5E00000u to 46u, // apz -> Latn
                0x61720000u to 1u, // ar -> Arab
                0x61725842u to 99u, // ar-XB -> ~~~B
                0x8A200000u to 2u, // arc -> Armi
                0x9E200000u to 46u, // arh -> Latn
                0xB6200000u to 46u, // arn -> Latn
                0xBA200000u to 46u, // aro -> Latn
                0xC2200000u to 1u, // arq -> Arab
                0xCA200000u to 1u, // ars -> Arab
                0xE2200000u to 1u, // ary -> Arab
                0xE6200000u to 1u, // arz -> Arab
                0x61730000u to 7u, // as -> Beng
                0x82400000u to 46u, // asa -> Latn
                0x92400000u to 76u, // ase -> Sgnw
                0x9A400000u to 46u, // asg -> Latn
                0xBA400000u to 46u, // aso -> Latn
                0xCE400000u to 46u, // ast -> Latn
                0x82600000u to 46u, // ata -> Latn
                0x9A600000u to 46u, // atg -> Latn
                0xA6600000u to 46u, // atj -> Latn
                0xE2800000u to 46u, // auy -> Latn
                0x61760000u to 17u, // av -> Cyrl
                0xAEA00000u to 1u, // avl -> Arab
                0xB6A00000u to 46u, // avn -> Latn
                0xCEA00000u to 46u, // avt -> Latn
                0xD2A00000u to 46u, // avu -> Latn
                0x82C00000u to 18u, // awa -> Deva
                0x86C00000u to 46u, // awb -> Latn
                0xBAC00000u to 46u, // awo -> Latn
                0xDEC00000u to 46u, // awx -> Latn
                0x61790000u to 46u, // ay -> Latn
                0x87000000u to 46u, // ayb -> Latn
                0x617A0000u to 46u, // az -> Latn
                0x617A4951u to 1u, // az-IQ -> Arab
                0x617A4952u to 1u, // az-IR -> Arab
                0x617A5255u to 17u, // az-RU -> Cyrl
                0x62610000u to 17u, // ba -> Cyrl
                0xAC010000u to 1u, // bal -> Arab
                0xB4010000u to 46u, // ban -> Latn
                0xBC010000u to 18u, // bap -> Deva
                0xC4010000u to 46u, // bar -> Latn
                0xC8010000u to 46u, // bas -> Latn
                0xD4010000u to 46u, // bav -> Latn
                0xDC010000u to 5u, // bax -> Bamu
                0x80210000u to 46u, // bba -> Latn
                0x84210000u to 46u, // bbb -> Latn
                0x88210000u to 46u, // bbc -> Latn
                0x8C210000u to 46u, // bbd -> Latn
                0xA4210000u to 46u, // bbj -> Latn
                0xBC210000u to 46u, // bbp -> Latn
                0xC4210000u to 46u, // bbr -> Latn
                0x94410000u to 46u, // bcf -> Latn
                0x9C410000u to 46u, // bch -> Latn
                0xA0410000u to 46u, // bci -> Latn
                0xB0410000u to 46u, // bcm -> Latn
                0xB4410000u to 46u, // bcn -> Latn
                0xB8410000u to 46u, // bco -> Latn
                0xC0410000u to 20u, // bcq -> Ethi
                0xD0410000u to 46u, // bcu -> Latn
                0x8C610000u to 46u, // bdd -> Latn
                0x62650000u to 17u, // be -> Cyrl
                0x94810000u to 46u, // bef -> Latn
                0x9C810000u to 46u, // beh -> Latn
                0xA4810000u to 1u, // bej -> Arab
                0xB0810000u to 46u, // bem -> Latn
                0xCC810000u to 46u, // bet -> Latn
                0xD8810000u to 46u, // bew -> Latn
                0xDC810000u to 46u, // bex -> Latn
                0xE4810000u to 46u, // bez -> Latn
                0x8CA10000u to 46u, // bfd -> Latn
                0xC0A10000u to 84u, // bfq -> Taml
                0xCCA10000u to 1u, // bft -> Arab
                0xE0A10000u to 18u, // bfy -> Deva
                0x62670000u to 17u, // bg -> Cyrl
                0x88C10000u to 18u, // bgc -> Deva
                0xB4C10000u to 1u, // bgn -> Arab
                0xDCC10000u to 25u, // bgx -> Grek
                0x84E10000u to 18u, // bhb -> Deva
                0x98E10000u to 46u, // bhg -> Latn
                0xA0E10000u to 18u, // bhi -> Deva
                0xACE10000u to 46u, // bhl -> Latn
                0xB8E10000u to 18u, // bho -> Deva
                0xE0E10000u to 46u, // bhy -> Latn
                0x62690000u to 46u, // bi -> Latn
                0x85010000u to 46u, // bib -> Latn
                0x99010000u to 46u, // big -> Latn
                0xA9010000u to 46u, // bik -> Latn
                0xB1010000u to 46u, // bim -> Latn
                0xB5010000u to 46u, // bin -> Latn
                0xB9010000u to 46u, // bio -> Latn
                0xC1010000u to 46u, // biq -> Latn
                0x9D210000u to 46u, // bjh -> Latn
                0xA1210000u to 20u, // bji -> Ethi
                0xA5210000u to 18u, // bjj -> Deva
                0xB5210000u to 46u, // bjn -> Latn
                0xB9210000u to 46u, // bjo -> Latn
                0xC5210000u to 46u, // bjr -> Latn
                0xCD210000u to 46u, // bjt -> Latn
                0xE5210000u to 46u, // bjz -> Latn
                0x89410000u to 46u, // bkc -> Latn
                0xB1410000u to 46u, // bkm -> Latn
                0xC1410000u to 46u, // bkq -> Latn
                0xD1410000u to 46u, // bku -> Latn
                0xD5410000u to 46u, // bkv -> Latn
                0xCD610000u to 86u, // blt -> Tavt
                0x626D0000u to 46u, // bm -> Latn
                0x9D810000u to 46u, // bmh -> Latn
                0xA9810000u to 46u, // bmk -> Latn
                0xC1810000u to 46u, // bmq -> Latn
                0xD1810000u to 46u, // bmu -> Latn
                0x626E0000u to 7u, // bn -> Beng
                0x99A10000u to 46u, // bng -> Latn
                0xB1A10000u to 46u, // bnm -> Latn
                0xBDA10000u to 46u, // bnp -> Latn
                0x626F0000u to 91u, // bo -> Tibt
                0xA5C10000u to 46u, // boj -> Latn
                0xB1C10000u to 46u, // bom -> Latn
                0xB5C10000u to 46u, // bon -> Latn
                0xE1E10000u to 7u, // bpy -> Beng
                0x8A010000u to 46u, // bqc -> Latn
                0xA2010000u to 1u, // bqi -> Arab
                0xBE010000u to 46u, // bqp -> Latn
                0xD6010000u to 46u, // bqv -> Latn
                0x62720000u to 46u, // br -> Latn
                0x82210000u to 18u, // bra -> Deva
                0x9E210000u to 1u, // brh -> Arab
                0xDE210000u to 18u, // brx -> Deva
                0xE6210000u to 46u, // brz -> Latn
                0x62730000u to 46u, // bs -> Latn
                0xA6410000u to 46u, // bsj -> Latn
                0xC2410000u to 6u, // bsq -> Bass
                0xCA410000u to 46u, // bss -> Latn
                0xCE410000u to 20u, // bst -> Ethi
                0xBA610000u to 46u, // bto -> Latn
                0xCE610000u to 46u, // btt -> Latn
                0xD6610000u to 18u, // btv -> Deva
                0x82810000u to 17u, // bua -> Cyrl
                0x8A810000u to 46u, // buc -> Latn
                0x8E810000u to 46u, // bud -> Latn
                0x9A810000u to 46u, // bug -> Latn
                0xAA810000u to 46u, // buk -> Latn
                0xB2810000u to 46u, // bum -> Latn
                0xBA810000u to 46u, // buo -> Latn
                0xCA810000u to 46u, // bus -> Latn
                0xD2810000u to 46u, // buu -> Latn
                0x86A10000u to 46u, // bvb -> Latn
                0x8EC10000u to 46u, // bwd -> Latn
                0xC6C10000u to 46u, // bwr -> Latn
                0x9EE10000u to 46u, // bxh -> Latn
                0x93010000u to 46u, // bye -> Latn
                0xB7010000u to 20u, // byn -> Ethi
                0xC7010000u to 46u, // byr -> Latn
                0xCB010000u to 46u, // bys -> Latn
                0xD7010000u to 46u, // byv -> Latn
                0xDF010000u to 46u, // byx -> Latn
                0x83210000u to 46u, // bza -> Latn
                0x93210000u to 46u, // bze -> Latn
                0x97210000u to 46u, // bzf -> Latn
                0x9F210000u to 46u, // bzh -> Latn
                0xDB210000u to 46u, // bzw -> Latn
                0x63610000u to 46u, // ca -> Latn
                0x8C020000u to 46u, // cad -> Latn
                0xB4020000u to 46u, // can -> Latn
                0xA4220000u to 46u, // cbj -> Latn
                0x9C420000u to 46u, // cch -> Latn
                0xBC420000u to 9u, // ccp -> Cakm
                0x63650000u to 17u, // ce -> Cyrl
                0x84820000u to 46u, // ceb -> Latn
                0x80A20000u to 46u, // cfa -> Latn
                0x98C20000u to 46u, // cgg -> Latn
                0x63680000u to 46u, // ch -> Latn
                0xA8E20000u to 46u, // chk -> Latn
                0xB0E20000u to 17u, // chm -> Cyrl
                0xB8E20000u to 46u, // cho -> Latn
                0xBCE20000u to 46u, // chp -> Latn
                0xC4E20000u to 13u, // chr -> Cher
                0x89020000u to 46u, // cic -> Latn
                0x81220000u to 1u, // cja -> Arab
                0xB1220000u to 12u, // cjm -> Cham
                0xD5220000u to 46u, // cjv -> Latn
                0x85420000u to 1u, // ckb -> Arab
                0xAD420000u to 46u, // ckl -> Latn
                0xB9420000u to 46u, // cko -> Latn
                0xE1420000u to 46u, // cky -> Latn
                0x81620000u to 46u, // cla -> Latn
                0x91820000u to 46u, // cme -> Latn
                0x99820000u to 80u, // cmg -> Soyo
                0x636F0000u to 46u, // co -> Latn
                0xBDC20000u to 15u, // cop -> Copt
                0xC9E20000u to 46u, // cps -> Latn
                0x63720000u to 10u, // cr -> Cans
                0x9E220000u to 17u, // crh -> Cyrl
                0xA6220000u to 10u, // crj -> Cans
                0xAA220000u to 10u, // crk -> Cans
                0xAE220000u to 10u, // crl -> Cans
                0xB2220000u to 10u, // crm -> Cans
                0xCA220000u to 46u, // crs -> Latn
                0x63730000u to 46u, // cs -> Latn
                0x86420000u to 46u, // csb -> Latn
                0xDA420000u to 10u, // csw -> Cans
                0x8E620000u to 67u, // ctd -> Pauc
                0x63750000u to 17u, // cu -> Cyrl
                0x63760000u to 17u, // cv -> Cyrl
                0x63790000u to 46u, // cy -> Latn
                0x64610000u to 46u, // da -> Latn
                0x8C030000u to 46u, // dad -> Latn
                0x94030000u to 46u, // daf -> Latn
                0x98030000u to 46u, // dag -> Latn
                0x9C030000u to 46u, // dah -> Latn
                0xA8030000u to 46u, // dak -> Latn
                0xC4030000u to 17u, // dar -> Cyrl
                0xD4030000u to 46u, // dav -> Latn
                0x8C230000u to 46u, // dbd -> Latn
                0xC0230000u to 46u, // dbq -> Latn
                0x88430000u to 1u, // dcc -> Arab
                0xB4630000u to 46u, // ddn -> Latn
                0x64650000u to 46u, // de -> Latn
                0x8C830000u to 46u, // ded -> Latn
                0xB4830000u to 46u, // den -> Latn
                0x80C30000u to 46u, // dga -> Latn
                0x9CC30000u to 46u, // dgh -> Latn
                0xA0C30000u to 46u, // dgi -> Latn
                0xACC30000u to 1u, // dgl -> Arab
                0xC4C30000u to 46u, // dgr -> Latn
                0xE4C30000u to 46u, // dgz -> Latn
                0x81030000u to 46u, // dia -> Latn
                0x91230000u to 46u, // dje -> Latn
                0xA5A30000u to 46u, // dnj -> Latn
                0x85C30000u to 46u, // dob -> Latn
                0xA1C30000u to 18u, // doi -> Deva
                0xBDC30000u to 46u, // dop -> Latn
                0xD9C30000u to 46u, // dow -> Latn
                0x9E230000u to 56u, // drh -> Mong
                0xA2230000u to 46u, // dri -> Latn
                0xCA230000u to 20u, // drs -> Ethi
                0x86430000u to 46u, // dsb -> Latn
                0xB2630000u to 46u, // dtm -> Latn
                0xBE630000u to 46u, // dtp -> Latn
                0xCA630000u to 46u, // dts -> Latn
                0xE2630000u to 18u, // dty -> Deva
                0x82830000u to 46u, // dua -> Latn
                0x8A830000u to 46u, // duc -> Latn
                0x8E830000u to 46u, // dud -> Latn
                0x9A830000u to 46u, // dug -> Latn
                0x64760000u to 89u, // dv -> Thaa
                0x82A30000u to 46u, // dva -> Latn
                0xDAC30000u to 46u, // dww -> Latn
                0xBB030000u to 46u, // dyo -> Latn
                0xD3030000u to 46u, // dyu -> Latn
                0x647A0000u to 91u, // dz -> Tibt
                0x9B230000u to 46u, // dzg -> Latn
                0xD0240000u to 46u, // ebu -> Latn
                0x65650000u to 46u, // ee -> Latn
                0xA0A40000u to 46u, // efi -> Latn
                0xACC40000u to 46u, // egl -> Latn
                0xE0C40000u to 19u, // egy -> Egyp
                0x81440000u to 46u, // eka -> Latn
                0xE1440000u to 37u, // eky -> Kali
                0x656C0000u to 25u, // el -> Grek
                0x81840000u to 46u, // ema -> Latn
                0xA1840000u to 46u, // emi -> Latn
                0x656E0000u to 46u, // en -> Latn
                0x656E5841u to 98u, // en-XA -> ~~~A
                0xB5A40000u to 46u, // enn -> Latn
                0xC1A40000u to 46u, // enq -> Latn
                0x656F0000u to 46u, // eo -> Latn
                0xA2240000u to 46u, // eri -> Latn
                0x65730000u to 46u, // es -> Latn
                0x9A440000u to 23u, // esg -> Gonm
                0xD2440000u to 46u, // esu -> Latn
                0x65740000u to 46u, // et -> Latn
                0xC6640000u to 46u, // etr -> Latn
                0xCE640000u to 35u, // ett -> Ital
                0xD2640000u to 46u, // etu -> Latn
                0xDE640000u to 46u, // etx -> Latn
                0x65750000u to 46u, // eu -> Latn
                0xBAC40000u to 46u, // ewo -> Latn
                0xCEE40000u to 46u, // ext -> Latn
                0x83240000u to 46u, // eza -> Latn
                0x66610000u to 1u, // fa -> Arab
                0x80050000u to 46u, // faa -> Latn
                0x84050000u to 46u, // fab -> Latn
                0x98050000u to 46u, // fag -> Latn
                0xA0050000u to 46u, // fai -> Latn
                0xB4050000u to 46u, // fan -> Latn
                0x66660000u to 46u, // ff -> Latn
                0xA0A50000u to 46u, // ffi -> Latn
                0xB0A50000u to 46u, // ffm -> Latn
                0x66690000u to 46u, // fi -> Latn
                0x81050000u to 1u, // fia -> Arab
                0xAD050000u to 46u, // fil -> Latn
                0xCD050000u to 46u, // fit -> Latn
                0x666A0000u to 46u, // fj -> Latn
                0xC5650000u to 46u, // flr -> Latn
                0xBD850000u to 46u, // fmp -> Latn
                0x666F0000u to 46u, // fo -> Latn
                0x8DC50000u to 46u, // fod -> Latn
                0xB5C50000u to 46u, // fon -> Latn
                0xC5C50000u to 46u, // for -> Latn
                0x91E50000u to 46u, // fpe -> Latn
                0xCA050000u to 46u, // fqs -> Latn
                0x66720000u to 46u, // fr -> Latn
                0x8A250000u to 46u, // frc -> Latn
                0xBE250000u to 46u, // frp -> Latn
                0xC6250000u to 46u, // frr -> Latn
                0xCA250000u to 46u, // frs -> Latn
                0x86850000u to 1u, // fub -> Arab
                0x8E850000u to 46u, // fud -> Latn
                0x92850000u to 46u, // fue -> Latn
                0x96850000u to 46u, // fuf -> Latn
                0x9E850000u to 46u, // fuh -> Latn
                0xC2850000u to 46u, // fuq -> Latn
                0xC6850000u to 46u, // fur -> Latn
                0xD6850000u to 46u, // fuv -> Latn
                0xE2850000u to 46u, // fuy -> Latn
                0xC6A50000u to 46u, // fvr -> Latn
                0x66790000u to 46u, // fy -> Latn
                0x67610000u to 46u, // ga -> Latn
                0x80060000u to 46u, // gaa -> Latn
                0x94060000u to 46u, // gaf -> Latn
                0x98060000u to 46u, // gag -> Latn
                0x9C060000u to 46u, // gah -> Latn
                0xA4060000u to 46u, // gaj -> Latn
                0xB0060000u to 46u, // gam -> Latn
                0xB4060000u to 28u, // gan -> Hans
                0xD8060000u to 46u, // gaw -> Latn
                0xE0060000u to 46u, // gay -> Latn
                0x80260000u to 46u, // gba -> Latn
                0x94260000u to 46u, // gbf -> Latn
                0xB0260000u to 18u, // gbm -> Deva
                0xE0260000u to 46u, // gby -> Latn
                0xE4260000u to 1u, // gbz -> Arab
                0xC4460000u to 46u, // gcr -> Latn
                0x67640000u to 46u, // gd -> Latn
                0x90660000u to 46u, // gde -> Latn
                0xB4660000u to 46u, // gdn -> Latn
                0xC4660000u to 46u, // gdr -> Latn
                0x84860000u to 46u, // geb -> Latn
                0xA4860000u to 46u, // gej -> Latn
                0xAC860000u to 46u, // gel -> Latn
                0xE4860000u to 20u, // gez -> Ethi
                0xA8A60000u to 46u, // gfk -> Latn
                0xB4C60000u to 18u, // ggn -> Deva
                0xC8E60000u to 46u, // ghs -> Latn
                0xAD060000u to 46u, // gil -> Latn
                0xB1060000u to 46u, // gim -> Latn
                0xA9260000u to 1u, // gjk -> Arab
                0xB5260000u to 46u, // gjn -> Latn
                0xD1260000u to 1u, // gju -> Arab
                0xB5460000u to 46u, // gkn -> Latn
                0xBD460000u to 46u, // gkp -> Latn
                0x676C0000u to 46u, // gl -> Latn
                0xA9660000u to 1u, // glk -> Arab
                0xB1860000u to 46u, // gmm -> Latn
                0xD5860000u to 20u, // gmv -> Ethi
                0x676E0000u to 46u, // gn -> Latn
                0x8DA60000u to 46u, // gnd -> Latn
                0x99A60000u to 46u, // gng -> Latn
                0x8DC60000u to 46u, // god -> Latn
                0x95C60000u to 20u, // gof -> Ethi
                0xA1C60000u to 46u, // goi -> Latn
                0xB1C60000u to 18u, // gom -> Deva
                0xB5C60000u to 87u, // gon -> Telu
                0xC5C60000u to 46u, // gor -> Latn
                0xC9C60000u to 46u, // gos -> Latn
                0xCDC60000u to 24u, // got -> Goth
                0x86260000u to 46u, // grb -> Latn
                0x8A260000u to 16u, // grc -> Cprt
                0xCE260000u to 7u, // grt -> Beng
                0xDA260000u to 46u, // grw -> Latn
                0xDA460000u to 46u, // gsw -> Latn
                0x67750000u to 26u, // gu -> Gujr
                0x86860000u to 46u, // gub -> Latn
                0x8A860000u to 46u, // guc -> Latn
                0x8E860000u to 46u, // gud -> Latn
                0xC6860000u to 46u, // gur -> Latn
                0xDA860000u to 46u, // guw -> Latn
                0xDE860000u to 46u, // gux -> Latn
                0xE6860000u to 46u, // guz -> Latn
                0x67760000u to 46u, // gv -> Latn
                0x96A60000u to 46u, // gvf -> Latn
                0xC6A60000u to 18u, // gvr -> Deva
                0xCAA60000u to 46u, // gvs -> Latn
                0x8AC60000u to 1u, // gwc -> Arab
                0xA2C60000u to 46u, // gwi -> Latn
                0xCEC60000u to 1u, // gwt -> Arab
                0xA3060000u to 46u, // gyi -> Latn
                0x68610000u to 46u, // ha -> Latn
                0x6861434Du to 1u, // ha-CM -> Arab
                0x68615344u to 1u, // ha-SD -> Arab
                0x98070000u to 46u, // hag -> Latn
                0xA8070000u to 28u, // hak -> Hans
                0xB0070000u to 46u, // ham -> Latn
                0xD8070000u to 46u, // haw -> Latn
                0xE4070000u to 1u, // haz -> Arab
                0x84270000u to 46u, // hbb -> Latn
                0xE0670000u to 20u, // hdy -> Ethi
                0x68650000u to 31u, // he -> Hebr
                0xE0E70000u to 46u, // hhy -> Latn
                0x68690000u to 18u, // hi -> Deva
                0x81070000u to 46u, // hia -> Latn
                0x95070000u to 46u, // hif -> Latn
                0x99070000u to 46u, // hig -> Latn
                0x9D070000u to 46u, // hih -> Latn
                0xAD070000u to 46u, // hil -> Latn
                0x81670000u to 46u, // hla -> Latn
                0xD1670000u to 32u, // hlu -> Hluw
                0x8D870000u to 70u, // hmd -> Plrd
                0xCD870000u to 46u, // hmt -> Latn
                0x8DA70000u to 1u, // hnd -> Arab
                0x91A70000u to 18u, // hne -> Deva
                0xA5A70000u to 33u, // hnj -> Hmng
                0xB5A70000u to 46u, // hnn -> Latn
                0xB9A70000u to 1u, // hno -> Arab
                0x686F0000u to 46u, // ho -> Latn
                0x89C70000u to 18u, // hoc -> Deva
                0xA5C70000u to 18u, // hoj -> Deva
                0xCDC70000u to 46u, // hot -> Latn
                0x68720000u to 46u, // hr -> Latn
                0x86470000u to 46u, // hsb -> Latn
                0xB6470000u to 28u, // hsn -> Hans
                0x68740000u to 46u, // ht -> Latn
                0x68750000u to 46u, // hu -> Latn
                0xA2870000u to 46u, // hui -> Latn
                0x68790000u to 3u, // hy -> Armn
                0x687A0000u to 46u, // hz -> Latn
                0x69610000u to 46u, // ia -> Latn
                0xB4080000u to 46u, // ian -> Latn
                0xC4080000u to 46u, // iar -> Latn
                0x80280000u to 46u, // iba -> Latn
                0x84280000u to 46u, // ibb -> Latn
                0xE0280000u to 46u, // iby -> Latn
                0x80480000u to 46u, // ica -> Latn
                0x9C480000u to 46u, // ich -> Latn
                0x69640000u to 46u, // id -> Latn
                0x8C680000u to 46u, // idd -> Latn
                0xA0680000u to 46u, // idi -> Latn
                0xD0680000u to 46u, // idu -> Latn
                0x90A80000u to 46u, // ife -> Latn
                0x69670000u to 46u, // ig -> Latn
                0x84C80000u to 46u, // igb -> Latn
                0x90C80000u to 46u, // ige -> Latn
                0x69690000u to 97u, // ii -> Yiii
                0xA5280000u to 46u, // ijj -> Latn
                0x696B0000u to 46u, // ik -> Latn
                0xA9480000u to 46u, // ikk -> Latn
                0xCD480000u to 46u, // ikt -> Latn
                0xD9480000u to 46u, // ikw -> Latn
                0xDD480000u to 46u, // ikx -> Latn
                0xB9680000u to 46u, // ilo -> Latn
                0xB9880000u to 46u, // imo -> Latn
                0x696E0000u to 46u, // in -> Latn
                0x9DA80000u to 17u, // inh -> Cyrl
                0x696F0000u to 46u, // io -> Latn
                0xD1C80000u to 46u, // iou -> Latn
                0xA2280000u to 46u, // iri -> Latn
                0x69730000u to 46u, // is -> Latn
                0x69740000u to 46u, // it -> Latn
                0x69750000u to 10u, // iu -> Cans
                0x69770000u to 31u, // iw -> Hebr
                0xB2C80000u to 46u, // iwm -> Latn
                0xCAC80000u to 46u, // iws -> Latn
                0x9F280000u to 46u, // izh -> Latn
                0xA3280000u to 46u, // izi -> Latn
                0x6A610000u to 36u, // ja -> Jpan
                0x84090000u to 46u, // jab -> Latn
                0xB0090000u to 46u, // jam -> Latn
                0xC4090000u to 46u, // jar -> Latn
                0xB8290000u to 46u, // jbo -> Latn
                0xD0290000u to 46u, // jbu -> Latn
                0xB4890000u to 46u, // jen -> Latn
                0xA8C90000u to 46u, // jgk -> Latn
                0xB8C90000u to 46u, // jgo -> Latn
                0x6A690000u to 31u, // ji -> Hebr
                0x85090000u to 46u, // jib -> Latn
                0x89890000u to 46u, // jmc -> Latn
                0xAD890000u to 18u, // jml -> Deva
                0x82290000u to 46u, // jra -> Latn
                0xCE890000u to 46u, // jut -> Latn
                0x6A760000u to 46u, // jv -> Latn
                0x6A770000u to 46u, // jw -> Latn
                0x6B610000u to 21u, // ka -> Geor
                0x800A0000u to 17u, // kaa -> Cyrl
                0x840A0000u to 46u, // kab -> Latn
                0x880A0000u to 46u, // kac -> Latn
                0x8C0A0000u to 46u, // kad -> Latn
                0xA00A0000u to 46u, // kai -> Latn
                0xA40A0000u to 46u, // kaj -> Latn
                0xB00A0000u to 46u, // kam -> Latn
                0xB80A0000u to 46u, // kao -> Latn
                0x8C2A0000u to 17u, // kbd -> Cyrl
                0xB02A0000u to 46u, // kbm -> Latn
                0xBC2A0000u to 46u, // kbp -> Latn
                0xC02A0000u to 46u, // kbq -> Latn
                0xDC2A0000u to 46u, // kbx -> Latn
                0xE02A0000u to 1u, // kby -> Arab
                0x984A0000u to 46u, // kcg -> Latn
                0xA84A0000u to 46u, // kck -> Latn
                0xAC4A0000u to 46u, // kcl -> Latn
                0xCC4A0000u to 46u, // kct -> Latn
                0x906A0000u to 46u, // kde -> Latn
                0x9C6A0000u to 1u, // kdh -> Arab
                0xAC6A0000u to 46u, // kdl -> Latn
                0xCC6A0000u to 90u, // kdt -> Thai
                0x808A0000u to 46u, // kea -> Latn
                0xB48A0000u to 46u, // ken -> Latn
                0xE48A0000u to 46u, // kez -> Latn
                0xB8AA0000u to 46u, // kfo -> Latn
                0xC4AA0000u to 18u, // kfr -> Deva
                0xE0AA0000u to 18u, // kfy -> Deva
                0x6B670000u to 46u, // kg -> Latn
                0x90CA0000u to 46u, // kge -> Latn
                0x94CA0000u to 46u, // kgf -> Latn
                0xBCCA0000u to 46u, // kgp -> Latn
                0x80EA0000u to 46u, // kha -> Latn
                0x84EA0000u to 83u, // khb -> Talu
                0xB4EA0000u to 18u, // khn -> Deva
                0xC0EA0000u to 46u, // khq -> Latn
                0xC8EA0000u to 46u, // khs -> Latn
                0xCCEA0000u to 58u, // kht -> Mymr
                0xD8EA0000u to 1u, // khw -> Arab
                0xE4EA0000u to 46u, // khz -> Latn
                0x6B690000u to 46u, // ki -> Latn
                0xA50A0000u to 46u, // kij -> Latn
                0xD10A0000u to 46u, // kiu -> Latn
                0xD90A0000u to 46u, // kiw -> Latn
                0x6B6A0000u to 46u, // kj -> Latn
                0x8D2A0000u to 46u, // kjd -> Latn
                0x992A0000u to 45u, // kjg -> Laoo
                0xC92A0000u to 46u, // kjs -> Latn
                0xE12A0000u to 46u, // kjy -> Latn
                0x6B6B0000u to 17u, // kk -> Cyrl
                0x6B6B4146u to 1u, // kk-AF -> Arab
                0x6B6B434Eu to 1u, // kk-CN -> Arab
                0x6B6B4952u to 1u, // kk-IR -> Arab
                0x6B6B4D4Eu to 1u, // kk-MN -> Arab
                0x894A0000u to 46u, // kkc -> Latn
                0xA54A0000u to 46u, // kkj -> Latn
                0x6B6C0000u to 46u, // kl -> Latn
                0xB56A0000u to 46u, // kln -> Latn
                0xC16A0000u to 46u, // klq -> Latn
                0xCD6A0000u to 46u, // klt -> Latn
                0xDD6A0000u to 46u, // klx -> Latn
                0x6B6D0000u to 40u, // km -> Khmr
                0x858A0000u to 46u, // kmb -> Latn
                0x9D8A0000u to 46u, // kmh -> Latn
                0xB98A0000u to 46u, // kmo -> Latn
                0xC98A0000u to 46u, // kms -> Latn
                0xD18A0000u to 46u, // kmu -> Latn
                0xD98A0000u to 46u, // kmw -> Latn
                0x6B6E0000u to 42u, // kn -> Knda
                0x95AA0000u to 46u, // knf -> Latn
                0xBDAA0000u to 46u, // knp -> Latn
                0x6B6F0000u to 43u, // ko -> Kore
                0xA1CA0000u to 17u, // koi -> Cyrl
                0xA9CA0000u to 18u, // kok -> Deva
                0xADCA0000u to 46u, // kol -> Latn
                0xC9CA0000u to 46u, // kos -> Latn
                0xE5CA0000u to 46u, // koz -> Latn
                0x91EA0000u to 46u, // kpe -> Latn
                0x95EA0000u to 46u, // kpf -> Latn
                0xB9EA0000u to 46u, // kpo -> Latn
                0xC5EA0000u to 46u, // kpr -> Latn
                0xDDEA0000u to 46u, // kpx -> Latn
                0x860A0000u to 46u, // kqb -> Latn
                0x960A0000u to 46u, // kqf -> Latn
                0xCA0A0000u to 46u, // kqs -> Latn
                0xE20A0000u to 20u, // kqy -> Ethi
                0x6B720000u to 46u, // kr -> Latn
                0x8A2A0000u to 17u, // krc -> Cyrl
                0xA22A0000u to 46u, // kri -> Latn
                0xA62A0000u to 46u, // krj -> Latn
                0xAE2A0000u to 46u, // krl -> Latn
                0xCA2A0000u to 46u, // krs -> Latn
                0xD22A0000u to 18u, // kru -> Deva
                0x6B730000u to 1u, // ks -> Arab
                0x864A0000u to 46u, // ksb -> Latn
                0x8E4A0000u to 46u, // ksd -> Latn
                0x964A0000u to 46u, // ksf -> Latn
                0x9E4A0000u to 46u, // ksh -> Latn
                0xA64A0000u to 46u, // ksj -> Latn
                0xC64A0000u to 46u, // ksr -> Latn
                0x866A0000u to 20u, // ktb -> Ethi
                0xB26A0000u to 46u, // ktm -> Latn
                0xBA6A0000u to 46u, // kto -> Latn
                0xC66A0000u to 46u, // ktr -> Latn
                0x6B750000u to 46u, // ku -> Latn
                0x6B754952u to 1u, // ku-IR -> Arab
                0x6B754C42u to 1u, // ku-LB -> Arab
                0x868A0000u to 46u, // kub -> Latn
                0x8E8A0000u to 46u, // kud -> Latn
                0x928A0000u to 46u, // kue -> Latn
                0xA68A0000u to 46u, // kuj -> Latn
                0xB28A0000u to 17u, // kum -> Cyrl
                0xB68A0000u to 46u, // kun -> Latn
                0xBE8A0000u to 46u, // kup -> Latn
                0xCA8A0000u to 46u, // kus -> Latn
                0x6B760000u to 17u, // kv -> Cyrl
                0x9AAA0000u to 46u, // kvg -> Latn
                0xC6AA0000u to 46u, // kvr -> Latn
                0xDEAA0000u to 1u, // kvx -> Arab
                0x6B770000u to 46u, // kw -> Latn
                0xA6CA0000u to 46u, // kwj -> Latn
                0xBACA0000u to 46u, // kwo -> Latn
                0xC2CA0000u to 46u, // kwq -> Latn
                0x82EA0000u to 46u, // kxa -> Latn
                0x8AEA0000u to 20u, // kxc -> Ethi
                0x92EA0000u to 46u, // kxe -> Latn
                0xAEEA0000u to 18u, // kxl -> Deva
                0xB2EA0000u to 90u, // kxm -> Thai
                0xBEEA0000u to 1u, // kxp -> Arab
                0xDAEA0000u to 46u, // kxw -> Latn
                0xE6EA0000u to 46u, // kxz -> Latn
                0x6B790000u to 17u, // ky -> Cyrl
                0x6B79434Eu to 1u, // ky-CN -> Arab
                0x6B795452u to 46u, // ky-TR -> Latn
                0x930A0000u to 46u, // kye -> Latn
                0xDF0A0000u to 46u, // kyx -> Latn
                0x9F2A0000u to 1u, // kzh -> Arab
                0xA72A0000u to 46u, // kzj -> Latn
                0xC72A0000u to 46u, // kzr -> Latn
                0xCF2A0000u to 46u, // kzt -> Latn
                0x6C610000u to 46u, // la -> Latn
                0x840B0000u to 48u, // lab -> Lina
                0x8C0B0000u to 31u, // lad -> Hebr
                0x980B0000u to 46u, // lag -> Latn
                0x9C0B0000u to 1u, // lah -> Arab
                0xA40B0000u to 46u, // laj -> Latn
                0xC80B0000u to 46u, // las -> Latn
                0x6C620000u to 46u, // lb -> Latn
                0x902B0000u to 17u, // lbe -> Cyrl
                0xD02B0000u to 46u, // lbu -> Latn
                0xD82B0000u to 46u, // lbw -> Latn
                0xB04B0000u to 46u, // lcm -> Latn
                0xBC4B0000u to 90u, // lcp -> Thai
                0x846B0000u to 46u, // ldb -> Latn
                0x8C8B0000u to 46u, // led -> Latn
                0x908B0000u to 46u, // lee -> Latn
                0xB08B0000u to 46u, // lem -> Latn
                0xBC8B0000u to 47u, // lep -> Lepc
                0xC08B0000u to 46u, // leq -> Latn
                0xD08B0000u to 46u, // leu -> Latn
                0xE48B0000u to 17u, // lez -> Cyrl
                0x6C670000u to 46u, // lg -> Latn
                0x98CB0000u to 46u, // lgg -> Latn
                0x6C690000u to 46u, // li -> Latn
                0x810B0000u to 46u, // lia -> Latn
                0x8D0B0000u to 46u, // lid -> Latn
                0x950B0000u to 18u, // lif -> Deva
                0x990B0000u to 46u, // lig -> Latn
                0x9D0B0000u to 46u, // lih -> Latn
                0xA50B0000u to 46u, // lij -> Latn
                0xC90B0000u to 49u, // lis -> Lisu
                0xBD2B0000u to 46u, // ljp -> Latn
                0xA14B0000u to 1u, // lki -> Arab
                0xCD4B0000u to 46u, // lkt -> Latn
                0x916B0000u to 46u, // lle -> Latn
                0xB56B0000u to 46u, // lln -> Latn
                0xB58B0000u to 87u, // lmn -> Telu
                0xB98B0000u to 46u, // lmo -> Latn
                0xBD8B0000u to 46u, // lmp -> Latn
                0x6C6E0000u to 46u, // ln -> Latn
                0xC9AB0000u to 46u, // lns -> Latn
                0xD1AB0000u to 46u, // lnu -> Latn
                0x6C6F0000u to 45u, // lo -> Laoo
                0xA5CB0000u to 46u, // loj -> Latn
                0xA9CB0000u to 46u, // lok -> Latn
                0xADCB0000u to 46u, // lol -> Latn
                0xC5CB0000u to 46u, // lor -> Latn
                0xC9CB0000u to 46u, // los -> Latn
                0xE5CB0000u to 46u, // loz -> Latn
                0x8A2B0000u to 1u, // lrc -> Arab
                0x6C740000u to 46u, // lt -> Latn
                0x9A6B0000u to 46u, // ltg -> Latn
                0x6C750000u to 46u, // lu -> Latn
                0x828B0000u to 46u, // lua -> Latn
                0xBA8B0000u to 46u, // luo -> Latn
                0xE28B0000u to 46u, // luy -> Latn
                0xE68B0000u to 1u, // luz -> Arab
                0x6C760000u to 46u, // lv -> Latn
                0xAECB0000u to 90u, // lwl -> Thai
                0x9F2B0000u to 28u, // lzh -> Hans
                0xE72B0000u to 46u, // lzz -> Latn
                0x8C0C0000u to 46u, // mad -> Latn
                0x940C0000u to 46u, // maf -> Latn
                0x980C0000u to 18u, // mag -> Deva
                0xA00C0000u to 18u, // mai -> Deva
                0xA80C0000u to 46u, // mak -> Latn
                0xB40C0000u to 46u, // man -> Latn
                0xB40C474Eu to 60u, // man-GN -> Nkoo
                0xC80C0000u to 46u, // mas -> Latn
                0xD80C0000u to 46u, // maw -> Latn
                0xE40C0000u to 46u, // maz -> Latn
                0x9C2C0000u to 46u, // mbh -> Latn
                0xB82C0000u to 46u, // mbo -> Latn
                0xC02C0000u to 46u, // mbq -> Latn
                0xD02C0000u to 46u, // mbu -> Latn
                0xD82C0000u to 46u, // mbw -> Latn
                0xA04C0000u to 46u, // mci -> Latn
                0xBC4C0000u to 46u, // mcp -> Latn
                0xC04C0000u to 46u, // mcq -> Latn
                0xC44C0000u to 46u, // mcr -> Latn
                0xD04C0000u to 46u, // mcu -> Latn
                0x806C0000u to 46u, // mda -> Latn
                0x906C0000u to 1u, // mde -> Arab
                0x946C0000u to 17u, // mdf -> Cyrl
                0x9C6C0000u to 46u, // mdh -> Latn
                0xA46C0000u to 46u, // mdj -> Latn
                0xC46C0000u to 46u, // mdr -> Latn
                0xDC6C0000u to 20u, // mdx -> Ethi
                0x8C8C0000u to 46u, // med -> Latn
                0x908C0000u to 46u, // mee -> Latn
                0xA88C0000u to 46u, // mek -> Latn
                0xB48C0000u to 46u, // men -> Latn
                0xC48C0000u to 46u, // mer -> Latn
                0xCC8C0000u to 46u, // met -> Latn
                0xD08C0000u to 46u, // meu -> Latn
                0x80AC0000u to 1u, // mfa -> Arab
                0x90AC0000u to 46u, // mfe -> Latn
                0xB4AC0000u to 46u, // mfn -> Latn
                0xB8AC0000u to 46u, // mfo -> Latn
                0xC0AC0000u to 46u, // mfq -> Latn
                0x6D670000u to 46u, // mg -> Latn
                0x9CCC0000u to 46u, // mgh -> Latn
                0xACCC0000u to 46u, // mgl -> Latn
                0xB8CC0000u to 46u, // mgo -> Latn
                0xBCCC0000u to 18u, // mgp -> Deva
                0xE0CC0000u to 46u, // mgy -> Latn
                0x6D680000u to 46u, // mh -> Latn
                0xA0EC0000u to 46u, // mhi -> Latn
                0xACEC0000u to 46u, // mhl -> Latn
                0x6D690000u to 46u, // mi -> Latn
                0x950C0000u to 46u, // mif -> Latn
                0xB50C0000u to 46u, // min -> Latn
                0xC90C0000u to 30u, // mis -> Hatr
                0xD90C0000u to 46u, // miw -> Latn
                0x6D6B0000u to 17u, // mk -> Cyrl
                0xA14C0000u to 1u, // mki -> Arab
                0xAD4C0000u to 46u, // mkl -> Latn
                0xBD4C0000u to 46u, // mkp -> Latn
                0xD94C0000u to 46u, // mkw -> Latn
                0x6D6C0000u to 55u, // ml -> Mlym
                0x916C0000u to 46u, // mle -> Latn
                0xBD6C0000u to 46u, // mlp -> Latn
                0xC96C0000u to 46u, // mls -> Latn
                0xB98C0000u to 46u, // mmo -> Latn
                0xD18C0000u to 46u, // mmu -> Latn
                0xDD8C0000u to 46u, // mmx -> Latn
                0x6D6E0000u to 17u, // mn -> Cyrl
                0x6D6E434Eu to 56u, // mn-CN -> Mong
                0x81AC0000u to 46u, // mna -> Latn
                0x95AC0000u to 46u, // mnf -> Latn
                0xA1AC0000u to 7u, // mni -> Beng
                0xD9AC0000u to 58u, // mnw -> Mymr
                0x6D6F0000u to 46u, // mo -> Latn
                0x81CC0000u to 46u, // moa -> Latn
                0x91CC0000u to 46u, // moe -> Latn
                0x9DCC0000u to 46u, // moh -> Latn
                0xC9CC0000u to 46u, // mos -> Latn
                0xDDCC0000u to 46u, // mox -> Latn
                0xBDEC0000u to 46u, // mpp -> Latn
                0xC9EC0000u to 46u, // mps -> Latn
                0xCDEC0000u to 46u, // mpt -> Latn
                0xDDEC0000u to 46u, // mpx -> Latn
                0xAE0C0000u to 46u, // mql -> Latn
                0x6D720000u to 18u, // mr -> Deva
                0x8E2C0000u to 18u, // mrd -> Deva
                0xA62C0000u to 17u, // mrj -> Cyrl
                0xBA2C0000u to 57u, // mro -> Mroo
                0x6D730000u to 46u, // ms -> Latn
                0x6D734343u to 1u, // ms-CC -> Arab
                0x6D740000u to 46u, // mt -> Latn
                0x8A6C0000u to 46u, // mtc -> Latn
                0x966C0000u to 46u, // mtf -> Latn
                0xA26C0000u to 46u, // mti -> Latn
                0xC66C0000u to 18u, // mtr -> Deva
                0x828C0000u to 46u, // mua -> Latn
                0xC68C0000u to 46u, // mur -> Latn
                0xCA8C0000u to 46u, // mus -> Latn
                0x82AC0000u to 46u, // mva -> Latn
                0xB6AC0000u to 46u, // mvn -> Latn
                0xE2AC0000u to 1u, // mvy -> Arab
                0xAACC0000u to 46u, // mwk -> Latn
                0xC6CC0000u to 18u, // mwr -> Deva
                0xD6CC0000u to 46u, // mwv -> Latn
                0xDACC0000u to 34u, // mww -> Hmnp
                0x8AEC0000u to 46u, // mxc -> Latn
                0xB2EC0000u to 46u, // mxm -> Latn
                0x6D790000u to 58u, // my -> Mymr
                0xAB0C0000u to 46u, // myk -> Latn
                0xB30C0000u to 20u, // mym -> Ethi
                0xD70C0000u to 17u, // myv -> Cyrl
                0xDB0C0000u to 46u, // myw -> Latn
                0xDF0C0000u to 46u, // myx -> Latn
                0xE70C0000u to 52u, // myz -> Mand
                0xAB2C0000u to 46u, // mzk -> Latn
                0xB32C0000u to 46u, // mzm -> Latn
                0xB72C0000u to 1u, // mzn -> Arab
                0xBF2C0000u to 46u, // mzp -> Latn
                0xDB2C0000u to 46u, // mzw -> Latn
                0xE72C0000u to 46u, // mzz -> Latn
                0x6E610000u to 46u, // na -> Latn
                0x880D0000u to 46u, // nac -> Latn
                0x940D0000u to 46u, // naf -> Latn
                0xA80D0000u to 46u, // nak -> Latn
                0xB40D0000u to 28u, // nan -> Hans
                0xBC0D0000u to 46u, // nap -> Latn
                0xC00D0000u to 46u, // naq -> Latn
                0xC80D0000u to 46u, // nas -> Latn
                0x6E620000u to 46u, // nb -> Latn
                0x804D0000u to 46u, // nca -> Latn
                0x904D0000u to 46u, // nce -> Latn
                0x944D0000u to 46u, // ncf -> Latn
                0x9C4D0000u to 46u, // nch -> Latn
                0xB84D0000u to 46u, // nco -> Latn
                0xD04D0000u to 46u, // ncu -> Latn
                0x6E640000u to 46u, // nd -> Latn
                0x886D0000u to 46u, // ndc -> Latn
                0xC86D0000u to 46u, // nds -> Latn
                0x6E650000u to 18u, // ne -> Deva
                0x848D0000u to 46u, // neb -> Latn
                0xD88D0000u to 18u, // new -> Deva
                0xDC8D0000u to 46u, // nex -> Latn
                0xC4AD0000u to 46u, // nfr -> Latn
                0x6E670000u to 46u, // ng -> Latn
                0x80CD0000u to 46u, // nga -> Latn
                0x84CD0000u to 46u, // ngb -> Latn
                0xACCD0000u to 46u, // ngl -> Latn
                0x84ED0000u to 46u, // nhb -> Latn
                0x90ED0000u to 46u, // nhe -> Latn
                0xD8ED0000u to 46u, // nhw -> Latn
                0x950D0000u to 46u, // nif -> Latn
                0xA10D0000u to 46u, // nii -> Latn
                0xA50D0000u to 46u, // nij -> Latn
                0xB50D0000u to 46u, // nin -> Latn
                0xD10D0000u to 46u, // niu -> Latn
                0xE10D0000u to 46u, // niy -> Latn
                0xE50D0000u to 46u, // niz -> Latn
                0xB92D0000u to 46u, // njo -> Latn
                0x994D0000u to 46u, // nkg -> Latn
                0xB94D0000u to 46u, // nko -> Latn
                0x6E6C0000u to 46u, // nl -> Latn
                0x998D0000u to 46u, // nmg -> Latn
                0xE58D0000u to 46u, // nmz -> Latn
                0x6E6E0000u to 46u, // nn -> Latn
                0x95AD0000u to 46u, // nnf -> Latn
                0x9DAD0000u to 46u, // nnh -> Latn
                0xA9AD0000u to 46u, // nnk -> Latn
                0xB1AD0000u to 46u, // nnm -> Latn
                0xBDAD0000u to 94u, // nnp -> Wcho
                0x6E6F0000u to 46u, // no -> Latn
                0x8DCD0000u to 44u, // nod -> Lana
                0x91CD0000u to 18u, // noe -> Deva
                0xB5CD0000u to 72u, // non -> Runr
                0xBDCD0000u to 46u, // nop -> Latn
                0xD1CD0000u to 46u, // nou -> Latn
                0xBA0D0000u to 60u, // nqo -> Nkoo
                0x6E720000u to 46u, // nr -> Latn
                0x862D0000u to 46u, // nrb -> Latn
                0xAA4D0000u to 10u, // nsk -> Cans
                0xB64D0000u to 46u, // nsn -> Latn
                0xBA4D0000u to 46u, // nso -> Latn
                0xCA4D0000u to 46u, // nss -> Latn
                0xB26D0000u to 46u, // ntm -> Latn
                0xC66D0000u to 46u, // ntr -> Latn
                0xA28D0000u to 46u, // nui -> Latn
                0xBE8D0000u to 46u, // nup -> Latn
                0xCA8D0000u to 46u, // nus -> Latn
                0xD68D0000u to 46u, // nuv -> Latn
                0xDE8D0000u to 46u, // nux -> Latn
                0x6E760000u to 46u, // nv -> Latn
                0x86CD0000u to 46u, // nwb -> Latn
                0xC2ED0000u to 46u, // nxq -> Latn
                0xC6ED0000u to 46u, // nxr -> Latn
                0x6E790000u to 46u, // ny -> Latn
                0xB30D0000u to 46u, // nym -> Latn
                0xB70D0000u to 46u, // nyn -> Latn
                0xA32D0000u to 46u, // nzi -> Latn
                0x6F630000u to 46u, // oc -> Latn
                0x88CE0000u to 46u, // ogc -> Latn
                0xC54E0000u to 46u, // okr -> Latn
                0xD54E0000u to 46u, // okv -> Latn
                0x6F6D0000u to 46u, // om -> Latn
                0x99AE0000u to 46u, // ong -> Latn
                0xB5AE0000u to 46u, // onn -> Latn
                0xC9AE0000u to 46u, // ons -> Latn
                0xB1EE0000u to 46u, // opm -> Latn
                0x6F720000u to 65u, // or -> Orya
                0xBA2E0000u to 46u, // oro -> Latn
                0xD22E0000u to 1u, // oru -> Arab
                0x6F730000u to 17u, // os -> Cyrl
                0x824E0000u to 66u, // osa -> Osge
                0x826E0000u to 1u, // ota -> Arab
                0xAA6E0000u to 64u, // otk -> Orkh
                0xB32E0000u to 46u, // ozm -> Latn
                0x70610000u to 27u, // pa -> Guru
                0x7061504Bu to 1u, // pa-PK -> Arab
                0x980F0000u to 46u, // pag -> Latn
                0xAC0F0000u to 68u, // pal -> Phli
                0xB00F0000u to 46u, // pam -> Latn
                0xBC0F0000u to 46u, // pap -> Latn
                0xD00F0000u to 46u, // pau -> Latn
                0xA02F0000u to 46u, // pbi -> Latn
                0x8C4F0000u to 46u, // pcd -> Latn
                0xB04F0000u to 46u, // pcm -> Latn
                0x886F0000u to 46u, // pdc -> Latn
                0xCC6F0000u to 46u, // pdt -> Latn
                0x8C8F0000u to 46u, // ped -> Latn
                0xB88F0000u to 95u, // peo -> Xpeo
                0xDC8F0000u to 46u, // pex -> Latn
                0xACAF0000u to 46u, // pfl -> Latn
                0xACEF0000u to 1u, // phl -> Arab
                0xB4EF0000u to 69u, // phn -> Phnx
                0xAD0F0000u to 46u, // pil -> Latn
                0xBD0F0000u to 46u, // pip -> Latn
                0x814F0000u to 8u, // pka -> Brah
                0xB94F0000u to 46u, // pko -> Latn
                0x706C0000u to 46u, // pl -> Latn
                0x816F0000u to 46u, // pla -> Latn
                0xC98F0000u to 46u, // pms -> Latn
                0x99AF0000u to 46u, // png -> Latn
                0xB5AF0000u to 46u, // pnn -> Latn
                0xCDAF0000u to 25u, // pnt -> Grek
                0xB5CF0000u to 46u, // pon -> Latn
                0x81EF0000u to 18u, // ppa -> Deva
                0xB9EF0000u to 46u, // ppo -> Latn
                0x822F0000u to 39u, // pra -> Khar
                0x8E2F0000u to 1u, // prd -> Arab
                0x9A2F0000u to 46u, // prg -> Latn
                0x70730000u to 1u, // ps -> Arab
                0xCA4F0000u to 46u, // pss -> Latn
                0x70740000u to 46u, // pt -> Latn
                0xBE6F0000u to 46u, // ptp -> Latn
                0xD28F0000u to 46u, // puu -> Latn
                0x82CF0000u to 46u, // pwa -> Latn
                0x71750000u to 46u, // qu -> Latn
                0x8A900000u to 46u, // quc -> Latn
                0x9A900000u to 46u, // qug -> Latn
                0xA0110000u to 46u, // rai -> Latn
                0xA4110000u to 18u, // raj -> Deva
                0xB8110000u to 46u, // rao -> Latn
                0x94510000u to 46u, // rcf -> Latn
                0xA4910000u to 46u, // rej -> Latn
                0xAC910000u to 46u, // rel -> Latn
                0xC8910000u to 46u, // res -> Latn
                0xB4D10000u to 46u, // rgn -> Latn
                0x98F10000u to 1u, // rhg -> Arab
                0x81110000u to 46u, // ria -> Latn
                0x95110000u to 88u, // rif -> Tfng
                0x95114E4Cu to 46u, // rif-NL -> Latn
                0xC9310000u to 18u, // rjs -> Deva
                0xCD510000u to 7u, // rkt -> Beng
                0x726D0000u to 46u, // rm -> Latn
                0x95910000u to 46u, // rmf -> Latn
                0xB9910000u to 46u, // rmo -> Latn
                0xCD910000u to 1u, // rmt -> Arab
                0xD1910000u to 46u, // rmu -> Latn
                0x726E0000u to 46u, // rn -> Latn
                0x81B10000u to 46u, // rna -> Latn
                0x99B10000u to 46u, // rng -> Latn
                0x726F0000u to 46u, // ro -> Latn
                0x85D10000u to 46u, // rob -> Latn
                0x95D10000u to 46u, // rof -> Latn
                0xB9D10000u to 46u, // roo -> Latn
                0xBA310000u to 46u, // rro -> Latn
                0xB2710000u to 46u, // rtm -> Latn
                0x72750000u to 17u, // ru -> Cyrl
                0x92910000u to 17u, // rue -> Cyrl
                0x9A910000u to 46u, // rug -> Latn
                0x72770000u to 46u, // rw -> Latn
                0xAAD10000u to 46u, // rwk -> Latn
                0xBAD10000u to 46u, // rwo -> Latn
                0xD3110000u to 38u, // ryu -> Kana
                0x73610000u to 18u, // sa -> Deva
                0x94120000u to 46u, // saf -> Latn
                0x9C120000u to 17u, // sah -> Cyrl
                0xC0120000u to 46u, // saq -> Latn
                0xC8120000u to 46u, // sas -> Latn
                0xCC120000u to 63u, // sat -> Olck
                0xD4120000u to 46u, // sav -> Latn
                0xE4120000u to 75u, // saz -> Saur
                0x80320000u to 46u, // sba -> Latn
                0x90320000u to 46u, // sbe -> Latn
                0xBC320000u to 46u, // sbp -> Latn
                0x73630000u to 46u, // sc -> Latn
                0xA8520000u to 18u, // sck -> Deva
                0xAC520000u to 1u, // scl -> Arab
                0xB4520000u to 46u, // scn -> Latn
                0xB8520000u to 46u, // sco -> Latn
                0xC8520000u to 46u, // scs -> Latn
                0x73640000u to 1u, // sd -> Arab
                0x88720000u to 46u, // sdc -> Latn
                0x9C720000u to 1u, // sdh -> Arab
                0x73650000u to 46u, // se -> Latn
                0x94920000u to 46u, // sef -> Latn
                0x9C920000u to 46u, // seh -> Latn
                0xA0920000u to 46u, // sei -> Latn
                0xC8920000u to 46u, // ses -> Latn
                0x73670000u to 46u, // sg -> Latn
                0x80D20000u to 62u, // sga -> Ogam
                0xC8D20000u to 46u, // sgs -> Latn
                0xD8D20000u to 20u, // sgw -> Ethi
                0xE4D20000u to 46u, // sgz -> Latn
                0x73680000u to 46u, // sh -> Latn
                0xA0F20000u to 88u, // shi -> Tfng
                0xA8F20000u to 46u, // shk -> Latn
                0xB4F20000u to 58u, // shn -> Mymr
                0xD0F20000u to 1u, // shu -> Arab
                0x73690000u to 77u, // si -> Sinh
                0x8D120000u to 46u, // sid -> Latn
                0x99120000u to 46u, // sig -> Latn
                0xAD120000u to 46u, // sil -> Latn
                0xB1120000u to 46u, // sim -> Latn
                0xC5320000u to 46u, // sjr -> Latn
                0x736B0000u to 46u, // sk -> Latn
                0x89520000u to 46u, // skc -> Latn
                0xC5520000u to 1u, // skr -> Arab
                0xC9520000u to 46u, // sks -> Latn
                0x736C0000u to 46u, // sl -> Latn
                0x8D720000u to 46u, // sld -> Latn
                0xA1720000u to 46u, // sli -> Latn
                0xAD720000u to 46u, // sll -> Latn
                0xE1720000u to 46u, // sly -> Latn
                0x736D0000u to 46u, // sm -> Latn
                0x81920000u to 46u, // sma -> Latn
                0xA5920000u to 46u, // smj -> Latn
                0xB5920000u to 46u, // smn -> Latn
                0xBD920000u to 73u, // smp -> Samr
                0xC1920000u to 46u, // smq -> Latn
                0xC9920000u to 46u, // sms -> Latn
                0x736E0000u to 46u, // sn -> Latn
                0x89B20000u to 46u, // snc -> Latn
                0xA9B20000u to 46u, // snk -> Latn
                0xBDB20000u to 46u, // snp -> Latn
                0xDDB20000u to 46u, // snx -> Latn
                0xE1B20000u to 46u, // sny -> Latn
                0x736F0000u to 46u, // so -> Latn
                0x99D20000u to 78u, // sog -> Sogd
                0xA9D20000u to 46u, // sok -> Latn
                0xC1D20000u to 46u, // soq -> Latn
                0xD1D20000u to 90u, // sou -> Thai
                0xE1D20000u to 46u, // soy -> Latn
                0x8DF20000u to 46u, // spd -> Latn
                0xADF20000u to 46u, // spl -> Latn
                0xC9F20000u to 46u, // sps -> Latn
                0x73710000u to 46u, // sq -> Latn
                0x73720000u to 17u, // sr -> Cyrl
                0x73724D45u to 46u, // sr-ME -> Latn
                0x7372524Fu to 46u, // sr-RO -> Latn
                0x73725255u to 46u, // sr-RU -> Latn
                0x73725452u to 46u, // sr-TR -> Latn
                0x86320000u to 79u, // srb -> Sora
                0xB6320000u to 46u, // srn -> Latn
                0xC6320000u to 46u, // srr -> Latn
                0xDE320000u to 18u, // srx -> Deva
                0x73730000u to 46u, // ss -> Latn
                0x8E520000u to 46u, // ssd -> Latn
                0x9A520000u to 46u, // ssg -> Latn
                0xE2520000u to 46u, // ssy -> Latn
                0x73740000u to 46u, // st -> Latn
                0xAA720000u to 46u, // stk -> Latn
                0xC2720000u to 46u, // stq -> Latn
                0x73750000u to 46u, // su -> Latn
                0x82920000u to 46u, // sua -> Latn
                0x92920000u to 46u, // sue -> Latn
                0xAA920000u to 46u, // suk -> Latn
                0xC6920000u to 46u, // sur -> Latn
                0xCA920000u to 46u, // sus -> Latn
                0x73760000u to 46u, // sv -> Latn
                0x73770000u to 46u, // sw -> Latn
                0x86D20000u to 1u, // swb -> Arab
                0x8AD20000u to 46u, // swc -> Latn
                0x9AD20000u to 46u, // swg -> Latn
                0xBED20000u to 46u, // swp -> Latn
                0xD6D20000u to 18u, // swv -> Deva
                0xB6F20000u to 46u, // sxn -> Latn
                0xDAF20000u to 46u, // sxw -> Latn
                0xAF120000u to 7u, // syl -> Beng
                0xC7120000u to 81u, // syr -> Syrc
                0xAF320000u to 46u, // szl -> Latn
                0x74610000u to 84u, // ta -> Taml
                0xA4130000u to 18u, // taj -> Deva
                0xAC130000u to 46u, // tal -> Latn
                0xB4130000u to 46u, // tan -> Latn
                0xC0130000u to 46u, // taq -> Latn
                0x88330000u to 46u, // tbc -> Latn
                0x8C330000u to 46u, // tbd -> Latn
                0x94330000u to 46u, // tbf -> Latn
                0x98330000u to 46u, // tbg -> Latn
                0xB8330000u to 46u, // tbo -> Latn
                0xD8330000u to 46u, // tbw -> Latn
                0xE4330000u to 46u, // tbz -> Latn
                0xA0530000u to 46u, // tci -> Latn
                0xE0530000u to 42u, // tcy -> Knda
                0x8C730000u to 82u, // tdd -> Tale
                0x98730000u to 18u, // tdg -> Deva
                0x9C730000u to 18u, // tdh -> Deva
                0xD0730000u to 46u, // tdu -> Latn
                0x74650000u to 87u, // te -> Telu
                0x8C930000u to 46u, // ted -> Latn
                0xB0930000u to 46u, // tem -> Latn
                0xB8930000u to 46u, // teo -> Latn
                0xCC930000u to 46u, // tet -> Latn
                0xA0B30000u to 46u, // tfi -> Latn
                0x74670000u to 17u, // tg -> Cyrl
                0x7467504Bu to 1u, // tg-PK -> Arab
                0x88D30000u to 46u, // tgc -> Latn
                0xB8D30000u to 46u, // tgo -> Latn
                0xD0D30000u to 46u, // tgu -> Latn
                0x74680000u to 90u, // th -> Thai
                0xACF30000u to 18u, // thl -> Deva
                0xC0F30000u to 18u, // thq -> Deva
                0xC4F30000u to 18u, // thr -> Deva
                0x74690000u to 20u, // ti -> Ethi
                0x95130000u to 46u, // tif -> Latn
                0x99130000u to 20u, // tig -> Ethi
                0xA9130000u to 46u, // tik -> Latn
                0xB1130000u to 46u, // tim -> Latn
                0xB9130000u to 46u, // tio -> Latn
                0xD5130000u to 46u, // tiv -> Latn
                0x746B0000u to 46u, // tk -> Latn
                0xAD530000u to 46u, // tkl -> Latn
                0xC5530000u to 46u, // tkr -> Latn
                0xCD530000u to 18u, // tkt -> Deva
                0x746C0000u to 46u, // tl -> Latn
                0x95730000u to 46u, // tlf -> Latn
                0xDD730000u to 46u, // tlx -> Latn
                0xE1730000u to 46u, // tly -> Latn
                0x9D930000u to 46u, // tmh -> Latn
                0xE1930000u to 46u, // tmy -> Latn
                0x746E0000u to 46u, // tn -> Latn
                0x9DB30000u to 46u, // tnh -> Latn
                0x746F0000u to 46u, // to -> Latn
                0x95D30000u to 46u, // tof -> Latn
                0x99D30000u to 46u, // tog -> Latn
                0xC1D30000u to 46u, // toq -> Latn
                0xA1F30000u to 46u, // tpi -> Latn
                0xB1F30000u to 46u, // tpm -> Latn
                0xE5F30000u to 46u, // tpz -> Latn
                0xBA130000u to 46u, // tqo -> Latn
                0x74720000u to 46u, // tr -> Latn
                0xD2330000u to 46u, // tru -> Latn
                0xD6330000u to 46u, // trv -> Latn
                0xDA330000u to 1u, // trw -> Arab
                0x74730000u to 46u, // ts -> Latn
                0x8E530000u to 25u, // tsd -> Grek
                0x96530000u to 18u, // tsf -> Deva
                0x9A530000u to 46u, // tsg -> Latn
                0xA6530000u to 91u, // tsj -> Tibt
                0xDA530000u to 46u, // tsw -> Latn
                0x74740000u to 17u, // tt -> Cyrl
                0x8E730000u to 46u, // ttd -> Latn
                0x92730000u to 46u, // tte -> Latn
                0xA6730000u to 46u, // ttj -> Latn
                0xC6730000u to 46u, // ttr -> Latn
                0xCA730000u to 90u, // tts -> Thai
                0xCE730000u to 46u, // ttt -> Latn
                0x9E930000u to 46u, // tuh -> Latn
                0xAE930000u to 46u, // tul -> Latn
                0xB2930000u to 46u, // tum -> Latn
                0xC2930000u to 46u, // tuq -> Latn
                0x8EB30000u to 46u, // tvd -> Latn
                0xAEB30000u to 46u, // tvl -> Latn
                0xD2B30000u to 46u, // tvu -> Latn
                0x9ED30000u to 46u, // twh -> Latn
                0xC2D30000u to 46u, // twq -> Latn
                0x9AF30000u to 85u, // txg -> Tang
                0x74790000u to 46u, // ty -> Latn
                0x83130000u to 46u, // tya -> Latn
                0xD7130000u to 17u, // tyv -> Cyrl
                0xB3330000u to 46u, // tzm -> Latn
                0xD0340000u to 46u, // ubu -> Latn
                0xB0740000u to 17u, // udm -> Cyrl
                0x75670000u to 1u, // ug -> Arab
                0x75674B5Au to 17u, // ug-KZ -> Cyrl
                0x75674D4Eu to 17u, // ug-MN -> Cyrl
                0x80D40000u to 92u, // uga -> Ugar
                0x756B0000u to 17u, // uk -> Cyrl
                0xA1740000u to 46u, // uli -> Latn
                0x85940000u to 46u, // umb -> Latn
                0xC5B40000u to 7u, // unr -> Beng
                0xC5B44E50u to 18u, // unr-NP -> Deva
                0xDDB40000u to 7u, // unx -> Beng
                0xA9D40000u to 46u, // uok -> Latn
                0x75720000u to 1u, // ur -> Arab
                0xA2340000u to 46u, // uri -> Latn
                0xCE340000u to 46u, // urt -> Latn
                0xDA340000u to 46u, // urw -> Latn
                0x82540000u to 46u, // usa -> Latn
                0x9E740000u to 46u, // uth -> Latn
                0xC6740000u to 46u, // utr -> Latn
                0x9EB40000u to 46u, // uvh -> Latn
                0xAEB40000u to 46u, // uvl -> Latn
                0x757A0000u to 46u, // uz -> Latn
                0x757A4146u to 1u, // uz-AF -> Arab
                0x757A434Eu to 17u, // uz-CN -> Cyrl
                0x98150000u to 46u, // vag -> Latn
                0xA0150000u to 93u, // vai -> Vaii
                0xB4150000u to 46u, // van -> Latn
                0x76650000u to 46u, // ve -> Latn
                0x88950000u to 46u, // vec -> Latn
                0xBC950000u to 46u, // vep -> Latn
                0x76690000u to 46u, // vi -> Latn
                0x89150000u to 46u, // vic -> Latn
                0xD5150000u to 46u, // viv -> Latn
                0xC9750000u to 46u, // vls -> Latn
                0x95950000u to 46u, // vmf -> Latn
                0xD9950000u to 46u, // vmw -> Latn
                0x766F0000u to 46u, // vo -> Latn
                0xCDD50000u to 46u, // vot -> Latn
                0xBA350000u to 46u, // vro -> Latn
                0xB6950000u to 46u, // vun -> Latn
                0xCE950000u to 46u, // vut -> Latn
                0x77610000u to 46u, // wa -> Latn
                0x90160000u to 46u, // wae -> Latn
                0xA4160000u to 46u, // waj -> Latn
                0xAC160000u to 20u, // wal -> Ethi
                0xB4160000u to 46u, // wan -> Latn
                0xC4160000u to 46u, // war -> Latn
                0xBC360000u to 46u, // wbp -> Latn
                0xC0360000u to 87u, // wbq -> Telu
                0xC4360000u to 18u, // wbr -> Deva
                0xA0560000u to 46u, // wci -> Latn
                0xC4960000u to 46u, // wer -> Latn
                0xA0D60000u to 46u, // wgi -> Latn
                0x98F60000u to 46u, // whg -> Latn
                0x85160000u to 46u, // wib -> Latn
                0xD1160000u to 46u, // wiu -> Latn
                0xD5160000u to 46u, // wiv -> Latn
                0x81360000u to 46u, // wja -> Latn
                0xA1360000u to 46u, // wji -> Latn
                0xC9760000u to 46u, // wls -> Latn
                0xB9960000u to 46u, // wmo -> Latn
                0x89B60000u to 46u, // wnc -> Latn
                0xA1B60000u to 1u, // wni -> Arab
                0xD1B60000u to 46u, // wnu -> Latn
                0x776F0000u to 46u, // wo -> Latn
                0x85D60000u to 46u, // wob -> Latn
                0xC9D60000u to 46u, // wos -> Latn
                0xCA360000u to 46u, // wrs -> Latn
                0x9A560000u to 22u, // wsg -> Gong
                0xAA560000u to 46u, // wsk -> Latn
                0xB2760000u to 18u, // wtm -> Deva
                0xD2960000u to 28u, // wuu -> Hans
                0xD6960000u to 46u, // wuv -> Latn
                0x82D60000u to 46u, // wwa -> Latn
                0xD4170000u to 46u, // xav -> Latn
                0xA0370000u to 46u, // xbi -> Latn
                0xB8570000u to 14u, // xco -> Chrs
                0xC4570000u to 11u, // xcr -> Cari
                0xC8970000u to 46u, // xes -> Latn
                0x78680000u to 46u, // xh -> Latn
                0x81770000u to 46u, // xla -> Latn
                0x89770000u to 50u, // xlc -> Lyci
                0x8D770000u to 51u, // xld -> Lydi
                0x95970000u to 21u, // xmf -> Geor
                0xB5970000u to 53u, // xmn -> Mani
                0xC5970000u to 54u, // xmr -> Merc
                0x81B70000u to 59u, // xna -> Narb
                0xC5B70000u to 18u, // xnr -> Deva
                0x99D70000u to 46u, // xog -> Latn
                0xB5D70000u to 46u, // xon -> Latn
                0xC5F70000u to 71u, // xpr -> Prti
                0x86370000u to 46u, // xrb -> Latn
                0x82570000u to 74u, // xsa -> Sarb
                0xA2570000u to 46u, // xsi -> Latn
                0xB2570000u to 46u, // xsm -> Latn
                0xC6570000u to 18u, // xsr -> Deva
                0x92D70000u to 46u, // xwe -> Latn
                0xB0180000u to 46u, // yam -> Latn
                0xB8180000u to 46u, // yao -> Latn
                0xBC180000u to 46u, // yap -> Latn
                0xC8180000u to 46u, // yas -> Latn
                0xCC180000u to 46u, // yat -> Latn
                0xD4180000u to 46u, // yav -> Latn
                0xE0180000u to 46u, // yay -> Latn
                0xE4180000u to 46u, // yaz -> Latn
                0x80380000u to 46u, // yba -> Latn
                0x84380000u to 46u, // ybb -> Latn
                0xE0380000u to 46u, // yby -> Latn
                0xC4980000u to 46u, // yer -> Latn
                0xC4D80000u to 46u, // ygr -> Latn
                0xD8D80000u to 46u, // ygw -> Latn
                0x79690000u to 31u, // yi -> Hebr
                0xB9580000u to 46u, // yko -> Latn
                0x91780000u to 46u, // yle -> Latn
                0x99780000u to 46u, // ylg -> Latn
                0xAD780000u to 46u, // yll -> Latn
                0xAD980000u to 46u, // yml -> Latn
                0x796F0000u to 46u, // yo -> Latn
                0xB5D80000u to 46u, // yon -> Latn
                0x86380000u to 46u, // yrb -> Latn
                0x92380000u to 46u, // yre -> Latn
                0xAE380000u to 46u, // yrl -> Latn
                0xCA580000u to 46u, // yss -> Latn
                0x82980000u to 46u, // yua -> Latn
                0x92980000u to 29u, // yue -> Hant
                0x9298434Eu to 28u, // yue-CN -> Hans
                0xA6980000u to 46u, // yuj -> Latn
                0xCE980000u to 46u, // yut -> Latn
                0xDA980000u to 46u, // yuw -> Latn
                0x7A610000u to 46u, // za -> Latn
                0x98190000u to 46u, // zag -> Latn
                0xA4790000u to 1u, // zdj -> Arab
                0x80990000u to 46u, // zea -> Latn
                0x9CD90000u to 88u, // zgh -> Tfng
                0x7A680000u to 28u, // zh -> Hans
                0x7A684155u to 29u, // zh-AU -> Hant
                0x7A68424Eu to 29u, // zh-BN -> Hant
                0x7A684742u to 29u, // zh-GB -> Hant
                0x7A684746u to 29u, // zh-GF -> Hant
                0x7A68484Bu to 29u, // zh-HK -> Hant
                0x7A684944u to 29u, // zh-ID -> Hant
                0x7A684D4Fu to 29u, // zh-MO -> Hant
                0x7A685041u to 29u, // zh-PA -> Hant
                0x7A685046u to 29u, // zh-PF -> Hant
                0x7A685048u to 29u, // zh-PH -> Hant
                0x7A685352u to 29u, // zh-SR -> Hant
                0x7A685448u to 29u, // zh-TH -> Hant
                0x7A685457u to 29u, // zh-TW -> Hant
                0x7A685553u to 29u, // zh-US -> Hant
                0x7A68564Eu to 29u, // zh-VN -> Hant
                0xDCF90000u to 61u, // zhx -> Nshu
                0x81190000u to 46u, // zia -> Latn
                0xCD590000u to 41u, // zkt -> Kits
                0xB1790000u to 46u, // zlm -> Latn
                0xA1990000u to 46u, // zmi -> Latn
                0x91B90000u to 46u, // zne -> Latn
                0x7A750000u to 46u, // zu -> Latn
                0x83390000u to 46u, // zza -> Latn
            )
        }

        val REPRESENTATIVE_LOCALES by lazy {
            setOf<ULong>(
                0x616145544C61746EUL, // aa_Latn_ET
                0x616247454379726CUL, // ab_Cyrl_GE
                0xC42047484C61746EUL, // abr_Latn_GH
                0x904049444C61746EUL, // ace_Latn_ID
                0x9C4055474C61746EUL, // ach_Latn_UG
                0x806047484C61746EUL, // ada_Latn_GH
                0xBC60425454696274UL, // adp_Tibt_BT
                0xE06052554379726CUL, // ady_Cyrl_RU
                0x6165495241767374UL, // ae_Avst_IR
                0x8480544E41726162UL, // aeb_Arab_TN
                0x61665A414C61746EUL, // af_Latn_ZA
                0xC0C0434D4C61746EUL, // agq_Latn_CM
                0xB8E0494E41686F6DUL, // aho_Ahom_IN
                0x616B47484C61746EUL, // ak_Latn_GH
                0xA940495158737578UL, // akk_Xsux_IQ
                0xB560584B4C61746EUL, // aln_Latn_XK
                0xCD6052554379726CUL, // alt_Cyrl_RU
                0x616D455445746869UL, // am_Ethi_ET
                0xB9804E474C61746EUL, // amo_Latn_NG
                0x616E45534C61746EUL, // an_Latn_ES
                0xE5C049444C61746EUL, // aoz_Latn_ID
                0x8DE0544741726162UL, // apd_Arab_TG
                0x6172454741726162UL, // ar_Arab_EG
                0x8A20495241726D69UL, // arc_Armi_IR
                0x8A204A4F4E626174UL, // arc_Nbat_JO
                0x8A20535950616C6DUL, // arc_Palm_SY
                0xB620434C4C61746EUL, // arn_Latn_CL
                0xBA20424F4C61746EUL, // aro_Latn_BO
                0xC220445A41726162UL, // arq_Arab_DZ
                0xCA20534141726162UL, // ars_Arab_SA
                0xE2204D4141726162UL, // ary_Arab_MA
                0xE620454741726162UL, // arz_Arab_EG
                0x6173494E42656E67UL, // as_Beng_IN
                0x8240545A4C61746EUL, // asa_Latn_TZ
                0x9240555353676E77UL, // ase_Sgnw_US
                0xCE4045534C61746EUL, // ast_Latn_ES
                0xA66043414C61746EUL, // atj_Latn_CA
                0x617652554379726CUL, // av_Cyrl_RU
                0x82C0494E44657661UL, // awa_Deva_IN
                0x6179424F4C61746EUL, // ay_Latn_BO
                0x617A495241726162UL, // az_Arab_IR
                0x617A415A4C61746EUL, // az_Latn_AZ
                0x626152554379726CUL, // ba_Cyrl_RU
                0xAC01504B41726162UL, // bal_Arab_PK
                0xB40149444C61746EUL, // ban_Latn_ID
                0xBC014E5044657661UL, // bap_Deva_NP
                0xC40141544C61746EUL, // bar_Latn_AT
                0xC801434D4C61746EUL, // bas_Latn_CM
                0xDC01434D42616D75UL, // bax_Bamu_CM
                0x882149444C61746EUL, // bbc_Latn_ID
                0xA421434D4C61746EUL, // bbj_Latn_CM
                0xA04143494C61746EUL, // bci_Latn_CI
                0x626542594379726CUL, // be_Cyrl_BY
                0xA481534441726162UL, // bej_Arab_SD
                0xB0815A4D4C61746EUL, // bem_Latn_ZM
                0xD88149444C61746EUL, // bew_Latn_ID
                0xE481545A4C61746EUL, // bez_Latn_TZ
                0x8CA1434D4C61746EUL, // bfd_Latn_CM
                0xC0A1494E54616D6CUL, // bfq_Taml_IN
                0xCCA1504B41726162UL, // bft_Arab_PK
                0xE0A1494E44657661UL, // bfy_Deva_IN
                0x626742474379726CUL, // bg_Cyrl_BG
                0x88C1494E44657661UL, // bgc_Deva_IN
                0xB4C1504B41726162UL, // bgn_Arab_PK
                0xDCC154524772656BUL, // bgx_Grek_TR
                0x84E1494E44657661UL, // bhb_Deva_IN
                0xA0E1494E44657661UL, // bhi_Deva_IN
                0xB8E1494E44657661UL, // bho_Deva_IN
                0x626956554C61746EUL, // bi_Latn_VU
                0xA90150484C61746EUL, // bik_Latn_PH
                0xB5014E474C61746EUL, // bin_Latn_NG
                0xA521494E44657661UL, // bjj_Deva_IN
                0xB52149444C61746EUL, // bjn_Latn_ID
                0xCD21534E4C61746EUL, // bjt_Latn_SN
                0xB141434D4C61746EUL, // bkm_Latn_CM
                0xD14150484C61746EUL, // bku_Latn_PH
                0xCD61564E54617674UL, // blt_Tavt_VN
                0x626D4D4C4C61746EUL, // bm_Latn_ML
                0xC1814D4C4C61746EUL, // bmq_Latn_ML
                0x626E424442656E67UL, // bn_Beng_BD
                0x626F434E54696274UL, // bo_Tibt_CN
                0xE1E1494E42656E67UL, // bpy_Beng_IN
                0xA201495241726162UL, // bqi_Arab_IR
                0xD60143494C61746EUL, // bqv_Latn_CI
                0x627246524C61746EUL, // br_Latn_FR
                0x8221494E44657661UL, // bra_Deva_IN
                0x9E21504B41726162UL, // brh_Arab_PK
                0xDE21494E44657661UL, // brx_Deva_IN
                0x627342414C61746EUL, // bs_Latn_BA
                0xC2414C5242617373UL, // bsq_Bass_LR
                0xCA41434D4C61746EUL, // bss_Latn_CM
                0xBA6150484C61746EUL, // bto_Latn_PH
                0xD661504B44657661UL, // btv_Deva_PK
                0x828152554379726CUL, // bua_Cyrl_RU
                0x8A8159544C61746EUL, // buc_Latn_YT
                0x9A8149444C61746EUL, // bug_Latn_ID
                0xB281434D4C61746EUL, // bum_Latn_CM
                0x86A147514C61746EUL, // bvb_Latn_GQ
                0xB701455245746869UL, // byn_Ethi_ER
                0xD701434D4C61746EUL, // byv_Latn_CM
                0x93214D4C4C61746EUL, // bze_Latn_ML
                0x636145534C61746EUL, // ca_Latn_ES
                0x8C0255534C61746EUL, // cad_Latn_US
                0x9C424E474C61746EUL, // cch_Latn_NG
                0xBC42424443616B6DUL, // ccp_Cakm_BD
                0x636552554379726CUL, // ce_Cyrl_RU
                0x848250484C61746EUL, // ceb_Latn_PH
                0x98C255474C61746EUL, // cgg_Latn_UG
                0x636847554C61746EUL, // ch_Latn_GU
                0xA8E2464D4C61746EUL, // chk_Latn_FM
                0xB0E252554379726CUL, // chm_Cyrl_RU
                0xB8E255534C61746EUL, // cho_Latn_US
                0xBCE243414C61746EUL, // chp_Latn_CA
                0xC4E2555343686572UL, // chr_Cher_US
                0x890255534C61746EUL, // cic_Latn_US
                0x81224B4841726162UL, // cja_Arab_KH
                0xB122564E4368616DUL, // cjm_Cham_VN
                0x8542495141726162UL, // ckb_Arab_IQ
                0x99824D4E536F796FUL, // cmg_Soyo_MN
                0x636F46524C61746EUL, // co_Latn_FR
                0xBDC24547436F7074UL, // cop_Copt_EG
                0xC9E250484C61746EUL, // cps_Latn_PH
                0x6372434143616E73UL, // cr_Cans_CA
                0x9E2255414379726CUL, // crh_Cyrl_UA
                0xA622434143616E73UL, // crj_Cans_CA
                0xAA22434143616E73UL, // crk_Cans_CA
                0xAE22434143616E73UL, // crl_Cans_CA
                0xB222434143616E73UL, // crm_Cans_CA
                0xCA2253434C61746EUL, // crs_Latn_SC
                0x6373435A4C61746EUL, // cs_Latn_CZ
                0x8642504C4C61746EUL, // csb_Latn_PL
                0xDA42434143616E73UL, // csw_Cans_CA
                0x8E624D4D50617563UL, // ctd_Pauc_MM
                0x637552554379726CUL, // cu_Cyrl_RU
                0x63754247476C6167UL, // cu_Glag_BG
                0x637652554379726CUL, // cv_Cyrl_RU
                0x637947424C61746EUL, // cy_Latn_GB
                0x6461444B4C61746EUL, // da_Latn_DK
                0x940343494C61746EUL, // daf_Latn_CI
                0xA80355534C61746EUL, // dak_Latn_US
                0xC40352554379726CUL, // dar_Cyrl_RU
                0xD4034B454C61746EUL, // dav_Latn_KE
                0x8843494E41726162UL, // dcc_Arab_IN
                0x646544454C61746EUL, // de_Latn_DE
                0xB48343414C61746EUL, // den_Latn_CA
                0xC4C343414C61746EUL, // dgr_Latn_CA
                0x91234E454C61746EUL, // dje_Latn_NE
                0xA5A343494C61746EUL, // dnj_Latn_CI
                0xA1C3494E44657661UL, // doi_Deva_IN
                0x9E23434E4D6F6E67UL, // drh_Mong_CN
                0x864344454C61746EUL, // dsb_Latn_DE
                0xB2634D4C4C61746EUL, // dtm_Latn_ML
                0xBE634D594C61746EUL, // dtp_Latn_MY
                0xE2634E5044657661UL, // dty_Deva_NP
                0x8283434D4C61746EUL, // dua_Latn_CM
                0x64764D5654686161UL, // dv_Thaa_MV
                0xBB03534E4C61746EUL, // dyo_Latn_SN
                0xD30342464C61746EUL, // dyu_Latn_BF
                0x647A425454696274UL, // dz_Tibt_BT
                0xD0244B454C61746EUL, // ebu_Latn_KE
                0x656547484C61746EUL, // ee_Latn_GH
                0xA0A44E474C61746EUL, // efi_Latn_NG
                0xACC449544C61746EUL, // egl_Latn_IT
                0xE0C4454745677970UL, // egy_Egyp_EG
                0xE1444D4D4B616C69UL, // eky_Kali_MM
                0x656C47524772656BUL, // el_Grek_GR
                0x656E47424C61746EUL, // en_Latn_GB
                0x656E55534C61746EUL, // en_Latn_US
                0x656E474253686177UL, // en_Shaw_GB
                0x657345534C61746EUL, // es_Latn_ES
                0x65734D584C61746EUL, // es_Latn_MX
                0x657355534C61746EUL, // es_Latn_US
                0x9A44494E476F6E6DUL, // esg_Gonm_IN
                0xD24455534C61746EUL, // esu_Latn_US
                0x657445454C61746EUL, // et_Latn_EE
                0xCE6449544974616CUL, // ett_Ital_IT
                0x657545534C61746EUL, // eu_Latn_ES
                0xBAC4434D4C61746EUL, // ewo_Latn_CM
                0xCEE445534C61746EUL, // ext_Latn_ES
                0x6661495241726162UL, // fa_Arab_IR
                0xB40547514C61746EUL, // fan_Latn_GQ
                0x6666474E41646C6DUL, // ff_Adlm_GN
                0x6666534E4C61746EUL, // ff_Latn_SN
                0xB0A54D4C4C61746EUL, // ffm_Latn_ML
                0x666946494C61746EUL, // fi_Latn_FI
                0x8105534441726162UL, // fia_Arab_SD
                0xAD0550484C61746EUL, // fil_Latn_PH
                0xCD0553454C61746EUL, // fit_Latn_SE
                0x666A464A4C61746EUL, // fj_Latn_FJ
                0x666F464F4C61746EUL, // fo_Latn_FO
                0xB5C5424A4C61746EUL, // fon_Latn_BJ
                0x667246524C61746EUL, // fr_Latn_FR
                0x8A2555534C61746EUL, // frc_Latn_US
                0xBE2546524C61746EUL, // frp_Latn_FR
                0xC62544454C61746EUL, // frr_Latn_DE
                0xCA2544454C61746EUL, // frs_Latn_DE
                0x8685434D41726162UL, // fub_Arab_CM
                0x8E8557464C61746EUL, // fud_Latn_WF
                0x9685474E4C61746EUL, // fuf_Latn_GN
                0xC2854E454C61746EUL, // fuq_Latn_NE
                0xC68549544C61746EUL, // fur_Latn_IT
                0xD6854E474C61746EUL, // fuv_Latn_NG
                0xC6A553444C61746EUL, // fvr_Latn_SD
                0x66794E4C4C61746EUL, // fy_Latn_NL
                0x676149454C61746EUL, // ga_Latn_IE
                0x800647484C61746EUL, // gaa_Latn_GH
                0x98064D444C61746EUL, // gag_Latn_MD
                0xB406434E48616E73UL, // gan_Hans_CN
                0xE00649444C61746EUL, // gay_Latn_ID
                0xB026494E44657661UL, // gbm_Deva_IN
                0xE426495241726162UL, // gbz_Arab_IR
                0xC44647464C61746EUL, // gcr_Latn_GF
                0x676447424C61746EUL, // gd_Latn_GB
                0xE486455445746869UL, // gez_Ethi_ET
                0xB4C64E5044657661UL, // ggn_Deva_NP
                0xAD064B494C61746EUL, // gil_Latn_KI
                0xA926504B41726162UL, // gjk_Arab_PK
                0xD126504B41726162UL, // gju_Arab_PK
                0x676C45534C61746EUL, // gl_Latn_ES
                0xA966495241726162UL, // glk_Arab_IR
                0x676E50594C61746EUL, // gn_Latn_PY
                0xB1C6494E44657661UL, // gom_Deva_IN
                0xB5C6494E54656C75UL, // gon_Telu_IN
                0xC5C649444C61746EUL, // gor_Latn_ID
                0xC9C64E4C4C61746EUL, // gos_Latn_NL
                0xCDC65541476F7468UL, // got_Goth_UA
                0x8A26435943707274UL, // grc_Cprt_CY
                0x8A2647524C696E62UL, // grc_Linb_GR
                0xCE26494E42656E67UL, // grt_Beng_IN
                0xDA4643484C61746EUL, // gsw_Latn_CH
                0x6775494E47756A72UL, // gu_Gujr_IN
                0x868642524C61746EUL, // gub_Latn_BR
                0x8A86434F4C61746EUL, // guc_Latn_CO
                0xC68647484C61746EUL, // gur_Latn_GH
                0xE6864B454C61746EUL, // guz_Latn_KE
                0x6776494D4C61746EUL, // gv_Latn_IM
                0xC6A64E5044657661UL, // gvr_Deva_NP
                0xA2C643414C61746EUL, // gwi_Latn_CA
                0x68614E474C61746EUL, // ha_Latn_NG
                0xA807434E48616E73UL, // hak_Hans_CN
                0xD80755534C61746EUL, // haw_Latn_US
                0xE407414641726162UL, // haz_Arab_AF
                0x6865494C48656272UL, // he_Hebr_IL
                0x6869494E44657661UL, // hi_Deva_IN
                0x9507464A4C61746EUL, // hif_Latn_FJ
                0xAD0750484C61746EUL, // hil_Latn_PH
                0xD1675452486C7577UL, // hlu_Hluw_TR
                0x8D87434E506C7264UL, // hmd_Plrd_CN
                0x8DA7504B41726162UL, // hnd_Arab_PK
                0x91A7494E44657661UL, // hne_Deva_IN
                0xA5A74C41486D6E67UL, // hnj_Hmng_LA
                0xB5A750484C61746EUL, // hnn_Latn_PH
                0xB9A7504B41726162UL, // hno_Arab_PK
                0x686F50474C61746EUL, // ho_Latn_PG
                0x89C7494E44657661UL, // hoc_Deva_IN
                0xA5C7494E44657661UL, // hoj_Deva_IN
                0x687248524C61746EUL, // hr_Latn_HR
                0x864744454C61746EUL, // hsb_Latn_DE
                0xB647434E48616E73UL, // hsn_Hans_CN
                0x687448544C61746EUL, // ht_Latn_HT
                0x687548554C61746EUL, // hu_Latn_HU
                0x6879414D41726D6EUL, // hy_Armn_AM
                0x687A4E414C61746EUL, // hz_Latn_NA
                0x80284D594C61746EUL, // iba_Latn_MY
                0x84284E474C61746EUL, // ibb_Latn_NG
                0x696449444C61746EUL, // id_Latn_ID
                0x90A854474C61746EUL, // ife_Latn_TG
                0x69674E474C61746EUL, // ig_Latn_NG
                0x6969434E59696969UL, // ii_Yiii_CN
                0x696B55534C61746EUL, // ik_Latn_US
                0xCD4843414C61746EUL, // ikt_Latn_CA
                0xB96850484C61746EUL, // ilo_Latn_PH
                0x696E49444C61746EUL, // in_Latn_ID
                0x9DA852554379726CUL, // inh_Cyrl_RU
                0x697349534C61746EUL, // is_Latn_IS
                0x697449544C61746EUL, // it_Latn_IT
                0x6975434143616E73UL, // iu_Cans_CA
                0x6977494C48656272UL, // iw_Hebr_IL
                0x9F2852554C61746EUL, // izh_Latn_RU
                0x6A614A504A70616EUL, // ja_Jpan_JP
                0xB0094A4D4C61746EUL, // jam_Latn_JM
                0xB8C9434D4C61746EUL, // jgo_Latn_CM
                0x8989545A4C61746EUL, // jmc_Latn_TZ
                0xAD894E5044657661UL, // jml_Deva_NP
                0xCE89444B4C61746EUL, // jut_Latn_DK
                0x6A7649444C61746EUL, // jv_Latn_ID
                0x6A7749444C61746EUL, // jw_Latn_ID
                0x6B61474547656F72UL, // ka_Geor_GE
                0x800A555A4379726CUL, // kaa_Cyrl_UZ
                0x840A445A4C61746EUL, // kab_Latn_DZ
                0x880A4D4D4C61746EUL, // kac_Latn_MM
                0xA40A4E474C61746EUL, // kaj_Latn_NG
                0xB00A4B454C61746EUL, // kam_Latn_KE
                0xB80A4D4C4C61746EUL, // kao_Latn_ML
                0x8C2A52554379726CUL, // kbd_Cyrl_RU
                0xE02A4E4541726162UL, // kby_Arab_NE
                0x984A4E474C61746EUL, // kcg_Latn_NG
                0xA84A5A574C61746EUL, // kck_Latn_ZW
                0x906A545A4C61746EUL, // kde_Latn_TZ
                0x9C6A544741726162UL, // kdh_Arab_TG
                0xCC6A544854686169UL, // kdt_Thai_TH
                0x808A43564C61746EUL, // kea_Latn_CV
                0xB48A434D4C61746EUL, // ken_Latn_CM
                0xB8AA43494C61746EUL, // kfo_Latn_CI
                0xC4AA494E44657661UL, // kfr_Deva_IN
                0xE0AA494E44657661UL, // kfy_Deva_IN
                0x6B6743444C61746EUL, // kg_Latn_CD
                0x90CA49444C61746EUL, // kge_Latn_ID
                0xBCCA42524C61746EUL, // kgp_Latn_BR
                0x80EA494E4C61746EUL, // kha_Latn_IN
                0x84EA434E54616C75UL, // khb_Talu_CN
                0xB4EA494E44657661UL, // khn_Deva_IN
                0xC0EA4D4C4C61746EUL, // khq_Latn_ML
                0xCCEA494E4D796D72UL, // kht_Mymr_IN
                0xD8EA504B41726162UL, // khw_Arab_PK
                0x6B694B454C61746EUL, // ki_Latn_KE
                0xD10A54524C61746EUL, // kiu_Latn_TR
                0x6B6A4E414C61746EUL, // kj_Latn_NA
                0x992A4C414C616F6FUL, // kjg_Laoo_LA
                0x6B6B434E41726162UL, // kk_Arab_CN
                0x6B6B4B5A4379726CUL, // kk_Cyrl_KZ
                0xA54A434D4C61746EUL, // kkj_Latn_CM
                0x6B6C474C4C61746EUL, // kl_Latn_GL
                0xB56A4B454C61746EUL, // kln_Latn_KE
                0x6B6D4B484B686D72UL, // km_Khmr_KH
                0x858A414F4C61746EUL, // kmb_Latn_AO
                0x6B6E494E4B6E6461UL, // kn_Knda_IN
                0x95AA47574C61746EUL, // knf_Latn_GW
                0x6B6F4B524B6F7265UL, // ko_Kore_KR
                0xA1CA52554379726CUL, // koi_Cyrl_RU
                0xA9CA494E44657661UL, // kok_Deva_IN
                0xC9CA464D4C61746EUL, // kos_Latn_FM
                0x91EA4C524C61746EUL, // kpe_Latn_LR
                0x8A2A52554379726CUL, // krc_Cyrl_RU
                0xA22A534C4C61746EUL, // kri_Latn_SL
                0xA62A50484C61746EUL, // krj_Latn_PH
                0xAE2A52554C61746EUL, // krl_Latn_RU
                0xD22A494E44657661UL, // kru_Deva_IN
                0x6B73494E41726162UL, // ks_Arab_IN
                0x864A545A4C61746EUL, // ksb_Latn_TZ
                0x964A434D4C61746EUL, // ksf_Latn_CM
                0x9E4A44454C61746EUL, // ksh_Latn_DE
                0xC66A4D594C61746EUL, // ktr_Latn_MY
                0x6B75495141726162UL, // ku_Arab_IQ
                0x6B7554524C61746EUL, // ku_Latn_TR
                0x6B75474559657A69UL, // ku_Yezi_GE
                0xB28A52554379726CUL, // kum_Cyrl_RU
                0x6B7652554379726CUL, // kv_Cyrl_RU
                0xC6AA49444C61746EUL, // kvr_Latn_ID
                0xDEAA504B41726162UL, // kvx_Arab_PK
                0x6B7747424C61746EUL, // kw_Latn_GB
                0xAEEA494E44657661UL, // kxl_Deva_IN
                0xB2EA544854686169UL, // kxm_Thai_TH
                0xBEEA504B41726162UL, // kxp_Arab_PK
                0x6B79434E41726162UL, // ky_Arab_CN
                0x6B794B474379726CUL, // ky_Cyrl_KG
                0x6B7954524C61746EUL, // ky_Latn_TR
                0xA72A4D594C61746EUL, // kzj_Latn_MY
                0xCF2A4D594C61746EUL, // kzt_Latn_MY
                0x6C6156414C61746EUL, // la_Latn_VA
                0x840B47524C696E61UL, // lab_Lina_GR
                0x8C0B494C48656272UL, // lad_Hebr_IL
                0x980B545A4C61746EUL, // lag_Latn_TZ
                0x9C0B504B41726162UL, // lah_Arab_PK
                0xA40B55474C61746EUL, // laj_Latn_UG
                0x6C624C554C61746EUL, // lb_Latn_LU
                0x902B52554379726CUL, // lbe_Cyrl_RU
                0xD82B49444C61746EUL, // lbw_Latn_ID
                0xBC4B434E54686169UL, // lcp_Thai_CN
                0xBC8B494E4C657063UL, // lep_Lepc_IN
                0xE48B52554379726CUL, // lez_Cyrl_RU
                0x6C6755474C61746EUL, // lg_Latn_UG
                0x6C694E4C4C61746EUL, // li_Latn_NL
                0x950B4E5044657661UL, // lif_Deva_NP
                0x950B494E4C696D62UL, // lif_Limb_IN
                0xA50B49544C61746EUL, // lij_Latn_IT
                0xC90B434E4C697375UL, // lis_Lisu_CN
                0xBD2B49444C61746EUL, // ljp_Latn_ID
                0xA14B495241726162UL, // lki_Arab_IR
                0xCD4B55534C61746EUL, // lkt_Latn_US
                0xB58B494E54656C75UL, // lmn_Telu_IN
                0xB98B49544C61746EUL, // lmo_Latn_IT
                0x6C6E43444C61746EUL, // ln_Latn_CD
                0x6C6F4C414C616F6FUL, // lo_Laoo_LA
                0xADCB43444C61746EUL, // lol_Latn_CD
                0xE5CB5A4D4C61746EUL, // loz_Latn_ZM
                0x8A2B495241726162UL, // lrc_Arab_IR
                0x6C744C544C61746EUL, // lt_Latn_LT
                0x9A6B4C564C61746EUL, // ltg_Latn_LV
                0x6C7543444C61746EUL, // lu_Latn_CD
                0x828B43444C61746EUL, // lua_Latn_CD
                0xBA8B4B454C61746EUL, // luo_Latn_KE
                0xE28B4B454C61746EUL, // luy_Latn_KE
                0xE68B495241726162UL, // luz_Arab_IR
                0x6C764C564C61746EUL, // lv_Latn_LV
                0xAECB544854686169UL, // lwl_Thai_TH
                0x9F2B434E48616E73UL, // lzh_Hans_CN
                0xE72B54524C61746EUL, // lzz_Latn_TR
                0x8C0C49444C61746EUL, // mad_Latn_ID
                0x940C434D4C61746EUL, // maf_Latn_CM
                0x980C494E44657661UL, // mag_Deva_IN
                0xA00C494E44657661UL, // mai_Deva_IN
                0xA80C49444C61746EUL, // mak_Latn_ID
                0xB40C474D4C61746EUL, // man_Latn_GM
                0xB40C474E4E6B6F6FUL, // man_Nkoo_GN
                0xC80C4B454C61746EUL, // mas_Latn_KE
                0xE40C4D584C61746EUL, // maz_Latn_MX
                0x946C52554379726CUL, // mdf_Cyrl_RU
                0x9C6C50484C61746EUL, // mdh_Latn_PH
                0xC46C49444C61746EUL, // mdr_Latn_ID
                0xB48C534C4C61746EUL, // men_Latn_SL
                0xC48C4B454C61746EUL, // mer_Latn_KE
                0x80AC544841726162UL, // mfa_Arab_TH
                0x90AC4D554C61746EUL, // mfe_Latn_MU
                0x6D674D474C61746EUL, // mg_Latn_MG
                0x9CCC4D5A4C61746EUL, // mgh_Latn_MZ
                0xB8CC434D4C61746EUL, // mgo_Latn_CM
                0xBCCC4E5044657661UL, // mgp_Deva_NP
                0xE0CC545A4C61746EUL, // mgy_Latn_TZ
                0x6D684D484C61746EUL, // mh_Latn_MH
                0x6D694E5A4C61746EUL, // mi_Latn_NZ
                0xB50C49444C61746EUL, // min_Latn_ID
                0xC90C495148617472UL, // mis_Hatr_IQ
                0xC90C4E474D656466UL, // mis_Medf_NG
                0x6D6B4D4B4379726CUL, // mk_Cyrl_MK
                0x6D6C494E4D6C796DUL, // ml_Mlym_IN
                0xC96C53444C61746EUL, // mls_Latn_SD
                0x6D6E4D4E4379726CUL, // mn_Cyrl_MN
                0x6D6E434E4D6F6E67UL, // mn_Mong_CN
                0xA1AC494E42656E67UL, // mni_Beng_IN
                0xD9AC4D4D4D796D72UL, // mnw_Mymr_MM
                0x6D6F524F4C61746EUL, // mo_Latn_RO
                0x91CC43414C61746EUL, // moe_Latn_CA
                0x9DCC43414C61746EUL, // moh_Latn_CA
                0xC9CC42464C61746EUL, // mos_Latn_BF
                0x6D72494E44657661UL, // mr_Deva_IN
                0x8E2C4E5044657661UL, // mrd_Deva_NP
                0xA62C52554379726CUL, // mrj_Cyrl_RU
                0xBA2C42444D726F6FUL, // mro_Mroo_BD
                0x6D734D594C61746EUL, // ms_Latn_MY
                0x6D744D544C61746EUL, // mt_Latn_MT
                0xC66C494E44657661UL, // mtr_Deva_IN
                0x828C434D4C61746EUL, // mua_Latn_CM
                0xCA8C55534C61746EUL, // mus_Latn_US
                0xE2AC504B41726162UL, // mvy_Arab_PK
                0xAACC4D4C4C61746EUL, // mwk_Latn_ML
                0xC6CC494E44657661UL, // mwr_Deva_IN
                0xD6CC49444C61746EUL, // mwv_Latn_ID
                0xDACC5553486D6E70UL, // mww_Hmnp_US
                0x8AEC5A574C61746EUL, // mxc_Latn_ZW
                0x6D794D4D4D796D72UL, // my_Mymr_MM
                0xD70C52554379726CUL, // myv_Cyrl_RU
                0xDF0C55474C61746EUL, // myx_Latn_UG
                0xE70C49524D616E64UL, // myz_Mand_IR
                0xB72C495241726162UL, // mzn_Arab_IR
                0x6E614E524C61746EUL, // na_Latn_NR
                0xB40D434E48616E73UL, // nan_Hans_CN
                0xBC0D49544C61746EUL, // nap_Latn_IT
                0xC00D4E414C61746EUL, // naq_Latn_NA
                0x6E624E4F4C61746EUL, // nb_Latn_NO
                0x9C4D4D584C61746EUL, // nch_Latn_MX
                0x6E645A574C61746EUL, // nd_Latn_ZW
                0x886D4D5A4C61746EUL, // ndc_Latn_MZ
                0xC86D44454C61746EUL, // nds_Latn_DE
                0x6E654E5044657661UL, // ne_Deva_NP
                0xD88D4E5044657661UL, // new_Deva_NP
                0x6E674E414C61746EUL, // ng_Latn_NA
                0xACCD4D5A4C61746EUL, // ngl_Latn_MZ
                0x90ED4D584C61746EUL, // nhe_Latn_MX
                0xD8ED4D584C61746EUL, // nhw_Latn_MX
                0xA50D49444C61746EUL, // nij_Latn_ID
                0xD10D4E554C61746EUL, // niu_Latn_NU
                0xB92D494E4C61746EUL, // njo_Latn_IN
                0x6E6C4E4C4C61746EUL, // nl_Latn_NL
                0x998D434D4C61746EUL, // nmg_Latn_CM
                0x6E6E4E4F4C61746EUL, // nn_Latn_NO
                0x9DAD434D4C61746EUL, // nnh_Latn_CM
                0xBDAD494E5763686FUL, // nnp_Wcho_IN
                0x6E6F4E4F4C61746EUL, // no_Latn_NO
                0x8DCD54484C616E61UL, // nod_Lana_TH
                0x91CD494E44657661UL, // noe_Deva_IN
                0xB5CD534552756E72UL, // non_Runr_SE
                0xBA0D474E4E6B6F6FUL, // nqo_Nkoo_GN
                0x6E725A414C61746EUL, // nr_Latn_ZA
                0xAA4D434143616E73UL, // nsk_Cans_CA
                0xBA4D5A414C61746EUL, // nso_Latn_ZA
                0xCA8D53534C61746EUL, // nus_Latn_SS
                0x6E7655534C61746EUL, // nv_Latn_US
                0xC2ED434E4C61746EUL, // nxq_Latn_CN
                0x6E794D574C61746EUL, // ny_Latn_MW
                0xB30D545A4C61746EUL, // nym_Latn_TZ
                0xB70D55474C61746EUL, // nyn_Latn_UG
                0xA32D47484C61746EUL, // nzi_Latn_GH
                0x6F6346524C61746EUL, // oc_Latn_FR
                0x6F6D45544C61746EUL, // om_Latn_ET
                0x6F72494E4F727961UL, // or_Orya_IN
                0x6F7347454379726CUL, // os_Cyrl_GE
                0x824E55534F736765UL, // osa_Osge_US
                0xAA6E4D4E4F726B68UL, // otk_Orkh_MN
                0x7061504B41726162UL, // pa_Arab_PK
                0x7061494E47757275UL, // pa_Guru_IN
                0x980F50484C61746EUL, // pag_Latn_PH
                0xAC0F495250686C69UL, // pal_Phli_IR
                0xAC0F434E50686C70UL, // pal_Phlp_CN
                0xB00F50484C61746EUL, // pam_Latn_PH
                0xBC0F41574C61746EUL, // pap_Latn_AW
                0xD00F50574C61746EUL, // pau_Latn_PW
                0x8C4F46524C61746EUL, // pcd_Latn_FR
                0xB04F4E474C61746EUL, // pcm_Latn_NG
                0x886F55534C61746EUL, // pdc_Latn_US
                0xCC6F43414C61746EUL, // pdt_Latn_CA
                0xB88F49525870656FUL, // peo_Xpeo_IR
                0xACAF44454C61746EUL, // pfl_Latn_DE
                0xB4EF4C4250686E78UL, // phn_Phnx_LB
                0x814F494E42726168UL, // pka_Brah_IN
                0xB94F4B454C61746EUL, // pko_Latn_KE
                0x706C504C4C61746EUL, // pl_Latn_PL
                0xC98F49544C61746EUL, // pms_Latn_IT
                0xCDAF47524772656BUL, // pnt_Grek_GR
                0xB5CF464D4C61746EUL, // pon_Latn_FM
                0x81EF494E44657661UL, // ppa_Deva_IN
                0x822F504B4B686172UL, // pra_Khar_PK
                0x8E2F495241726162UL, // prd_Arab_IR
                0x7073414641726162UL, // ps_Arab_AF
                0x707442524C61746EUL, // pt_Latn_BR
                0xD28F47414C61746EUL, // puu_Latn_GA
                0x717550454C61746EUL, // qu_Latn_PE
                0x8A9047544C61746EUL, // quc_Latn_GT
                0x9A9045434C61746EUL, // qug_Latn_EC
                0xA411494E44657661UL, // raj_Deva_IN
                0x945152454C61746EUL, // rcf_Latn_RE
                0xA49149444C61746EUL, // rej_Latn_ID
                0xB4D149544C61746EUL, // rgn_Latn_IT
                0x98F14D4D41726162UL, // rhg_Arab_MM
                0x8111494E4C61746EUL, // ria_Latn_IN
                0x95114D4154666E67UL, // rif_Tfng_MA
                0xC9314E5044657661UL, // rjs_Deva_NP
                0xCD51424442656E67UL, // rkt_Beng_BD
                0x726D43484C61746EUL, // rm_Latn_CH
                0x959146494C61746EUL, // rmf_Latn_FI
                0xB99143484C61746EUL, // rmo_Latn_CH
                0xCD91495241726162UL, // rmt_Arab_IR
                0xD19153454C61746EUL, // rmu_Latn_SE
                0x726E42494C61746EUL, // rn_Latn_BI
                0x99B14D5A4C61746EUL, // rng_Latn_MZ
                0x726F524F4C61746EUL, // ro_Latn_RO
                0x85D149444C61746EUL, // rob_Latn_ID
                0x95D1545A4C61746EUL, // rof_Latn_TZ
                0xB271464A4C61746EUL, // rtm_Latn_FJ
                0x727552554379726CUL, // ru_Cyrl_RU
                0x929155414379726CUL, // rue_Cyrl_UA
                0x9A9153424C61746EUL, // rug_Latn_SB
                0x727752574C61746EUL, // rw_Latn_RW
                0xAAD1545A4C61746EUL, // rwk_Latn_TZ
                0xD3114A504B616E61UL, // ryu_Kana_JP
                0x7361494E44657661UL, // sa_Deva_IN
                0x941247484C61746EUL, // saf_Latn_GH
                0x9C1252554379726CUL, // sah_Cyrl_RU
                0xC0124B454C61746EUL, // saq_Latn_KE
                0xC81249444C61746EUL, // sas_Latn_ID
                0xCC12494E4F6C636BUL, // sat_Olck_IN
                0xD412534E4C61746EUL, // sav_Latn_SN
                0xE412494E53617572UL, // saz_Saur_IN
                0xBC32545A4C61746EUL, // sbp_Latn_TZ
                0x736349544C61746EUL, // sc_Latn_IT
                0xA852494E44657661UL, // sck_Deva_IN
                0xB45249544C61746EUL, // scn_Latn_IT
                0xB85247424C61746EUL, // sco_Latn_GB
                0xC85243414C61746EUL, // scs_Latn_CA
                0x7364504B41726162UL, // sd_Arab_PK
                0x7364494E44657661UL, // sd_Deva_IN
                0x7364494E4B686F6AUL, // sd_Khoj_IN
                0x7364494E53696E64UL, // sd_Sind_IN
                0x887249544C61746EUL, // sdc_Latn_IT
                0x9C72495241726162UL, // sdh_Arab_IR
                0x73654E4F4C61746EUL, // se_Latn_NO
                0x949243494C61746EUL, // sef_Latn_CI
                0x9C924D5A4C61746EUL, // seh_Latn_MZ
                0xA0924D584C61746EUL, // sei_Latn_MX
                0xC8924D4C4C61746EUL, // ses_Latn_ML
                0x736743464C61746EUL, // sg_Latn_CF
                0x80D249454F67616DUL, // sga_Ogam_IE
                0xC8D24C544C61746EUL, // sgs_Latn_LT
                0xA0F24D4154666E67UL, // shi_Tfng_MA
                0xB4F24D4D4D796D72UL, // shn_Mymr_MM
                0x73694C4B53696E68UL, // si_Sinh_LK
                0x8D1245544C61746EUL, // sid_Latn_ET
                0x736B534B4C61746EUL, // sk_Latn_SK
                0xC552504B41726162UL, // skr_Arab_PK
                0x736C53494C61746EUL, // sl_Latn_SI
                0xA172504C4C61746EUL, // sli_Latn_PL
                0xE17249444C61746EUL, // sly_Latn_ID
                0x736D57534C61746EUL, // sm_Latn_WS
                0x819253454C61746EUL, // sma_Latn_SE
                0xA59253454C61746EUL, // smj_Latn_SE
                0xB59246494C61746EUL, // smn_Latn_FI
                0xBD92494C53616D72UL, // smp_Samr_IL
                0xC99246494C61746EUL, // sms_Latn_FI
                0x736E5A574C61746EUL, // sn_Latn_ZW
                0xA9B24D4C4C61746EUL, // snk_Latn_ML
                0x736F534F4C61746EUL, // so_Latn_SO
                0x99D2555A536F6764UL, // sog_Sogd_UZ
                0xD1D2544854686169UL, // sou_Thai_TH
                0x7371414C4C61746EUL, // sq_Latn_AL
                0x737252534379726CUL, // sr_Cyrl_RS
                0x737252534C61746EUL, // sr_Latn_RS
                0x8632494E536F7261UL, // srb_Sora_IN
                0xB63253524C61746EUL, // srn_Latn_SR
                0xC632534E4C61746EUL, // srr_Latn_SN
                0xDE32494E44657661UL, // srx_Deva_IN
                0x73735A414C61746EUL, // ss_Latn_ZA
                0xE25245524C61746EUL, // ssy_Latn_ER
                0x73745A414C61746EUL, // st_Latn_ZA
                0xC27244454C61746EUL, // stq_Latn_DE
                0x737549444C61746EUL, // su_Latn_ID
                0xAA92545A4C61746EUL, // suk_Latn_TZ
                0xCA92474E4C61746EUL, // sus_Latn_GN
                0x737653454C61746EUL, // sv_Latn_SE
                0x7377545A4C61746EUL, // sw_Latn_TZ
                0x86D2595441726162UL, // swb_Arab_YT
                0x8AD243444C61746EUL, // swc_Latn_CD
                0x9AD244454C61746EUL, // swg_Latn_DE
                0xD6D2494E44657661UL, // swv_Deva_IN
                0xB6F249444C61746EUL, // sxn_Latn_ID
                0xAF12424442656E67UL, // syl_Beng_BD
                0xC712495153797263UL, // syr_Syrc_IQ
                0xAF32504C4C61746EUL, // szl_Latn_PL
                0x7461494E54616D6CUL, // ta_Taml_IN
                0xA4134E5044657661UL, // taj_Deva_NP
                0xD83350484C61746EUL, // tbw_Latn_PH
                0xE053494E4B6E6461UL, // tcy_Knda_IN
                0x8C73434E54616C65UL, // tdd_Tale_CN
                0x98734E5044657661UL, // tdg_Deva_NP
                0x9C734E5044657661UL, // tdh_Deva_NP
                0xD0734D594C61746EUL, // tdu_Latn_MY
                0x7465494E54656C75UL, // te_Telu_IN
                0xB093534C4C61746EUL, // tem_Latn_SL
                0xB89355474C61746EUL, // teo_Latn_UG
                0xCC93544C4C61746EUL, // tet_Latn_TL
                0x7467504B41726162UL, // tg_Arab_PK
                0x7467544A4379726CUL, // tg_Cyrl_TJ
                0x7468544854686169UL, // th_Thai_TH
                0xACF34E5044657661UL, // thl_Deva_NP
                0xC0F34E5044657661UL, // thq_Deva_NP
                0xC4F34E5044657661UL, // thr_Deva_NP
                0x7469455445746869UL, // ti_Ethi_ET
                0x9913455245746869UL, // tig_Ethi_ER
                0xD5134E474C61746EUL, // tiv_Latn_NG
                0x746B544D4C61746EUL, // tk_Latn_TM
                0xAD53544B4C61746EUL, // tkl_Latn_TK
                0xC553415A4C61746EUL, // tkr_Latn_AZ
                0xCD534E5044657661UL, // tkt_Deva_NP
                0x746C50484C61746EUL, // tl_Latn_PH
                0xE173415A4C61746EUL, // tly_Latn_AZ
                0x9D934E454C61746EUL, // tmh_Latn_NE
                0x746E5A414C61746EUL, // tn_Latn_ZA
                0x746F544F4C61746EUL, // to_Latn_TO
                0x99D34D574C61746EUL, // tog_Latn_MW
                0xA1F350474C61746EUL, // tpi_Latn_PG
                0x747254524C61746EUL, // tr_Latn_TR
                0xD23354524C61746EUL, // tru_Latn_TR
                0xD63354574C61746EUL, // trv_Latn_TW
                0xDA33504B41726162UL, // trw_Arab_PK
                0x74735A414C61746EUL, // ts_Latn_ZA
                0x8E5347524772656BUL, // tsd_Grek_GR
                0x96534E5044657661UL, // tsf_Deva_NP
                0x9A5350484C61746EUL, // tsg_Latn_PH
                0xA653425454696274UL, // tsj_Tibt_BT
                0x747452554379726CUL, // tt_Cyrl_RU
                0xA67355474C61746EUL, // ttj_Latn_UG
                0xCA73544854686169UL, // tts_Thai_TH
                0xCE73415A4C61746EUL, // ttt_Latn_AZ
                0xB2934D574C61746EUL, // tum_Latn_MW
                0xAEB354564C61746EUL, // tvl_Latn_TV
                0xC2D34E454C61746EUL, // twq_Latn_NE
                0x9AF3434E54616E67UL, // txg_Tang_CN
                0x747950464C61746EUL, // ty_Latn_PF
                0xD71352554379726CUL, // tyv_Cyrl_RU
                0xB3334D414C61746EUL, // tzm_Latn_MA
                0xB07452554379726CUL, // udm_Cyrl_RU
                0x7567434E41726162UL, // ug_Arab_CN
                0x75674B5A4379726CUL, // ug_Cyrl_KZ
                0x80D4535955676172UL, // uga_Ugar_SY
                0x756B55414379726CUL, // uk_Cyrl_UA
                0xA174464D4C61746EUL, // uli_Latn_FM
                0x8594414F4C61746EUL, // umb_Latn_AO
                0xC5B4494E42656E67UL, // unr_Beng_IN
                0xC5B44E5044657661UL, // unr_Deva_NP
                0xDDB4494E42656E67UL, // unx_Beng_IN
                0x7572504B41726162UL, // ur_Arab_PK
                0x757A414641726162UL, // uz_Arab_AF
                0x757A555A4C61746EUL, // uz_Latn_UZ
                0xA0154C5256616969UL, // vai_Vaii_LR
                0x76655A414C61746EUL, // ve_Latn_ZA
                0x889549544C61746EUL, // vec_Latn_IT
                0xBC9552554C61746EUL, // vep_Latn_RU
                0x7669564E4C61746EUL, // vi_Latn_VN
                0x891553584C61746EUL, // vic_Latn_SX
                0xC97542454C61746EUL, // vls_Latn_BE
                0x959544454C61746EUL, // vmf_Latn_DE
                0xD9954D5A4C61746EUL, // vmw_Latn_MZ
                0xCDD552554C61746EUL, // vot_Latn_RU
                0xBA3545454C61746EUL, // vro_Latn_EE
                0xB695545A4C61746EUL, // vun_Latn_TZ
                0x776142454C61746EUL, // wa_Latn_BE
                0x901643484C61746EUL, // wae_Latn_CH
                0xAC16455445746869UL, // wal_Ethi_ET
                0xC41650484C61746EUL, // war_Latn_PH
                0xBC3641554C61746EUL, // wbp_Latn_AU
                0xC036494E54656C75UL, // wbq_Telu_IN
                0xC436494E44657661UL, // wbr_Deva_IN
                0xC97657464C61746EUL, // wls_Latn_WF
                0xA1B64B4D41726162UL, // wni_Arab_KM
                0x776F534E4C61746EUL, // wo_Latn_SN
                0x9A56494E476F6E67UL, // wsg_Gong_IN
                0xB276494E44657661UL, // wtm_Deva_IN
                0xD296434E48616E73UL, // wuu_Hans_CN
                0xD41742524C61746EUL, // xav_Latn_BR
                0xB857555A43687273UL, // xco_Chrs_UZ
                0xC457545243617269UL, // xcr_Cari_TR
                0x78685A414C61746EUL, // xh_Latn_ZA
                0x897754524C796369UL, // xlc_Lyci_TR
                0x8D7754524C796469UL, // xld_Lydi_TR
                0x9597474547656F72UL, // xmf_Geor_GE
                0xB597434E4D616E69UL, // xmn_Mani_CN
                0xC59753444D657263UL, // xmr_Merc_SD
                0x81B753414E617262UL, // xna_Narb_SA
                0xC5B7494E44657661UL, // xnr_Deva_IN
                0x99D755474C61746EUL, // xog_Latn_UG
                0xC5F7495250727469UL, // xpr_Prti_IR
                0x8257594553617262UL, // xsa_Sarb_YE
                0xC6574E5044657661UL, // xsr_Deva_NP
                0xB8184D5A4C61746EUL, // yao_Latn_MZ
                0xBC18464D4C61746EUL, // yap_Latn_FM
                0xD418434D4C61746EUL, // yav_Latn_CM
                0x8438434D4C61746EUL, // ybb_Latn_CM
                0x796F4E474C61746EUL, // yo_Latn_NG
                0xAE3842524C61746EUL, // yrl_Latn_BR
                0x82984D584C61746EUL, // yua_Latn_MX
                0x9298434E48616E73UL, // yue_Hans_CN
                0x9298484B48616E74UL, // yue_Hant_HK
                0x7A61434E4C61746EUL, // za_Latn_CN
                0x981953444C61746EUL, // zag_Latn_SD
                0xA4794B4D41726162UL, // zdj_Arab_KM
                0x80994E4C4C61746EUL, // zea_Latn_NL
                0x9CD94D4154666E67UL, // zgh_Tfng_MA
                0x7A685457426F706FUL, // zh_Bopo_TW
                0x7A68545748616E62UL, // zh_Hanb_TW
                0x7A68434E48616E73UL, // zh_Hans_CN
                0x7A68545748616E74UL, // zh_Hant_TW
                0xDCF9434E4E736875UL, // zhx_Nshu_CN
                0xCD59434E4B697473UL, // zkt_Kits_CN
                0xB17954474C61746EUL, // zlm_Latn_TG
                0xA1994D594C61746EUL, // zmi_Latn_MY
                0x7A755A414C61746EUL, // zu_Latn_ZA
                0x833954524C61746EUL, // zza_Latn_TR
            )
        }

        val ARAB_PARENTS by lazy {
            mapOf<UInt, UInt>(
                0x6172445Au to 0x61729420u, // ar-DZ -> ar-015
                0x61724548u to 0x61729420u, // ar-EH -> ar-015
                0x61724C59u to 0x61729420u, // ar-LY -> ar-015
                0x61724D41u to 0x61729420u, // ar-MA -> ar-015
                0x6172544Eu to 0x61729420u, // ar-TN -> ar-015
            )
        }

        val HANT_PARENTS by lazy {
            mapOf<UInt, UInt>(
                0x7A684D4Fu to 0x7A68484Bu, // zh-Hant-MO -> zh-Hant-HK
            )
        }
            /*
        https://cs.android.com/android/platform/superproject/+/master:frameworks/base/libs/androidfw/LocaleDataTables.cpp;drc=master;l=2256

        Use the regex replacement rule: Replace

            \{(0x[0-9A-F]*u), (0x[0-9A-F]*u)\},

        with

            $1 to $2,
         */


        val LATN_PARENTS by lazy {
            mapOf<UInt, UInt>(
                0x656E80A1u to 0x656E8400u, // en-150 -> en-001
                0x656E4147u to 0x656E8400u, // en-AG -> en-001
                0x656E4149u to 0x656E8400u, // en-AI -> en-001
                0x656E4154u to 0x656E80A1u, // en-AT -> en-150
                0x656E4155u to 0x656E8400u, // en-AU -> en-001
                0x656E4242u to 0x656E8400u, // en-BB -> en-001
                0x656E4245u to 0x656E80A1u, // en-BE -> en-150
                0x656E424Du to 0x656E8400u, // en-BM -> en-001
                0x656E4253u to 0x656E8400u, // en-BS -> en-001
                0x656E4257u to 0x656E8400u, // en-BW -> en-001
                0x656E425Au to 0x656E8400u, // en-BZ -> en-001
                0x656E4341u to 0x656E8400u, // en-CA -> en-001
                0x656E4343u to 0x656E8400u, // en-CC -> en-001
                0x656E4348u to 0x656E80A1u, // en-CH -> en-150
                0x656E434Bu to 0x656E8400u, // en-CK -> en-001
                0x656E434Du to 0x656E8400u, // en-CM -> en-001
                0x656E4358u to 0x656E8400u, // en-CX -> en-001
                0x656E4359u to 0x656E8400u, // en-CY -> en-001
                0x656E4445u to 0x656E80A1u, // en-DE -> en-150
                0x656E4447u to 0x656E8400u, // en-DG -> en-001
                0x656E444Bu to 0x656E80A1u, // en-DK -> en-150
                0x656E444Du to 0x656E8400u, // en-DM -> en-001
                0x656E4552u to 0x656E8400u, // en-ER -> en-001
                0x656E4649u to 0x656E80A1u, // en-FI -> en-150
                0x656E464Au to 0x656E8400u, // en-FJ -> en-001
                0x656E464Bu to 0x656E8400u, // en-FK -> en-001
                0x656E464Du to 0x656E8400u, // en-FM -> en-001
                0x656E4742u to 0x656E8400u, // en-GB -> en-001
                0x656E4744u to 0x656E8400u, // en-GD -> en-001
                0x656E4747u to 0x656E8400u, // en-GG -> en-001
                0x656E4748u to 0x656E8400u, // en-GH -> en-001
                0x656E4749u to 0x656E8400u, // en-GI -> en-001
                0x656E474Du to 0x656E8400u, // en-GM -> en-001
                0x656E4759u to 0x656E8400u, // en-GY -> en-001
                0x656E484Bu to 0x656E8400u, // en-HK -> en-001
                0x656E4945u to 0x656E8400u, // en-IE -> en-001
                0x656E494Cu to 0x656E8400u, // en-IL -> en-001
                0x656E494Du to 0x656E8400u, // en-IM -> en-001
                0x656E494Eu to 0x656E8400u, // en-IN -> en-001
                0x656E494Fu to 0x656E8400u, // en-IO -> en-001
                0x656E4A45u to 0x656E8400u, // en-JE -> en-001
                0x656E4A4Du to 0x656E8400u, // en-JM -> en-001
                0x656E4B45u to 0x656E8400u, // en-KE -> en-001
                0x656E4B49u to 0x656E8400u, // en-KI -> en-001
                0x656E4B4Eu to 0x656E8400u, // en-KN -> en-001
                0x656E4B59u to 0x656E8400u, // en-KY -> en-001
                0x656E4C43u to 0x656E8400u, // en-LC -> en-001
                0x656E4C52u to 0x656E8400u, // en-LR -> en-001
                0x656E4C53u to 0x656E8400u, // en-LS -> en-001
                0x656E4D47u to 0x656E8400u, // en-MG -> en-001
                0x656E4D4Fu to 0x656E8400u, // en-MO -> en-001
                0x656E4D53u to 0x656E8400u, // en-MS -> en-001
                0x656E4D54u to 0x656E8400u, // en-MT -> en-001
                0x656E4D55u to 0x656E8400u, // en-MU -> en-001
                0x656E4D57u to 0x656E8400u, // en-MW -> en-001
                0x656E4D59u to 0x656E8400u, // en-MY -> en-001
                0x656E4E41u to 0x656E8400u, // en-NA -> en-001
                0x656E4E46u to 0x656E8400u, // en-NF -> en-001
                0x656E4E47u to 0x656E8400u, // en-NG -> en-001
                0x656E4E4Cu to 0x656E80A1u, // en-NL -> en-150
                0x656E4E52u to 0x656E8400u, // en-NR -> en-001
                0x656E4E55u to 0x656E8400u, // en-NU -> en-001
                0x656E4E5Au to 0x656E8400u, // en-NZ -> en-001
                0x656E5047u to 0x656E8400u, // en-PG -> en-001
                0x656E5048u to 0x656E8400u, // en-PH -> en-001
                0x656E504Bu to 0x656E8400u, // en-PK -> en-001
                0x656E504Eu to 0x656E8400u, // en-PN -> en-001
                0x656E5057u to 0x656E8400u, // en-PW -> en-001
                0x656E5257u to 0x656E8400u, // en-RW -> en-001
                0x656E5342u to 0x656E8400u, // en-SB -> en-001
                0x656E5343u to 0x656E8400u, // en-SC -> en-001
                0x656E5344u to 0x656E8400u, // en-SD -> en-001
                0x656E5345u to 0x656E80A1u, // en-SE -> en-150
                0x656E5347u to 0x656E8400u, // en-SG -> en-001
                0x656E5348u to 0x656E8400u, // en-SH -> en-001
                0x656E5349u to 0x656E80A1u, // en-SI -> en-150
                0x656E534Cu to 0x656E8400u, // en-SL -> en-001
                0x656E5353u to 0x656E8400u, // en-SS -> en-001
                0x656E5358u to 0x656E8400u, // en-SX -> en-001
                0x656E535Au to 0x656E8400u, // en-SZ -> en-001
                0x656E5443u to 0x656E8400u, // en-TC -> en-001
                0x656E544Bu to 0x656E8400u, // en-TK -> en-001
                0x656E544Fu to 0x656E8400u, // en-TO -> en-001
                0x656E5454u to 0x656E8400u, // en-TT -> en-001
                0x656E5456u to 0x656E8400u, // en-TV -> en-001
                0x656E545Au to 0x656E8400u, // en-TZ -> en-001
                0x656E5547u to 0x656E8400u, // en-UG -> en-001
                0x656E5643u to 0x656E8400u, // en-VC -> en-001
                0x656E5647u to 0x656E8400u, // en-VG -> en-001
                0x656E5655u to 0x656E8400u, // en-VU -> en-001
                0x656E5753u to 0x656E8400u, // en-WS -> en-001
                0x656E5A41u to 0x656E8400u, // en-ZA -> en-001
                0x656E5A4Du to 0x656E8400u, // en-ZM -> en-001
                0x656E5A57u to 0x656E8400u, // en-ZW -> en-001
                0x65734152u to 0x6573A424u, // es-AR -> es-419
                0x6573424Fu to 0x6573A424u, // es-BO -> es-419
                0x65734252u to 0x6573A424u, // es-BR -> es-419
                0x6573425Au to 0x6573A424u, // es-BZ -> es-419
                0x6573434Cu to 0x6573A424u, // es-CL -> es-419
                0x6573434Fu to 0x6573A424u, // es-CO -> es-419
                0x65734352u to 0x6573A424u, // es-CR -> es-419
                0x65734355u to 0x6573A424u, // es-CU -> es-419
                0x6573444Fu to 0x6573A424u, // es-DO -> es-419
                0x65734543u to 0x6573A424u, // es-EC -> es-419
                0x65734754u to 0x6573A424u, // es-GT -> es-419
                0x6573484Eu to 0x6573A424u, // es-HN -> es-419
                0x65734D58u to 0x6573A424u, // es-MX -> es-419
                0x65734E49u to 0x6573A424u, // es-NI -> es-419
                0x65735041u to 0x6573A424u, // es-PA -> es-419
                0x65735045u to 0x6573A424u, // es-PE -> es-419
                0x65735052u to 0x6573A424u, // es-PR -> es-419
                0x65735059u to 0x6573A424u, // es-PY -> es-419
                0x65735356u to 0x6573A424u, // es-SV -> es-419
                0x65735553u to 0x6573A424u, // es-US -> es-419
                0x65735559u to 0x6573A424u, // es-UY -> es-419
                0x65735645u to 0x6573A424u, // es-VE -> es-419
                0x7074414Fu to 0x70745054u, // pt-AO -> pt-PT
                0x70744348u to 0x70745054u, // pt-CH -> pt-PT
                0x70744356u to 0x70745054u, // pt-CV -> pt-PT
                0x70744751u to 0x70745054u, // pt-GQ -> pt-PT
                0x70744757u to 0x70745054u, // pt-GW -> pt-PT
                0x70744C55u to 0x70745054u, // pt-LU -> pt-PT
                0x70744D4Fu to 0x70745054u, // pt-MO -> pt-PT
                0x70744D5Au to 0x70745054u, // pt-MZ -> pt-PT
                0x70745354u to 0x70745054u, // pt-ST -> pt-PT
                0x7074544Cu to 0x70745054u, // pt-TL -> pt-PT
            )
        }

        val ___B_PARENTS by lazy {
            mapOf(
                0x61725842u to 0x61729420u, // ar-XB -> ar-015
            )
        }

        /**
         * https://android.googlesource.com/platform/frameworks/base/+/9489b44d47372be1cd19dd0e5e8da61c25380de6/libs/androidfw/LocaleDataTables.cpp#2404
         */
        data class ScriptParents(val script: ByteArray, val map: Map<UInt, UInt>)

        val SCRIPT_PARENTS by lazy {
            arrayOf(
                ScriptParents(byteArrayOfChars('A', 'r', 'a', 'b'), ARAB_PARENTS),
                ScriptParents(byteArrayOfChars('H', 'a', 'n', 't'), HANT_PARENTS),
                ScriptParents(byteArrayOfChars('L', 'a', 't', 'n'), LATN_PARENTS),
                ScriptParents(byteArrayOfChars('~', '~', '~', 'B'), ___B_PARENTS)
            )
        }
        const val MAX_PARENT_DEPTH = 3
        const val PACKED_ROOT = 0U // to represent the root locale

    }
}