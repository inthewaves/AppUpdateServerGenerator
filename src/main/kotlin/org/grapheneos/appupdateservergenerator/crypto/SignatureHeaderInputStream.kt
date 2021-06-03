package org.grapheneos.appupdateservergenerator.crypto

import org.grapheneos.appupdateservergenerator.util.Base64Util
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.Reader
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
 * This InputStream is a special [InputStream] for reading plaintext files that are expected to have a base64-encoded
 * signature signed by the given public key or certificate in the first line, and the signed contents in the rest of the
 * file.
 *
 * [SignatureHeaderInputStream]s can be used as a normal [InputStream], with the operations such as [read]. The
 * parser will exclude the line separator and the signature line from signature verification. The bytes that are given
 * to the user via the [read] functions will not include the base64-encoded signature or the line separator between the
 * signature and the rest of the contents.
 *
 * For use with a [Reader], [BufferedReader], a JSON parser etc., the wrap order should be
 * `Reader(SignatureHeaderInputStream(raw InputStream from file / network))`, because this needs access to the raw
 * bytes.
 *
 * On instance creation, the [signature] instance is initiated with the public key. The signature is parsed on the
 * first invocations of [read], and then the signature stored in the stream. Every invocation of [read] will result in
 * an update to the underlying [java.security.Signature] instance.
 *
 * After consuming the input stream, the signature should verified against the bytes in the underlying [in]put stream by
 * using [verify] or [verifyOrThrow]. The only valid time for signature verification is when the input stream is
 * completely consumed, as that is when the underlying [signature] instance has read all the bytes for verification.
 *
 * An example of such a file (with a ECDSA signature):
 *
 *     MEQCIEeMy08Ef6Kd+KuR1IkalbM7vb3jd8xz2BFwWldDKwBOAiBLT7DOZokfWSMZec7VCS5Ggq2Y9nAAzVlVEJlU1T2cmg==
 *     This is going to be signed
 *     This is more signed text.
 *
 * The signed contents are exactly the bytes of the string
 *
 *     This is going to be signed\nThis is more signed text.
 *
 * The bytes of that string are also the exact bytes that will be given by the [read] functions. The public key used to
 * sign the above example is
 *
 *     -----BEGIN PUBLIC KEY-----
 *     MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEJKBDo8lXMKktway7BM+1CJpsLNbt
 *     Ws9SWYO7r967MOdecCQwbLGSO35cSsI8L1C1mMeMNbLqjoHy6TUruik+rQ==
 *     -----END PUBLIC KEY-----
 *
 * Note: This class is **not** thread-safe.
 *
 * Note: The signature header files should NOT use a single CR control character (`\r`) to separate the signature from
 * the associated signed contents. If the contents of a file begins with a LF control character (`\n`), the parser will
 * interpret that as a CRLF sequence and skip both the CR and LF from signature verification.
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

    /**
     * Constructs a [SignatureHeaderInputStream] instance using the [certificate] and the [signatureAlgorithm] for
     * verifying the stream contents past the signature header.
     *
     * @throws IllegalArgumentException – if the provider name is null or empty
     * @throws java.security.InvalidKeyException – if the public key in the [certificate] is not encoded properly or
     * does not include required parameter information or cannot be used for digital signature purposes.
     * @throws java.security.NoSuchAlgorithmException – if a SignatureSpi implementation for the specified algorithm is
     * not available from the specified provider
     * @throws java.security.NoSuchProviderException – if the specified provider is not registered in the security
     * provider list
     */
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

    /**
     * @throws java.security.InvalidKeyException – if the public key in the [certificate] is not encoded properly or
     * does not include required parameter information or cannot be used for digital signature purposes.
     */
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

    private val maxSignatureLengthEncodedInBase64: Int
    init {
        require((publicKey != null) xor (certificate != null))
        maxSignatureLengthEncodedInBase64 = Base64Util.getBase64SizeForLength(
            if (publicKey != null) {
                signature.initVerify(publicKey)
                publicKey.maxSignatureLength()
            } else {
                signature.initVerify(certificate!!)
                certificate.publicKey.maxSignatureLength()
            }
        )
    }

    /**
     * The signature extracted from the header. This is initialized on the first call to the [read] functions, because
     * those functions need to know about the CRLF edge case.
     */
    private var signatureBytes: ByteArray? = null

    /**
     * Parses the signature line and populates the [signatureBytes] property with the signature for future verification.
     *
     * @return null if the line separator between the signature and the signed contents is not a CR control character
     * (`\r`), or if the line separator is a CRLF sequence. A non-null result is returned if the line separator is a CR
     * control character is something that isn't a LF (i.e., not a CRLF sequence). Callers of this function should patch
     * contents with the returned byte if it's not null.
     * @throws IOException
     */
    @Throws(IOException::class)
    private fun parseSignatureBytesByReadingFirstLine(): Int? {
        if (signatureBytes != null) return null

        // Parse the signature header. Don't verify this part of the stream.
        on = false
        val signatureStream = ByteArrayOutputStream(maxSignatureLengthEncodedInBase64)
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
     * Reads a byte, and updates the signature instance if [on].
     *
     * If this is the first time either of the [read] functions have been called, the stream will be first parsed for
     * the signature in the first line.
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
            // This function call will advance the stream until the signature line is parsed.
            // If this is null,
            val nextByteAfterCr = parseSignatureBytesByReadingFirstLine()
            // Fixing up from CRLF detection if needed.
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
     * If this is the first time either of the [read] functions have been called, the stream will be first parsed for
     * the signature in the first line.
     *
     * @return the total number of bytes read into the buffer, or -1 if there is no more data because the end of the
     * stream has been reached.
     * @exception IOException if an IO error occurs.
     * @see [InputStream.read]
     * @see [Signature.update]
     */
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val numBytesRead: Int = if (signatureBytes != null || len <= 0) {
            `in`.read(b, off, len)
        } else {
            val nonLineFeedByteRightAfterCr = parseSignatureBytesByReadingFirstLine()
            nonLineFeedByteRightAfterCr?.let {
                // We're fixing up the CRLF detection.
                b[off] = it.toByte()
                if (len > 1) {
                    val result = `in`.read(b, off + 1, len - 1)
                    // If the underlying input stream returns -1, we still want to communicate to the caller that
                    // the CRLF line fixup resulted in a byte "read". If we just left this at -1, then 1 - 1 = 0 :o
                    1 + if (result == -1) 0 else result
                } else {
                    // Note: We know that len > 0 here from the outer if statement; so since in here we have len <= 1,
                    // we conclude that len == 1. Therefore, the single-byte fixup is the number of bytes read.
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