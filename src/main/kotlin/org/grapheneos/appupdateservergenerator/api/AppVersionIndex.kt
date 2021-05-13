package org.grapheneos.appupdateservergenerator.api

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.grapheneos.appupdateservergenerator.crypto.OpenSSLInvoker
import org.grapheneos.appupdateservergenerator.crypto.PKCS8PrivateKeyFile
import org.grapheneos.appupdateservergenerator.files.FileManager
import org.grapheneos.appupdateservergenerator.model.UnixTimestamp
import org.grapheneos.appupdateservergenerator.model.VersionCode
import java.io.IOException
import java.util.*

data class AppVersionIndex constructor(
    val timestamp: UnixTimestamp,
    val packageToVersionMap: SortedMap<String, VersionCode>
) {

    fun writeToDiskAndSign(privateKey: PKCS8PrivateKeyFile, openSSLInvoker: OpenSSLInvoker, fileManager: FileManager) {
        val latestAppVersionIndex = fileManager.latestAppVersionIndex
        latestAppVersionIndex.bufferedWriter().use { writer ->
            writer.appendLine(timestamp.seconds.toString())
            packageToVersionMap.forEach { entry -> writer.appendLine("${entry.key}:${entry.value.code}") }
        }
        openSSLInvoker.signFileAndPrependSignatureToFile(privateKey, latestAppVersionIndex)
    }

    companion object {
        /**
         * Creates a new [AppVersionIndex] instance from the files on disk in the database.
         */
        suspend fun create(fileManager: FileManager, timestamp: UnixTimestamp): AppVersionIndex = coroutineScope {
            val map = fileManager.appDirectory.listFiles()
                ?.filter { it.isDirectory }
                ?.map { dirForApp ->
                    async {
                        try {
                            AppMetadata.getMetadataFromDiskForPackage(dirForApp.name, fileManager)
                        } catch (e: IOException) {
                            null
                        }
                    }
                }
                ?.awaitAll()
                ?.filterNotNull()
                // sort by keys
                ?.associateTo(TreeMap()) { it.packageName to it.latestVersionCode }
                ?: throw IOException("failed to get files from app directory")
            AppVersionIndex(timestamp, map)
        }
    }
}