package api

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
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
                ?.map {
                    try {
                        it.name to Json.decodeFromString<LatestAppVersionInfo>(
                            fileManager.getLatestAppVersionInfoMetadata(it.name).readText()
                        )
                    } catch (e: IOException) {
                        null
                    }
                }
                ?.filterNotNull()
                ?.sortedBy { it.first }
                ?.associate { it.first to it.second.latestVersionCode }
                ?: throw IOException("failed to get files from app directory")
            return AppVersionIndex(map)
        }
    }
}