package org.grapheneos.appupdateservergenerator.model

/**
 * Represents the screen pixel density in dpi. The [approximateDpi] values are from [1] and [2].
 *
 * [1] https://android.googlesource.com/platform/frameworks/native/+/7e563f090ba19c36b9879e14388a0e377f1523b5/include/android/configuration.h#92
 * [2] https://developer.android.com/guide/topics/resources/providing-resources#DensityQualifier
 */
enum class Density(val qualifierValue: String, val approximateDpi: Int) {
    DEFAULT("*", 0),

    /** Low-density screens; approximately 120dpi.*/
    LOW("ldpi", 120),
    /** Medium-density (on traditional HVGA) screens; approximately 160dpi.*/
    MEDIUM("mdpi",160),
    /**
     * Screens somewhere between mdpi and hdpi; approximately 213dpi. This isn't considered a "primary" density
     * group. It is mostly intended for televisions and most apps shouldn't need it—providing mdpi and hdpi
     * resources is sufficient for most apps and the system scales them as appropriate.
     */
    TV("tvdpi", 213),
    /** High-density screens; approximately 240dpi.*/
    HIGH("hdpi", 240),
    /** Extra-high-density screens; approximately 320dpi.*/
    XHIGH("xhdpi", 320),
    /** Extra-extra-high-density screens; approximately 480dpi.*/
    XXHIGH("xxhdpi", 480),
    /**
     * Extra-extra-extra-high-density uses (launcher icon only, see the note in Supporting Multiple Screens);
     * approximately 640dpi.
     *
     * https://developer.android.com/guide/practices/screens_support#xxxhdpi-note
     */
    XXXHIGH("xxxhdpi", 640),

    /**
     * This qualifier matches all screen densities and takes precedence over other qualifiers. This is useful for
     * vector drawables.
     */
    ANY("anydpi", 0xfffe),

    /** No density specified */
    NONE("nodpi", 0xffff);

    override fun toString(): String {
        return "Density(qualifierValue='$qualifierValue', approximateDpi=$approximateDpi)"
    }

    companion object {
        private val regex = Regex("-(l|m|tv|x{0,3}h|any)dpi")
        private val dpiToDensityMap: Map<Int, Density> by lazy {
            values().associateByTo(HashMap(values().size)) { it.approximateDpi }
        }

        fun fromDpi(dpi: Int): Density? = dpiToDensityMap[dpi]

        /**
         * Parses a path and returns a [Density], or [Density.DEFAULT] if it doesn't correspond to any instance.
         * Examples of paths: res/drawable-mdpi-v4/notification_bg_normal.9.png corresponds to [Density.MEDIUM].
         */
        fun fromPath(path: String): Density =
            regex.find(path)
                ?.groupValues?.get(1)
                ?.let { dpiPrefix ->
                    val qualifierValue = dpiPrefix + "dpi"
                    values().find { it.qualifierValue == qualifierValue }
                }
                ?: DEFAULT
    }
}