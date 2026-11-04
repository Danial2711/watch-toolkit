package dev.kafli.rftoolkit.modules.lan

import android.content.Context
import android.net.wifi.WifiManager
import dev.kafli.rftoolkit.modules.net.NetTools
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException

data class LanState(
    val running: Boolean = false,
    val listenedS: Long = 0,
    val packets: Int = 0,
    val isolation: Isolation = Isolation.UNKNOWN,
    val devices: List<LanDevice> = emptyList(),
    val selfIp: String? = null,
    val errors: List<String> = emptyList(),
)

/**
 * Passive LAN listener: joins the mDNS (224.0.0.251:5353), SSDP (239.255.255.250:1900) and
 * LLMNR (224.0.0.252:5355) multicast groups on the Wi-Fi interface and feeds [LanInventory].
 * Runs only while its screen is open. [askOnce] sends one mDNS service query and one SSDP search.
 */
object LanListener {
    private data class Group(val proto: String, val addr: String, val port: Int)
    private val MDNS = Group("mDNS", "224.0.0.251", 5353)
    private val SSDP = Group("SSDP", "239.255.255.250", 1900)
    private val LLMNR = Group("LLMNR", "224.0.0.252", 5355)

    private val _state = MutableStateFlow(LanState())
    val state: StateFlow<LanState> = _state.asStateFlow()

    private var scope: CoroutineScope? = null
    private var lock: WifiManager.MulticastLock? = null
    private val sockets = mutableMapOf<Group, MulticastSocket>()
    private var inventory: LanInventory? = null

    suspend fun start(ctx: Context) {
        if (scope != null) return
        val app = ctx.applicationContext
        NetTools.acquireWifi(app)
        val link = NetTools.linkInfo(app)
        val selfIps = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.mapNotNull { it.hostAddress }
        }.getOrDefault(emptyList()).toSet() + setOfNotNull(link.ip)
        val inv = LanInventory(selfIps, link.gateway).also { it.start(System.currentTimeMillis()) }
        inventory = inv
        lock = app.getSystemService(WifiManager::class.java).createMulticastLock("rftoolkit-lan").apply {
            setReferenceCounted(false)
            acquire()
        }
        val iface = link.ip?.let { runCatching { NetworkInterface.getByInetAddress(InetAddress.getByName(it)) }.getOrNull() }
        val errors = mutableListOf<String>()
        if (!link.viaWifi) errors += "Not on Wi-Fi"
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        for (g in listOf(MDNS, SSDP, LLMNR)) {
            val sock = runCatching {
                MulticastSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(g.port))
                    soTimeout = 1_000
                    @Suppress("DEPRECATION")
                    loopbackMode = true // true = loopback DISABLED (Java's inverted API)
                    joinGroup(InetSocketAddress(InetAddress.getByName(g.addr), g.port), iface)
                }
            }.onFailure { errors += "${g.proto}: ${it.message}" }.getOrNull() ?: continue
            sockets[g] = sock
            s.launch { receiveLoop(g, sock, inv) }
        }
        _state.value = LanState(running = true, selfIp = link.ip, errors = errors)
        s.launch {
            while (isActive) {
                publish()
                delay(1_000)
            }
        }
    }

    private fun receiveLoop(g: Group, sock: MulticastSocket, inv: LanInventory) {
        val buf = ByteArray(9_000)
        while (scope != null && !sock.isClosed) {
            val pkt = DatagramPacket(buf, buf.size)
            try {
                sock.receive(pkt)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: Exception) {
                break
            }
            val src = pkt.address?.hostAddress ?: continue
            val now = System.currentTimeMillis()
            synchronized(inv) {
                when (g) {
                    SSDP -> Ssdp.parse(String(pkt.data, 0, pkt.length, Charsets.UTF_8))?.let { inv.ingestSsdp(src, it, now) }
                    else -> DnsPacket.parse(pkt.data, pkt.length)?.let { inv.ingestDns(src, g.proto, it, now) }
                }
            }
        }
    }

    private fun publish() {
        val inv = inventory ?: return
        val now = System.currentTimeMillis()
        synchronized(inv) {
            _state.value = _state.value.copy(
                listenedS = (now - inv.startedMs) / 1000,
                packets = inv.packets,
                isolation = inv.isolation(now),
                devices = inv.snapshot(),
            )
        }
    }

    /** One mDNS "what services exist?" query + one SSDP search; responses land in the same sockets. */
    fun askOnce() {
        val s = scope ?: return
        s.launch {
            sockets[MDNS]?.let { sock ->
                val q = DnsPacket.query("_services._dns-sd._udp.local", DnsPacket.PTR)
                runCatching { sock.send(DatagramPacket(q, q.size, InetAddress.getByName(MDNS.addr), MDNS.port)) }
            }
            sockets[SSDP]?.let { sock ->
                val q = Ssdp.SEARCH_ALL.toByteArray()
                runCatching { sock.send(DatagramPacket(q, q.size, InetAddress.getByName(SSDP.addr), SSDP.port)) }
            }
        }
    }

    fun stop(ctx: Context) {
        scope?.cancel()
        scope = null
        sockets.values.forEach { runCatching { it.close() } }
        sockets.clear()
        lock?.takeIf { it.isHeld }?.release()
        lock = null
        NetTools.releaseWifi(ctx.applicationContext)
        _state.value = _state.value.copy(running = false)
    }
}
