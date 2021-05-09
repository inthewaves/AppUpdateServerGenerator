package api

import model.VersionCode
import util.FileManager
import java.io.File
import java.io.IOException

data class AppVersionIndex constructor(val packageToVersionMap: Map<String, VersionCode>) {
    fun writeToFile(file: File) {
        file.bufferedWriter().use { writer ->
            packageToVersionMap.forEach { entry ->
                writer.appendLine("${entry.key}:${entry.value.code}")
            }
        }
    }

    companion object {
        fun create(fileManager: FileManager): AppVersionIndex {
            val map = fileManager.appDirectory.listFiles()?.asSequence()
                ?.filter { it.isDirectory }
                ?.map { dir ->
                    try {
                        LatestAppVersionInfo.getInfoFromDiskForPackage(dir.name, fileManager)
                    } catch (e: IOException) {
                        null
                    }
                }
                ?.filterNotNull()
                ?.sortedBy { it.packageName }
                ?.associate { it.packageName to it.latestVersionCode }
                ?: throw IOException("failed to get files from app directory")
            return AppVersionIndex(map)
        }
    }
}