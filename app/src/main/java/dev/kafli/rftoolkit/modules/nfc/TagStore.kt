package dev.kafli.rftoolkit.modules.nfc

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class TagContentType { URL, TEXT }

data class TagConfig(
    val enabled: Boolean = false,
    val type: TagContentType = TagContentType.URL,
    val value: String = "https://example.com",
) {
    fun ndefMessage(): ByteArray = when (type) {
        TagContentType.URL -> Ndef.uriRecord(value)
        TagContentType.TEXT -> Ndef.textRecord(value)
    }
}

data class ApduLogEntry(val ms: Long, val command: String, val description: String, val status: String)

/** Tag configuration (persisted) and the in-memory command log shared with the UI. */
object TagStore {
    private const val PREFS = "nfc_tag"
    private const val MAX_LOG = 200

    private val _config = MutableStateFlow(TagConfig())
    val config: StateFlow<TagConfig> = _config.asStateFlow()

    private val _log = MutableStateFlow<List<ApduLogEntry>>(emptyList())
    val log: StateFlow<List<ApduLogEntry>> = _log.asStateFlow()

    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _config.value = TagConfig(
            enabled = p.getBoolean("enabled", false),
            type = runCatching { TagContentType.valueOf(p.getString("type", "URL")!!) }.getOrDefault(TagContentType.URL),
            value = p.getString("value", null) ?: TagConfig().value,
        )
        loaded = true
    }

    fun update(ctx: Context, f: (TagConfig) -> TagConfig) {
        load(ctx)
        val c = f(_config.value)
        // Validate before saving: a message that doesn't fit the tag file would break every read.
        if (c.ndefMessage().size + 2 > Type4Tag.MAX_NDEF_FILE) return
        _config.value = c
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", c.enabled)
            .putString("type", c.type.name)
            .putString("value", c.value)
            .apply()
    }

    fun record(command: ByteArray, response: ByteArray) {
        val sw = if (response.size >= 2) hex(response.copyOfRange(response.size - 2, response.size)) else "?"
        val entry = ApduLogEntry(
            ms = System.currentTimeMillis(),
            command = hex(command).take(40),
            description = Type4Tag.describe(command),
            status = sw + if (response.size > 2) " +${response.size - 2}B" else "",
        )
        _log.update { (listOf(entry) + it).take(MAX_LOG) }
    }

    fun clearLog() { _log.value = emptyList() }

    fun hex(b: ByteArray): String = b.joinToString("") { "%02X".format(it) }
}
