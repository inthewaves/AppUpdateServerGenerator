package org.grapheneos.appupdateservergenerator.db

import kotlinx.coroutines.channels.SendChannel
import org.grapheneos.appupdateservergenerator.files.FileManager
import org.grapheneos.appupdateservergenerator.model.PackageName
import org.grapheneos.appupdateservergenerator.repo.DeltaGenerationManager
import java.io.IOException

class DeltaInfoDao(private val fileManager: FileManager) {
    fun insertDeltaInfos(database: Database, deltaInfo: Iterable<DeltaInfo>) {
        database.transaction {
            deltaInfo.forEach { database.deltaInfoQueries.insertOrReplace(it) }
        }
    }

    fun deleteDeltasForApp(database: Database, packageName: PackageName, printer: SendChannel<DeltaGenerationManager.PrintRequest>?) {
        fileManager.getDirForApp(packageName).listDeltaFilesUnsorted().forEach { oldDelta ->
            printer?.trySend(DeltaGenerationManager.PrintRequest.NewLine("deleting outdated delta ${oldDelta.name}"))
            if (!oldDelta.delete()) {
                throw IOException("failed to delete $oldDelta")
            }
        }

        database.transaction {
            database.deltaInfoQueries.deleteAllDeltasForApp(packageName)
        }
    }
}