package api

import model.UnixTimestamp
import model.VersionCode
import util.FileManager
import util.invoker.OpenSSLInvoker
import java.io.IOException

data class AppVersionIndex constructor(
    val timestamp: UnixTimestamp,
    val packageToVersionMap: Map<String, VersionCode>
) {
    constructor(map: Map<String, VersionCode>) : this(UnixTimestamp.now(), map)

    fun writeToDiskAndSign(key: OpenSSLInvoker.Key, openSSLInvoker: OpenSSLInvoker, fileManager: FileManager) {
        val latestAppVersionIndex = fileManager.latestAppVersionIndex
        latestAppVersionIndex.bufferedWriter().use { writer ->
            writer.appendLine(timestamp.seconds.toString())
            packageToVersionMap.forEach { entry -> writer.appendLine("${entry.key}:${entry.value.code}") }
        }
        openSSLInvoker.signFileAndPrependSignatureToFile(key, latestAppVersionIndex)
    }

    companion object {
        fun create(fileManager: FileManager): AppVersionIndex {
            val map = fileManager.appDirectory.listFiles()?.asSequence()
                ?.filter { it.isDirectory }
                ?.map { dirForApp ->
                    try {
                        LatestAppVersionInfo.getInfoFromDiskForPackage(dirForApp.name, fileManager)
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