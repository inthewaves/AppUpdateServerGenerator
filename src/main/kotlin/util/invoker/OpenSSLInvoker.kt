package util.invoker

import model.Base64String
import util.prependLine
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class OpenSSLInvoker(apkSignerPath: Path = Path.of("openssl")) : Invoker(executablePath = apkSignerPath) {
    @Throws(IOException::class)
    fun getKeyWithType(keyFile: File): Key {
        val asn1ParseProcess: Process = ProcessBuilder().run {
            command(executablePath.toString(), "asn1parse", "-inform", "DER", "-in", keyFile.absolutePath,
                "-item", "PKCS8_PRIV_KEY_INFO")
            start()
        }

        val asn1Stuff = asn1ParseProcess.inputStream.bufferedReader().use { it.readText() }
        asn1ParseProcess.waitFor()
        if (asn1ParseProcess.exitValue() != 0) {
            throw IOException("openssl asn1parse failed to run")
        }

        val algorithmLine = algorithmLineRegex.find(asn1Stuff)?.groupValues?.get(2)
            ?: throw IOException("failed to find algorithm line")

        val keyType = KeyType.values().find { algorithmLine.startsWith(it.algorithmIdentifier) }
            ?: throw IOException("only supported key types are ${KeyType.values().toList()}, but got $algorithmLine")

        return Key(file = keyFile, keyType = keyType)
    }

    @Throws(IOException::class)
    fun signFile(key: Key, fileToSign: File): Base64String {
        val fileSize = Files.readAttributes(fileToSign.toPath(), "size")
            .run { get("size") as Long }
        if (fileSize <= 0) {
            throw IOException("cannot sign an empty file")
        }

        val command = mutableListOf<String>(
            executablePath.toString(), "dgst", "-sha256", "-keyform", "DER", "-sign", key.file.absolutePath
        ).apply {
            if (key.keyType == KeyType.RSA) {
                addAll(listOf("-sigopt", "rsa_padding_mode:pss", "-sigopt", "rsa_pss_saltlen:digest"))
            }
        }
        val signingProcess: Process = ProcessBuilder(command).start()
        fileToSign.inputStream().use { input ->
            signingProcess.outputStream.use { output -> input.copyTo(output) }
        }

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
    fun signFileAndPrependSignatureToFile(key: Key, fileToSign: File): Base64String =
        signFile(key, fileToSign)
            .also { signature -> fileToSign.prependLine(signature.s) }

    data class Key(val file: File, val keyType: KeyType)

    enum class KeyType(val algorithmIdentifier: String) {
        RSA("rsaEncryption"), EC("id-ecPublicKey")
    }

    companion object {
        private val algorithmLineRegex = Regex("(algorithm: )(.*)")
    }
}