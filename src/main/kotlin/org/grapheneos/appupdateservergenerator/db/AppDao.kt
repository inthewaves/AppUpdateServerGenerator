package org.grapheneos.appupdateservergenerator.db

import org.grapheneos.appupdateservergenerator.api.AppMetadata
import org.grapheneos.appupdateservergenerator.api.toAppReleaseDbModel
import org.grapheneos.appupdateservergenerator.api.toSerializableModel
import org.grapheneos.appupdateservergenerator.api.toSerializableModelAndVerify
import org.grapheneos.appupdateservergenerator.files.AppDir
import org.grapheneos.appupdateservergenerator.files.FileManager
import org.grapheneos.appupdateservergenerator.model.AndroidApk
import org.grapheneos.appupdateservergenerator.model.ApkVerifyResult
import org.grapheneos.appupdateservergenerator.model.Density
import org.grapheneos.appupdateservergenerator.model.PackageApkGroup
import org.grapheneos.appupdateservergenerator.model.PackageName
import org.grapheneos.appupdateservergenerator.model.UnixTimestamp
import org.grapheneos.appupdateservergenerator.model.VersionCode
import org.grapheneos.appupdateservergenerator.model.encodeToBase64String
import org.grapheneos.appupdateservergenerator.repo.AppRepoException
import org.grapheneos.appupdateservergenerator.util.digest
import org.grapheneos.appupdateservergenerator.util.executeAsSequence
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.SortedSet
import java.util.TreeSet

class AppDao(private val fileManager: FileManager) {
    fun updateReleaseNotes(
        database: Database,
        packageName: PackageName,
        version: VersionCode,
        newReleaseNotes: String?,
        newReleaseTime: UnixTimestamp
    ) {
        database.appReleaseQueries.updateReleaseNotes(newReleaseNotes, newReleaseTime, packageName, version)
    }

    fun getLatestRelease(database: Database, packageName: PackageName): AppRelease? =
        database.appReleaseQueries
            .selectLatestRelease(packageName)
            .executeAsOneOrNull()

    fun getRelease(database: Database, packageName: PackageName, version: VersionCode): AppRelease? =
        database.appReleaseQueries
        .selectByAppAndVersion(packageName, version)
        .executeAsOneOrNull()

    fun getApp(database: Database, packageName: PackageName): App? = database.appQueries.select(packageName).executeAsOneOrNull()

    fun createSerializableAppMetadataAndGetIcon(
        database: Database,
        app: App,
        repoIndexTimestamp: UnixTimestamp,
        iconMinimumDensity: Density
    ): Pair<AppMetadata, AndroidApk.AppIcon?> = database.transactionWithResult {
        val sha256MessageDigest = MessageDigest.getInstance("SHA-256")

        val allReleases: TreeSet<AppMetadata.ReleaseInfo> =
            database.appReleaseQueries.selectAllByApp(app.packageName)
                .executeAsSequence { releasesSequence ->
                    releasesSequence.mapTo(TreeSet()) { release ->
                        val deltaInfo: SortedSet<AppMetadata.ReleaseInfo.DeltaInfo> =
                            AppDir.DeltaFile.allFromAppDir(fileManager.getDirForApp(app.packageName))
                                .filter { it.targetVersion == release.versionCode }
                                .map {
                                    AppMetadata.ReleaseInfo.DeltaInfo(
                                        baseVersionCode = it.baseVersion,
                                        size = it.delta.length(),
                                        sha256 = it.delta.digest(sha256MessageDigest).encodeToBase64String()
                                    )
                                }
                                .toSortedSet()

                        return@mapTo release.toSerializableModelAndVerify(deltaInfo, fileManager)
                    }
                }

        val icon: AndroidApk.AppIcon? = try {
            allReleases.ifEmpty { null }
                ?.last()
                ?.apkFile
                ?.getIcon(iconMinimumDensity)
        } catch (e: IOException) {
            println("warning: ${app.packageName}: icon extraction failed with message: ${e.message}")
            null
        }
        return@transactionWithResult app.toSerializableModel(repoIndexTimestamp, icon, allReleases) to icon
    }

    fun doesAppExist(database: Database, packageName: PackageName): Boolean {
        return database.appQueries.doesAppExist(packageName).executeAsOne() == 1L
    }

    fun forEachAppName(database: Database, action: (packageName: PackageName) -> Unit) {
        database.appQueries.orderedPackages().executeAsSequence { sequence -> sequence.forEach(action) }
    }

    fun upsertApks(
        database: Database,
        apksToInsert: PackageApkGroup,
        releaseNotesForMostRecentVersion: String?,
        releaseTimestamp: UnixTimestamp
    ) {
        if (apksToInsert.size <= 0) return

        val appDir = fileManager.getDirForApp(apksToInsert.packageName)
        if (!appDir.dir.exists()) {
            if (!appDir.dir.mkdirs()) {
                throw AppRepoException.InsertFailed("failed to create directory ${appDir.dir.absolutePath}")
            }
        }

        val mostRecentApk = apksToInsert.highestVersionApk!!

        database.transaction {
            val newFiles = mutableListOf<File>()
            afterRollback {
                println("rolling back due to error: deleting $newFiles")
                newFiles.forEach { it.delete() }
            }

            database.appQueries.upsert(
                packageName = mostRecentApk.packageName,
                label = mostRecentApk.label,
                lastUpdateTimestamp = releaseTimestamp
            )

            apksToInsert.sortedApks.forEach { apkToInsert ->
                val apkFileLocationInRepo = fileManager.getVersionedApk(apkToInsert.packageName, apkToInsert.versionCode)
                apkToInsert.apkFile.copyTo(apkFileLocationInRepo)
                newFiles.add(apkFileLocationInRepo)
                println("copied ${apkToInsert.apkFile.absolutePath} to ${apkFileLocationInRepo.absolutePath}")

                if (apkToInsert.verifyResult is ApkVerifyResult.V4) {
                    val originalV4SigFile = apkToInsert.verifyResult.v4SignatureFile
                    val v4SigInRepo = fileManager.getVersionedApkV4Signature(apkToInsert.packageName, apkToInsert.versionCode)
                    originalV4SigFile.copyTo(v4SigInRepo)
                    newFiles.add(v4SigInRepo)
                    println("copied $originalV4SigFile to $v4SigInRepo")
                }

                val release = apkToInsert.toAppReleaseDbModel(
                    releaseTimestamp = releaseTimestamp,
                    releaseNotes = if (apkToInsert.versionCode == mostRecentApk.versionCode) {
                        releaseNotesForMostRecentVersion
                    } else {
                        null
                    }
                )
                database.appReleaseQueries.insert(release)
            }
        }
    }
}
