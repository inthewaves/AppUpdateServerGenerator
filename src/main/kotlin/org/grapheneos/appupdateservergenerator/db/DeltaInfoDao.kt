package org.grapheneos.appupdateservergenerator.db

import org.grapheneos.appupdateservergenerator.files.FileManager
import org.grapheneos.appupdateservergenerator.model.PackageName
import java.io.IOException

class DeltaInfoDao(private val database: Database, private val fileManager: FileManager) {
    fun insertDeltaInfos(deltaInfo: Iterable<DeltaInfo>) {
        database.transaction {
            deltaInfo.forEach { database.deltaInfoQueries.insertOrReplace(it) }
        }
    }

    fun deleteDeltasForApp(packageName: PackageName) {
        fileManager.getDirForApp(packageName).listDeltaFilesUnsorted().forEach { oldDelta ->
            println("deleting outdated delta ${oldDelta.name}")
            if (!oldDelta.delete()) {
                throw IOException("failed to delete $oldDelta")
            }
        }

        database.transaction {
            database.deltaInfoQueries.deleteAllDeltasForApp(packageName)
        }
    }
}