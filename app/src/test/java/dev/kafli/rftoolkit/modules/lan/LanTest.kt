package dev.kafli.rftoolkit.modules.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Hand-built mDNS packets (RFC 6762 / 1035 wire format). */
object Pkt {
    fun name(n: String): ByteArray = ByteArrayOutputStream().apply {
        n.split('.').forEach { val l = it.toByteArray(); write(l.size); write(l) }
        write(0)
    }.toByteArray()

    fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())

    fun rr(nameBytes: ByteArray, type: Int, rdata: ByteArray) =
        nameBytes + u16(type) + u16(0x8001) + byteArrayOf(0, 0, 0, 120) + u16(rdata.size) + rdata

    fun txt(vararg kv: String) = kv.fold(ByteArray(0)) { acc, s -> acc + byteArrayOf(s.length.toByte()) + s.toByteArray() }

    /** Response: PTR (name at offset 12), SRV/TXT owner via compression pointer, A record. */
    fun airplayResponse(): ByteArray {
        val header = u16(0) + u16(0x8400) + u16(0) + u16(4) + u16(0) + u16(0)
        val ptrOwner = name("_airplay._tcp.local")                 // at offset 12
        val instance = byteArrayOf(11) + "Living Room".toByteArray() + byteArrayOf(0xC0.toByte(), 12) // "Living Room" + ptr
        val ptr = rr(ptrOwner, DnsPacket.PTR, instance)
        val instanceOffset = 12 + ptrOwner.size + 10                // rdata start of the PTR record
        val ptrToInstance = byteArrayOf(0xC0.toByte(), instanceOffset.toByte())
        val srv = rr(ptrToInstance, DnsPacket.SRV, u16(0) + u16(0) + u16(7000) + name("Living-Room.local"))
        val txt = rr(ptrToInstance, DnsPacket.TXT, txt("md=AppleTV", "am=AppleTV6,2", "deviceid=AA:BB"))
        val a = rr(name("Living-Room.local"), DnsPacket.A, byteArrayOf(10, 0, 0, 5))
        return header + ptr + srv + txt + a
    }
}

class DnsPacketTest {
    @Test fun parsesResponseWithCompression() {
        val m = DnsPacket.parse(Pkt.airplayResponse())!!
        assertTrue(m.isResponse)
        assertEquals(4, m.records.size)
        assertEquals("Living Room._airplay._tcp.local", m.records[0].data)
        assertEquals("Living Room._airplay._tcp.local", m.records[1].name)
        assertEquals("Living-Room.local:7000", m.records[1].data)
        assertEquals("md=AppleTV;am=AppleTV6,2;deviceid=AA:BB", m.records[2].data)
        assertEquals("10.0.0.5", m.records[3].data)
    }

    @Test fun queryRoundTrips() {
        val m = DnsPacket.parse(DnsPacket.query("_services._dns-sd._udp.local", DnsPacket.PTR))!!
        assertEquals(listOf("_services._dns-sd._udp.local" to DnsPacket.PTR), m.questions)
        assertTrue(!m.isResponse)
    }

    @Test fun truncatedAndLoopingPacketsRejected() {
        val p = Pkt.airplayResponse()
        assertNull(DnsPacket.parse(p.copyOf(30)))
        // Header claiming 1 answer whose name is a pointer to itself.
        val loop = Pkt.u16(0) + Pkt.u16(0x8400) + Pkt.u16(0) + Pkt.u16(1) + Pkt.u16(0) + Pkt.u16(0) + byteArrayOf(0xC0.toByte(), 12)
        assertNull(DnsPacket.parse(loop))
        assertNull(DnsPacket.parse(ByteArray(5)))
    }
}

class SsdpTest {
    @Test fun parsesNotify() {
        val m = Ssdp.parse(
            "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nNT: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
                "NTS: ssdp:alive\r\nSERVER: Linux/4.9 UPnP/1.0 Samsung/1.0\r\nLOCATION: http://10.0.0.7:9197/dmr\r\n\r\n",
        )!!
        assertTrue(m.isNotify)
        assertEquals("Linux/4.9 UPnP/1.0 Samsung/1.0", m.headers["server"])
    }

    @Test fun rejectsGarbage() = assertNull(Ssdp.parse("hello world"))
}

class LanInventoryTest {
    @Test fun buildsDeviceFromMdns() {
        val inv = LanInventory(selfIp = "10.0.0.80", gatewayIp = "10.0.0.1")
        inv.start(0)
        inv.ingestDns("10.0.0.5", "mDNS", DnsPacket.parse(Pkt.airplayResponse())!!, 1_000)
        val d = inv.snapshot().single()
        assertEquals("Living-Room", d.label)
        assertEquals(setOf("airplay"), d.services)
        assertTrue("md=AppleTV" in d.hints && "am=AppleTV6,2" in d.hints)
        assertTrue(d.hints.none { it.startsWith("deviceid") }) // identifiers not kept
    }

    @Test fun llmnrQueriesRecordedAsLookups() {
        val inv = LanInventory("10.0.0.80", "10.0.0.1")
        inv.ingestDns("10.0.0.9", "LLMNR", DnsPacket.parse(DnsPacket.query("wpad", DnsPacket.A))!!, 0)
        assertEquals(setOf("wpad"), inv.snapshot().single().lookups)
    }

    @Test fun isolationVerdicts() {
        val inv = LanInventory("10.0.0.80", "10.0.0.1")
        inv.start(0)
        assertEquals(Isolation.UNKNOWN, inv.isolation(10_000))
        inv.ingestSsdp("10.0.0.1", Ssdp.parse("NOTIFY * HTTP/1.1\r\nNT: upnp:rootdevice\r\n\r\n")!!, 5_000)
        assertEquals(Isolation.LIKELY_ON, inv.isolation(61_000)) // only the gateway talks
        inv.ingestSsdp("10.0.0.7", Ssdp.parse("NOTIFY * HTTP/1.1\r\nNT: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n")!!, 62_000)
        assertEquals(Isolation.OFF, inv.isolation(62_000))
        assertEquals(setOf("upnp:MediaRenderer"), inv.snapshot().first { it.ip == "10.0.0.7" }.services)
    }

    @Test fun ownPacketsIgnored() {
        val inv = LanInventory("10.0.0.80", "10.0.0.1")
        inv.ingestDns("10.0.0.80", "mDNS", DnsPacket.parse(Pkt.airplayResponse())!!, 0)
        assertTrue(inv.snapshot().isEmpty())
    }

    @Test fun ownIpv6LinkLocalIgnored() {
        val inv = LanInventory(setOf("10.0.0.80", "fe80::665d:f4ff:fe83:a771%wlan0"), "10.0.0.1")
        inv.start(0)
        inv.ingestDns("fe80::665d:f4ff:fe83:a771%wlan0", "mDNS", DnsPacket.parse(Pkt.airplayResponse())!!, 0)
        assertTrue(inv.snapshot().isEmpty())
        assertEquals(Isolation.LIKELY_ON, inv.isolation(61_000))
    }

    @Test fun sameHostnameOverIpv4AndIpv6Merged() {
        val inv = LanInventory("10.0.0.80", "10.0.0.1")
        val m = DnsPacket.parse(Pkt.airplayResponse())!!
        inv.ingestDns("2a02:1::5", "mDNS", m, 0)
        inv.ingestDns("10.0.0.5", "mDNS", m, 1)
        val d = inv.snapshot().single()
        assertEquals("10.0.0.5", d.ip)
        assertEquals(setOf("2a02:1::5"), d.otherIps)
    }

    @Test fun serviceTypes() {
        assertEquals("googlecast", LanInventory.serviceType("_googlecast._tcp.local"))
        assertEquals("ipp", LanInventory.serviceType("HP LaserJet._ipp._tcp.local"))
        assertNull(LanInventory.serviceType("_services._dns-sd._udp.local"))
    }
}
