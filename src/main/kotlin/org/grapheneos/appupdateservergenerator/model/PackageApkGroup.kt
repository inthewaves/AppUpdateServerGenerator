package org.grapheneos.appupdateservergenerator.model

import java.util.*

/**
 * Represents a single package's APKs to insert into the repository. [packageName] is the package for all of the APKs
 * in [sortedApks].
 */
class PackageApkGroup private constructor(
    val packageName: String,
    /**
     * A set of [AndroidApk] instances for [packageName], sorted in ascending order
     */
    val sortedApks: SortedSet<AndroidApk>
) {
    init {
        require(sortedApks.isNotEmpty())
        require(sortedApks.all { it.packageName == packageName })
    }

    constructor(apks: Iterable<AndroidApk>): this(
        apks.first().packageName,
        apks.toSortedSet(AndroidApk.ascendingVersionCodeComparator)
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as PackageApkGroup
        if (packageName != other.packageName) return false
        if (sortedApks != other.sortedApks) return false
        return true
    }

    override fun hashCode(): Int {
        var result = packageName.hashCode()
        result = 31 * result + sortedApks.hashCode()
        return result
    }

    override fun toString(): String {
        return "ApkInsertionGroup(packageName='$packageName', sortedApks=$sortedApks)"
    }
}
