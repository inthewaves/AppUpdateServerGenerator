package util.invoker

import model.Base64String
import util.prependLine
import java.io.File
import java.io.IOException
import java.lang.IllegalArgumentException
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

class OpenSSLInvoker(apkSignerPath: Path = Path.of("openssl")) : Invoker(executablePath = apkSignerPath) {
    @Throws(IOException::class)
    fun getKeyType(keyFile: File): Key {
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
    fun signFileAndPrependToFile(key: Key, fileToSign: File) {
        val fileSize = Files.readAttributes(fileToSign.toPath(), "size")
            .run { get("size") as Long }
        if (fileSize <= 0) {
            throw IOException("cannot sign an empty file")
        }

        val dgstProcess: Process = ProcessBuilder().run {
            val command = mutableListOf<String>(
                executablePath.toString(), "dgst", "-sha256", "-keyform", "DER", "-sign", key.file.absolutePath
            ).apply {
                if (key.keyType == KeyType.RSA) {
                    addAll(listOf("-sigopt", "rsa_padding_mode:pss", "-sigopt", "rsa_pss_saltlen:digest"))
                }
            }
            command(command)
            start()
        }
        fileToSign.inputStream().use { input ->
            dgstProcess.outputStream.use { output -> input.copyTo(output) }
        }

        val digest: Base64String = dgstProcess.inputStream.use {
            try {
                Base64String.fromBytes(it.readBytes())
            } catch (e: IllegalArgumentException) {
                throw IOException(e)
            }
        }
        dgstProcess.waitFor()
        if (dgstProcess.exitValue() != 0) {
            throw IOException("dgst returned non-zero exit code")
        }

        fileToSign.prependLine(digest.s)
    }

    data class Key(val file: File, val keyType: KeyType)

    enum class KeyType(val algorithmIdentifier: String) {
        RSA("rsaEncryption"), EC("id-ecPublicKey")
    }

    companion object {
        private val algorithmLineRegex = Regex("(algorithm: )(.*)")
    }
}