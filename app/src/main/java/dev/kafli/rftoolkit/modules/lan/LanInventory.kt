package dev.kafli.rftoolkit.modules.lan

/** One device on the LAN, keyed by source IP. */
data class LanDevice(
    val ip: String,
    val names: Set<String> = emptySet(),
    val services: Set<String> = emptySet(),
    val hints: Set<String> = emptySet(),
    val lookups: Set<String> = emptySet(),
    val protocols: Set<String> = emptySet(),
    val firstMs: Long = 0,
    val lastMs: Long = 0,
    val packets: Int = 0,
    /** Further addresses of the same machine (merged by hostname). */
    val otherIps: Set<String> = emptySet(),
) {
    val label: String get() = names.firstOrNull() ?: hints.firstOrNull() ?: ip
}

enum class Isolation(val text: String) {
    UNKNOWN("Listening…"),
    OFF("Client isolation OFF — other devices visible"),
    LIKELY_ON("No other clients heard — isolation likely (or quiet network)"),
}

/**
 * Builds a device inventory from passively received mDNS / SSDP / LLMNR packets. Pure Kotlin.
 * Kept in memory only — nothing from other people's devices is written to disk.
 */
class LanInventory(selfIps: Set<String>, private val gatewayIp: String?) {
    /** Every address of this watch (IPv4 + IPv6, incl. link-local) — our own multicast must not count. */
    private val self: Set<String> = selfIps.map(::normalize).toSet()

    constructor(selfIp: String?, gatewayIp: String?) : this(setOfNotNull(selfIp), gatewayIp)
    private val devices = LinkedHashMap<String, LanDevice>()
    var startedMs: Long = 0
        private set
    var packets = 0
        private set

    fun start(nowMs: Long) { startedMs = nowMs }

    /** TXT keys that carry human-meaningful model/firmware info. */
    private val HINT_KEYS = setOf("md", "model", "am", "fn", "ty", "usb_mdl", "product", "fv", "vers", "osxvers", "manufacturer")

    fun ingestDns(src: String, protocol: String, msg: DnsPacket.Message, nowMs: Long) {
        if (normalize(src) in self) return
        packets++
        var d = devices[src] ?: LanDevice(ip = src, firstMs = nowMs)
        val names = d.names.toMutableSet()
        val services = d.services.toMutableSet()
        val hints = d.hints.toMutableSet()
        val lookups = d.lookups.toMutableSet()
        if (msg.isResponse) {
            for (r in msg.records) when (r.type) {
                DnsPacket.A, DnsPacket.AAAA -> if (r.name.endsWith(".local")) names += r.name.removeSuffix(".local")
                DnsPacket.PTR -> serviceType(r.name)?.let { services += it }
                DnsPacket.SRV -> r.data.substringBefore(':').takeIf { it.endsWith(".local") }?.let { names += it.removeSuffix(".local") }
                DnsPacket.TXT -> r.data.split(';').forEach { kv ->
                    val k = kv.substringBefore('=').lowercase()
                    val v = kv.substringAfter('=', "")
                    if (k in HINT_KEYS && v.isNotBlank() && v.length <= 60) hints += "$k=$v"
                }
            }
        } else {
            // Queries reveal what the sender is looking for (LLMNR lookups are classic poisoning targets).
            msg.questions.forEach { (q, _) -> if (q.isNotBlank()) lookups += q }
        }
        d = d.copy(
            names = names.take(6).toSet(), services = services.take(12).toSet(), hints = hints.take(8).toSet(),
            lookups = lookups.take(10).toSet(), protocols = d.protocols + protocol,
            lastMs = nowMs, packets = d.packets + 1,
        )
        devices[src] = d
    }

    fun ingestSsdp(src: String, msg: Ssdp.Message, nowMs: Long) {
        if (normalize(src) in self) return
        packets++
        val d = devices[src] ?: LanDevice(ip = src, firstMs = nowMs)
        val hints = d.hints.toMutableSet()
        msg.headers["server"]?.let { if (it.length <= 80) hints += "server=$it" }
        val services = d.services.toMutableSet()
        (msg.headers["nt"] ?: msg.headers["st"])?.let { nt ->
            Regex("""urn:schemas-upnp-org:device:([A-Za-z]+):""").find(nt)?.let { services += "upnp:${it.groupValues[1]}" }
        }
        devices[src] = d.copy(
            hints = hints.take(8).toSet(), services = services.take(12).toSet(),
            protocols = d.protocols + "SSDP", lastMs = nowMs, packets = d.packets + 1,
        )
    }

    /**
     * Devices sorted by recency; entries sharing an mDNS hostname (same machine over IPv4 and IPv6)
     * are merged, preferring the IPv4 address as the displayed one.
     */
    fun snapshot(): List<LanDevice> {
        val merged = mutableListOf<LanDevice>()
        for (d in devices.values.sortedBy { if (':' in it.ip) 1 else 0 }) {
            val i = merged.indexOfFirst { m -> d.names.isNotEmpty() && m.names.any { it in d.names } }
            if (i < 0) {
                merged += d
            } else {
                val m = merged[i]
                merged[i] = m.copy(
                    names = m.names + d.names, services = m.services + d.services, hints = m.hints + d.hints,
                    lookups = (m.lookups + d.lookups).take(10).toSet(), protocols = m.protocols + d.protocols,
                    firstMs = minOf(m.firstMs, d.firstMs), lastMs = maxOf(m.lastMs, d.lastMs),
                    packets = m.packets + d.packets, otherIps = m.otherIps + d.ip,
                )
            }
        }
        return merged.sortedByDescending { it.lastMs }
    }

    /** Devices other than us and the gateway mean the AP forwards client-to-client traffic. */
    fun isolation(nowMs: Long, minListenMs: Long = 60_000): Isolation {
        val others = devices.keys.count { it != gatewayIp && normalize(it) !in self }
        return when {
            others > 0 -> Isolation.OFF
            nowMs - startedMs >= minListenMs -> Isolation.LIKELY_ON
            else -> Isolation.UNKNOWN
        }
    }

    companion object {
        /** Drops the IPv6 zone suffix ("fe80::1%wlan0" → "fe80::1") and lower-cases. */
        fun normalize(ip: String): String = ip.substringBefore('%').lowercase()

        /** "_airplay._tcp.local" → "airplay"; ignores the meta-service and instance names. */
        fun serviceType(name: String): String? {
            val m = Regex("""(?:^|\.)_([A-Za-z0-9-]+)\._(?:tcp|udp)\.local$""").find(name) ?: return null
            val t = m.groupValues[1]
            return if (t == "services" || t == "dns-sd") null else t
        }
    }
}
