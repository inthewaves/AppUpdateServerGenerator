package api

import model.VersionCode
import util.FileManager
import util.invoker.OpenSSLInvoker
import java.io.IOException

@JvmInline
value class AppVersionIndex constructor(val packageToVersionMap: Map<String, VersionCode>) {
    fun writeToDiskAndSign(key: OpenSSLInvoker.Key, openSSLInvoker: OpenSSLInvoker, fileManager: FileManager) {
        val latestAppVersionIndex = fileManager.latestAppVersionIndex
        latestAppVersionIndex.bufferedWriter().use { writer ->
            packageToVersionMap.forEach { entry -> writer.appendLine("${entry.key}:${entry.value.code}") }
        }
        openSSLInvoker.signFileAndPrependSignatureToFile(key, latestAppVersionIndex)
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