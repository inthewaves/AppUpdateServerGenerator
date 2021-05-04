package util.invoker

import model.HexString
import java.io.File
import java.io.IOException
import java.nio.file.Path

class ApkSignerInvoker(private val apkSignerPath: Path = Path.of("apksigner")) : Invoker(apkSignerPath) {
    fun getSigningCerts(apkFile: File): List<HexString> {
        val certProcess: Process = ProcessBuilder().run {
            command(apkSignerPath.toString(), "verify", "--print-certs", apkFile.absolutePath)
            start()
        }
        val certificateSha256Regex = Regex("^Signer #[0-9]* certificate SHA-256 digest: ([0-9a-f]*)$")
        val certificates = mutableListOf<HexString>()
        certProcess.inputStream.bufferedReader().use { reader ->
            reader.forEachLine { line ->
                certificateSha256Regex.matchEntire(line)?.let { matchResult ->
                    certificates.add(HexString(matchResult.groupValues[1]))
                }
            }
        }
        if (certificates.isEmpty()) {
            throw IOException("missing signing certificates")
        }

        return certificates
    }
}