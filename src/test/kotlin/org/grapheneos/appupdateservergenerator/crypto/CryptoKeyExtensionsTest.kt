package org.grapheneos.appupdateservergenerator.crypto

import kotlinx.coroutines.runBlocking
import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.asn1.ASN1Integer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.ArgumentsProvider
import org.junit.jupiter.params.provider.ArgumentsSource
import org.junit.jupiter.params.provider.CsvSource
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Random
import java.util.stream.Stream

internal class CryptoKeyExtensionsTest {
    @ParameterizedTest(name = "getSignatureLengthForRSAKey: {0}-bit key signing [{1}]")
    @CsvSource(
        "2048, This is a string",
        "4096, This is a another string"
    )
    fun getSignatureLengthForRSAKey(keySize: Int, stringToSign: String) {
        val (privateKey: RSAPrivateKey, pubKey: RSAPublicKey) =
            KeyPairGenerator.getInstance("RSA").run {
                initialize(keySize)
                genKeyPair()
                    .let { it.private as RSAPrivateKey to it.public as RSAPublicKey }
            }

        val signature: ByteArray = Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey)
            update(stringToSign.encodeToByteArray())
            sign()!!
        }

        assertEquals(signature.size, pubKey.signatureLength())
    }

    class ECStdNameStringToSignArguments : ArgumentsProvider {
        override fun provideArguments(context: ExtensionContext?): Stream<out Arguments> =
            Stream.of(
                Arguments.of("secp256r1", "This is going to be signed"),
                Arguments.of("secp224r1", "sign this piece of text!"),
                Arguments.of("secp384r1", "this is something that you need to sign"),
                Arguments.of("secp521r1", "Another piece of text that needs signing!"),
                Arguments.of("sect571r1", "Something else that needs signing!")
            )
    }
    @ParameterizedTest(name = "testECSignatureLenUpperBound: {0} EC key signing [{1}]")
    @ArgumentsSource(ECStdNameStringToSignArguments::class)
    fun testECSignatureLenUpperBound(stdName: String, stringToSign: String) = runBlocking {
        val (privateKey: ECPrivateKey, publicKey: ECPublicKey) = KeyPairGenerator.getInstance("EC")
            .run {
                initialize(ECGenParameterSpec(stdName))
                generateKeyPair().run { private as ECPrivateKey to public as ECPublicKey }
            }

        val stringAsBytes = stringToSign.encodeToByteArray()
        val upperBound = publicKey.maxSignatureLength()
        val signatureInstance = Signature.getInstance("SHA256withECDSA")
            .apply { initSign(privateKey) }
        // check various signatures due to the variance in signature length
        repeat(100) {
            val signature: ByteArray = signatureInstance
                .run {
                    update(stringAsBytes)
                    sign()!!
                }
            // the signature size varies for ECDSA since signing involves a pseudorandom integer
            assert(signature.size in (upperBound - 4)..upperBound) {
                "length check failed: got actual signature size of ${signature.size}, but upperbound was $upperBound." +
                        " Expected to be in the range ${(upperBound - 4)..upperBound}"
            }
        }
    }

    class BigIntArguments : ArgumentsProvider {
        override fun provideArguments(context: ExtensionContext?): Stream<out Arguments> =
            Stream.iterate(Arguments.of(BigInteger.probablePrime(16, Random(5)))) {
                val bigInt = it.get()[0] as BigInteger
                Arguments.of(bigInt * 2.toBigInteger().pow(128))
            }.limit(90)
    }
    @ParameterizedTest(name = "testAsn1NumLengthOctetsCalculation: {0} octet")
    @ArgumentsSource(BigIntArguments::class)
    fun testAsn1NumLengthOctetsCalculation(bigIntToEncode: BigInteger) {
        assertEquals(
            ASN1Integer(bigIntToEncode).getEncoded(ASN1Encoding.DER).size,
            1 + calculateNumOfLengthOctetsForDER(bigIntToEncode.toByteArray().size) +
                    bigIntToEncode.toByteArray().size
        ) {
            "failed to get correct length octets for big int $bigIntToEncode"
        }
    }
}