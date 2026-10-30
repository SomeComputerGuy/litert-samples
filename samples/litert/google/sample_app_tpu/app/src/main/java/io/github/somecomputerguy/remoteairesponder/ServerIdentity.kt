package io.github.somecomputerguy.remoteairesponder

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.security.auth.x500.X500Principal

/**
 * The phone's TLS identity: an EC key pair kept in the Android Keystore (it never leaves
 * the secure hardware) with a self-signed certificate. Coordinators pin the certificate's
 * SHA-256 fingerprint when they pair, so the identity must stay stable across restarts.
 */
object ServerIdentity {

    private const val ALIAS = "remoteai_tls"
    private const val KEYSTORE = "AndroidKeyStore"

    private val keyStore: KeyStore by lazy { KeyStore.getInstance(KEYSTORE).apply { load(null) } }

    @Synchronized
    private fun ensureKey() {
        if (keyStore.containsAlias(ALIAS)) return
        val now = System.currentTimeMillis()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).apply {
            initialize(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(
                        KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256,
                        KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512
                    )
                    .setCertificateSubject(X500Principal("CN=RemoteAI Responder"))
                    .setCertificateSerialNumber(BigInteger.valueOf(now))
                    .setCertificateNotBefore(Date(now - 24 * 3600 * 1000L))
                    .setCertificateNotAfter(Date(now + 30L * 365 * 24 * 3600 * 1000))
                    .build()
            )
            generateKeyPair()
        }
    }

    val certificate: X509Certificate
        get() {
            ensureKey()
            return keyStore.getCertificate(ALIAS) as X509Certificate
        }

    /** SHA-256 of the DER certificate, as raw bytes. */
    val fingerprintBytes: ByteArray
        get() = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)

    /** SHA-256 of the DER certificate, lowercase hex. This is what coordinators pin. */
    val fingerprint: String
        get() = fingerprintBytes.joinToString("") { "%02x".format(it) }

    fun createServerSocket(): SSLServerSocket {
        ensureKey()
        val key = keyStore.getKey(ALIAS, null) as PrivateKey
        val chain = arrayOf(certificate)
        // A hand-rolled key manager, because the platform KeyManagerFactory can't always
        // read Keystore-backed keys; the key is only used through the Keystore's signer.
        val keyManager = object : X509ExtendedKeyManager() {
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
            override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
            override fun getCertificateChain(alias: String?) = chain
            override fun getPrivateKey(alias: String?) = key
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null
        }
        val context = SSLContext.getInstance("TLS").apply { init(arrayOf(keyManager), null, null) }
        return (context.serverSocketFactory.createServerSocket() as SSLServerSocket).apply {
            enabledProtocols = supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
        }
    }
}
