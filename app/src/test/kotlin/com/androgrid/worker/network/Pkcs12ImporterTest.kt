package com.androgrid.worker.network

import com.google.common.truth.Truth.assertThat
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.PKCS12PfxPduBuilder
import org.bouncycastle.pkcs.jcajce.JcaPKCS12SafeBagBuilder
import org.bouncycastle.pkcs.jcajce.JcePKCSPBEOutputEncryptorBuilder
import org.bouncycastle.pkcs.jcajce.JcePKCS12MacCalculatorBuilder
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date

/**
 * Unit tests for [Pkcs12Importer] covering OpenSSL 3.x PBES2/AES-256-CBC/SHA-256 bundles,
 * password-protected bundles, and malformed/keyless error diagnostics.
 *
 * Traces to: [INV-002], [NFR-SEC-001], [REQ-OP-001]
 */
class Pkcs12ImporterTest {

    private fun generateEcCertAndKey(cn: String): Pair<X509Certificate, PrivateKey> {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val kp = kpg.generateKeyPair()
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            X500Name("CN=$cn"),
            BigInteger.valueOf(now),
            Date(now - 60_000),
            Date(now + 86_400_000),
            X500Name("CN=$cn"),
            kp.public
        )
        val signer = JcaContentSignerBuilder("SHA256withECDSA").build(kp.private)
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        return cert to kp.private
    }

    private fun buildModernOpenSsl3P12(
        cert: X509Certificate,
        key: PrivateKey,
        password: CharArray
    ): ByteArray {
        val provider = BouncyCastleProvider()
        // Mirror OpenSSL 3.x defaults: PBES2 with AES-256-CBC and SHA-256 MAC
        val keyBag = JcaPKCS12SafeBagBuilder(
            key,
            JcePKCSPBEOutputEncryptorBuilder(PKCSObjectIdentifiers.id_PBES2)
                .setProvider(provider)
                .build(password)
        ).build()
        val certBag = JcaPKCS12SafeBagBuilder(cert).build()
        val pfx = PKCS12PfxPduBuilder()
            .addData(keyBag)
            .addData(certBag)
            .build(
                JcePKCS12MacCalculatorBuilder(org.bouncycastle.asn1.nist.NISTObjectIdentifiers.id_sha256)
                    .setProvider(provider),
                password
            )
        return pfx.encoded
    }

    @Test
    fun testImport_openSsl3Pbes2Aes256Sha256_passwordless_succeeds() {
        val (cert, key) = generateEcCertAndKey("worker-p12-01")
        val p12Bytes = buildModernOpenSsl3P12(cert, key, charArrayOf())

        val result = Pkcs12Importer.import(p12Bytes)
        assertThat(result).isInstanceOf(Pkcs12ImportResult.Success::class.java)
        val creds = (result as Pkcs12ImportResult.Success).credentials
        assertThat(creds.clientCertPem).contains("-----BEGIN CERTIFICATE-----")
        assertThat(creds.clientKeyPem).contains("-----BEGIN PRIVATE KEY-----")
    }

    @Test
    fun testImport_passwordProtectedBundle_promptsThenSucceeds() {
        val (cert, key) = generateEcCertAndKey("worker-p12-02")
        val pass = "s3cr3t!".toCharArray()
        val p12Bytes = buildModernOpenSsl3P12(cert, key, pass)

        val emptyPassResult = Pkcs12Importer.import(p12Bytes, charArrayOf())
        assertThat(emptyPassResult).isInstanceOf(Pkcs12ImportResult.PasswordRequired::class.java)

        val okResult = Pkcs12Importer.import(p12Bytes, pass)
        assertThat(okResult).isInstanceOf(Pkcs12ImportResult.Success::class.java)
    }

    @Test
    fun testImport_emptyOrCorruptBytes_returnsDiagnosticFailure() {
        val emptyRes = Pkcs12Importer.import(byteArrayOf())
        assertThat(emptyRes).isInstanceOf(Pkcs12ImportResult.Failure::class.java)
        assertThat((emptyRes as Pkcs12ImportResult.Failure).userMessage).contains("empty")

        val corruptRes = Pkcs12Importer.import("not-a-p12".toByteArray())
        assertThat(corruptRes).isInstanceOf(Pkcs12ImportResult.Failure::class.java)
        assertThat((corruptRes as Pkcs12ImportResult.Failure).userMessage).contains("Failed to parse PKCS#12")
    }

    @Test
    fun testImport_certOnlyBundleWithoutKey_returnsDiagnosticFailure() {
        val (cert, _) = generateEcCertAndKey("ca-only")
        val ks = KeyStore.getInstance("PKCS12", BouncyCastleProvider()).apply {
            load(null, charArrayOf())
            setCertificateEntry("ca", cert)
        }
        val out = ByteArrayOutputStream()
        ks.store(out, charArrayOf())

        val result = Pkcs12Importer.import(out.toByteArray())
        assertThat(result).isInstanceOf(Pkcs12ImportResult.Failure::class.java)
        assertThat((result as Pkcs12ImportResult.Failure).userMessage).contains("no private key")
    }
}
