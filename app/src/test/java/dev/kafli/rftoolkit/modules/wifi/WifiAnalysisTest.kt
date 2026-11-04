package dev.kafli.rftoolkit.modules.wifi

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures are real IEs recorded by the watch (survey-test1, MUNI campus). */
object Fx {
    fun ie(id: Int, hex: String) = RawIe(id, 0, Ies.hexToBytes(hex))
    fun ssidIe(s: String) = RawIe(0, 0, s.toByteArray())

    const val RSN_PSK_MFPC = "0100000fac040100000fac040100000fac028c00"   // POCO M6 Pro hotspot
    const val RSN_PSK_NOPMF = "0100000fac040100000fac040100000fac020c00"  // "1234"
    const val RSN_EAP = "0100000fac040100000fac040100000fac012800"        // eduroam
    const val RSN_SAE_PSK = "0100000fac040100000fac040200000fac08000fac02c000" // WPA3 transition, MFPC+MFPR
    const val WMM = "0050f2020101800003a4000027a4000042435e0072322f00"

    /** WPS vendor IE: state=configured(2), not locked, Manufacturer/Model. */
    fun wpsIe(state: Int, selReg: Boolean = false, locked: Boolean? = null): RawIe {
        fun tlv(type: Int, v: ByteArray) =
            byteArrayOf((type shr 8).toByte(), type.toByte(), (v.size shr 8).toByte(), v.size.toByte()) + v
        var body = byteArrayOf(0x00, 0x50, 0xF2.toByte(), 0x04) +
            tlv(0x104A, byteArrayOf(0x10)) +
            tlv(0x1044, byteArrayOf(state.toByte())) +
            tlv(0x1021, "TP-LINK".toByteArray()) +
            tlv(0x1023, "Archer C6".toByteArray()) +
            tlv(0x1024, "6.0".toByteArray())
        if (selReg) body += tlv(0x1041, byteArrayOf(1)) + tlv(0x1012, byteArrayOf(0x00, 0x04))
        if (locked != null) body += tlv(0x1057, byteArrayOf(if (locked) 1 else 0))
        return RawIe(221, 0, body)
    }

    fun ap(
        ssid: String, bssid: String = "34:5d:a8:1c:da:41", caps: String = "[ESS]",
        ies: List<RawIe> = emptyList(), ms: Long = 1_000,
    ) = ApProfile.from(bssid, ssid, caps, 2412, -60, ms, listOf(ssidIe(ssid)) + ies)
}

class IesTest {
    @Test fun parsesPskWithMfpCapable() {
        val r = Ies.parseRsn(Ies.hexToBytes(Fx.RSN_PSK_MFPC))!!
        assertEquals(Cipher.CCMP, r.group)
        assertEquals(listOf(Cipher.CCMP), r.pairwise)
        assertEquals(listOf(Akm.PSK), r.akms)
        assertTrue(r.mfpCapable)
        assertFalse(r.mfpRequired)
    }

    @Test fun parsesEnterprise() {
        val r = Ies.parseRsn(Ies.hexToBytes(Fx.RSN_EAP))!!
        assertEquals(listOf(Akm.EAP), r.akms)
        assertFalse(r.mfpCapable)
    }

    @Test fun parsesTransitionMode() {
        val r = Ies.parseRsn(Ies.hexToBytes(Fx.RSN_SAE_PSK))!!
        assertEquals(listOf(Akm.SAE, Akm.PSK), r.akms)
        assertTrue(r.mfpCapable && r.mfpRequired)
    }

    @Test fun truncatedRsnIsRejected() = assertNull(Ies.parseRsn(Ies.hexToBytes("0100000fac040200000fac04")))

    @Test fun parsesWps() {
        val w = Ies.wps(listOf(Fx.wpsIe(state = 1, selReg = true, locked = false)))!!
        assertTrue(w.unconfigured)
        assertEquals(true, w.selectedRegistrar)
        assertEquals(0x0004, w.devicePasswordId)
        assertEquals("TP-LINK Archer C6 (6.0)", w.deviceLabel)
    }

    @Test fun wmmIsNotWps() = assertNull(Ies.wps(listOf(Fx.ie(221, Fx.WMM))))

    @Test fun hiddenSsid() {
        assertTrue(Ies.ssidHidden(listOf(RawIe(0, 0, ByteArray(0)))))
        assertTrue(Ies.ssidHidden(listOf(RawIe(0, 0, ByteArray(5)))))
        assertFalse(Ies.ssidHidden(listOf(Fx.ssidIe("eduroam"))))
    }
}

class ClassifyTest {
    @Test fun realCampusNetworks() {
        assertEquals(SecurityClass.WPA2_EAP, Fx.ap("eduroam", ies = listOf(Fx.ie(48, Fx.RSN_EAP))).security)
        assertEquals(SecurityClass.OPEN, Fx.ap("MUNI").security)
        assertEquals(SecurityClass.WPA2_PSK, Fx.ap("1234", ies = listOf(Fx.ie(48, Fx.RSN_PSK_NOPMF))).security)
        assertEquals(SecurityClass.WPA3_TRANSITION, Fx.ap("x", ies = listOf(Fx.ie(48, Fx.RSN_SAE_PSK))).security)
    }

    @Test fun capsFallbackWithoutIes() {
        assertEquals(SecurityClass.WEP, ApProfile.fromCaps("[WEP][ESS]").first)
        assertEquals(SecurityClass.WPA2_EAP, ApProfile.fromCaps("[WPA2-EAP/SHA1-CCMP][RSN-EAP/SHA1-CCMP][ESS]").first)
        assertEquals(SecurityClass.WPA1, ApProfile.fromCaps("[WPA-PSK-TKIP][ESS]").first)
        assertEquals(SecurityClass.OPEN, ApProfile.fromCaps("[ESS]").first)
    }

    @Test fun locallyAdministeredBssid() {
        assertTrue(Fx.ap("POCO", bssid = "86:2f:00:88:58:44").bssidLocallyAdministered)
        assertFalse(Fx.ap("MUNI", bssid = "34:5d:a8:1c:da:40").bssidLocallyAdministered)
    }
}

class WifiRulesTest {
    private fun types(ap: ApProfile) = WifiRules.analyze(ap).map { it.type }.toSet()

    @Test fun openNetwork() = assertEquals(setOf(FindingType.OPEN_NETWORK), types(Fx.ap("MUNI")))

    @Test fun iotSetupModeInsteadOfOpen() {
        assertEquals(setOf(FindingType.IOT_SETUP_MODE), types(Fx.ap("ESP_1A2B3C")))
        assertEquals(setOf(FindingType.IOT_SETUP_MODE), types(Fx.ap("shellyplus1pm-a8032ab12345")))
        assertEquals(setOf(FindingType.IOT_SETUP_MODE), types(Fx.ap("yeelink-light-color1_miap9A1B")))
    }

    @Test fun pskWithoutPmf() =
        assertEquals(setOf(FindingType.NO_PMF), types(Fx.ap("1234", ies = listOf(Fx.ie(48, Fx.RSN_PSK_NOPMF)))))

    @Test fun pskWithPmfIsClean() =
        assertTrue(types(Fx.ap("POCO M6 Pro", ies = listOf(Fx.ie(48, Fx.RSN_PSK_MFPC)))).isEmpty())

    @Test fun enterpriseIsClean() =
        assertTrue(types(Fx.ap("eduroam", ies = listOf(Fx.ie(48, Fx.RSN_EAP)))).isEmpty())

    @Test fun wpsVariants() {
        val rsn = Fx.ie(48, Fx.RSN_PSK_MFPC)
        assertTrue(FindingType.WPS_ENABLED in types(Fx.ap("Home", ies = listOf(rsn, Fx.wpsIe(2)))))
        assertFalse(FindingType.WPS_ENABLED in types(Fx.ap("Home", ies = listOf(rsn, Fx.wpsIe(2, locked = true)))))
        assertTrue(FindingType.WPS_UNCONFIGURED in types(Fx.ap("Home", ies = listOf(rsn, Fx.wpsIe(1)))))
        assertTrue(FindingType.WPS_ACTIVE_NOW in types(Fx.ap("Home", ies = listOf(rsn, Fx.wpsIe(2, selReg = true)))))
    }

    @Test fun wpsDetailCarriesRouterModel() {
        val f = WifiRules.analyze(Fx.ap("Home", ies = listOf(Fx.ie(48, Fx.RSN_PSK_MFPC), Fx.wpsIe(2)))).first { it.type == FindingType.WPS_ENABLED }
        assertEquals("TP-LINK Archer C6 (6.0)", f.detail)
    }

    @Test fun wepAndWpa1() {
        assertTrue(FindingType.WEP_IN_USE in types(Fx.ap("old", caps = "[WEP][ESS]")))
        assertTrue(FindingType.WPA1_ONLY in types(Fx.ap("old", caps = "[WPA-PSK-TKIP][ESS]")))
    }

    @Test fun defaultSsidAndWifiDirect() {
        assertTrue(FindingType.DEFAULT_SSID in types(Fx.ap("TP-Link_A1B2", ies = listOf(Fx.ie(48, Fx.RSN_PSK_MFPC)))))
        assertTrue(FindingType.WIFI_DIRECT in types(Fx.ap("DIRECT-7E-HP M283 LaserJet", ies = listOf(Fx.ie(48, Fx.RSN_PSK_MFPC)))))
    }

    @Test fun findingSerializes() {
        val f = WifiRules.analyze(Fx.ap("MUNI")).first()
        val o = JSONObject(f.toJson().toString())
        assertEquals("finding", o.getString("t"))
        assertEquals("OPEN_NETWORK", o.getString("type"))
        assertEquals("LOW", o.getString("sev"))
    }
}

class EvilTwinDetectorTest {
    private val eap = Fx.ie(48, Fx.RSN_EAP)

    @Test fun firstSightingIsLearnedNotFlagged() {
        val d = EvilTwinDetector()
        assertTrue(d.observe(Fx.ap("eduroam", ies = listOf(eap))).isEmpty())
        assertEquals(1, d.size)
    }

    @Test fun openTwinOfEnterpriseNetworkIsFlagged() {
        val d = EvilTwinDetector()
        d.observe(Fx.ap("eduroam", ies = listOf(eap)))
        val f = d.observe(Fx.ap("eduroam", bssid = "86:2f:00:88:58:44"))
        assertEquals(listOf(FindingType.EVIL_TWIN_DOWNGRADE), f.map { it.type })
        assertTrue(f.single().detail.contains("WPA2-Enterprise"))
        // Not learned: keeps firing while the twin is around.
        assertEquals(1, d.observe(Fx.ap("eduroam", bssid = "86:2f:00:88:58:44", ms = 2_000)).size)
    }

    @Test fun pskTwinOfEnterpriseIsFlagged() {
        val d = EvilTwinDetector()
        d.observe(Fx.ap("eduroam", ies = listOf(eap)))
        val f = d.observe(Fx.ap("eduroam", bssid = "00:11:22:33:44:55", ies = listOf(Fx.ie(48, Fx.RSN_PSK_MFPC))))
        assertEquals(FindingType.EVIL_TWIN_DOWNGRADE, f.single().type)
    }

    @Test fun upgradeIsLearnedSilently() {
        val d = EvilTwinDetector()
        d.observe(Fx.ap("Home", ies = listOf(Fx.ie(48, Fx.RSN_PSK_MFPC))))
        assertTrue(d.observe(Fx.ap("Home", bssid = "00:11:22:33:44:56", ies = listOf(Fx.ie(48, Fx.RSN_SAE_PSK)))).isEmpty())
    }

    @Test fun vendorMismatchAfterEnoughHistory() {
        val d = EvilTwinDetector(minBssidsForVendorCheck = 3)
        listOf("34:5d:a8:1c:da:41", "34:5d:a8:1c:68:21", "34:5d:a8:1c:fc:c1").forEach {
            assertTrue(d.observe(Fx.ap("eduroam", bssid = it, ies = listOf(eap))).isEmpty())
        }
        val f = d.observe(Fx.ap("eduroam", bssid = "00:0b:86:aa:bb:cc", ies = listOf(eap)))
        assertEquals(FindingType.EVIL_TWIN_VENDOR, f.single().type)
        // Randomized BSSIDs are not judged by vendor.
        assertTrue(d.observe(Fx.ap("eduroam", bssid = "86:2f:00:88:58:45", ies = listOf(eap))).isEmpty())
    }

    @Test fun hiddenNetworksIgnored() =
        assertTrue(EvilTwinDetector().observe(Fx.ap("")).isEmpty())

    @Test fun baselineRoundTrips() {
        val d = EvilTwinDetector()
        d.observe(Fx.ap("eduroam", ies = listOf(eap)))
        val d2 = EvilTwinDetector().apply { load(JSONObject(d.toJson().toString())) }
        assertEquals(1, d2.size)
        assertEquals(FindingType.EVIL_TWIN_DOWNGRADE, d2.observe(Fx.ap("eduroam", bssid = "86:2f:00:88:58:44")).single().type)
    }
}
