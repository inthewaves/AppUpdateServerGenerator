package org.grapheneos.appupdateservergenerator.db

import org.grapheneos.appupdateservergenerator.api.AppMetadata
import org.grapheneos.appupdateservergenerator.api.toAppReleaseDbModel
import org.grapheneos.appupdateservergenerator.api.toSerializableModel
import org.grapheneos.appupdateservergenerator.api.toSerializableModelAndVerify
import org.grapheneos.appupdateservergenerator.files.AppDir
import org.grapheneos.appupdateservergenerator.files.FileManager
import org.grapheneos.appupdateservergenerator.model.ApkVerifyResult
import org.grapheneos.appupdateservergenerator.model.GroupId
import org.grapheneos.appupdateservergenerator.model.PackageApkGroup
import org.grapheneos.appupdateservergenerator.model.PackageName
import org.grapheneos.appupdateservergenerator.model.UnixTimestamp
import org.grapheneos.appupdateservergenerator.model.VersionCode
import org.grapheneos.appupdateservergenerator.repo.AppRepoException
import org.grapheneos.appupdateservergenerator.util.executeAsSequence
import java.util.TreeSet

class AppDao(private val database: Database) {
    fun updateReleaseNotes(
        packageName: PackageName,
        version: VersionCode,
        newReleaseNotes: String?,
        newReleaseTime: UnixTimestamp
    ) {
        database.appReleaseQueries.updateReleaseNotes(newReleaseNotes, newReleaseTime, packageName, version)
    }

    fun getLatestRelease(packageName: PackageName): AppRelease? =
        database.appReleaseQueries
            .selectLatestRelease(packageName)
            .executeAsOneOrNull()

    fun getRelease(packageName: PackageName, version: VersionCode): AppRelease? =
        database.appReleaseQueries
        .selectByAppAndVersion(packageName, version)
        .executeAsOneOrNull()

    fun getApp(packageName: PackageName): App? = database.appQueries.select(packageName).executeAsOneOrNull()

    fun getAppLabelsInGroup(groupId: GroupId): List<PackageLabelsByGroup> = database.appQueries
        .packageLabelsByGroup(groupId)
        .executeAsList()

    fun getAppsInGroupButExcludingApps(groupId: GroupId, groupsToExclude: Collection<PackageName>) =
        database.appQueries.appsInGroupAndNotInSet(groupId, groupsToExclude).executeAsList()

    fun createSerializableAppMetadata(
        app: App,
        repoIndexTimestamp: UnixTimestamp,
        fileManager: FileManager
    ): AppMetadata = database.transactionWithResult {
        val allReleases: TreeSet<AppMetadata.ReleaseInfo> =
            database.appReleaseQueries.selectAllByApp(app.packageName)
                .executeAsSequence { releasesSequence ->
                    releasesSequence.mapTo(TreeSet()) { release ->
                        val deltaInfo: TreeSet<AppMetadata.ReleaseInfo.DeltaInfo> = database.deltaInfoQueries
                            .selectAllForTargetVersion(release.packageName, release.versionCode)
                            .executeAsSequence { deltaInfos ->
                                deltaInfos.mapTo(TreeSet()) { it.toSerializableModel() }
                            }

                        return@mapTo release.toSerializableModelAndVerify(deltaInfo, fileManager)
                    }
                }

        return@transactionWithResult app.toSerializableModel(repoIndexTimestamp, allReleases)
    }

    fun doesAppExist(packageName: PackageName): Boolean {
        return database.appQueries.doesAppExist(packageName).executeAsOne() == 1L
    }

    fun forEachAppName(action: (packageName: PackageName) -> Unit) {
        database.appQueries.orderedPackages().executeAsSequence { sequence -> sequence.forEach(action) }
    }

    fun setGroupForPackages(groupId: GroupId?, packages: Iterable<PackageName>, updateTimestamp: UnixTimestamp) {
        database.transaction {
            packages.forEach { database.appQueries.setGroup(groupId, updateTimestamp, it) }
        }
    }

    fun upsertApks(
        appDir: AppDir,
        apksToInsert: PackageApkGroup,
        icon: ByteArray?,
        releaseNotesForMostRecentVersion: String?,
        updateTimestamp: UnixTimestamp,
        fileManager: FileManager
    ) {
        if (apksToInsert.size <= 0) return

        if (!appDir.dir.exists()) {
            if (!appDir.dir.mkdirs()) {
                throw AppRepoException.InsertFailed("failed to create directory ${appDir.dir.absolutePath}")
            }
        }

        database.transaction {
            val mostRecentApk = apksToInsert.highestVersionApk!!
            database.appQueries.upsertWithoutGroup(
                packageName = mostRecentApk.packageName,
                label = mostRecentApk.label,
                icon = icon,
                lastUpdateTimestamp = updateTimestamp
            )
            apksToInsert.sortedApks.forEach { apkToInsert ->
                val apkFileLocationInRepo = fileManager.getVersionedApk(apkToInsert.packageName, apkToInsert.versionCode)
                apkToInsert.apkFile.copyTo(apkFileLocationInRepo)
                println("copied ${apkToInsert.apkFile.absolutePath} to ${apkFileLocationInRepo.absolutePath}")
                if (apkToInsert.verifyResult is ApkVerifyResult.V4) {
                    val originalV4SigFile = apkToInsert.verifyResult.v4SignatureFile
                    val v4SigInRepo = fileManager.getVersionedApkV4Signature(apkToInsert.packageName, apkToInsert.versionCode)
                    originalV4SigFile.copyTo(v4SigInRepo)
                    println("copied $originalV4SigFile to $v4SigInRepo")
                }

                database.appReleaseQueries.insert(
                    apkToInsert.toAppReleaseDbModel(
                        releaseTimestamp = updateTimestamp,
                        releaseNotes = if (apkToInsert.versionCode == mostRecentApk.versionCode) {
                            releaseNotesForMostRecentVersion
                        } else {
                            null
                        }
                    )
                )
            }
        }
    }
}
