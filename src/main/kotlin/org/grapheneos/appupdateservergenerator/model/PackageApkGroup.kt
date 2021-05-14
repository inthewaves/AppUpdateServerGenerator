package org.grapheneos.appupdateservergenerator.model

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.grapheneos.appupdateservergenerator.apkparsing.AAPT2Invoker
import org.grapheneos.appupdateservergenerator.apkparsing.ApkSignerInvoker
import org.grapheneos.appupdateservergenerator.files.AppDir
import org.grapheneos.appupdateservergenerator.files.FileManager.Companion.APK_REGEX
import java.io.File
import java.io.FileFilter
import java.io.IOException
import java.util.*
import java.util.zip.ZipFile

/**
 * Represents the APKs for a package.
 * [packageName] is the package for all of the APKs in [sortedApks].
 */
sealed class PackageApkGroup private constructor(
    val packageName: String,
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

    /**
     * Variant of [PackageApkGroup] where the [sortedApks] are sorted in ascending order by version code.
     */
    class AscendingOrder constructor(packageName: String, apks: SortedSet<AndroidApk>) : PackageApkGroup(packageName, apks)
    /**
     * Variant of [PackageApkGroup] where the [sortedApks] are sorted in descending order by version code.
     */
    class DescendingOrder constructor(packageName: String, apks: SortedSet<AndroidApk>) : PackageApkGroup(packageName, apks)

    companion object {
        /**
         * Constructs a [PackageApkGroup] list from a given list of [apkFilePaths].
         * This will group every APK by the package.
         */
        suspend fun fromStringPathsAscending(
            apkFilePaths: Iterable<String>,
            aaptInvoker: AAPT2Invoker,
            apkSignerInvoker: ApkSignerInvoker
        ): List<AscendingOrder> {
            @Suppress("UNCHECKED_CAST")
            return fromStringPaths(
                apkFilePaths = apkFilePaths,
                aaptInvoker = aaptInvoker,
                apkSignerInvoker = apkSignerInvoker, ascendingOrder = true
            ) as List<AscendingOrder>
        }

        private suspend fun fromStringPaths(
            apkFilePaths: Iterable<String>,
            aaptInvoker: AAPT2Invoker,
            apkSignerInvoker: ApkSignerInvoker,
            ascendingOrder: Boolean
        ): List<PackageApkGroup> = coroutineScope {
            val comparator = if (ascendingOrder) {
                AndroidApk.ascendingVersionCodeComparator
            } else {
                AndroidApk.descendingVersionCodeComparator
            }

            apkFilePaths
                .map { apkFilePathString ->
                    val apkFile = File(apkFilePathString)
                    if (!apkFile.exists() || !apkFile.canRead()) {
                        throw IOException("unable to read APK file $apkFilePathString")
                    }

                    async {
                        AndroidApk.verifyApkSignatureAndBuildFromApkFile(apkFile, aaptInvoker, apkSignerInvoker)
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
         * Gets a sorted list of the previous APK files in the [appDir], mapping them to [AndroidApk] instances.
         * The sort order is determined by [ascendingOrder].
         *
         * @throws IOException if an I/O error occurs
         */
        suspend fun fromDir(
            appDir: AppDir,
            aaptInvoker: AAPT2Invoker,
            apkSignerInvoker: ApkSignerInvoker,
            ascendingOrder: Boolean
        ): PackageApkGroup = coroutineScope {
            val comparator = if (ascendingOrder) {
                AndroidApk.ascendingVersionCodeComparator
            } else {
                AndroidApk.descendingVersionCodeComparator
            }

            val set = appDir.dir
                .listFiles(
                    FileFilter { it.isFile && APK_REGEX.matches(it.name) && isZipFile(it) }
                )
                ?.map {
                    async { AndroidApk.verifyApkSignatureAndBuildFromApkFile(it, aaptInvoker, apkSignerInvoker) }
                }
                ?.mapTo(TreeSet(comparator)) { it.await() }
                ?: throw IOException("failed to get previous apks: listFiles returned null")

            val packageName = if (set.isEmpty()) appDir.packageName else set.first().packageName
            if (ascendingOrder) {
                AscendingOrder(packageName, set)
            } else {
                DescendingOrder(packageName, set)
            }
        }

        private fun isZipFile(file: File): Boolean = try {
            ZipFile(file).close()
            true
        } catch (e: IOException) {
            false
        }
    }
}
