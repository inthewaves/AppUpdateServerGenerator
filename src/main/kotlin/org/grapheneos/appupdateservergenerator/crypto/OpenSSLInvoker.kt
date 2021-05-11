package org.grapheneos.appupdateservergenerator.crypto

import org.grapheneos.appupdateservergenerator.files.prependLine
import org.grapheneos.appupdateservergenerator.model.Base64String
import org.grapheneos.appupdateservergenerator.util.Invoker
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

class OpenSSLInvoker(apkSignerPath: Path = Path.of("openssl")) : Invoker(executablePath = apkSignerPath) {
    @Throws(IOException::class)
    fun getKeyWithType(keyFile: File): PrivateKeyFile {
        val asn1ParseProcess: Process =
            ProcessBuilder(
                executablePath.toString(), "asn1parse", "-inform", "DER", "-in", keyFile.absolutePath,
                "-item", "PKCS8_PRIV_KEY_INFO"
            ).start()

        val asn1Stuff = asn1ParseProcess.inputStream.bufferedReader().use { it.readText() }
        asn1ParseProcess.waitFor()
        if (asn1ParseProcess.exitValue() != 0) {
            throw IOException("openssl asn1parse failed to run")
        }

        val algorithmLine = algorithmLineRegex.find(asn1Stuff)?.groupValues?.get(2)
            ?: throw IOException("failed to find algorithm line")

        return try {
            PrivateKeyFile.fromAlgorithmLine(keyFile, algorithmLine)
        } catch (e: IllegalArgumentException) {
            throw IOException(e)
        }
    }

    @Throws(IOException::class)
    fun getPublicKey(privateKey: PrivateKeyFile): PEMPublicKey {
        val pkcs8Process = ProcessBuilder(
            executablePath.toString(), "pkcs8", "-inform", "DER", "-in", privateKey.file.absolutePath, "-nocrypt"
        ).start()
        val pubKeyCreatorProcess: Process = if (privateKey is PrivateKeyFile.RSA) {
            ProcessBuilder(executablePath.toString(), "rsa", "-pubout").start()
        } else {
            ProcessBuilder(executablePath.toString(), "ec", "-pubout").start()
        }
        pubKeyCreatorProcess.outputStream.use { output ->
            pkcs8Process.inputStream.use { it.copyTo(output) }
        }
        val pubKey = PEMPublicKey(pubKeyCreatorProcess.inputStream.bufferedReader().use { it.readText() })

        pubKeyCreatorProcess.waitFor()
        if (pubKeyCreatorProcess.exitValue() != 0) {
            val errorInfo = pubKeyCreatorProcess.errorStream.bufferedReader().use { it.readText() }
            throw IOException("failed to generate public key: $errorInfo")
        }

        return pubKey
    }

    /**
     * Note: It is the caller's responsibility to close the [inputStream]
     */
    @Throws(IOException::class)
    fun signFromInputStream(privateKey: PrivateKeyFile, inputStream: InputStream): Base64String {
        val signingProcess: Process = createDgstSigningProcess(privateKey)
        signingProcess.outputStream.use { output -> inputStream.copyTo(output) }
        val signature: Base64String = signingProcess.inputStream.use {
            try {
                Base64String.fromBytes(it.readBytes())
            } catch (e: IllegalArgumentException) {
                throw IOException(e)
            }
        }
        signingProcess.waitFor()
        if (signingProcess.exitValue() != 0) {
            throw IOException("dgst returned non-zero exit code")
        }
        return signature
    }

    @Throws(IOException::class)
    fun signString(privateKey: PrivateKeyFile, string: String): Base64String =
        signFromInputStream(privateKey, string.byteInputStream())

    @Throws(IOException::class)
    fun signFile(privateKey: PrivateKeyFile, fileToSign: File): Base64String {
        val fileSize = Files.readAttributes(fileToSign.toPath(), "size")
            .run { get("size") as Long }
        if (fileSize <= 0) {
            throw IOException("cannot sign an empty file")
        }

        return fileToSign.inputStream().use {
            signFromInputStream(privateKey, it)
        }
    }

    @Throws(IOException::class)
    fun signFileAndPrependSignatureToFile(privateKey: PrivateKeyFile, fileToSign: File): Base64String =
        signFile(privateKey, fileToSign)
            .also { signature -> fileToSign.prependLine(signature.s) }

    private fun createDgstSigningProcess(privateKey: PrivateKeyFile): Process {
        val command = mutableListOf<String>(
            executablePath.toString(), "dgst", "-sha256", "-keyform", "DER", "-sign", privateKey.file.absolutePath
        ).apply {
            if (privateKey is PrivateKeyFile.RSA) {
                addAll(listOf("-sigopt", "rsa_padding_mode:pss", "-sigopt", "rsa_pss_saltlen:digest"))
            }
        }
        return ProcessBuilder(command).start()
    }

    companion object {
        private val algorithmLineRegex = Regex("(algorithm: )(.*)")
    }
}