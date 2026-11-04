package dev.kafli.rftoolkit.modules.lan

/** Minimal DNS / mDNS / LLMNR message parser (RFC 1035 §4, with name compression). Pure Kotlin. */
object DnsPacket {
    const val A = 1
    const val PTR = 12
    const val TXT = 16
    const val AAAA = 28
    const val SRV = 33

    data class Record(val name: String, val type: Int, val data: String)
    data class Message(val isResponse: Boolean, val questions: List<Pair<String, Int>>, val records: List<Record>)

    fun parse(b: ByteArray, len: Int = b.size): Message? = runCatching {
        if (len < 12) return null
        fun u16(i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
        val flags = u16(2)
        val qd = u16(4)
        val rrCount = u16(6) + u16(8) + u16(10)
        var pos = 12

        fun name(start: Int): Pair<String, Int> {
            val labels = mutableListOf<String>()
            var p = start
            var end = -1
            var jumps = 0
            while (true) {
                require(p < len)
                val l = b[p].toInt() and 0xFF
                when {
                    l == 0 -> { p++; break }
                    l and 0xC0 == 0xC0 -> {
                        require(p + 1 < len && ++jumps < 32) // pointer loops
                        if (end < 0) end = p + 2
                        p = ((l and 0x3F) shl 8) or (b[p + 1].toInt() and 0xFF)
                    }
                    else -> {
                        require(p + 1 + l <= len)
                        labels += String(b, p + 1, l, Charsets.UTF_8)
                        p += 1 + l
                    }
                }
            }
            return labels.joinToString(".") to (if (end >= 0) end else p)
        }

        val qs = List(qd) {
            val (n, p) = name(pos)
            pos = p + 4
            n to u16(p)
        }
        val rrs = mutableListOf<Record>()
        repeat(rrCount) {
            val (n, p) = name(pos)
            val type = u16(p)
            val rdLen = u16(p + 8)
            val rd = p + 10
            require(rd + rdLen <= len)
            val data = when (type) {
                A -> if (rdLen == 4) (0 until 4).joinToString(".") { (b[rd + it].toInt() and 0xFF).toString() } else ""
                PTR -> name(rd).first
                SRV -> if (rdLen >= 7) "${name(rd + 6).first}:${u16(rd + 4)}" else ""
                TXT -> {
                    val parts = mutableListOf<String>()
                    var i = rd
                    while (i < rd + rdLen) {
                        val l = b[i].toInt() and 0xFF
                        if (i + 1 + l > rd + rdLen) break
                        if (l > 0) parts += String(b, i + 1, l, Charsets.UTF_8)
                        i += 1 + l
                    }
                    parts.joinToString(";")
                }
                AAAA -> "ipv6"
                else -> ""
            }
            rrs += Record(n, type, data)
            pos = rd + rdLen
        }
        Message(flags and 0x8000 != 0, qs, rrs)
    }.getOrNull()

    /** Builds a one-question mDNS query (used by the optional "ask once" button). */
    fun query(name: String, type: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(ByteArray(4)) // id 0, flags 0 (standard query)
        out.write(byteArrayOf(0, 1, 0, 0, 0, 0, 0, 0))
        name.split('.').filter { it.isNotEmpty() }.forEach {
            val l = it.toByteArray(Charsets.UTF_8)
            out.write(l.size); out.write(l)
        }
        out.write(0)
        out.write(byteArrayOf(0, type.toByte(), 0, 1))
        return out.toByteArray()
    }
}

/** SSDP (UPnP discovery) — HTTP-over-UDP start line + headers. Pure Kotlin. */
object Ssdp {
    data class Message(val startLine: String, val headers: Map<String, String>) {
        val isNotify get() = startLine.startsWith("NOTIFY")
        val isSearch get() = startLine.startsWith("M-SEARCH")
    }

    fun parse(text: String): Message? {
        val lines = text.split("\r\n", "\n").filter { it.isNotBlank() }
        val first = lines.firstOrNull() ?: return null
        if (!first.startsWith("NOTIFY") && !first.startsWith("M-SEARCH") && !first.startsWith("HTTP/")) return null
        val headers = lines.drop(1).mapNotNull { l ->
            val i = l.indexOf(':')
            if (i <= 0) null else l.substring(0, i).trim().lowercase() to l.substring(i + 1).trim()
        }.toMap()
        return Message(first.trim(), headers)
    }

    val SEARCH_ALL: String =
        "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: ssdp:all\r\n\r\n"
}
