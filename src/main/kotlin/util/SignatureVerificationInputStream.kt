package util

import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.*
import java.util.*

/**
 * This InputStream allows for parsing a metadata file line-by-line without
 * needing to write the entire file to the disk, verifying the signature,
 * and then parsing it again.
 */
class SignatureVerificationInputStream
@Throws(InvalidKeyException::class)
constructor(
    stream: InputStream,
    val publicKey: PublicKey,
    signatureAlgorithm: String,
    provider: String? = null
) : FilterInputStream(stream) {
    /**
     * The signature associated with this stream.
     */
    val signature: Signature = provider
        ?.let { Signature.getInstance(signatureAlgorithm, it) } ?: Signature.getInstance(signatureAlgorithm)
        .apply {
            initVerify(publicKey)
        }

    /**
     * Turns the signature verification function on or off. The default is on.
     * On means that the [read] methods will update the [signature] instance.
     */
    var on = true

    /**
     * Reads a byte, and updates the signature instance if [on]
     *
     * @return the next byte of data, or -1 if the end of the stream is reached.
     * @exception IOException if an IO error occurs.
     * @see [InputStream.read]
     * @see [Signature.update]
     */
    @Throws(IOException::class)
    override fun read(): Int {
        val ch = `in`.read()
        if (on && ch != -1) {
            signature.update(ch.toByte())
        }
        return ch
    }

    /**
     * Reads the input stream and updates the associated [signature] instance if the signature
     * stream is [on].
     *
     * @return the total number of bytes read into the buffer, or -1 if there is no more data because the end of the
     * stream has been reached.
     * @exception IOException if an IO error occurs.
     * @see [InputStream.read]
     * @see [Signature.update]
     */
    @Throws(IOException::class)
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val result = `in`.read(b, off, len)
        if (on && result != -1) {
            signature.update(b, off, result)
        }
        return result
    }

    /**
     * Does the same thing as [forEachLineIndexedThenVerify] but doesn't use index.
     * @see forEachLineIndexedThenVerify
     */
    @Throws(GeneralSecurityException::class, IOException::class, SignatureException::class, )
    inline fun forEachLineThenVerify(crossinline action: (String) -> Unit) =
        forEachLineIndexedThenVerify { _, line -> action(line) }

    /**
     * For a file with header containing a base64-encoded signature, this function runs the given [action]
     * on every non-signature line in the file, and then after all of the lines are processed, verifies
     * the input stream byte contents with the associated [signature] instance. The portion of the stream
     * that is verified is after the signature header.
     *
     * **Note: It's the caller's responsibility to close the stream.**
     *
     * @throws GeneralSecurityException if the signature verification fails. This will be thrown when all the lines
     * have been read due to the nature of this being an input stream.
     * @throws IOException if an I/O error occurs or the signature header is malformed.
     * @throws SignatureException if this signature object is not initialized properly, the passed-in signature is
     * improperly encoded or of the wrong type, if this signature algorithm is unable to process the input data
     * provided, etc.
     */
    @Throws(GeneralSecurityException::class, IOException::class, SignatureException::class, )
    fun forEachLineIndexedThenVerify(action: (Int, String) -> Unit) {
        // Parse the signature header. Don't verify this part of the stream.
        on = false
        val signatureStream = ByteArrayOutputStream(Base64Util.getSizeWhenEncodedAsBase64(publicKey.maxSignatureLength))
        var current: Int = read()
        while (
            current != -1 &&
            current.toChar().let { it != '\n' && it != '\r'}
        ) {
            signatureStream.write(current)
            current = read()
        }
        val signatureDecoded: ByteArray = try {
            Base64.getDecoder().decode(signatureStream.toByteArray())
        } catch (e: IllegalArgumentException) {
            throw IOException(e)
        }

        // Account for CRLF. Not like this is expected anyway if the server generation is done
        // on Linux.
        val nextCharAfterCr: Char? = if (current.toChar() != '\r') {
            null
        } else {
            val nextChar: Int = read()
            if (nextChar.toChar() != '\n') {
                // This is not a CRLF, so we read a character that should be included
                // with the actual string.
                signature.update(nextChar.toByte())
                // Remember it for later.
                nextChar
            } else {
                null
            }
        }?.toChar()

        // Start verifying the rest of the stream. The BufferedReader here will
        // loading big chunks of the underlying input stream.
        on = true
        bufferedReader().lineSequence().forEachIndexed { index, line ->
            if (index != 0) {
                action(index, line)
            } else {
                // Include the character that we read if present.
                action(index, nextCharAfterCr?.let { it + line } ?: line)
            }
        }

        if (!signature.verify(signatureDecoded)) {
            throw GeneralSecurityException("signature for the input stream failed verification")
        }
    }

    /**
     * Prints a string representation of this signature input stream and
     * its associated [signature] object.
     */
    override fun toString(): String {
        return "[Signature Input Stream] $signature"
    }
}