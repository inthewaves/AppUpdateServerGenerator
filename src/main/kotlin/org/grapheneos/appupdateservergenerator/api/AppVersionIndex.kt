package org.grapheneos.appupdateservergenerator.api

import org.grapheneos.appupdateservergenerator.crypto.OpenSSLInvoker
import org.grapheneos.appupdateservergenerator.crypto.PrivateKeyFile
import org.grapheneos.appupdateservergenerator.files.FileManager
import org.grapheneos.appupdateservergenerator.model.UnixTimestamp
import org.grapheneos.appupdateservergenerator.model.VersionCode
import java.io.IOException

data class AppVersionIndex constructor(
    val timestamp: UnixTimestamp,
    val packageToVersionMap: Map<String, VersionCode>
) {
    constructor(map: Map<String, VersionCode>) : this(UnixTimestamp.now(), map)

    fun writeToDiskAndSign(privateKey: PrivateKeyFile, openSSLInvoker: OpenSSLInvoker, fileManager: FileManager) {
        val latestAppVersionIndex = fileManager.latestAppVersionIndex
        latestAppVersionIndex.bufferedWriter().use { writer ->
            writer.appendLine(timestamp.seconds.toString())
            packageToVersionMap.forEach { entry -> writer.appendLine("${entry.key}:${entry.value.code}") }
        }
        openSSLInvoker.signFileAndPrependSignatureToFile(privateKey, latestAppVersionIndex)
    }

    companion object {
        fun create(fileManager: FileManager): AppVersionIndex {
            val map = fileManager.appDirectory.listFiles()?.asSequence()
                ?.filter { it.isDirectory }
                ?.map { dirForApp ->
                    try {
                        AppMetadata.getInfoFromDiskForPackage(dirForApp.name, fileManager)
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