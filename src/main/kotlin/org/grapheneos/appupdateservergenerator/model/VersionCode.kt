package org.grapheneos.appupdateservergenerator.model

import kotlinx.serialization.Serializable

/**
 * Important note: A [Long] is used here instead of an [Int] to reflect the
 * [long version code](https://developer.android.com/reference/android/content/pm/PackageInfo.html#getLongVersionCode()).
 * The upper 32 bits contain the `versionCodeMajor`, while the lower 32 bits contain the legacy `versionCode`.
 *
 * Taken from [1]. Some parts are Google-Play specific, but can still be applicable in the future:
 *
 * A positive integer used as an internal version number. This number is used only to determine whether one version is
 * more recent than another, with higher numbers indicating more recent versions. This is not the version number shown
 * to users; that number is set by the `versionName` setting, below. The Android system uses the `versionCode` value to
 * protect against downgrades by preventing users from installing an APK with a lower `versionCode` than the version
 * currently installed on their device.
 *
 * The value is a positive integer so that other apps can programmatically evaluate it, for example to check an upgrade
 * or downgrade relationship. You can set the value to any positive integer you want; however, you should make sure that
 * each successive release of your app uses a greater value. You cannot upload an APK to the app repo with a
 * `versionCode` you have already used for a previous version.
 *
 * Note: In some specific situations, you might wish to upload a version of your app with a lower `versionCode` than the
 * most recent version. For example, if you are publishing multiple APKs, you might have pre-set `versionCode` ranges
 * for specific APKs. For more about assigning `versionCode` values for multiple APKs, see
 * [Multiple APK Support](https://developer.android.com/google/play/publishing/multiple-apks#VersionCodes)
 *
 * Typically, you would release the first version of your app with `versionCode` set to 1, then monotonically increase
 * the value with each release, regardless of whether the release constitutes a major or minor release. This means that
 * the versionCode value does not necessarily have a strong resemblance to the app release version that is visible to
 * the user (see `versionName`). Apps and publishing services should not display this version value to users.
 *
 * [1] https://developer.android.com/studio/publish/versioning#appversioning
 */
@Serializable
@JvmInline
value class VersionCode(val code: Long) : Comparable<VersionCode> {
    init {
        require(code > 0)
        if (code > 2100000000L) {
            // just FYI: https://developer.android.com/studio/publish/versioning#appversioning
            println("warning: version code $code exceeds max version code for Google Play")
        }
    }
    val legacyVersionCode: Int get() = (code and 0xffffffff).toInt()
    val versionCodeMajor: Int get() = (code ushr 32).toInt()

    override fun compareTo(other: VersionCode): Int = code.compareTo(other.code)
}
