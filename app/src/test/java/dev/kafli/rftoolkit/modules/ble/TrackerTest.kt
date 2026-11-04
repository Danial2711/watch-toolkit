package dev.kafli.rftoolkit.modules.ble

import dev.kafli.rftoolkit.modules.wifi.Ies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real advertisements recorded by the watch on campus (survey-test1). */
object Ble {
    const val APPLE_OF_NEARBY = "07ff4c0012020003"
    const val APPLE_OF_SEPARATED = "1eff4c00121900d0d030a34cf50aa3f9c928306e22e3402cc39aa459940200"
    const val FAST_PAIR = "0f162cfe004005a02088110e34463cff020af1" // Google Fast Pair, not a tracker
    const val APPLE_NEARBY_INFO = "0bff4c0010063b1d8a3f5a68" // Continuity 0x10 (Nearby Info), not a tracker

    // Synthetic (spec-shaped) frames for protocols not captured yet.
    const val FMDN_UTP = "1616aafe41" + "00112233445566778899aabbccddeeff0011" // frame 0x41
    const val FMDN_NORMAL = "1616aafe40" + "00112233445566778899aabbccddeeff0011"
    const val SMARTTAG = "0e165afd" + "1122334455667788990011"
    const val TILE = "020106" + "0303edfe" + "0b16edfe" + "0102030405060708"

    fun adv(hex: String) = Adv.parse(Ies.hexToBytes(hex))
}

class AdvParserTest {
    @Test fun manufacturerData() {
        val a = Ble.adv(Ble.APPLE_OF_SEPARATED)
        val d = a.manufacturer[0x004C]!!
        assertEquals(0x12, d[0].toInt() and 0xFF)
        assertEquals(0x19, d[1].toInt() and 0xFF)
        assertEquals(27, d.size)
    }

    @Test fun serviceDataAndUuids() {
        val t = Ble.adv(Ble.TILE)
        assertEquals(0x06, t.flags)
        assertEquals(listOf(0xFEED), t.uuids16)
        assertTrue(0xFEED in t.serviceData16)
    }

    @Test fun truncatedStructureStopsSafely() {
        val a = Adv.parse(Ies.hexToBytes("1eff4c0012")) // claims 30 bytes, has 4
        assertTrue(a.manufacturer.isEmpty())
    }

    @Test fun zeroPaddingIgnored() = assertEquals(1, Adv.parse(Ies.hexToBytes(Ble.APPLE_OF_NEARBY + "000000")).manufacturer.size)
}

class TrackerClassifyTest {
    @Test fun appleSeparated() {
        val t = Trackers.classify(Ble.adv(Ble.APPLE_OF_SEPARATED))!!
        assertEquals(TrackerKind.APPLE_FIND_MY, t.kind)
        assertEquals(true, t.separated)
    }

    @Test fun appleOwnerNearby() {
        val t = Trackers.classify(Ble.adv(Ble.APPLE_OF_NEARBY))!!
        assertEquals(TrackerKind.APPLE_FIND_MY, t.kind)
        assertEquals(false, t.separated)
    }

    @Test fun nonTrackers() {
        assertNull(Trackers.classify(Ble.adv(Ble.FAST_PAIR)))
        assertNull(Trackers.classify(Ble.adv(Ble.APPLE_NEARBY_INFO)))
    }

    @Test fun googleFmdn() {
        assertEquals(true, Trackers.classify(Ble.adv(Ble.FMDN_UTP))!!.separated)
        val n = Trackers.classify(Ble.adv(Ble.FMDN_NORMAL))!!
        assertEquals(TrackerKind.GOOGLE_FMDN, n.kind)
        assertNull(n.separated)
    }

    @Test fun smartTagAndTile() {
        assertEquals(TrackerKind.SAMSUNG_SMARTTAG, Trackers.classify(Ble.adv(Ble.SMARTTAG))!!.kind)
        assertEquals(TrackerKind.TILE, Trackers.classify(Ble.adv(Ble.TILE))!!.kind)
    }
}

class FollowDetectorTest {
    private val separated = TrackerInfo(TrackerKind.APPLE_FIND_MY, true, "")
    private val ownerNearby = TrackerInfo(TrackerKind.APPLE_FIND_MY, false, "")
    private val unknown = TrackerInfo(TrackerKind.TILE, null, "")
    private val min = 60_000L

    // Brno-ish walk: ~0.0025° lat ≈ 280 m per step.
    private fun place(i: Int) = GeoFix(49.20 + i * 0.0025, 16.60, 10f)

    @Test fun firstSeparatedSightingIsReported() {
        val d = FollowDetector()
        assertEquals(listOf(FollowEvent.NEW_SEPARATED), d.observe(Sighting("A", separated, -60, 0, place(0))))
        assertTrue(d.observe(Sighting("A", separated, -60, min, place(0))).isEmpty())
    }

    @Test fun followingAcrossThreePlaces() {
        val d = FollowDetector()
        d.observe(Sighting("A", separated, -60, 0, place(0)))
        assertTrue(d.observe(Sighting("A", separated, -60, 8 * min, place(1))).isEmpty())
        // Third place but span < 15 min → not yet.
        assertTrue(d.observe(Sighting("A", separated, -60, 12 * min, place(2))).isEmpty())
        assertEquals(listOf(FollowEvent.FOLLOWING), d.observe(Sighting("A", separated, -60, 16 * min, place(2))))
        // Only once.
        assertTrue(d.observe(Sighting("A", separated, -60, 20 * min, place(3))).isEmpty())
        assertTrue(d.snapshot().single().following)
    }

    @Test fun stationaryTrackerIsNotFollowing() {
        val d = FollowDetector()
        for (i in 0..30) d.observe(Sighting("A", separated, -60, i * min, place(0)))
        val t = d.snapshot().single()
        assertFalse(t.following)
        assertEquals(1, t.places.size)
    }

    @Test fun ownerNearbyTrackerNeverAlerts() {
        val d = FollowDetector()
        for (i in 0..40) assertTrue(d.observe(Sighting("A", ownerNearby, -60, i * min, place(i % 5))).isEmpty())
    }

    @Test fun persistentWithoutLocation() {
        val d = FollowDetector()
        val events = (0..31).flatMap { d.observe(Sighting("T", unknown, -70, it * min, null)) }
        assertEquals(listOf(FollowEvent.PERSISTENT), events)
    }

    @Test fun inaccurateFixesDontCountAsPlaces() {
        val d = FollowDetector()
        (0..20).forEach { d.observe(Sighting("A", separated, -60, it * min, place(it).copy(accM = 500f))) }
        assertEquals(0, d.snapshot().single().places.size)
    }

    @Test fun oldTracksExpire() {
        val d = FollowDetector(expiryMs = 60 * min)
        d.observe(Sighting("A", separated, -60, 0, null))
        d.observe(Sighting("B", separated, -60, 61 * min, null))
        assertEquals(listOf("B"), d.snapshot().map { it.id })
    }

    @Test fun haversine() {
        val m = FollowDetector.distanceM(GeoFix(49.2, 16.6, 0f), GeoFix(49.2025, 16.6, 0f))
        assertTrue("got $m", m in 270.0..290.0)
    }
}
