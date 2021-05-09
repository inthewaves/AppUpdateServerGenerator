package org.grapheneos.appupdateservergenerator.util.invoker

import org.grapheneos.appupdateservergenerator.model.HexString
import java.io.File
import java.io.IOException
import java.nio.file.Path

class ApkSignerInvoker(apkSignerPath: Path = Path.of("apksigner")) : Invoker(executablePath = apkSignerPath) {
    fun verifyAndGetSigningCerts(apkFile: File): List<HexString> {
        val certProcess: Process = ProcessBuilder(
            executablePath.toString(), "verify", "--print-certs", apkFile.absolutePath
        ).start()

        val certificates = mutableListOf<HexString>()
        certProcess.inputStream.bufferedReader().use { reader ->
            reader.forEachLine { line ->
                certificateSha256Regex.matchEntire(line)?.let { matchResult ->
                    certificates.add(HexString(matchResult.groupValues[1]))
                }
            }
        }
        if (certificates.isEmpty()) {
            throw IOException("failed to parse signing certificates for $apkFile from apksigner verify")
        }

        certProcess.waitFor()
        if (certProcess.exitValue() != 0) {
            throw IOException("apksigner verify exited with non-zero exit code ($apkFile): ${certProcess.exitValue()}")
        }

        return certificates
    }

    companion object {
        private val certificateSha256Regex = Regex("^Signer #[0-9]* certificate SHA-256 digest: ([0-9a-f]*)$")
    }
}