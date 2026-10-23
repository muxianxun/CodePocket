package com.dsh.codepocket.account

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * The server uses a self-signed certificate (no domain, so no public CA can issue one), which
 * means the app pins the exact certificate instead of trusting the system CA list.
 *
 * This is not a workaround, it is stronger than a public CA: no CA can be tricked or coerced
 * into issuing a certificate for this server, because only this one hash is accepted.
 *
 * The certificate is valid until 2036. If it is ever reissued, update PIN below and ship a new
 * build — that is the price of pinning.
 */
object PinnedTls {

    /** SHA-256 of the DER encoded server certificate, lowercase hex, no separators. */
    private const val PIN = "4567dcb6f3d55aec0ffb31662be17c0d867e00d82ab880eb5c0c2d04f19eefe9"

    /** Base URL of the auth server. NAT mapping: external 47493 -> internal 8443. */
    const val BASE_URL = "https://43.226.58.187:47493"

    fun socketFactory(): SSLSocketFactory {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<X509TrustManager>(PinnedTrustManager()), null)
        return context.socketFactory
    }

    private class PinnedTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("client certificates are not used")
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("empty certificate chain")
            val digest = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
            val hex = digest.joinToString("") { "%02x".format(it) }
            if (hex != PIN) {
                throw CertificateException("certificate pin mismatch")
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}