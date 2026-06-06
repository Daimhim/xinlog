package com.xinxin.xinlog

import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * xinlog 自带的老设备 TLS 兜底（纯 JDK，零三方依赖，与上报用的 HttpURLConnection 配套）。
 *
 * 为什么需要：上报端点 daimhim.top 用 Let's Encrypt 证书(根 ISRG Root X1)。
 *   - Android 7.1.1(API25) 才把 ISRG Root X1 收进系统信任库，旧 DST Root X3 交叉签名已过期；
 *   - HttpsURLConnection 在 API21~23 上既无系统根、network_security_config 又不生效。
 * 结果：Android 5.0~6.0 老设备上报会 Trust anchor not found 被静默吞掉，崩溃日志永远传不上来。
 * 这里给 HttpsURLConnection 套上「系统库 ∪ 内置 ISRG Root X1」的 TrustManager + 强制 TLS1.2/1.3，
 * 覆盖 API21+ 全部设备。宿主 app 的 OkHttp 另有一套等价兜底(core 的 Tls.kt)，两者互不依赖。
 */
internal object XinTls {

    /** ISRG Root X1（Let's Encrypt 根，有效期至 2035-06）。 */
    private const val ISRG_ROOT_X1 = """-----BEGIN CERTIFICATE-----
MIIFazCCA1OgAwIBAgIRAIIQz7DSQONZRGPgu2OCiwAwDQYJKoZIhvcNAQELBQAw
TzELMAkGA1UEBhMCVVMxKTAnBgNVBAoTIEludGVybmV0IFNlY3VyaXR5IFJlc2Vh
cmNoIEdyb3VwMRUwEwYDVQQDEwxJU1JHIFJvb3QgWDEwHhcNMTUwNjA0MTEwNDM4
WhcNMzUwNjA0MTEwNDM4WjBPMQswCQYDVQQGEwJVUzEpMCcGA1UEChMgSW50ZXJu
ZXQgU2VjdXJpdHkgUmVzZWFyY2ggR3JvdXAxFTATBgNVBAMTDElTUkcgUm9vdCBY
MTCCAiIwDQYJKoZIhvcNAQEBBQADggIPADCCAgoCggIBAK3oJHP0FDfzm54rVygc
h77ct984kIxuPOZXoHj3dcKi/vVqbvYATyjb3miGbESTtrFj/RQSa78f0uoxmyF+
0TM8ukj13Xnfs7j/EvEhmkvBioZxaUpmZmyPfjxwv60pIgbz5MDmgK7iS4+3mX6U
A5/TR5d8mUgjU+g4rk8Kb4Mu0UlXjIB0ttov0DiNewNwIRt18jA8+o+u3dpjq+sW
T8KOEUt+zwvo/7V3LvSye0rgTBIlDHCNAymg4VMk7BPZ7hm/ELNKjD+Jo2FR3qyH
B5T0Y3HsLuJvW5iB4YlcNHlsdu87kGJ55tukmi8mxdAQ4Q7e2RCOFvu396j3x+UC
B5iPNgiV5+I3lg02dZ77DnKxHZu8A/lJBdiB3QW0KtZB6awBdpUKD9jf1b0SHzUv
KBds0pjBqAlkd25HN7rOrFleaJ1/ctaJxQZBKT5ZPt0m9STJEadao0xAH0ahmbWn
OlFuhjuefXKnEgV4We0+UXgVCwOPjdAvBbI+e0ocS3MFEvzG6uBQE3xDk3SzynTn
jh8BCNAw1FtxNrQHusEwMFxIt4I7mKZ9YIqioymCzLq9gwQbooMDQaHWBfEbwrbw
qHyGO0aoSCqI3Haadr8faqU9GY/rOPNk3sgrDQoo//fb4hVC1CLQJ13hef4Y53CI
rU7m2Ys6xt0nUW7/vGT1M0NPAgMBAAGjQjBAMA4GA1UdDwEB/wQEAwIBBjAPBgNV
HRMBAf8EBTADAQH/MB0GA1UdDgQWBBR5tFnme7bl5AFzgAiIyBpY9umbbjANBgkq
hkiG9w0BAQsFAAOCAgEAVR9YqbyyqFDQDLHYGmkgJykIrGF1XIpu+ILlaS/V9lZL
ubhzEFnTIZd+50xx+7LSYK05qAvqFyFWhfFQDlnrzuBZ6brJFe+GnY+EgPbk6ZGQ
3BebYhtF8GaV0nxvwuo77x/Py9auJ/GpsMiu/X1+mvoiBOv/2X/qkSsisRcOj/KK
NFtY2PwByVS5uCbMiogziUwthDyC3+6WVwW6LLv3xLfHTjuCvjHIInNzktHCgKQ5
ORAzI4JMPJ+GslWYHb4phowim57iaztXOoJwTdwJx4nLCgdNbOhdjsnvzqvHu7Ur
TkXWStAmzOVyyghqpZXjFaH3pO3JLF+l+/+sKAIuvtd7u+Nxe5AW0wdeRlN8NwdC
jNPElpzVmbUq4JUagEiuTDkHzsxHpFKVK7q4+63SM1N95R1NbdWhscdCb+ZAJzVc
oyi3B43njTOQ5yOf+1CceWxG1bQVs5ZufpsMljq4Ui0/1lvh+wjChP4kqKOJ2qxq
4RgqsahDYVvTH9w7jXbyLeiNdd8XM2w9U/t7y0Ff/9yi0GE44Za4rF2LN9d11TPA
mRGunUHBcnWEvgJBQl9nJEiU0Zsnvgc/ubhPgXRR4Xq37Z0j4r7g1SgEEzwxA57d
emyPxgcYxn/eR44/KJ4EBs+lVDR3veyJm+kXQ99b21/+jh5Xos1AnX5iItreGCc=
-----END CERTIFICATE-----"""

    /** 给 HttpsURLConnection 用的 SocketFactory：组合信任 + 强制 TLS1.2/1.3。取不到时为 null（回退系统默认）。 */
    val socketFactory: SSLSocketFactory? by lazy {
        try {
            SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(buildTrustManager()), null)
            }.socketFactory.let(::Tls12SocketFactory)
        } catch (_: Throwable) {
            null
        }
    }

    private fun isrgRootX1(): X509Certificate =
        CertificateFactory.getInstance("X.509").let { cf ->
            ByteArrayInputStream(ISRG_ROOT_X1.toByteArray()).use {
                cf.generateCertificate(it) as X509Certificate
            }
        }

    /**
     * 信任锚 = 系统受信根(AndroidCAStore 全量) ∪ 内置 ISRG Root X1，合并进一个 KeyStore 后用标准
     * TrustManagerFactory 生成平台原生 TrustManagerImpl。系统根照常生效，老设备额外补上 ISRG Root X1。
     */
    private fun buildTrustManager(): X509TrustManager {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        var n = 0
        runCatching {
            val sys = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
            val aliases = sys.aliases()
            while (aliases.hasMoreElements()) {
                sys.getCertificate(aliases.nextElement())?.let { ks.setCertificateEntry("sys${n++}", it) }
            }
        }
        ks.setCertificateEntry("isrg-root-x1", isrgRootX1())
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(if (n > 0) ks else null as KeyStore?)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /** 在每个新建 SSLSocket 上启用 TLSv1.2/1.3（API21/22 默认不开 TLS1.2，Let's Encrypt 要求 1.2+）。 */
    private class Tls12SocketFactory(private val delegate: SSLSocketFactory) : SSLSocketFactory() {
        private val wanted = arrayOf("TLSv1.3", "TLSv1.2")

        private fun enableModernTls(socket: Socket): Socket {
            if (socket is SSLSocket) {
                val supported = socket.supportedProtocols.toHashSet()
                socket.enabledProtocols = wanted.filter { it in supported }.toTypedArray()
            }
            return socket
        }

        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
        override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket =
            enableModernTls(delegate.createSocket(s, host, port, autoClose))
        override fun createSocket(host: String?, port: Int): Socket =
            enableModernTls(delegate.createSocket(host, port))
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            enableModernTls(delegate.createSocket(host, port, localHost, localPort))
        override fun createSocket(host: InetAddress?, port: Int): Socket =
            enableModernTls(delegate.createSocket(host, port))
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            enableModernTls(delegate.createSocket(address, port, localAddress, localPort))
    }
}
