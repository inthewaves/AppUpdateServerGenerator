package org.grapheneos.appupdateservergenerator.crypto

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.grapheneos.appupdateservergenerator.model.encodeToBase64String
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.ArgumentsProvider
import org.junit.jupiter.params.provider.ArgumentsSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.Security
import java.security.Signature
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
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
        KeyFactory.getInstance("RSA").run {
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
            assertThrows(IOException::class.java) {
                verifiedStream.read()
            }
        }
    }

    @ParameterizedTest(name = "testNonSignatureBase64Header: {0}")
    @ValueSource(
        strings = [
            "VGhhdCBtaWdodCBiZSBiYXNlNjQtZW5jb2RlZCwgYnV0IGl0J3Mgbm90IGEgc2lnbmF0dXJlIQ==\n" +
                    "That might be base64-encoded, but it's not a signature!",
            "bm9wZQ==\n\nThat might be base64-encoded, but it's not a signature!\n",
            "Tm9wZQ==\r\nThat might be base64-encoded, but it's not a signature!\r",
        ]
    )
    fun testNonSignatureBase64Header(stringWithFakeSignatureHeader: String) {
        SignatureHeaderInputStream(
            stream = stringWithFakeSignatureHeader.byteInputStream(),
            signatureAlgorithm = SIGNATURE_ALGORITHM,
            publicKey = publicKey
        ).use { verifiedStream ->
            while (verifiedStream.read() != -1) { // infinite loop
            }
            assertThrows(GeneralSecurityException::class.java) { verifiedStream.verifyOrThrow() }
        }
    }

    private fun readSignatureHeaderStreamAndVerifyContentsAndSignature(
        expectedLines: List<String>,
        input: String,
        inputTestIndex: Int
    ) {
        mapOf(
            "with signature algorithm with default provider" to SignatureHeaderInputStream(
                stream = input.byteInputStream(),
                publicKey = publicKey,
                signatureAlgorithm = SIGNATURE_ALGORITHM,
                provider = null
            ),
            "with signature algorithm with BouncyCastle provider" to SignatureHeaderInputStream(
                stream = input.byteInputStream(),
                publicKey = publicKey,
                signatureAlgorithm = SIGNATURE_ALGORITHM,
                provider = "BC"
            ),
            "with pre-constructed Signature instance with default provider" to SignatureHeaderInputStream(
                stream = input.byteInputStream(),
                publicKey = publicKey,
                signature = Signature.getInstance(SIGNATURE_ALGORITHM)
            ),
            "with pre-constructed Signature instance with BouncyCastle provider" to SignatureHeaderInputStream(
                stream = input.byteInputStream(),
                publicKey = publicKey,
                signature = Signature.getInstance(SIGNATURE_ALGORITHM, "BC")
            ),
        ).forEach { (description, verificationInputStream) ->
            val processedLinesFromStream = ArrayList<String>().also { list ->
                verificationInputStream.bufferedReader().forEachLine { list.add(it) }
                assertDoesNotThrow({
                    verificationInputStream.verifyOrThrow()
                }, "unexpected exception for encoded file index $inputTestIndex using $description. " +
                        "string input:\n[$input]")
            }
            assertEquals(expectedLines, processedLinesFromStream) {
                "line processing of encodedFile failed for #$inputTestIndex using $description:\n" +
                        "string input:\n[$input]"
            }
        }
    }


    // https://github.com/junit-team/junit5-samples/tree/r5.7.1
    @ParameterizedTest(name = "verify {0}")
    @ValueSource(
        strings = [
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
        }

        val encodedFilesToTest = createSignatureHeaderFilesForTest(signature, stringToSign)
        encodedFilesToTest.forEachIndexed { index, encodedFile ->
            if (encodedFile is SignatureHeaderFile.CRSeparatingTheSignature) {
                Assumptions.assumeFalse({ stringToSign.startsWith('\n') }) {
                    """a CR separating the signature and the string beginning with LF means the 
                        |${SignatureHeaderInputStream::class.java.simpleName} will treat it as a CRLF sequence. This
                        |means the stream will skip over the LF character in the string, and the signature verifications
                        |will only include content after that. 
                        |
                        |However, this is not likely to happen in any actual usage. This software is
                        |designed for Linux, so using CR is very unlikely. Furthermore, the software specifically
                        |inserts a LF (\n) character when constructing signature header files.
                    """.trimMargin()
                }
            }

            val expectedLines: List<String> = encodedFile.fileContents.byteInputStream().bufferedReader().useLines {
                it.drop(1).toList() // drop the signature line
            }
            readSignatureHeaderStreamAndVerifyContentsAndSignature(expectedLines, encodedFile.fileContents, index)
        }
    }

    private sealed class SignatureHeaderFile private constructor(
        signature: ByteArray,
        lineSeparator: String,
        stringSignedBySignature: String
    ) {
        val fileContents: String = "${signature.encodeToBase64String().s}$lineSeparator$stringSignedBySignature"

        class LFSeparatingTheSignature(
            signature: ByteArray,
            stringSignedBySignature: String
        ) : SignatureHeaderFile(signature, "\n", stringSignedBySignature)
        class CRLFSeparatingTheSignature(
            signature: ByteArray,
            stringSignedBySignature: String
        ) : SignatureHeaderFile(signature, "\r\n", stringSignedBySignature)
        class CRSeparatingTheSignature(
            signature: ByteArray,
            stringSignedBySignature: String
        ) : SignatureHeaderFile(signature, "\r", stringSignedBySignature)
    }

    private fun createSignatureHeaderFilesForTest(
        signature: ByteArray,
        stringToSign: String
    ): List<SignatureHeaderFile> {
        return listOf(
            SignatureHeaderFile.LFSeparatingTheSignature(signature, stringToSign),
            SignatureHeaderFile.CRLFSeparatingTheSignature(signature, stringToSign),
            SignatureHeaderFile.CRSeparatingTheSignature(signature, stringToSign),
        )
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

        val encodedFilesMismatched = createSignatureHeaderFilesForTest(signature, differentString)
        encodedFilesMismatched.forEachIndexed { index, encodedFile: SignatureHeaderFile ->
            SignatureHeaderInputStream(
                stream = encodedFile.fileContents.byteInputStream(),
                publicKey = publicKey,
                signatureAlgorithm = SIGNATURE_ALGORITHM,
            ).use { verificationInputStream ->
                val linesRead = mutableListOf<String>()
                verificationInputStream.bufferedReader().forEachLine { linesRead.add(it) }
                assertThrows(
                    GeneralSecurityException::class.java,
                    { verificationInputStream.verifyOrThrow() },
                    "expected encoded file[$index] to fail verification, but didn't"
                )
                val linesFromSignedString = stringToSign.byteInputStream().bufferedReader().lineSequence().toList()
                assertNotEquals(linesFromSignedString, linesRead) { "expected not equal for encoded index $index,, but was equal" }
                val linesFromOriginalString = differentString.byteInputStream().bufferedReader().lineSequence().toList()
                assertEquals(linesFromOriginalString, linesRead) { "not equal for encoded index $index" }
            }
        }
    }
}