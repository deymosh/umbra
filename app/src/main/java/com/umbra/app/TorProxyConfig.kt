package com.umbra.app

/**
 * Holds the TOR SOCKS proxy configuration reported by Orbot via its STATUS broadcast.
 * Updated by TorGateViewModel when Orbot broadcasts STATUS=ON with its actual host and port.
 * All code that needs to route traffic through TOR reads from here — nothing is hardcoded.
 */
object TorProxyConfig {
    const val DEFAULT_HOST = "127.0.0.1"
    const val DEFAULT_PORT = 9050
    private val ALLOWED_LOOPBACK_HOSTS = setOf(DEFAULT_HOST, "localhost", "::1", "[::1]")
    private val ALLOWED_SOCKS_PORTS = setOf(9050, 9150)

    @Volatile var host: String = DEFAULT_HOST
        private set
    @Volatile var port: Int = DEFAULT_PORT
        private set
    @Volatile var isReady: Boolean = false
        private set

    fun isValidSocksEndpoint(host: String, port: Int): Boolean {
        val normalizedHost = normalizeHost(host)
        return normalizedHost in ALLOWED_LOOPBACK_HOSTS && port in ALLOWED_SOCKS_PORTS
    }

    fun update(host: String, port: Int): Boolean {
        if (!isValidSocksEndpoint(host, port)) {
            return false
        }
        this.host = normalizeHost(host)
        this.port = port
        this.isReady = true
        return true
    }

    fun reset() {
        host = DEFAULT_HOST
        port = DEFAULT_PORT
        isReady = false
    }

    private fun normalizeHost(raw: String): String = raw.trim().lowercase()
}
