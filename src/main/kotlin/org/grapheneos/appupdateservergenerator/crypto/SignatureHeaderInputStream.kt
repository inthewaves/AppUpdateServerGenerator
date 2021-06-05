package org.grapheneos.appupdateservergenerator.crypto

import org.grapheneos.appupdateservergenerator.model.Base64String
import org.grapheneos.appupdateservergenerator.util.Base64Util
import org.grapheneos.appupdateservergenerator.util.readInt32Le
import org.grapheneos.appupdateservergenerator.util.writeInt32Le
import java.io.BufferedReader
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
 * ### Expected format with example
 *
 * An example of such a file (with a ECDSA signature):
 *
 *     YAAAAA== MEUCIGTui/RfLOJ+141P+VheLrik8f1nOtWVgmHqICRJb4gKAiEA6fJwrmDXShE6cT5fmRa62TFwTwKldaBkA834mmcEiao=
 *     This is going to be signed
 *     This is more signed text.
 *
 * The header first contains a 32-bit integer, expressed in little-endian and encoded as padded base64 (UTF-8). The
 * other UTF-8 base64 string is the cryptographic signature . A single line feed (`\n`) separates the signature header
 * from the signed contents. Headers can be created with the helper function
 * [SignatureHeaderInputStream.createSignatureHeaderWithLineFeed]. Note: The only acceptable newline character to
 * separate the signature header from the contents is `\n`. CRLF (`\r\n`) is not supported.
 *
 * The signed contents are exactly the bytes of the string
 *
 *     This is going to be signed\nThis is more signed text.
 *
 * The bytes of that string are also the exact bytes that will be given by the [read] functions.
 *
 * ### Usage
 *
 * [SignatureHeaderInputStream]s can be used like normal [InputStream]s, with operations such as [read]. The
 * parser will exclude the line separator and the signature line from signature verification. The bytes that are given
 * to the user via the [read] functions will not include the signature header or the line separator between the
 * signature and the rest of the contents.
 *
 * For use with a [Reader], [BufferedReader], a JSON parser etc., the wrap order should be
 * `Reader(SignatureHeaderInputStream(raw InputStream from file / network))`, because this needs access to the raw
 * bytes.
 *
 * On instance creation, the [signature] instance is initiated for verification with the public key. The signature is
 * parsed on the first invocation of [read] (either function), and then the signature stored in the stream. Every
 * invocation of [read] will result in an update to the underlying [java.security.Signature] instance.
 *
 * After consuming the input stream, the signature should verified against the bytes in the underlying [in]put stream by
 * using [verify] or [verifyOrThrow]. Due to the nature of cryptographic signatures, the correct time to call the
 * [verify] functions is when the input stream is completely consumed.
 *
 * Note: This class is **not** thread-safe.
 */
class SignatureHeaderInputStream private constructor(
    stream: InputStream,
    publicKey: PublicKey?,
    certificate: Certificate?,
    /** The signature associated with this stream. */
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
     * Constructs an instance of [SignatureHeaderInputStream] and an existing [Signature] instance
     * and initializing the instance for verification using the given [Certificate].
     *
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
     * [Signature.getInstance] with the [signatureAlgorithm] and initializing it for verification using the given
     * [publicKey]. The [SignatureHeaderInputStream] will be verifying the non-signature bytes of the given [stream].
     *
     * @throws InvalidKeyException if the [publicKey] is invalid
     * @throws NoSuchAlgorithmException if no [Provider] supports a [Signature] implementation for the specified
     * [signatureAlgorithm]
     * @throws java.security.NoSuchProviderException – if a specified [provider] is given but is not registered in the
     * security provider list
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

    /**
     * Constructs an instance of [SignatureHeaderInputStream] and  an existing [Signature] instance
     * and initializing the instance it for verification using the given [publicKey]. The [SignatureHeaderInputStream]
     * will be verifying the non-signature bytes of the given [stream].
     *
     * @throws java.security.InvalidKeyException – if the [publicKey] is invalid.
     */
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

    /**
     * The maximum length of a signature from the given public key (or public key inside of a certificate).
     * This is used to make sure signature-header parsing doesn't read too much.
     */
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
     * The signature (raw bytes, not base64-encoded) extracted from the header. This is initialized on the first call to
     * the [read] functions.
     */
    private var signatureBytes: ByteArray? = null

    /**
     * Parses the signature line and populates the [signatureBytes] property with the signature for future verification.
     *
     * @throws IOException
     */
    private fun parseSignatureBytesByReadingFirstLine() {
        if (signatureBytes != null) return

        // Parse the signature header.
        // A signature header-ed file looks like this:
        //
        //     YAAAAA== MEUCIGTui/RfLOJ+141P+VheLrik8f1nOtWVgmHqICRJb4gKAiEA6fJwrmDXShE6cT5fmRa62TFwTwKldaBkA834mmcEiao=
        //     This is going to be signed
        //     This is more signed text.
        //
        // YAAAAA== is the length of the signature in little endian encoded as a 32-bit integer (8 bytes in base64 with
        // padding),
        // MEUCIGTui/RfLOJ+141P+VheLrik8f1nOtWVgmHqICRJb4gKAiEA6fJwrmDXShE6cT5fmRa62TFwTwKldaBkA834mmcEiao= is signature
        // "This is going to be signed\nThis is more signed text." are the signed contents

        // Read the signature length as a 32-bit integer, which is encoded as 8 bytes in base64.
        val signatureLengthBuffer = ByteArray(8)
        if (`in`.read(signatureLengthBuffer) != signatureLengthBuffer.size) {
            throw IOException("reached end of stream: expected signature length")
        }
        val signatureLength: Int = try {
            Base64.getDecoder().decode(signatureLengthBuffer).readInt32Le()
        } catch (e: IllegalArgumentException) {
            throw IOException("signature length isn't valid base64", e)
        }
        if (signatureLength <= 0) throw IOException("signature length too small")
        if (signatureLength > maxSignatureLengthEncodedInBase64 + 2) throw IOException("signature length too big")

        // Read the actual signature
        if (`in`.read() == -1) throw IOException("reached end of stream: expected space between length and signature")
        val signatureBuffer = ByteArray(signatureLength)
        if (`in`.read(signatureBuffer) != signatureBuffer.size) {
            throw IOException("reached end of stream instead of signature")
        }
        // Note: \n is the only acceptable new line delimiter. CRLF \r\n is undefined, and this tool specifically
        // doesn't do CRLF when creating signed metadata.
        if (`in`.read() == -1) throw IOException("malformed signature: missing line feed after signature")

        signatureBytes = try {
            Base64.getDecoder().decode(signatureBuffer)
        } catch (e: IllegalArgumentException) {
            throw IOException("signature isn't valid base64", e)
        }
    }

    /**
     * @return true if the signature was verified, false if not.
     *
     * @throws GeneralSecurityException if there wasn't a signature parsed from the header
     * @throws SignatureException if this signature object is not initialized properly, the passed-in signature is
     * improperly encoded or of the wrong type, if this signature algorithm is unable to process the input data
     * provided, etc.
     */
    @Throws(SignatureException::class)
    fun verify(): Boolean = signatureBytes?.let { signature.verify(it) }
        ?: throw GeneralSecurityException("missing signature bytes")

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
     * Reads a byte, and updates the signature instance if [on].
     *
     * If this is the first time either of the [read] functions have been called, the stream will be first parsed for
     * the signature in the first line.
     *
     * @return the next byte of data, or -1 if the end of the stream is reached.
     * @throws IOException if an I/O error occurs or if the [signature] instance is not initialized properly. Note that
     * [java.security.SignatureException]s from the [signature] are wrapped in [IOException]s for compatibility.
     * @see [InputStream.read]
     * @see [Signature.update]
     */
    override fun read(): Int {
        if (signatureBytes == null) {
            parseSignatureBytesByReadingFirstLine()
        }

        val byte = `in`.read()
        if (byte != -1) {
            try {
                signature.update(byte.toByte())
            } catch (e: SignatureException) {
                throw IOException(e)
            }
        }
        return byte
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
     * @throws IOException if an I/O error occurs or if the [signature] instance is not initialized properly. Note that
     * [java.security.SignatureException]s from the [signature] are wrapped in [IOException]s for compatibility.
     * @see [InputStream.read]
     * @see [Signature.update]
     */
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (signatureBytes == null) {
            parseSignatureBytesByReadingFirstLine()
        }

        val numBytesRead: Int = `in`.read(b, off, len)
        if (numBytesRead != -1) {
            try {
                signature.update(b, off, numBytesRead)
            } catch (e: SignatureException) {
                throw IOException(e)
            }
        }
        return numBytesRead
    }

    override fun skip(n: Long): Long {
        // these skip methods are not intended to be used with this class, since it defeats the purpose of verifying
        // the bytes of this stream
        if (signatureBytes == null) {
            parseSignatureBytesByReadingFirstLine()
        }
        return super.skip(n)
    }

    override fun skipNBytes(n: Long) {
        // these skip methods are not intended to be used with this class, since it defeats the purpose of verifying
        // the bytes of this stream
        if (signatureBytes == null) {
            parseSignatureBytesByReadingFirstLine()
        }
        super.skipNBytes(n)
    }

    /**
     * Tests if this [InputStream] supports the [mark] and [reset], which it doesn't.
     */
    override fun markSupported(): Boolean = false

    /**
     * Prints a string representation of this signature input stream and
     * its associated [signature] object.
     */
    override fun toString(): String {
        return "[Signature Input Stream] $signature"
    }

    companion object {
        /**
         * Creates a properly-formatted signature header with the expected line feed character at the end.
         */
        fun createSignatureHeaderWithLineFeed(signature: Base64String): String =
            Base64.getEncoder().encodeToString(signature.s.length.writeInt32Le()) + " " + signature.s + "\n"
    }
}