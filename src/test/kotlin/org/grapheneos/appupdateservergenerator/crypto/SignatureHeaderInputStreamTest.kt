package org.grapheneos.appupdateservergenerator.crypto

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.grapheneos.appupdateservergenerator.model.Base64String
import org.grapheneos.appupdateservergenerator.model.encodeToBase64String
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.ArgumentsProvider
import org.junit.jupiter.params.provider.ArgumentsSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Security
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.stream.Stream

internal class SignatureHeaderInputStreamTest {
    companion object {
        private const val SIGNATURE_ALGORITHM = "SHA256withRSA/PSS"
    }
    private val privateKey: RSAPrivateCrtKey
    private val publicKey: RSAPublicKey
    init {
        Security.addProvider(BouncyCastleProvider())

        val privateKeyBytes = Base64.getDecoder().decode(
            """
                MIIJRQIBADANBgkqhkiG9w0BAQEFAASCCS8wggkrAgEAAoICAQDiRsGF0FQEgimT
                r5Z+/5oYLbukfMBIWMYXxKEci7G5rIMOroEGd5CPy+o7xVpKq1CMQQII+vA+uV1+
                BKLvRi3PoSvUG4oE85XdrgT7NLg1xnmJGq3tb92fhoQl8/2NoAxMdFDrGB1ibcx8
                Hx+5QK24/RTJdNc4peKQwhzEy42ilVZgXw0E3G7efjGuOhS9JT8on/Md9DKJApyf
                eLkLGGYJ7hrB+g5YjLpga8XnYgr2tzgeWUhkkTBf2d0SApysRBsHi/JRf7OFxyF3
                CVCj9UjM28R+lMECB+dTC12X/JbAX87AjpR8teicHsVki5dKyAv3Hes+K9W22+VA
                HsO74dB463oeqeSgxM1FG54c7/Fhmv/s6vxxVqdqRkMfBLdX9aI2lWDq741oV/YN
                sMvclFo16FZ4TbpiUoOBNTzle6UztfJjZkDkOJmbzRnExqUMyopEfiYXS5+dCS1M
                t+tmYAkjpVHz7UsIpp7KqrzuyIHk8hknlUcarRYCwLpErHysjZOJkazILQ8M7vnY
                WBNbmIip6uKCsgf5Ta/lpjp9iHW6e0LcLTIUByKktGujCvZUMKRenZIEBwDxXqNx
                CknAoMnWSEwUaARiX2Ni8eXsp5oqHy0BKPPt+rWB6cC5tY9WYNQ0Jiz2t9ro6YDY
                9CPz5Wj283aKbszxS/3RiN82V3nhtQIDAQABAoICAQCPNmcvkU5L2DIGZiCjWpUl
                7nQPxGFSqNUfn/S0g3nF4XjFZw8Ej9IwIMiscdkW31zImDB4jJJXsKyKoNabCFjg
                S8rYoWF2htD4kDZY4+IfLvInI3qnh6DGYbXr64Q0CfMexJOeaBHZBVNhz7UdY+FP
                7uYkJ048bUl7g1AAAjEvkMMtlZtA019cdJRvCBWuLQ/PX4TZFYCSlNOaXycG7bdP
                W6MV2HUR+GfRtQR50OTNh+L92lZmSDpMwDAaQQr2QNi2qw2AJYnzBriYhY3DZ/Be
                sE2qK+4IogX32n6vPlAPi9PorZUvZVbG0PbwDyV/UCO6kPYd0FKgPIbZUV9i36Y4
                YWpqMTMGSeIToOUFsRcTfycuLuFzQ/1GuiOgQjhNICCxbL+Gx6jZN5W3/zf3eWRt
                Mpo2MRsEbPElZLQTWH3Vd+RhAS6NFLdX6OVP2s6TVsK0E72fdiBrCw2nLVebMakd
                KlvBBeGG7FQG/UubIyYyX4vBPxIuTbgr9UF0Maw5jRwqMcmlgOgkRi705BxsIR4l
                GQG0v8JcBW2vfMX4yCisQjggNud1bbcCCWUuoUIbGPq2I5sfENZ+2mDl4iBsJWGn
                zE5bghvjf3ctWa+OZ6CQTjVOQL4bxfoTgYP5ER7lkiZJXQ1bp4U0V4z+H/+615UG
                a6Ey2cEYfG1b0QOcZRL6oQKCAQEA847nbj3xXOmTKxY5ET37pbZqDAqSbyxRfdhP
                wYZ64R/CQNDctDpYukV1gsY3bCweLay8nrTLqRByZ+EOA6bmTZ374n9YUtADmBer
                0FnZT/xTmwjOaWQyyMaE2ZUUwg0aWbTJy8ftGWvMraoCtYrfHAq34QxzxY0j0lIK
                U+MswcJcx3vWrUiSKqRw3wvSnVWOk2H7W8WjgOWAh5QTIZOC+eU7wLNX++FZDCPU
                sitwJ+TxlmfCHMV2zlaQpZYR/++Jlfk8/wXappVUg4x2L/qFjJCI1yl8ivRX/pUt
                jTE/qMjXxfb7vw9dihOlfXn7gqzt+NX9xQGdwQIoAm0Bxp+RzQKCAQEA7dXZ9gUB
                eQhNkvAKsJlQCNXRnDxxkwQ2EljVu0DqEBw3FbjGG2Grn77URmN26/sfUf4znkbF
                kvGL95h8RuOyLRmTTmjmOfSxzM7w5nRKwbV3Vkgwlj7jgxuv4oqgrrCOaXYTg3Ya
                17rpZCuNF3S4d9Ss/0myTeppcs7km1BsefMvlGrTrG/G6zIs0/NPAZeIcJK37LVX
                f57JHMPLz4FSvGiwpKLASG1esE1vP8Lojr0ZMDZLZSV6bCjn2tvrqyrCnXyVHBwW
                3k6Hlyhwio199QkHcXTALQHqTvV9xUkkwNijSaYDPJSwudqK59ZMDgiYoTZl0w2U
                QD/tTTM2pDlHiQKCAQEAvZfYf/CoGnOYpEnAUrO1WOIO2yC/rNsK9LOWIkfVD7nx
                NRNhOsrQlu/K8enq6sHNHDEDA2gpwYmUBVkj16PsUy9QDJik4JIcuiBzFtVaXPFH
                A8BvLYtaHQCsGdP7PLOGd55MF92hq9BGnEljon5f/yw4x28yD+42nFpQv8xv4sSy
                BHdaPbnoTlmDo51IbkFTo1b6nA3VoVkGHIQgAFsb8fuH8BGrw98/ujuKLsexntyR
                U0uhx1PPj//CyaNwj5Kfv6cs3DhqZJKKH30P43lYzHsWiqc409IxTXRC6U4VCAaQ
                MQJ6JdiIMBvrDyoNp0OGW9X4nYsMilprWrgQUdWVYQKCAQEA03Wo9ilbNt5gAn30
                MzIMy944IN5I6Lr8zHE5juTXdv1vKJwBX8UGT+DeYbr7uDKtlTwbbuVjsxOaK8ji
                7jBycdDkfPmdgPgDwaJY472P3gzXDtregCdoJ2DNj/FLjiYiaLf3/5FZqmdgLZTf
                PHSlsr1gFxbSNp5tpQs4jLMDz6WdrysQCqfTR7hzzPruSu8M+3Inn4lYQ3rNOwsG
                wfcstaGrNKYTqhG796rRd/J2zLpqk8giXsrkvxfblWalcalyIY3sEXMUDhHqUkY9
                UaMd02h0urZgS2QBjLSOX4N57xBulgPJqupU+tnJWPna4ztXYTa5b94J6torxdD8
                CyV2aQKCAQEA3weUPlFp8o5triBli8cUG45FH9YXJwlqeeBMmb3BBRJbcZAUPO/f
                KByEwnQXl4fcOmAXbJGkzhxrMuN5c4CT/pAStWuujHHwaeml9OQRHM8vmDuLx6V7
                udfrbN+0LPzClJaN78+ac9s4p7MX5ZyuPNWeg19/ytd5oPtfw0KsAxYNVqQczhKZ
                zv7/REuyxOeGU75bG2JO5fZudxg7MkgZ4cBjQvDwUvDuh4lFcvo7YR8V4fNRvfz6
                o/n/2JqBzjE77qFbcoaRPjcfIm8GlN4PqGDrlIg/aMUWdbKIF6PvVr01akGRukSg
                hpv4I7InV5HB2dxDuJAVr7vEmf7qCZ/Prg==
            """.trimIndent().replace("\n", "")
        )
        val privateKeySpec = PKCS8EncodedKeySpec(privateKeyBytes)
        KeyFactory.getInstance("RSA", "BC").run {
            privateKey = generatePrivate(privateKeySpec) as RSAPrivateCrtKey
            publicKey = generatePublic(RSAPublicKeySpec(privateKey.modulus, privateKey.publicExponent)) as RSAPublicKey
        }
    }

    @ParameterizedTest(name = "testMissingSignatureHeader: {0}")
    @ValueSource(
        strings = [
            "This is a file that doesn't have a signature header\nThis is a file.",
            "This is a single-line file.",
        ]
    )
    fun testMissingSignatureHeader(stringOfFileWithoutSignatureHeader: String) {
        SignatureHeaderInputStream(
            stream = stringOfFileWithoutSignatureHeader.byteInputStream(),
            signatureAlgorithm = SIGNATURE_ALGORITHM,
            publicKey = publicKey
        ).use { verifiedStream ->
            assertThrows<IOException>("expected IOException because of missing signature header") {
                verifiedStream.read()
            }
        }
    }

    @ParameterizedTest(name = "testNonSignatureBase64Header: {0}")
    @ValueSource(
        strings = [
            "VGhhdCBt aWdodCBiZSBiYXNlNjQtZW5jb2RlZCwgYnV0IGl0J3Mgbm90IGEgc2lnbmF0dXJlIQ==\n" +
                    "That might be base64-encoded, but it's not a signature!",
            "bm9wZQ==\n\nThat might be base64-encoded, but it's not a signature!\n",
            "Tm9wZQ==\r\nThat might be base64-encoded, but it's not a signature!\r",
            "rAIAAA== MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEA4kbBhdBUBIIpk6+Wfv+aGC27pHzASFjGF8ShHIuxuayDDq6B" +
                    "BneQj8vqO8VaSqtQjEECCPrwPrldfgSi70Ytz6Er1BuKBPOV3a4E+zS4NcZ5iRqt7W/dn4aEJfP9jaAMTHRQ6xgdYm3Mf" +
                    "B8fuUCtuP0UyXTXOKXikMIcxMuNopVWYF8NBNxu3n4xrjoUvSU/KJ/zHfQyiQKcn3i5CxhmCe4awfoOWIy6YGvF52IK9r" +
                    "c4HllIZJEwX9ndEgKcrEQbB4vyUX+zhcchdwlQo/VIzNvEfpTBAgfnUwtdl/yWwF/OwI6UfLXonB7FZIuXSsgL9x3rPiv" +
                    "VttvlQB7Du+HQeOt6HqnkoMTNRRueHO/xYZr/7Or8cVanakZDHwS3V/WiNpVg6u+NaFf2DbDL3JRaNehWeE26YlKDgTU8" +
                    "5XulM7XyY2ZA5DiZm80ZxMalDMqKRH4mF0ufnQktTLfrZmAJI6VR8+1LCKaeyqq87siB5PIZJ5VHGq0WAsC6RKx8rI2Ti" +
                    "ZGsyC0PDO752FgTW5iIqerigrIH+U2v5aY6fYh1untC3C0yFAcipLRrowr2VDCkXp2SBAcA8V6jcQpJwKDJ1khMFGgEYl" +
                    "9jYvHl7KeaKh8tASjz7fq1genAubWPVmDUNCYs9rfa6OmA2PQj8+Vo9vN2im7M8Uv90YjfNld54bUCAwEAAQ== \n" +
                    "Something",
            "rAIAAA== BAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
                    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
                    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
                    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
                    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
                    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
                    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
                    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
                    "AAAA\n"
        ]
    )
    fun testNonSignatureBase64Header(stringWithFakeSignatureHeader: String) {
        SignatureHeaderInputStream(
            stream = stringWithFakeSignatureHeader.byteInputStream(),
            signatureAlgorithm = SIGNATURE_ALGORITHM,
            publicKey = publicKey
        ).use { verifiedStream ->
            try {
                verifiedStream.readBytes()
            } catch (e: IOException) {
            } finally {
                assertThrows<GeneralSecurityException> { verifiedStream.verifyOrThrow() }
            }
        }

        SignatureHeaderInputStream(
            stream = stringWithFakeSignatureHeader.byteInputStream(),
            signatureAlgorithm = SIGNATURE_ALGORITHM,
            publicKey = publicKey
        ).use { verifiedStream ->
            try {
                while (verifiedStream.read() != -1) { // attempt to read the whole thing
                }
            } catch (e: IOException) {
            } finally {
                assertThrows<GeneralSecurityException> { verifiedStream.verifyOrThrow() }
            }
        }
    }

    // https://github.com/junit-team/junit5-samples/tree/r5.7.1
    @ParameterizedTest(name = "verify {0}")
    @ValueSource(
        strings = [
            "a",
            "1. This is a string: to sign.\nThere are multiple lines.\nHello there",
            "2. This is a string: to sign.\nThere are multiple lines.\nHello there\n",
            "3. This is a string: to sign.\rThere are multiple lines.\rHello there\r",
            "4. This is a string: to sign.\r\nThere are multiple lines.\r\nHello there\r\n",
            "5. This is a string: to sign.\rThere are multiple lines.\rHello there\r\r\r",
            "6. This is a string: to sign.\nThere are multiple lines.\rHello there\n\n\n",
            "\r7. This starts with carriage return.\nThere are multiple lines.\nHello!\n\r\r\n\r\n\n\r\r\n\r",
            "\n8. This starts with line feed.",
            """{
                "package":"com.example.appa",
                "label":"AppA",
                "lastUpdateTimestamp":1622699767,
                "releases":[
                    {
                        "versionCode":1,
                        "versionName":"1.0",
                        "minSdkVersion":29,
                        "releaseTimestamp":1622699767,
                        "apkSha256":"/I+iQcq4RsmVu0MLfW+uZZL2mUv5WLJeNh4qv6PomJg=",
                        "v4SigSha256":"3/PxeJvFHKDlqUgk5PMojd2A6jfHklAQ3d51GMtCLu4=",
                        "releaseNotes":null
                    }
                ]
            }           
            """,
            "app.attestation.auditor:26\norg.chromium.chrome:443009134\n",
            "app.attestation.auditor:26\norg.chromium.chrome:443009134",
        ]
    )
    fun testSuccessArbitrarySignatureVerification(stringToSign: String) {
        runSignatureTests(SIGNATURE_ALGORITHM, privateKey, publicKey, stringToSign)
        val (ecPrivateKey: ECPrivateKey, ecPubKey: ECPublicKey) = KeyPairGenerator.getInstance("EC")
            .run {
                initialize(ECGenParameterSpec("secp256r1"))
                generateKeyPair().run { private as ECPrivateKey to public as ECPublicKey }
            }
        runSignatureTests("SHA256withECDSA", ecPrivateKey, ecPubKey, stringToSign)
    }

    private fun runSignatureTests(
        signatureAlgorithm: String,
        privateKey: PrivateKey,
        publicKey: PublicKey,
        stringToSign: String,
    ) {
        val signature = Signature.getInstance(signatureAlgorithm).run {
            initSign(privateKey)
            update(stringToSign.encodeToByteArray())
            sign()!!
        }

        // sanity check
        Signature.getInstance(signatureAlgorithm).apply {
            initVerify(publicKey)
            update(stringToSign.encodeToByteArray())
            assert(verify(signature))
        }

        val encodedFile = createSignatureHeaderFilesForTest(signature, stringToSign)

        val expectedLines: List<String> = encodedFile.fileContents.inputStream().bufferedReader().useLines {
            it.drop(1).toList() // drop the signature line
        }
        mapOf<String, (sourceInputStream: InputStream) -> SignatureHeaderInputStream>(
            "with signature algorithm with default provider" to {
                SignatureHeaderInputStream(
                    stream = it,
                    publicKey = publicKey,
                    signatureAlgorithm = signatureAlgorithm,
                    provider = null
                )
            },
            "with signature algorithm with BouncyCastle provider" to {
                SignatureHeaderInputStream(
                    stream = it,
                    publicKey = publicKey,
                    signatureAlgorithm = signatureAlgorithm,
                    provider = "BC"
                )
            },
            "with pre-constructed Signature instance with default provider" to {
                SignatureHeaderInputStream(
                    stream = it,
                    publicKey = publicKey,
                    signature = Signature.getInstance(signatureAlgorithm)
                )
            },
            "with pre-constructed Signature instance with BouncyCastle provider" to {
                SignatureHeaderInputStream(
                    stream = it,
                    publicKey = publicKey,
                    signature = Signature.getInstance(signatureAlgorithm, "BC")
                )
            },
        ).forEach { (description, verificationInputStreamCreator) ->
            val processedLinesFromStream = ArrayList<String>().also { list ->
                val verificationInputStream = verificationInputStreamCreator(encodedFile.fileContents.inputStream())

                verificationInputStream.bufferedReader().forEachLine { list.add(it) }
                assertDoesNotThrow(
                    {
                        verificationInputStream.verifyOrThrow()
                    }, "unexpected exception for encoded file using $description. " +
                            "string input:\n[${encodedFile.fileContents.decodeToString()}]"
                )
            }
            assertEquals(expectedLines, processedLinesFromStream) {
                "line processing of encodedFile failed for using $description:\n" +
                        "string input:\n[${encodedFile.fileContents.decodeToString()}]"
            }

            // test the skip options
            val streamToTestSkip: SignatureHeaderInputStream =
                verificationInputStreamCreator(encodedFile.fileContents.inputStream())
            streamToTestSkip.skip(encodedFile.fileContents.size.toLong())
            assertEquals(-1, streamToTestSkip.read())
            // we expect verification to fail, because skips mean the underlying signature instance won't read
            assert(!streamToTestSkip.verify())
        }
    }

    private data class SignatureHeaderFile constructor(
        val signature: Base64String,
        val stringSignedBySignature: String
    ) {
        val fileContents: ByteArray = ByteArrayOutputStream().run {
            writeBytes(SignatureHeaderInputStream.createSignatureHeaderWithLineFeed(signature).encodeToByteArray())
            bufferedWriter().use { it.write(stringSignedBySignature) }
            toByteArray()
        }
    }

    private fun createSignatureHeaderFilesForTest(
        signature: ByteArray,
        stringToSign: String
    ): SignatureHeaderFile {
        val base64Signature = signature.encodeToBase64String()
        return SignatureHeaderFile(base64Signature, stringToSign)
    }

    class FailedVerificationArgumentProvider : ArgumentsProvider {
        override fun provideArguments(context: ExtensionContext?): Stream<Arguments> = Stream.of(
            Arguments.of(
                "1620429240\napp.attestation.auditor:26\norg.chromium.chrome:443009134\n",
                // Chromium version is different
                "1620429240\napp.attestation.auditor:26\norg.chromium.chrome:443009135\n",
            ),
            Arguments.of(
                "1620429240\r\napp.attestation.auditor:26\r\norg.chromium.chrome:443009135",
                // Auditor version is different
                "1620429240\r\napp.attestation.auditor:25\r\norg.chromium.chrome:443009135",
            ),
            Arguments.of(
                "1620429240\napp.attestation.auditor:26\norg.chromium.chrome:443009135",
                // Timestamp is different
                "1520429240\napp.attestation.auditor:25\norg.chromium.chrome:443009135",
            ),
        )
    }
    @ParameterizedTest(name = "verify and expect fail: [{0}] vs. [{1}]")
    @ArgumentsSource(FailedVerificationArgumentProvider::class)
    fun testFailedArbitrarySignatureVerification(stringToSign: String, differentString: String) {
        assertNotEquals(stringToSign, differentString)

        val signature = Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(privateKey)
            update(stringToSign.encodeToByteArray())
            sign()!!
        }

        // sanity check
        Signature.getInstance(SIGNATURE_ALGORITHM).apply {
            initVerify(publicKey)
            update(stringToSign.encodeToByteArray())
            assert(verify(signature))
            update(differentString.encodeToByteArray())
            assertFalse(verify(signature))
        }

        val encodedFilesMismatch = createSignatureHeaderFilesForTest(signature, differentString)
        SignatureHeaderInputStream(
            stream = encodedFilesMismatch.fileContents.inputStream(),
            publicKey = publicKey,
            signatureAlgorithm = SIGNATURE_ALGORITHM,
        ).use { verificationInputStream ->
            val linesRead = mutableListOf<String>()
            verificationInputStream.bufferedReader().forEachLine { linesRead.add(it) }
            assert(linesRead.isNotEmpty())
            assertThrows<GeneralSecurityException>("expected encoded file to fail verification, but didn't") {
                verificationInputStream.verifyOrThrow()
            }

            val linesFromSignedString = stringToSign.byteInputStream().bufferedReader().lineSequence().toList()
            assertNotEquals(linesFromSignedString, linesRead) { "expected not equal for encoded, but was equal" }
            val linesFromOriginalString = differentString.byteInputStream().bufferedReader().lineSequence().toList()
            assertEquals(linesFromOriginalString, linesRead) { "not equal" }
        }
    }

    @Test
    fun testJsonParsing() {
        val mapper = jacksonObjectMapper()

        val instances = listOf(
            SomethingSerializable(
                SomeInlineClass("com.example"),
                SomethingSerializable.NestedItem(5454),
                listOf(
                    SomethingSerializable.NestedItem(5454),
                )
            ),
            SomethingSerializable(
                SomeInlineClass("com.example.no"),
                SomethingSerializable.NestedItem(111),
                listOf(
                    SomethingSerializable.NestedItem(2222),
                    SomethingSerializable.NestedItem(986),
                    SomethingSerializable.NestedItem(-45),
                )
            )
        )
        val json = """
            [
                {
                    "someName":"com.example",
                    "propertyA":{"otherProperty":5454},
                    "propertyB":[{"otherProperty":5454}]
                },
                {
                    "someName":"com.example.no",
                    "propertyA":{"otherProperty":111},
                    "propertyB":[{"otherProperty":2222},{"otherProperty":986},{"otherProperty":-45}]
                }
            ]
            """.trimIndent()
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(privateKey)
            update(json.encodeToByteArray())
            sign()!!
        }

        val instancesJsonWithSignatureHeader =
            "${SignatureHeaderInputStream.createSignatureHeaderWithLineFeed(signature.encodeToBase64String())}${json}"
        // we expect that the input stream just contains the JSON stuff
        SignatureHeaderInputStream(instancesJsonWithSignatureHeader.byteInputStream(), publicKey, SIGNATURE_ALGORITHM)
            .use { signatureInputStream ->
                // this should consume the whole stream and make the java.security.Signature instance ready for
                // verification
                val instancesFromJson = mapper.readerFor(SomethingSerializable::class.java)
                    .readValues<SomethingSerializable>(signatureInputStream)
                    .asSequence()
                    .toCollection(ArrayList<SomethingSerializable>(2))
                assertEquals(instances, instancesFromJson)
                // check signature verification
                assert(signatureInputStream.verify())
            }

        val differentInstances = listOf(
            SomethingSerializable(
                SomeInlineClass("com.example.amdifferent"),
                SomethingSerializable.NestedItem(5454),
                listOf(
                    SomethingSerializable.NestedItem(5454),
                )
            ),
            SomethingSerializable(
                SomeInlineClass("com.example.no"),
                SomethingSerializable.NestedItem(111),
                listOf(
                    SomethingSerializable.NestedItem(2222),
                    SomethingSerializable.NestedItem(986),
                    SomethingSerializable.NestedItem(-45),
                )
            )
        )
        assertNotEquals(instances, differentInstances)
        val differentJson = """
            [
                {
                    "someName":"com.example.amdifferent",
                    "propertyA":{"otherProperty":5454},
                    "propertyB":[{"otherProperty":5454}]
                },
                {
                    "someName":"com.example.no",
                    "propertyA":{"otherProperty":111},
                    "propertyB":[{"otherProperty":2222},{"otherProperty":986},{"otherProperty":-45}]
                }
            ]
            """.trimIndent()

        val differentJsonWithMismatchedSignatureHeader =
            SignatureHeaderInputStream.createSignatureHeaderWithLineFeed(signature.encodeToBase64String()) +
                    differentJson

        SignatureHeaderInputStream(
            differentJsonWithMismatchedSignatureHeader.byteInputStream(),
            publicKey,
            SIGNATURE_ALGORITHM
        )
            .use { signatureInputStream ->
                val instancesFromJson = mapper.readerFor(SomethingSerializable::class.java)
                    .readValues<SomethingSerializable>(signatureInputStream)
                    .asSequence()
                    .toCollection(ArrayList<SomethingSerializable>(2))
                assertNotEquals(instances, instancesFromJson)
                assertEquals(differentInstances, instancesFromJson)
                assertFalse(signatureInputStream.verify())
            }
    }
}

@JvmInline
value class SomeInlineClass constructor(@JsonProperty val value: String)

internal data class SomethingSerializable constructor(
    val someName: SomeInlineClass,
    val propertyA: NestedItem,
    val propertyB: List<NestedItem>
) {

    data class NestedItem constructor(
        val otherProperty: Int
    )

    companion object {

        /**
         * a [JsonCreator] to deal with inline class breakage:
         * https://github.com/FasterXML/jackson-module-kotlin/issues/413#issuecomment-824148244
         */
        @JvmStatic
        @JsonCreator
        @Suppress("UNUSED")
        fun create(
            someName: String,
            propertyA: NestedItem,
            propertyB: List<NestedItem>
        ) = SomethingSerializable(SomeInlineClass(someName), propertyA, propertyB)
    }
}
