package org.grapheneos.appupdateservergenerator.apkparsing

import org.grapheneos.appupdateservergenerator.model.HexString
import org.grapheneos.appupdateservergenerator.util.Invoker
import org.grapheneos.appupdateservergenerator.util.readTextFromErrorStream
import java.io.File
import java.io.IOException
import java.nio.file.Path

@Deprecated(
    "use ApkVerifier directly",
    ReplaceWith(
        "ApkVerifier.Builder(apkFile).build().verify()",
        "com.android.apksig.ApkVerifier"
    )
)
class ApkSignerInvoker(apkSignerPath: Path = Path.of("apksigner")) : Invoker(executablePath = apkSignerPath) {
    /**
     * Verifies the [apkFile] using apksigner and returns a list of the SHA256 hashes of the signing
     * certificates.
     *
     * @throws IOException if an I/O error occurs or verification failed or apksigner fails.
     */
    @Deprecated(
        "use ApkVerifier directly",
        ReplaceWith(
            "ApkVerifier.Builder(apkFile).build().verify()",
            "com.android.apksig.ApkVerifier"
        )
    )
    fun verifyAndGetSigningCerts(apkFile: File): List<HexString> {
        val certProcess: Process = ProcessBuilder(
            executablePath.toString(), "verify", "--print-certs", apkFile.absolutePath
        ).start()

        val certificates = mutableListOf<HexString>()
        certProcess.inputStream.bufferedReader().use { reader ->
            reader.forEachLine { line ->
                certificateSha256Regex.matchEntire(line)?.let { matchResult ->
                    certificates.add(HexString.fromHex(matchResult.groupValues[1]))
                }
            }
        }

        certProcess.waitFor()
        if (certProcess.exitValue() != 0) {
            val errorMessage = certProcess.readTextFromErrorStream()
            throw IOException(
                "apksigner verify exited with non-zero exit code ($apkFile): ${certProcess.exitValue()}:\n$errorMessage"
            )
        }
        if (certificates.isEmpty()) {
            throw IOException("failed to parse signing certificates for $apkFile from apksigner verify")
        }

        return certificates
    }

    companion object {
        private val certificateSha256Regex = Regex("^Signer #[0-9]* certificate SHA-256 digest: ([0-9a-f]*)$")
    }
}