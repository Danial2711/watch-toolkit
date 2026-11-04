package dev.kafli.rftoolkit.modules.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume

data class LinkInfo(val ip: String?, val prefix: Int?, val gateway: String?, val dns: List<String>, val viaWifi: Boolean)

/**
 * Network tools. Wear OS often routes app traffic through the phone over Bluetooth; [acquireWifi]
 * requests the Wi-Fi network and binds the process to it so tools test the network you're actually on.
 */
object NetTools {
    private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile var wifi: Network? = null
        private set

    /** Requests Wi-Fi and binds the process to it. Returns null if Wi-Fi isn't available within [timeoutMs]. */
    suspend fun acquireWifi(ctx: Context, timeoutMs: Long = 10_000): Network? {
        wifi?.let { return it }
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val net = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (cont.isActive) cont.resume(network)
                    }
                    override fun onLost(network: Network) {
                        if (network == wifi) wifi = null
                    }
                }
                callback = cb
                cm.requestNetwork(
                    NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                    cb,
                )
            }
        }
        if (net != null) {
            cm.bindProcessToNetwork(net)
            wifi = net
        }
        return net
    }

    fun releaseWifi(ctx: Context) {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm.bindProcessToNetwork(null)
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        wifi = null
    }

    fun linkInfo(ctx: Context): LinkInfo {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val net = wifi ?: cm.activeNetwork
        val lp: LinkProperties? = net?.let { cm.getLinkProperties(it) }
        val v4 = lp?.linkAddresses?.firstOrNull { it.address is Inet4Address }
        val gw = lp?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
        val viaWifi = net?.let { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) } ?: false
        return LinkInfo(v4?.address?.hostAddress, v4?.prefixLength, gw, lp?.dnsServers?.mapNotNull { it.hostAddress }.orEmpty(), viaWifi)
    }

    // ------------------------------------------------------------------ ping / traceroute

    private suspend fun exec(vararg cmd: String, timeoutS: Long): String = withContext(Dispatchers.IO) {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(timeoutS, TimeUnit.SECONDS)) p.destroy()
        out
    }

    suspend fun ping(host: String, count: Int = 4): List<String> {
        require(NetMath.isValidHost(host)) { "Invalid host" }
        val out = exec("/system/bin/ping", "-c", "$count", "-W", "2", host, timeoutS = count * 3L + 2)
        val s = NetMath.parsePing(out) ?: return listOf("No reply / not resolvable", out.lines().firstOrNull().orEmpty())
        return listOf(
            "${s.received}/${s.sent} replies, ${s.lossPct.toInt()}% loss",
            s.avgMs?.let { "rtt min/avg/max %.1f/%.1f/%.1f ms".format(s.minMs, s.avgMs, s.maxMs) } ?: "",
        ).filter { it.isNotEmpty() }
    }

    suspend fun traceroute(host: String, maxHops: Int = 15): List<String> {
        require(NetMath.isValidHost(host)) { "Invalid host" }
        val lines = mutableListOf<String>()
        for (ttl in 1..maxHops) {
            val hop = NetMath.parseHop(exec("/system/bin/ping", "-c", "1", "-W", "1", "-t", "$ttl", host, timeoutS = 3))
            when (hop) {
                is NetMath.Hop.Router -> lines += "%2d  %s".format(ttl, hop.ip)
                is NetMath.Hop.Reached -> {
                    lines += "%2d  %s  %s".format(ttl, hop.ip, hop.ms?.let { "%.1f ms".format(it) } ?: "")
                    return lines + "Reached in $ttl hops"
                }
                NetMath.Hop.Timeout -> lines += "%2d  *".format(ttl)
            }
        }
        return lines + "Not reached within $maxHops hops"
    }

    // ------------------------------------------------------------------ DNS

    /** Resolves via the network's DNS and via DNS-over-HTTPS (Cloudflare) and compares the answers. */
    suspend fun dnsCheck(name: String): List<String> = withContext(Dispatchers.IO) {
        require(NetMath.isValidHost(name)) { "Invalid name" }
        val system = runCatching { InetAddress.getAllByName(name).filterIsInstance<Inet4Address>().map { it.hostAddress!! } }
        val doh = runCatching {
            val c = URL("https://cloudflare-dns.com/dns-query?name=$name&type=A").openConnection() as HttpsURLConnection
            c.setRequestProperty("Accept", "application/dns-json")
            c.connectTimeout = 5_000
            c.readTimeout = 5_000
            DnsCompare.parseDohA(c.inputStream.bufferedReader().readText())
        }
        DnsCompare.report(system.getOrNull(), doh.getOrNull(), system.exceptionOrNull()?.message, doh.exceptionOrNull()?.message)
    }

    // ------------------------------------------------------------------ port scan

    private fun tcpOpen(ip: String, port: Int, timeoutMs: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(ip, port), timeoutMs); true }
    }.getOrDefault(false)

    suspend fun scanHost(ip: String, timeoutMs: Int = 500): List<String> = coroutineScope {
        require(NetMath.isPrivate(ip)) { "Only private/local addresses can be scanned" }
        val sem = Semaphore(24)
        val open = NetMath.PORTS.keys.map { port ->
            async(Dispatchers.IO) { sem.withPermit { port.takeIf { tcpOpen(ip, it, timeoutMs) } } }
        }.awaitAll().filterNotNull()
        if (open.isEmpty()) {
            listOf("$ip: no open ports among ${NetMath.PORTS.size} checked")
        } else {
            listOf("$ip: ${open.size} open") + open.map { p ->
                (if (p in NetMath.RISKY) "⚠ " else "  ") + "$p ${NetMath.PORTS[p]}"
            }
        }
    }

    suspend fun sweepSubnet(ctx: Context, onProgress: (String) -> Unit): List<String> = coroutineScope {
        val li = linkInfo(ctx)
        val self = li.ip?.let(NetMath::parseIpv4) ?: error("No IPv4 address")
        require(NetMath.isPrivate(self)) { "Not on a private network" }
        val hosts = NetMath.subnetHosts(self, li.prefix ?: 24)
        val sem = Semaphore(48)
        var done = 0
        val alive = hosts.map { h ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    val ip = NetMath.formatIpv4(h)
                    val ports = NetMath.SWEEP_PORTS.filter { tcpOpen(ip, it, 250) }
                    synchronized(this@NetTools) { done++; if (done % 32 == 0) onProgress("$done/${hosts.size}") }
                    if (ports.isNotEmpty()) ip to ports else null
                }
            }
        }.awaitAll().filterNotNull()
        listOf("${alive.size} hosts answered on ${hosts.size} addresses") +
            alive.map { (ip, ports) -> "$ip  ${ports.joinToString(",")}" } +
            (if (alive.isEmpty()) listOf("(client isolation, or hosts without common ports)") else emptyList())
    }

    // ------------------------------------------------------------------ TLS + headers

    suspend fun tlsInspect(host: String, port: Int = 443): List<String> = withContext(Dispatchers.IO) {
        require(NetMath.isValidHost(host)) { "Invalid host" }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
        val system = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        var chain: Array<X509Certificate> = emptyArray()
        var trustError: String? = null
        // Records the chain, then delegates to the platform trust store (validation is NOT bypassed).
        val capturing = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) = system.checkClientTrusted(c, a)
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {
                chain = c
                try { system.checkServerTrusted(c, a) } catch (e: Exception) { trustError = e.message; throw e }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(capturing), null) }
        val out = mutableListOf<String>()
        // Hotspots often hand out IPv6 without working IPv6 routing: try IPv4 addresses first, then IPv6.
        val addrs = InetAddress.getAllByName(host).sortedBy { if (it is Inet4Address) 0 else 1 }
        var sock: SSLSocket? = null
        var lastErr: Exception? = null
        for (a in addrs) {
            val s = ctx.socketFactory.createSocket() as SSLSocket
            try {
                s.connect(InetSocketAddress(a, port), 4_000)
                sock = s
                break
            } catch (e: Exception) {
                lastErr = e
                runCatching { s.close() }
            }
        }
        if (sock == null) return@withContext listOf("✗ Could not connect: ${lastErr?.message}")
        out += "Connected to ${sock.inetAddress.hostAddress}"
        try {
            sock.soTimeout = 6_000
            val params = sock.sslParameters
            params.serverNames = listOf(javax.net.ssl.SNIHostName(host))
            sock.sslParameters = params
            sock.startHandshake()
            val s = sock.session
            val hostOk = HttpsURLConnection.getDefaultHostnameVerifier().verify(host, s)
            out += "✓ Trusted chain" + if (hostOk) ", hostname OK" else ", ⚠ HOSTNAME MISMATCH"
            out += "${s.protocol} · ${s.cipherSuite}"
        } catch (e: Exception) {
            out += "⚠ Handshake failed: ${trustError ?: e.message}"
        } finally {
            runCatching { sock.close() }
        }
        chain.firstOrNull()?.let { leaf -> out += TlsReport.describeLeaf(leaf, System.currentTimeMillis()) }
        if (chain.size > 1) out += "Chain: ${chain.size} certs, issuer ${TlsReport.cn(chain[0].issuerX500Principal.name)}"
        out
    }

    suspend fun httpHeaders(host: String): List<String> = withContext(Dispatchers.IO) {
        require(NetMath.isValidHost(host)) { "Invalid host" }
        val c = URL("https://$host/").openConnection() as HttpsURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 6_000
        c.readTimeout = 6_000
        c.requestMethod = "GET"
        val code = c.responseCode
        val headers = c.headerFields.filterKeys { it != null }.mapKeys { it.key!!.lowercase() }.mapValues { it.value.joinToString(", ") }
        c.disconnect()
        listOf("HTTP $code" + (headers["server"]?.let { " · server: $it" } ?: "")) + SecurityHeaders.check(headers)
    }
}

/** Pure helpers for the DNS check (unit-tested). */
object DnsCompare {
    fun parseDohA(json: String): List<String> {
        val answers = JSONObject(json).optJSONArray("Answer") ?: return emptyList()
        return (0 until answers.length()).map { answers.getJSONObject(it) }
            .filter { it.optInt("type") == 1 }.map { it.getString("data") }
    }

    fun report(system: List<String>?, doh: List<String>?, sysErr: String?, dohErr: String?): List<String> {
        val out = mutableListOf<String>()
        out += "Network DNS: " + (system?.joinToString().takeUnless { it.isNullOrEmpty() } ?: "✗ ${sysErr ?: "no answer"}")
        out += "DoH (1.1.1.1): " + (doh?.joinToString().takeUnless { it.isNullOrEmpty() } ?: "✗ ${dohErr ?: "no answer"}")
        if (system != null && system.any { NetMath.isPrivate(it) } && doh != null && doh.none { NetMath.isPrivate(it) }) {
            out += "‼ Network DNS returns a PRIVATE address — captive portal or DNS hijack"
        } else if (!system.isNullOrEmpty() && !doh.isNullOrEmpty()) {
            out += if (system.toSet().intersect(doh.toSet()).isNotEmpty()) "✓ Answers overlap"
            else "! Answers differ (normal for CDNs; suspicious for small sites)"
        }
        return out
    }
}

/** Pure HTTP security-header audit (unit-tested). Keys must be lower-case. */
object SecurityHeaders {
    private val EXPECTED = listOf(
        "strict-transport-security" to "HSTS",
        "content-security-policy" to "CSP",
        "x-content-type-options" to "X-Content-Type-Options",
        "x-frame-options" to "X-Frame-Options",
        "referrer-policy" to "Referrer-Policy",
        "permissions-policy" to "Permissions-Policy",
    )

    fun check(headers: Map<String, String>): List<String> {
        val present = EXPECTED.filter { it.first in headers }.map { it.second }
        val missing = EXPECTED.filter { it.first !in headers }.map { it.second }
        val out = mutableListOf("Headers: ${present.size}/${EXPECTED.size}")
        if (present.isNotEmpty()) out += "✓ " + present.joinToString(", ")
        if (missing.isNotEmpty()) out += "✗ missing " + missing.joinToString(", ")
        headers["strict-transport-security"]?.let { v ->
            val maxAge = Regex("""max-age=(\d+)""").find(v)?.groupValues?.get(1)?.toLongOrNull()
            if (maxAge != null && maxAge < 15_552_000) out += "! HSTS max-age short (${maxAge / 86_400} d)"
        }
        // Frame protection may come from CSP instead of X-Frame-Options.
        if ("x-frame-options" !in headers && headers["content-security-policy"]?.contains("frame-ancestors") == true) {
            out += "  (framing controlled by CSP frame-ancestors)"
        }
        return out
    }
}

/** Pure certificate summary (unit-tested via a generated certificate is impractical; kept simple). */
object TlsReport {
    fun cn(dn: String): String = Regex("""CN=([^,]+)""").find(dn)?.groupValues?.get(1) ?: dn.take(40)

    fun describeLeaf(c: X509Certificate, nowMs: Long): List<String> {
        val daysLeft = (c.notAfter.time - nowMs) / 86_400_000
        val key = when (val k = c.publicKey) {
            is RSAPublicKey -> "RSA ${k.modulus.bitLength()}"
            is ECPublicKey -> "EC ${k.params.curve.field.fieldSize}"
            else -> k.algorithm
        }
        val sans = runCatching { c.subjectAlternativeNames?.filter { it[0] == 2 }?.map { it[1].toString() } }.getOrNull().orEmpty()
        return listOfNotNull(
            "Subject: ${cn(c.subjectX500Principal.name)}",
            "Expires in $daysLeft d" + when {
                daysLeft < 0 -> " ‼ EXPIRED"
                daysLeft < 14 -> " ⚠ soon"
                else -> ""
            },
            "Key $key · ${c.sigAlgName}",
            if (sans.isNotEmpty()) "SANs: ${sans.take(4).joinToString()}${if (sans.size > 4) " +${sans.size - 4}" else ""}" else null,
        )
    }
}
