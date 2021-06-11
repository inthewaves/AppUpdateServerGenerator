package org.grapheneos.appupdateservergenerator.repo

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.coroutineScope
import org.grapheneos.appupdateservergenerator.api.AppMetadata
import org.grapheneos.appupdateservergenerator.api.AppRepoIndex
import org.grapheneos.appupdateservergenerator.api.BulkAppMetadata
import org.grapheneos.appupdateservergenerator.crypto.OpenSSLInvoker
import org.grapheneos.appupdateservergenerator.crypto.PKCS8PrivateKeyFile
import org.grapheneos.appupdateservergenerator.db.AppDao
import org.grapheneos.appupdateservergenerator.db.DbWrapper
import org.grapheneos.appupdateservergenerator.files.FileManager
import org.grapheneos.appupdateservergenerator.model.Density
import org.grapheneos.appupdateservergenerator.model.UnixTimestamp
import org.grapheneos.appupdateservergenerator.util.executeAsSequence
import java.io.IOException

/**
 * Manages deletion and generation + signing of metadata files and icons.
 */
class StaticFileManager(
    private val appDao: AppDao,
    private val fileManager: FileManager,
    private val openSSLInvoker: OpenSSLInvoker,
) {
    private val dbWrapper = DbWrapper.getInstance(fileManager)

    private val filesNamesToDelete: Set<String> = setOf(
        FileManager.APP_ICON_FILENAME,
        FileManager.APP_METADATA_FILENAME,
        FileManager.APP_REPO_INDEX_FILENAME,
        FileManager.BULK_METADATA_FILENAME
    )

    private fun deleteOldMetadata(): Boolean {
        println("deleting old metadata files")
        var filesDeleted = 0
        fileManager.appDirectory
            .walkBottomUp()
            .maxDepth(2)
            .forEach { file ->
                if (file.name in filesNamesToDelete) {
                    if (!file.delete() && file.exists()) {
                        return false
                    }

                    filesDeleted++
                }
            }
        println("deleted $filesDeleted metadata and icon files")
        return true
    }

    suspend fun regenerateMetadataAndIcons(
        privateKeyFile: PKCS8PrivateKeyFile,
        updateTimestamp: UnixTimestamp,
    ): Unit = coroutineScope {
        println("regenerating metadata and icons")
        if (!deleteOldMetadata()) {
            throw AppRepoException.InvalidRepoState("failed to delete old metadata / icon files")
        }

        val metadataChannel = actor<AppMetadata>(capacity = Channel.UNLIMITED) {
            BulkAppMetadata.regenerateFromChannel(
                updateTimestamp,
                fileManager,
                openSSLInvoker,
                privateKeyFile
            ) { bulkAppChannel ->
                AppRepoIndex.regenerateIndexFromChannel(
                    updateTimestamp,
                    fileManager,
                    openSSLInvoker,
                    privateKeyFile
                ) { repoIndexChannel ->
                    var appsWritten = 0
                    for (appMetadata in channel) {
                        appMetadata.writeToDiskAndSign(privateKeyFile, openSSLInvoker, fileManager)
                        bulkAppChannel.send(appMetadata)
                        repoIndexChannel.send(appMetadata)
                        appsWritten++
                    }
                    println("regenerated metadata for $appsWritten ${if (appsWritten == 1) "app" else "apps"}")
                }
                println("regenerated the app repo index at ${fileManager.appIndex}")
            }
            println("regenerated the bulk app metadata at ${fileManager.bulkAppMetadata}")
        }

        try {
            dbWrapper.transaction { database ->
                database.appQueries.selectAll().executeAsSequence { apps ->
                    apps.forEach { app ->
                        // TODO: add this to some configuration
                        val iconMinimumDensity = Density.HIGH

                        val (metadata, icon) = appDao.createSerializableAppMetadataAndGetIcon(
                            database,
                            app,
                            updateTimestamp,
                            iconMinimumDensity = iconMinimumDensity
                        )

                        metadataChannel
                            .trySendBlocking(metadata)
                            .onFailure { throw IOException("failed to write ${app.packageName} into bulk metadata") }

                        if (icon != null) {
                            fileManager.getAppIconFile(app.packageName)
                                .outputStream()
                                .buffered()
                                .use { it.write(icon.bytes) }
                        } else {
                            val latestRelease = metadata.latestRelease()
                            val versionCode = latestRelease.versionCode
                            val versionName = latestRelease.versionName
                            println("warning: unable to extract icon for ${metadata.packageName}, " +
                                    "version $versionName (versionCode ${versionCode.code}) " +
                                    "for minimum density $iconMinimumDensity")
                        }
                    }
                }
            }
        } finally {
            metadataChannel.close()
        }
    }
}