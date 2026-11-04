package dev.kafli.rftoolkit.modules.survey

/**
 * Decides whether a repeated sighting should be logged again.
 * A device advertising 10x/s would otherwise produce ~36k identical rows per hour.
 *
 * Bounded: when [maxKeys] is exceeded the oldest entries are evicted (LRU by insertion/access).
 */
class SightingThrottle(private val minIntervalMs: Long, private val maxKeys: Int = 20_000) {

    private val last = object : LinkedHashMap<String, Long>(1024, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > maxKeys
    }

    @Synchronized
    fun shouldEmit(key: String, nowMs: Long): Boolean {
        val prev = last[key]
        if (prev != null && nowMs - prev < minIntervalMs) return false
        last[key] = nowMs
        return true
    }

    @Synchronized
    fun size(): Int = last.size
}
