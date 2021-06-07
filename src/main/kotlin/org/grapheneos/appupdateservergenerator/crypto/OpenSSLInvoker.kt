package org.grapheneos.appupdateservergenerator.crypto

import org.grapheneos.appupdateservergenerator.files.TempFile
import org.grapheneos.appupdateservergenerator.model.Base64String
import org.grapheneos.appupdateservergenerator.util.Invoker
import org.grapheneos.appupdateservergenerator.util.prependString
import org.grapheneos.appupdateservergenerator.util.readTextFromErrorStream
import org.grapheneos.appupdateservergenerator.util.removeFirstLine
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.GeneralSecurityException

class OpenSSLInvoker(apkSignerPath: Path = Path.of("openssl")) : Invoker(executablePath = apkSignerPath) {
    /**
     * Parses the [unencryptedPKCS8KeyFile] to return a type-safe [PKCS8PrivateKeyFile] instance
     *
     * @throws IOException if an I/O error occurs, openssl fails to parse the key, or an unsupported key algorithm
     * is being passed in.
     */
    fun getKeyWithType(unencryptedPKCS8KeyFile: File): PKCS8PrivateKeyFile {
        val asn1ParseProcess: Process =
            ProcessBuilder(
                executablePath.toString(), "asn1parse", "-inform", "DER", "-in", unencryptedPKCS8KeyFile.absolutePath,
                "-item", "PKCS8_PRIV_KEY_INFO"
            ).start()

        val asn1Stuff = asn1ParseProcess.inputStream.bufferedReader().use { it.readText() }
        asn1ParseProcess.waitFor()
        if (asn1ParseProcess.exitValue() != 0) {
            throw IOException("openssl asn1parse failed to run: ${asn1ParseProcess.readTextFromErrorStream()}")
        }

        val algorithmLine = algorithmLineRegex.find(asn1Stuff)?.groupValues?.get(2)
            ?: throw IOException("failed to find algorithm line")

        return try {
            PKCS8PrivateKeyFile.fromAlgorithmLine(unencryptedPKCS8KeyFile, algorithmLine)
        } catch (e: IllegalArgumentException) {
            throw IOException(e)
        }
    }

    /**
     * Generates a public key in PEM format from the given [privateKey].
     *
     * @throws IOException if an I/O error occurs or openssl fails to generate a public key.
     */
    fun getPublicKey(privateKey: PKCS8PrivateKeyFile): PEMPublicKey {
        val pkcs8Process = ProcessBuilder(
            executablePath.toString(), "pkcs8", "-inform", "DER", "-in", privateKey.file.absolutePath, "-nocrypt"
        ).start()
        val pubKeyCreatorProcess: Process = if (privateKey is PKCS8PrivateKeyFile.RSA) {
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
            throw IOException("failed to generate public key: ${pubKeyCreatorProcess.readTextFromErrorStream()}")
        }

        return pubKey
    }

    /**
     * Signs the bytes read from the [inputStream] using the given [privateKey].
     * The hash algorithm is SHA256. If [PKCS8PrivateKeyFile.RSA] is being used, then PSS padding is applied.
     *
     * Note: It is the caller's responsibility to close the [inputStream]
     *
     * @return a base64-encoded signature of the bytes from the [inputStream]
     * @throws IOException if an I/O exception occurs
     */
    fun signFromInputStream(privateKey: PKCS8PrivateKeyFile, inputStream: InputStream): Base64String {
        val signingProcess: Process = createDgstSigningProcess(privateKey)
        signingProcess.outputStream.use { output -> inputStream.copyTo(output) }
        val signature: Base64String = signingProcess.inputStream.use {
            try {
                Base64String.encodeFromBytes(it.readBytes())
            } catch (e: IllegalArgumentException) {
                throw IOException(e)
            }
        }
        signingProcess.waitFor()
        if (signingProcess.exitValue() != 0) {
            throw IOException("dgst returned non-zero exit code: ${signingProcess.readTextFromErrorStream()}")
        }
        return signature
    }

    /**
     * @see signFromInputStream
     * @return a base64-encoded signature of the [string]
     * @throws IOException if an I/O exception occurs
     */
    fun signString(privateKey: PKCS8PrivateKeyFile, string: String): Base64String =
        signFromInputStream(privateKey, string.byteInputStream())

    /**
     * Signs the binary contents of the [fileToSign] using the [privateKey].
     *
     * @see signFromInputStream
     * @return a base64-encoded signature of the [fileToSign]
     * @throws IOException if an I/O exception occurs
     */
    fun signFile(privateKey: PKCS8PrivateKeyFile, fileToSign: File): Base64String {
        val fileSize = Files.readAttributes(fileToSign.toPath(), "size")
            .run { get("size") as Long }
        if (fileSize <= 0) {
            throw IOException("cannot sign an empty file")
        }

        return fileToSign.inputStream().use {
            signFromInputStream(privateKey, it)
        }
    }

    /**
     * Signs the binary contents of the [fileToSign] using the [privateKey] and then prepends the signature in the
     * first line of the file. The [fileToSign] should be a plaintext file.
     *
     * @see signFromInputStream
     * @see prependString
     * @return a base64-encoded signature of the [fileToSign]
     * @throws IOException if an I/O exception occurs
     */
    fun signFileAndPrependSignatureToFile(privateKey: PKCS8PrivateKeyFile, fileToSign: File): Base64String =
        signFile(privateKey, fileToSign)
            .also { signature ->
                fileToSign.prependString(SignatureHeaderInputStream.createSignatureHeaderWithLineFeed(signature))
            }

    /**
     * @throws GeneralSecurityException
     * @throws IOException
     */
    fun verifyFileWithSignatureHeader(file: File, publicKeyFile: File) {
        TempFile.create("verifyFileWithSignatureHeader").useFile { tempFileToVerify ->
            TempFile.create("signature").useFile { tempSignatureFile ->
                file.copyTo(tempFileToVerify, overwrite = true)
                val base64Signature = Base64String.fromBase64(
                    tempFileToVerify.removeFirstLine() ?: throw GeneralSecurityException("missing signature header")
                )
                tempSignatureFile.outputStream().buffered().use { it.write(base64Signature.bytes) }

                val verifyProcess = ProcessBuilder(
                    executablePath.toString(),
                    "dgst",
                    "-sha256",
                    "-verify",
                    publicKeyFile.absolutePath,
                    "-signature",
                    tempSignatureFile.absolutePath,
                    tempFileToVerify.absolutePath
                ).start()
                verifyProcess.waitFor()
                if (verifyProcess.exitValue() != 0) {
                    val errorMessage = verifyProcess.readTextFromErrorStream()
                        .ifBlank { verifyProcess.inputStream.bufferedReader().readText() }
                        .trimEnd('\n')
                    throw GeneralSecurityException("openssl dgst verification of file $file failed: $errorMessage")
                }
            }
        }
    }

    /**
     * Runs `openssl dgst` with the appropriate arguments in order to sign a file. The hash function is SHA256.
     * If the [privateKey] is [PKCS8PrivateKeyFile.RSA], SHA256withRSA/PSS is used.
     */
    private fun createDgstSigningProcess(privateKey: PKCS8PrivateKeyFile): Process {
        val command = mutableListOf<String>(
            executablePath.toString(), "dgst", "-sha256", "-keyform", "DER", "-sign", privateKey.file.absolutePath
        ).apply {
            if (privateKey is PKCS8PrivateKeyFile.RSA) {
                addAll(listOf("-sigopt", "rsa_padding_mode:pss", "-sigopt", "rsa_pss_saltlen:digest"))
            }
        }
        return ProcessBuilder(command).start()
    }

    companion object {
        private val algorithmLineRegex = Regex("(algorithm: )(.*)")
    }
}