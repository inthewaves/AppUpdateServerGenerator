package org.grapheneos.appupdateservergenerator.crypto

import java.math.BigInteger
import java.security.KeyException
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey

/**
 * Gets the maximum signature length for this public key.
 * This is only supported for [RSAPublicKey] and [ECPublicKey].
 *
 * @throws KeyException if the [PublicKey] is not one of the supported types.
 */
@Throws(KeyException::class)
fun PublicKey.maxSignatureLength(): Int =
    when (this) {
        is RSAPublicKey -> signatureLength()
        is ECPublicKey -> maxSignatureLength()
        else -> throw KeyException("unsupported key")
    }

/**
 * Returns the signature length for this [RSAPublicKey].
 * (Rounding up to the nearest byte.)
 */
fun RSAPublicKey.signatureLength(): Int = (modulus.bitLength() + 7) / 8

/**
 * The upper bound for the lengths of DER-encoded ECDSA signatures.
 *
 * ECDSA has signatures using integers that are partly calculated from a
 * (pseudo)random integer, so the signature length can vary. The best
 * we can provide is an upper bound.
 *
 * This is effectively the length of the DER encoding n concatenated twice, where n is
 * the order of the generator G ∈ E(GF(q)).
 *
 * https://cs.android.com/android/platform/superproject/+/master:external/bouncycastle/bcprov/src/main/java/org/bouncycastle/crypto/signers/StandardDSAEncoding.java
 */
fun ECPublicKey.maxSignatureLength(): Int {
    /*
    ECDSA generates signatures consisting of the integers r, s that are in the
    closed interval [1, n - 1], where n is the order of the base point G ∈ E(GF(q)).
    The values of r and s partly come from a (pseudo)randomly generated integer, so
    the signature length can vary.

    n - 1 is the least upper bound for r and s, because r and s are calculated mod n, So, the length of n - 1
    concatenated with n - 1 will give us an upper bound for the signature. We concatenate, because ECDSA signatures
    in DER format are encoded as follows (https://tools.ietf.org/html/rfc3279#section-2.2.3):
        Ecdsa-Sig-Value  ::=  SEQUENCE  {
            r     INTEGER,
            s     INTEGER  }
     */
    val lengthInBytesOfMaxValOfRandS = (params.order - BigInteger.ONE).bitLength() / 8 + 1

    // DER: Using definite-length method, for an ASN.1 integer, we need to encode
    //      [identifier octets][length octets][content octets]
    // https://www.itu.int/rec/T-REC-X.690/en
    val lengthOfMaxAsASN1Int = 1 + // number of identifier octets
            calculateNumOfLengthOctetsForDER(lengthInBytesOfMaxValOfRandS) + // number of length octets
            lengthInBytesOfMaxValOfRandS // number of content octets

    // A DER sequence is also encoded in the following way:
    //      [identifier octets][length octets][content octets]
    // The content octets are the two integers, hence there are 2 * nEncodedAsASN1Integer content octets.
    val numContentOctetsForSequence = 2 * lengthOfMaxAsASN1Int
    return 1 + calculateNumOfLengthOctetsForDER(numContentOctetsForSequence) + numContentOctetsForSequence
}

/**
 * For the definite form, calculates the number of length octets, where the length octets is the number of
 * octets in the contents octets (i.e. [numContentOctets]).
 *
 * For a [BigInteger] x,
 * ```
 * 1 + calculateNumOfLengthOctetsForDER(x.toByteArray().size) + x.toByteArray().size
 * ````
 * is equal to `ASN1Integer(x).getEncoded(ASN1Encoding.DER).size` (where `ASN1Integer` is from `org.bouncycastle.asn1`),
 * since ASN.1 definite form encodes identifier octets, followed by length octets, followed by content octets. So,
 *
 * - number of identifier octets: 1
 * - number of length octets: calculated by this function
 * - number of content octets: x.toByteArray().size
 *
 * Note: Since we're using DER, we use the minimum necessary octets.
 *
 * See X.690: Information technology - ASN.1 encoding rules: Specification of Basic Encoding Rules (BER),
 * Canonical Encoding Rules (CER) and Distinguished Encoding Rules (DER)
 * (https://www.itu.int/rec/T-REC-X.690-202102-I/en)
 *
 * See https://cs.android.com/android/platform/superproject/+/master:external/bouncycastle/bcprov/src/main/java/org/bouncycastle/asn1/StreamUtil.java;l=63
 */
fun calculateNumOfLengthOctetsForDER(numContentOctets: Int): Int {
    /*
    From 8.1.3.3 in X.690:

    For the definite form, the length octets shall consist of one or more octets, and shall represent the number
    of octets in the contents octets using either the short form (see 8.1.3.4) or the long form (see 8.1.3.5) as a
    sender's option.

    NOTE - The short form can only be used if the number of octets in the contents octets is less than or equal to 127
     */
    if (numContentOctets <= 127) {
        /*
        From 8.1.3.4 in X.690:

        In the short form, the length octets shall consist of a single octet in which bit 8 is zero and bits 7 to 1
        encode the number of octets in the contents octets (which may be zero), as an unsigned binary integer with bit 7
        as the most significant bit.

        EXAMPLE: L = 38 can be encoded as 00100110₂
         */

        // A single octet for short form.
        return 1
    } else {
        /*
        From 8.1.3.5 in X.690:

        In the long form, the length octets shall consist of an initial octet and one or more subsequent
        octets. The initial octet shall be encoded as follows

        a) bit 8 shall be one;
        b) bits 7 to 1 shall encode the number of subsequent octets in the length octets, as an unsigned binary integer
           with bit 7 as the most significant bit;
        c) the value 11111111₂ shall not be used.
           (NOTE 1 – This restriction is introduced for possible future extension.)

        Bits 8 to 1 of the first subsequent octet, followed by bits 8 to 1 of the second subsequent octet, followed in
        turn by bits 8 to 1 of each further octet up to and including the last subsequent octet, shall be the encoding
        of an unsigned binary integer equal to the number of octets in the contents octets, with bit 8 of the first
        subsequent octet as the most significant bit.

        NOTE 2 – In the long form, it is a sender's option whether to use more length octets than the minimum necessary
        EXAMPLE: L = 201 can be encoded as: 10000001₂ 11001001₂
        EXAMPLE: L = 545 can be encoded as: 10000010₂ 00000010₂ 00100001₂.
                 10000010₂ represents 2 in decimal (2 subsequent octets), and 10000010₂ 00000010₂ is 545 in decimal.
         */

        // Determine how many octets it will take to encode numContentOctets.
        var numberOfSubsequentOctets = 1
        var value = numContentOctets ushr 8
        while (value != 0) {
            numberOfSubsequentOctets++
            value = value ushr 8
        }
        // The initial octet contributes to the number of length octets.
        return 1 + numberOfSubsequentOctets
    }
}
