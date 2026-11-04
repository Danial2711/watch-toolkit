package dev.kafli.rftoolkit.modules.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetMathTest {
    @Test fun ipv4RoundTrip() {
        assertEquals("10.129.112.80", NetMath.formatIpv4(NetMath.parseIpv4("10.129.112.80")!!))
        assertNull(NetMath.parseIpv4("256.1.1.1"))
        assertNull(NetMath.parseIpv4("1.2.3"))
        assertNull(NetMath.parseIpv4("01.2.3.4"))
    }

    @Test fun privateRanges() {
        listOf("10.129.112.24", "172.16.5.1", "172.31.255.255", "192.168.1.1", "100.64.0.1", "169.254.1.1", "127.0.0.1")
            .forEach { assertTrue(it, NetMath.isPrivate(it)) }
        listOf("8.8.8.8", "172.32.0.1", "1.1.1.1", "100.128.0.1", "example.com")
            .forEach { assertFalse(it, NetMath.isPrivate(it)) }
    }

    @Test fun subnetHostsExcludeSelfNetworkAndBroadcast() {
        val self = NetMath.parseIpv4("10.129.112.80")!!
        val hosts = NetMath.subnetHosts(self, 24).map(NetMath::formatIpv4)
        assertEquals(253, hosts.size)
        assertEquals("10.129.112.1", hosts.first())
        assertEquals("10.129.112.254", hosts.last())
        assertFalse("10.129.112.80" in hosts)
    }

    @Test fun wideSubnetsClampedTo24() = assertEquals(253, NetMath.subnetHosts(NetMath.parseIpv4("10.0.5.7")!!, 8).size)

    @Test fun parsesPing() {
        val out = """
            PING 10.129.112.24 (10.129.112.24) 56(84) bytes of data.
            64 bytes from 10.129.112.24: icmp_seq=1 ttl=64 time=4.21 ms
            --- 10.129.112.24 ping statistics ---
            4 packets transmitted, 3 received, 25% packet loss, time 3005ms
            rtt min/avg/max/mdev = 3.902/12.448/28.101/10.993 ms
        """.trimIndent()
        val s = NetMath.parsePing(out)!!
        assertEquals(4, s.sent); assertEquals(3, s.received); assertEquals(25.0, s.lossPct, 0.0)
        assertEquals(12.448, s.avgMs!!, 1e-9)
    }

    @Test fun parsesTracerouteHops() {
        assertEquals(NetMath.Hop.Router("10.129.112.24"),
            NetMath.parseHop("From 10.129.112.24: icmp_seq=1 Time to live exceeded"))
        assertEquals(NetMath.Hop.Router("147.251.4.1"),
            NetMath.parseHop("From 147.251.4.1 (147.251.4.1): icmp_seq=1 Time to live exceeded"))
        assertEquals(NetMath.Hop.Reached("93.184.216.34", 21.4),
            NetMath.parseHop("64 bytes from 93.184.216.34: icmp_seq=1 ttl=52 time=21.4 ms"))
        assertEquals(NetMath.Hop.Timeout, NetMath.parseHop("1 packets transmitted, 0 received, 100% packet loss"))
    }

    @Test fun hostValidationBlocksShellMetacharacters() {
        assertTrue(NetMath.isValidHost("example.com"))
        assertTrue(NetMath.isValidHost("10.0.0.1"))
        listOf("", "a;rm -rf", "x y", "\$(id)", "-c", "a|b").forEach { assertFalse(it, NetMath.isValidHost(it)) }
    }
}

class DnsCompareTest {
    @Test fun parsesDohJson() {
        val json = """{"Status":0,"Answer":[{"name":"example.com","type":5,"data":"cdn.example."},{"name":"cdn.example","type":1,"data":"93.184.216.34"}]}"""
        assertEquals(listOf("93.184.216.34"), DnsCompare.parseDohA(json))
        assertEquals(emptyList<String>(), DnsCompare.parseDohA("""{"Status":3}"""))
    }

    @Test fun flagsPrivateAnswerAsHijack() {
        val r = DnsCompare.report(listOf("10.0.0.1"), listOf("93.184.216.34"), null, null)
        assertTrue(r.any { it.startsWith("‼") })
    }

    @Test fun overlapIsOk() =
        assertTrue(DnsCompare.report(listOf("1.2.3.4", "5.6.7.8"), listOf("5.6.7.8"), null, null).last().startsWith("✓"))

    @Test fun reportsErrors() =
        assertTrue(DnsCompare.report(null, listOf("1.2.3.4"), "timeout", null).first().contains("timeout"))
}

class SecurityHeadersTest {
    @Test fun allPresent() {
        val h = mapOf(
            "strict-transport-security" to "max-age=63072000; includeSubDomains",
            "content-security-policy" to "default-src 'self'",
            "x-content-type-options" to "nosniff", "x-frame-options" to "DENY",
            "referrer-policy" to "no-referrer", "permissions-policy" to "geolocation=()",
        )
        val r = SecurityHeaders.check(h)
        assertEquals("Headers: 6/6", r.first())
        assertFalse(r.any { it.startsWith("✗") || it.startsWith("!") })
    }

    @Test fun missingAndWeakHsts() {
        val r = SecurityHeaders.check(mapOf("strict-transport-security" to "max-age=3600"))
        assertEquals("Headers: 1/6", r.first())
        assertTrue(r.any { it.startsWith("✗ missing") && "CSP" in it })
        assertTrue(r.any { it.startsWith("! HSTS max-age short") })
    }

    @Test fun cspFrameAncestorsNoted() =
        assertTrue(SecurityHeaders.check(mapOf("content-security-policy" to "frame-ancestors 'none'")).any { "frame-ancestors" in it })
}
