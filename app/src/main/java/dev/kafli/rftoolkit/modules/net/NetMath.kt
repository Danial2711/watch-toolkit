package dev.kafli.rftoolkit.modules.net

/** IPv4 helpers, port catalogue and ping-output parsers. Pure Kotlin. */
object NetMath {

    fun parseIpv4(s: String): Int? {
        val parts = s.trim().split('.')
        if (parts.size != 4) return null
        var v = 0
        for (p in parts) {
            val n = p.toIntOrNull() ?: return null
            if (n !in 0..255 || (p.length > 1 && p.startsWith('0'))) return null
            v = (v shl 8) or n
        }
        return v
    }

    fun formatIpv4(v: Int): String =
        "${(v ushr 24) and 0xFF}.${(v ushr 16) and 0xFF}.${(v ushr 8) and 0xFF}.${v and 0xFF}"

    private fun inRange(ip: Int, base: String, prefix: Int): Boolean {
        val b = parseIpv4(base)!!
        val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
        return (ip and mask) == (b and mask)
    }

    /**
     * Private / local ranges the port scanner is allowed to touch: RFC 1918, CGNAT (phone hotspots),
     * link-local and loopback. Scanning is restricted to these so the tool stays on networks you're on.
     */
    fun isPrivate(ip: Int): Boolean =
        inRange(ip, "10.0.0.0", 8) || inRange(ip, "172.16.0.0", 12) || inRange(ip, "192.168.0.0", 16) ||
            inRange(ip, "100.64.0.0", 10) || inRange(ip, "169.254.0.0", 16) || inRange(ip, "127.0.0.0", 8)

    fun isPrivate(s: String): Boolean = parseIpv4(s)?.let(::isPrivate) ?: false

    /** Host addresses of the subnet containing [ip]; prefixes shorter than /24 are clamped to the /24. */
    fun subnetHosts(ip: Int, prefix: Int): List<Int> {
        val p = prefix.coerceIn(24, 30)
        val mask = -1 shl (32 - p)
        val net = ip and mask
        val size = 1 shl (32 - p)
        return (1 until size - 1).map { net + it }.filter { it != ip }
    }

    /** Common TCP services worth checking on a LAN host. */
    val PORTS: Map<Int, String> = linkedMapOf(
        21 to "FTP", 22 to "SSH", 23 to "Telnet", 25 to "SMTP", 53 to "DNS", 80 to "HTTP", 110 to "POP3",
        135 to "MS-RPC", 139 to "NetBIOS", 143 to "IMAP", 443 to "HTTPS", 445 to "SMB", 515 to "LPD",
        554 to "RTSP", 631 to "IPP", 993 to "IMAPS", 1883 to "MQTT", 3306 to "MySQL", 3389 to "RDP",
        5000 to "UPnP/dev", 5353 to "mDNS", 5432 to "PostgreSQL", 5555 to "ADB", 5900 to "VNC",
        6379 to "Redis", 7000 to "AirPlay", 8008 to "Chromecast", 8009 to "Chromecast", 8080 to "HTTP-alt",
        8443 to "HTTPS-alt", 8883 to "MQTT-TLS", 9100 to "Printer", 27017 to "MongoDB", 49152 to "UPnP",
        62078 to "iPhone sync",
    )

    /** Ports used to decide whether a host is alive during a subnet sweep. */
    val SWEEP_PORTS = listOf(80, 443, 22, 445, 53, 8080, 8008, 62078, 5555, 9100)

    /** Ports that are risky to leave open on a LAN device. */
    val RISKY = setOf(21, 23, 445, 3389, 5555, 5900, 6379, 27017, 1883)

    data class PingSummary(val sent: Int, val received: Int, val lossPct: Double, val minMs: Double?, val avgMs: Double?, val maxMs: Double?)

    /** Parses iputils/toybox ping output. */
    fun parsePing(output: String): PingSummary? {
        val stats = Regex("""(\d+) packets transmitted, (\d+) (?:packets )?received.*?(\d+(?:\.\d+)?)% packet loss""")
            .find(output) ?: return null
        val rtt = Regex("""(?:rtt|round-trip) min/avg/max(?:/mdev)? = ([\d.]+)/([\d.]+)/([\d.]+)""").find(output)
        return PingSummary(
            sent = stats.groupValues[1].toInt(),
            received = stats.groupValues[2].toInt(),
            lossPct = stats.groupValues[3].toDouble(),
            minMs = rtt?.groupValues?.get(1)?.toDouble(),
            avgMs = rtt?.groupValues?.get(2)?.toDouble(),
            maxMs = rtt?.groupValues?.get(3)?.toDouble(),
        )
    }

    sealed class Hop {
        data class Router(val ip: String) : Hop()
        data class Reached(val ip: String, val ms: Double?) : Hop()
        object Timeout : Hop()
    }

    /** Parses one `ping -c 1 -t <ttl>` run into a traceroute hop. */
    fun parseHop(output: String): Hop {
        Regex("""[Ff]rom ([\d.]+)(?: \([^)]*\))?:? (?:icmp_seq=\d+ )?Time to live exceeded""").find(output)?.let {
            return Hop.Router(it.groupValues[1])
        }
        Regex("""bytes from ([\d.]+).*?time=([\d.]+)""").find(output)?.let {
            return Hop.Reached(it.groupValues[1], it.groupValues[2].toDoubleOrNull())
        }
        return Hop.Timeout
    }

    /** Hostname or IPv4 literal, conservative charset (no shell metacharacters reach `ping`). */
    fun isValidHost(s: String): Boolean =
        s.length in 1..253 && Regex("""^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$""").matches(s)
}
