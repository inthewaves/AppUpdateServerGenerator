package org.grapheneos.appupdateservergenerator.crypto

import org.grapheneos.appupdateservergenerator.util.Base64Util
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.NoSuchAlgorithmException
import java.security.Provider
import java.security.PublicKey
import java.security.Signature
import java.security.SignatureException
import java.security.cert.Certificate
import java.util.Base64

/**
 * This InputStream is a special [InputStream] for reading files that are expected to have a base64-encoded signature
 * signed by the given [publicKey] in the first line.
 *
 * On instance creation, the [signature] instance is initiated with the public key. The signature is parsed on the
 * first invocations of [read], and then the signature stored in the stream. The signature can be verified against the
 * bytes in the underlying [in]put stream by using [verify] or [verifyOrThrow].
 *
 * Note: This class is **not** thread-safe.
 */
class SignatureHeaderInputStream private constructor(
    stream: InputStream,
    publicKey: PublicKey?,
    certificate: Certificate?,
    /**
     * The signature associated with this stream.
     */
    val signature: Signature
) : FilterInputStream(stream) {

    constructor(
        stream: InputStream,
        certificate: Certificate,
        signatureAlgorithm: String,
        provider: String? = null
    ) : this(
        stream = stream,
        publicKey = null,
        certificate = certificate,
        signature = provider
            ?.let { Signature.getInstance(signatureAlgorithm, it) }
            ?: Signature.getInstance(signatureAlgorithm)
    )

    constructor(
        stream: InputStream,
        certificate: Certificate,
        signature: Signature
    ) : this(
        stream = stream,
        publicKey = null,
        certificate = certificate,
        signature = signature
    )

    /**
     * Constructs an instance of [SignatureHeaderInputStream] and a new instance of [Signature] via
     * [Signature.getInstance].
     *
     * @throws InvalidKeyException if the [publicKey] is invalid
     * @throws NoSuchAlgorithmException if no [Provider] supports a [Signature] implementation for the specified
     * [signatureAlgorithm]
     */
    constructor(
        stream: InputStream,
        publicKey: PublicKey,
        signatureAlgorithm: String,
        provider: String? = null
    ) : this(
        stream = stream,
        publicKey = publicKey,
        certificate = null,
        signature = provider
            ?.let { Signature.getInstance(signatureAlgorithm, it) }
            ?: Signature.getInstance(signatureAlgorithm)
    )

    constructor(
        stream: InputStream,
        publicKey: PublicKey,
        signature: Signature
    ) : this(
        stream = stream,
        publicKey = publicKey,
        certificate = null,
        signature = signature
    )

    private val maxSignatureLength: Int
    init {
        require((publicKey != null) xor (certificate != null))
        maxSignatureLength = if (publicKey != null) {
            signature.initVerify(publicKey)
            publicKey.maxSignatureLength()
        } else {
            signature.initVerify(certificate!!)
            certificate.publicKey.maxSignatureLength()
        }
    }

    private var signatureBytes: ByteArray? = null

    /**
     * @return the next byte after the carriage return, if any. Such a value will be returned if the signature line
     * is separated from the rest of the contents by a singular \r.
     * @throws IOException
     */
    @Throws(IOException::class)
    private fun initiateSignatureBytesByReadingFirstLine(): Int? {
        if (signatureBytes != null) return null

        // Parse the signature header. Don't verify this part of the stream.
        on = false
        val signatureStream = ByteArrayOutputStream(Base64Util.getBase64SizeForLength(maxSignatureLength))
        var current: Int = `in`.read()
        while (
            current != -1 &&
            current.toChar().let { it != '\n' && it != '\r'}
        ) {
            signatureStream.write(current)
            current = `in`.read()
        }

        signatureBytes = try {
            Base64.getDecoder().decode(signatureStream.toByteArray())
        } catch (e: IllegalArgumentException) {
            throw IOException("signature header isn't valid base64", e)
        }

        // Account for CRLF. Not like this is expected anyway if the server generation is done
        // on Linux, but this is for completeness.
        val nextByteAfterCr = if (current.toChar() == '\r') {
            val nextChar: Int = `in`.read()
            if (nextChar.toChar() != '\n') {
                // This is not a CRLF, so we read a character that should be included
                // with the actual string. Remember it for later.
                nextChar
            } else {
                null
            }
        } else {
            null
        }

        // Start verifying the rest of the stream.
        on = true

        return nextByteAfterCr
    }

    /**
     * @return true if the signature was verified, false if not.
     * @throws SignatureException if this signature object is not initialized properly, the passed-in signature is
     * improperly encoded or of the wrong type, if this signature algorithm is unable to process the input data
     * provided, etc.
     */
    @Throws(SignatureException::class)
    fun verify(): Boolean = signatureBytes?.let { signature.verify(it) }
        ?: throw GeneralSecurityException("missing signature")

    /**
     * @throws GeneralSecurityException if signature verification fails
     * @throws SignatureException if this signature object is not initialized properly, the passed-in signature is
     * improperly encoded or of the wrong type, if this signature algorithm is unable to process the input data
     * provided, etc.
     */
    @Throws(GeneralSecurityException::class, SignatureException::class)
    fun verifyOrThrow() {
        if (!verify()) throw GeneralSecurityException("signature verification failed")
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
    override fun read(): Int {
        val ch = if (signatureBytes != null) {
            `in`.read()
        } else {
            val nextByteAfterCr = initiateSignatureBytesByReadingFirstLine()
            nextByteAfterCr ?: `in`.read()
        }
        if (on && ch != -1) {
            signature.update(ch.toByte())
        }
        return ch
    }

    /**
     * Reads the input stream and updates the associated [signature] instance if the signature
     * stream is [on].
     *
     * Reads up to [len] bytes of data from the input stream into an array of bytes. An attempt is made to read as many
     * as [len] bytes, but a smaller number may be read. The number of bytes actually read is returned as an integer.
     *
     * This method blocks until input data is available, end of file is detected, or an exception is thrown.
     *
     * If len is zero, then no bytes are read and 0 is returned; otherwise, there is an attempt to read at least one
     * byte. If no byte is available because the stream is at end of file, the value -1 is returned; otherwise, at least
     * one byte is read and stored into b.
     *
     * The first byte read is stored into element b[off], the next one into `b[off+1]`, and so on. The number of bytes
     * read is, at most, equal to len. Let k be the number of bytes actually read; these bytes will be stored in
     * elements b[off] through b[off+k-1], leaving elements b[off+k] through b[off+len-1] unaffected.
     * In every case, elements `b[0]` through `b[off-1]` and elements `b[off+len]` through `b[b.length-1]` are unaffected.
     *
     * @return the total number of bytes read into the buffer, or -1 if there is no more data because the end of the
     * stream has been reached.
     * @exception IOException if an IO error occurs.
     * @see [InputStream.read]
     * @see [Signature.update]
     */
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val numBytesRead = if (signatureBytes != null || len <= 0) {
            `in`.read(b, off, len)
        } else {
            val nextByteAfterCr = initiateSignatureBytesByReadingFirstLine()
            nextByteAfterCr?.let {
                b[off] = it.toByte()
                if (len > 1) {
                    1 + `in`.read(b, off + 1, len - 1)
                } else {
                    1
                }
            } ?: `in`.read(b, off, len)
        }
        if (on && numBytesRead != -1) {
            signature.update(b, off, numBytesRead)
        }
        return numBytesRead
    }

    /**
     * Prints a string representation of this signature input stream and
     * its associated [signature] object.
     */
    override fun toString(): String {
        return "[Signature Input Stream] $signature"
    }
}