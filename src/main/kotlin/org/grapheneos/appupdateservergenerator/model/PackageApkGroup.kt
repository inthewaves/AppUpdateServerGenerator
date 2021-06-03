package org.grapheneos.appupdateservergenerator.model

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.grapheneos.appupdateservergenerator.apkparsing.AAPT2Invoker
import org.grapheneos.appupdateservergenerator.files.AppDir
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.SortedSet
import java.util.TreeSet

/**
 * Represents the APKs for a package.
 * [packageName] is the package for all of the APKs in [sortedApks].
 */
sealed class PackageApkGroup private constructor(
    val packageName: PackageName,
    inputApks: SortedSet<AndroidApk>
) {
    /**
     * A set of [AndroidApk] instances for [packageName], sorted in ascending order.
     * This set is unmodifiable.
     */
    val sortedApks: SortedSet<AndroidApk> = Collections.unmodifiableSortedSet(inputApks)
    init {
        require(sortedApks.all { it.packageName == packageName })
    }
    val size: Int
        get() = sortedApks.size

    /** The APK with the highest version code, or null if empty */
    abstract val highestVersionApk: AndroidApk?
    /** The APK with the lowest version code, or null if empty */
    abstract val lowestVersionApk: AndroidApk?

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
        return "PackageApkGroup(packageName='$packageName', sortedApks=$sortedApks)"
    }

    /**
     * Variant of [PackageApkGroup] where the [sortedApks] are sorted in ascending order by version code.
     */
    class AscendingOrder(packageName: PackageName, apks: SortedSet<AndroidApk>) : PackageApkGroup(packageName, apks) {
        override val highestVersionApk: AndroidApk? get() =
            try {
                sortedApks.last()
            } catch (e: NoSuchElementException) {
                null
            }
        override val lowestVersionApk: AndroidApk? get() =
            try {
                sortedApks.first()
            } catch (e: NoSuchElementException) {
                null
            }
    }
    /**
     * Variant of [PackageApkGroup] where the [sortedApks] are sorted in descending order by version code.
     */
    class DescendingOrder(packageName: PackageName, apks: SortedSet<AndroidApk>) : PackageApkGroup(packageName, apks) {
        override val highestVersionApk: AndroidApk? get() =
            try {
                sortedApks.first()
            } catch (e: NoSuchElementException) {
                null
            }
        override val lowestVersionApk: AndroidApk? get() =
            try {
                sortedApks.last()
            } catch (e: NoSuchElementException) {
                null
            }
    }

    companion object {
        /**
         * Constructs a [PackageApkGroup.AscendingOrder] list from a given list of [apkFilePaths].
         * This will group every APK by package.
         */
        suspend fun fromFilesAscending(
            apkFilePaths: Iterable<File>,
            aaptInvoker: AAPT2Invoker,
        ): List<AscendingOrder> {
            @Suppress("UNCHECKED_CAST")
            return fromFiles(
                apkFilePaths = apkFilePaths,
                aaptInvoker = aaptInvoker,
                ascendingOrder = true
            ) as List<AscendingOrder>
        }

        private suspend fun fromFiles(
            apkFilePaths: Iterable<File>,
            aaptInvoker: AAPT2Invoker,
            ascendingOrder: Boolean
        ): List<PackageApkGroup> = coroutineScope {
            val comparator = if (ascendingOrder) {
                AndroidApk.ascendingVersionCodeComparator
            } else {
                AndroidApk.descendingVersionCodeComparator
            }

            apkFilePaths
                .map { apkFile ->
                    if (!apkFile.exists() || !apkFile.canRead()) {
                        throw IOException("unable to read APK file $apkFile")
                    }

                    async {
                        AndroidApk.buildFromApkAndVerifySignature(apkFile, aaptInvoker)
                    }
                }
                .awaitAll()
                .groupingBy { it.packageName }
                .fold(
                    initialValueSelector = { _, _ -> TreeSet(comparator) },
                    operation = { _, accumulator, element -> accumulator.apply { add(element) } }
                )
                .run {
                    if (ascendingOrder) {
                        map { AscendingOrder(it.key, it.value) }
                    } else {
                        map { DescendingOrder(it.key, it.value) }
                    }
                }
        }

        /**
         * Constructs a [PackageApkGroup.AscendingOrder] list from a given list of [apkFilePaths].
         * This will group every APK by package.
         */
        suspend fun fromDirDescending(
            appDir: AppDir,
            aaptInvoker: AAPT2Invoker
        ): DescendingOrder {
            return fromDir(
                appDir,
                aaptInvoker,
                ascendingOrder = false
            ) as DescendingOrder
        }

        /**
         * Gets a sorted list of the previous APK files in the [appDir], mapping them to [AndroidApk] instances.
         * The sort order is determined by [ascendingOrder].
         *
         * @throws IOException if an I/O error occurs
         */
        suspend fun fromDir(
            appDir: AppDir,
            aaptInvoker: AAPT2Invoker,
            ascendingOrder: Boolean
        ): PackageApkGroup = coroutineScope {
            val comparator = if (ascendingOrder) {
                AndroidApk.ascendingVersionCodeComparator
            } else {
                AndroidApk.descendingVersionCodeComparator
            }

            val set = appDir.listApkFilesUnsorted()
                .map { async { AndroidApk.buildFromApkAndVerifySignature(it, aaptInvoker) } }
                .mapTo(TreeSet(comparator)) { it.await() }

            val packageName = if (set.isEmpty()) appDir.packageName else set.first().packageName
            if (ascendingOrder) {
                AscendingOrder(packageName, set)
            } else {
                DescendingOrder(packageName, set)
            }
        }
    }
}
