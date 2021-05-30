package org.grapheneos.appupdateservergenerator.db

import org.grapheneos.appupdateservergenerator.api.AppMetadata
import org.grapheneos.appupdateservergenerator.api.toSerializableModel
import org.grapheneos.appupdateservergenerator.files.AppDir
import org.grapheneos.appupdateservergenerator.model.GroupId
import org.grapheneos.appupdateservergenerator.model.PackageApkGroup
import org.grapheneos.appupdateservergenerator.model.PackageName
import org.grapheneos.appupdateservergenerator.model.UnixTimestamp
import org.grapheneos.appupdateservergenerator.model.VersionCode
import org.grapheneos.appupdateservergenerator.repo.AppRepoException
import org.grapheneos.appupdateservergenerator.util.executeAsSequence
import java.io.File
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

    fun getSerializableAppMetadata(packageName: PackageName): AppMetadata? =
        database.transactionWithResult {
            getApp(packageName)?.let { getSerializableAppMetadata(it) }
        }

    fun getSerializableAppMetadata(app: App): AppMetadata =
        database.transactionWithResult {
            val allReleases = database.appReleaseQueries.selectAllByApp(app.packageName)
                .executeAsSequence { releasesSequence ->
                    releasesSequence.mapTo(TreeSet()) { release ->
                        val deltaInfo: TreeSet<AppMetadata.DeltaInfo> = database.deltaInfoQueries
                            .selectAllForTargetVersion(release.packageName, release.versionCode)
                            .executeAsSequence { deltaInfos ->
                                deltaInfos.mapTo(TreeSet()) { it.toSerializableModel() }
                            }

                        return@mapTo AppMetadata.ReleaseInfo(
                            versionCode = release.versionCode,
                            versionName = release.versionName,
                            minSdkVersion = release.minSdkVersion,
                            releaseTimestamp = release.releaseTimestamp,
                            sha256Checksum = release.sha256Checksum,
                            deltaInfo = deltaInfo,
                            releaseNotes = release.releaseNotes
                        )
                    }
                }

            return@transactionWithResult AppMetadata(
                packageName = app.packageName,
                groupId = app.groupId,
                label = app.label,
                lastUpdateTimestamp = app.lastUpdateTimestamp,
                releases = allReleases
            )
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
        updateTimestamp: UnixTimestamp
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
                val newApkFile = File(appDir.dir, "${apkToInsert.versionCode.code}.apk")
                apkToInsert.apkFile.copyTo(newApkFile)
                println("copied ${apkToInsert.apkFile.absolutePath} to ${newApkFile.absolutePath}")

                database.appReleaseQueries.insert(
                    apkToInsert.toAppRelease(
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
