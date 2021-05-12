package org.grapheneos.appupdateservergenerator.crypto

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.grapheneos.appupdateservergenerator.model.UnixTimestamp
import org.grapheneos.appupdateservergenerator.util.prependLine
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.fail
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.ArgumentsProvider
import org.junit.jupiter.params.provider.ArgumentsSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.nio.file.Files
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.Security
import java.security.Signature
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.stream.Stream

internal class OpenSSLInvokerTest {
    companion object {
        private const val SIGNATURE_ALGORITHM = "SHA256withRSA/PSS"
        private const val PROVIDER = "BC"
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
        KeyFactory.getInstance("RSA", PROVIDER).run {
            privateKey = generatePrivate(privateKeySpec) as RSAPrivateCrtKey
            val publicKeySpec = RSAPublicKeySpec(privateKey.modulus, privateKey.publicExponent)
            publicKey = generatePublic(publicKeySpec) as RSAPublicKey
        }
    }
    private val keyFile: File = Files.createTempFile(
        "test-key-${UnixTimestamp.now().seconds}",
        null
    ).toFile()
        .apply {
            deleteOnExit()
            writeBytes(privateKey.encoded)
        }

    @ParameterizedTest(name = "verify {0}")
    @ValueSource(
        strings = [
            "1. This is a string: to sign.\nThere are multiple lines.\nHello there",
            "2. This is a string: to sign.\nThere are multiple lines.\nHello there\n",
            "3. This is a string: to sign.\rThere are multiple lines.\rHello there\r",
            "4. This is a string: to sign.\r\nThere are multiple lines.\r\nHello there\r\n",
            "5. This is a string: to sign.\rThere are multiple lines.\rHello there\r\r\r",
            "6. This is a string: to sign.\nThere are multiple lines.\rHello there\n\n\n",
            "app.attestation.auditor:26\norg.chromium.chrome:443009134\n",
            "app.attestation.auditor:26\norg.chromium.chrome:443009134"
        ]
    )
    fun testSignAndPrepend(stringToSign: String) {
        val openSSLInvoker = OpenSSLInvoker()
        // Failing on GitHub Runners --- IOException is thrown
        // assert(openSSLInvoker.isExecutablePresent()) { "missing openssl executable" }

        val tempFileToSign = Files.createTempFile(
            "test-${UnixTimestamp.now().seconds}-${stringToSign.hashCode()}",
            null
        ).toFile()
            .apply {
                deleteOnExit()
                writeText(stringToSign)
            }

        val key = PrivateKeyFile.RSA(file = keyFile)
        val signature = openSSLInvoker.signFileAndPrependSignatureToFile(key, tempFileToSign)
        // sanity check
        Signature.getInstance(SIGNATURE_ALGORITHM, PROVIDER).apply {
            initVerify(publicKey)
            update(stringToSign.encodeToByteArray())
            assert(verify(signature.bytes))
        }

        // The last line from lines() can be just a blank string for some reason.
        // The BufferedReader will not have a last line that is just a blank string, so
        // we need to remove it.
        val expectedLines = stringToSign.lines().toMutableList()
            .apply {
                if (last().isBlank()) {
                    removeLast()
                }
            }
        val actualLines = ArrayList<String>(expectedLines.size)
        SignatureVerificationInputStream(
            stream = tempFileToSign.inputStream(),
            publicKey,
            SIGNATURE_ALGORITHM,
        ).use {
            assertDoesNotThrow("failed to verify signature: contents are ${tempFileToSign.readText()}") {
                it.forEachLineThenVerify { actualLines.add(it) }
            }
        }
        assertEquals(expectedLines, actualLines)
    }

    class FailedVerificationArgumentProvider : ArgumentsProvider {
        override fun provideArguments(context: ExtensionContext?): Stream<Arguments> = Stream.of(
            Arguments.of(
                "1620429240\napp.attestation.auditor:26\norg.chromium.chrome:443009134\n",
                // Chromium version is different
                "1620429240\napp.attestation.auditor:26\norg.chromium.chrome:443009135\n",
            ),
            Arguments.of(
                "1620429240\napp.attestation.auditor:26\norg.chromium.chrome:443009135",
                // Auditor version is different
                "1620429240\napp.attestation.auditor:25\norg.chromium.chrome:443009135",
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

        val openSSLInvoker = OpenSSLInvoker()
        // Failing on GitHub Runners --- IOException is thrown
        // assert(openSSLInvoker.isExecutablePresent()) { "missing openssl executable" }

        val tempFileToSign = Files.createTempFile(
            "test-${UnixTimestamp.now().seconds}-${stringToSign.hashCode()}",
            null
        ).toFile()
            .apply {
                deleteOnExit()
                writeText(stringToSign)
            }

        val key = PrivateKeyFile.RSA(file = keyFile)
        val signature = openSSLInvoker.signFile(key, tempFileToSign)
        // Replace the temp file contents with the different, unexpected string.
        // This function overwrites the file.
        tempFileToSign.writeText(differentString)
        // Add the wrong signature.
        tempFileToSign.prependLine(signature.s)

        // sanity check
        Signature.getInstance(SIGNATURE_ALGORITHM, PROVIDER).apply {
            initVerify(publicKey)
            update(stringToSign.encodeToByteArray())
            assert(verify(signature.bytes))
            update(differentString.encodeToByteArray())
            assertFalse(verify(signature.bytes))
        }

        // The last line from lines() can be just a blank string for some reason.
        // The BufferedReader will not have a last line that is just a blank string, so
        // we need to remove it.
        val expectedLines = stringToSign.lines().toMutableList()
            .apply {
                if (last().isBlank()) {
                    removeLast()
                }
            }
        val actualLines = ArrayList<String>(expectedLines.size)
        SignatureVerificationInputStream(
            stream = tempFileToSign.inputStream(),
            publicKey = publicKey,
            signatureAlgorithm = SIGNATURE_ALGORITHM,
        ).use {
            assertThrows(GeneralSecurityException::class.java) {
                it.forEachLineThenVerify { actualLines.add(it) }
            }
        }
        assertNotEquals(expectedLines, actualLines)
    }

    @Test
    fun testGetKeyType() {
        val invoker = OpenSSLInvoker()
        val key = invoker.getKeyWithType(keyFile)
        assert(key is PrivateKeyFile.RSA) { "expected test key to be RSA key" }

        // Now generate an EC key and try to parse the key type from that.
        val ecKeyFile = Files.createTempFile("temp-ec-key", ".pk8").toFile()
            .apply { deleteOnExit() }

        val ecKeyGeneratorProcess = ProcessBuilder(
            "openssl", "ecparam", "-name", "prime256v1", "-genkey", "-noout"
        ).start()

        val pkcs8Process = ProcessBuilder(
            "openssl", "pkcs8", "-topk8", "-outform", "DER", "-out", ecKeyFile.absolutePath, "-nocrypt"
        ).start()

        pkcs8Process.outputStream.buffered().use { output ->
            ecKeyGeneratorProcess.inputStream.buffered().use { it.copyTo(output) }
        }

        if (!pkcs8Process.waitFor(15, TimeUnit.SECONDS)) {
            fail("key generation took too long")
        }
        if (pkcs8Process.exitValue() != 0) {
            fail("key generation not successful (non-zero error coe ${pkcs8Process.exitValue()}")
        }

        val parsedKey = invoker.getKeyWithType(ecKeyFile)
        assert(parsedKey is PrivateKeyFile.EC)
    }
}
