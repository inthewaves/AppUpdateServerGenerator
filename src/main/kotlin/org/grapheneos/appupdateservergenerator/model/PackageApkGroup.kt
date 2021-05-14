package org.grapheneos.appupdateservergenerator.model

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.grapheneos.appupdateservergenerator.apkparsing.AAPT2Invoker
import org.grapheneos.appupdateservergenerator.apkparsing.ApkSignerInvoker
import org.grapheneos.appupdateservergenerator.files.AppDir
import org.grapheneos.appupdateservergenerator.files.FileManager.Companion.APK_REGEX
import java.io.File
import java.io.FileFilter
import java.io.IOException
import java.util.*
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

/**
 * Represents the APKs for a package.
 * [packageName] is the package for all of the APKs in [sortedApks].
 */
sealed class PackageApkGroup private constructor(
    val packageName: String,
    /**
     * A set of [AndroidApk] instances for [packageName], sorted in ascending order
     */
    val sortedApks: SortedSet<AndroidApk>
) {
    init {
        require(sortedApks.all { it.packageName == packageName })
    }
    val size: Int
        get() = (sortedApks as TreeSet).size
    val first: AndroidApk
        get() = sortedApks.first()
    val last: AndroidApk
        get() = sortedApks.last()

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

    class AscendingOrder constructor(packageName: String, apks: SortedSet<AndroidApk>) : PackageApkGroup(packageName, apks)
    class DescendingOrder constructor(packageName: String, apks: SortedSet<AndroidApk>) : PackageApkGroup(packageName, apks)

    companion object {
        fun fromIterable(
            packageName: String,
            apks: Iterable<AndroidApk>,
            ascendingOrder: Boolean
        ): PackageApkGroup {
            return if (ascendingOrder) {
                AscendingOrder(packageName, apks.toSortedSet(AndroidApk.ascendingVersionCodeComparator))
            } else {
                DescendingOrder(packageName, apks.toSortedSet(AndroidApk.descendingVersionCodeComparator))
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
