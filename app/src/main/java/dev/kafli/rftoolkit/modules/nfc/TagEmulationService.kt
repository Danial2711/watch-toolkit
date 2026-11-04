package dev.kafli.rftoolkit.modules.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle

/**
 * Host Card Emulation service presenting the watch as a read-only NFC Forum Type 4 NDEF tag.
 * Android only routes APDUs here after a reader selects our registered AID (D2760000850101);
 * when emulation is switched off in the app, the SELECT is answered "not found" so readers
 * see no tag at all.
 */
class TagEmulationService : HostApduService() {

    private var tag: Type4Tag? = null

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        TagStore.load(this)
        val cfg = TagStore.config.value
        val isSelectApp = commandApdu.size >= 4 && commandApdu[1] == 0xA4.toByte() && commandApdu[2] == 0x04.toByte()

        val response = when {
            !cfg.enabled -> Type4Tag.SW_NOT_FOUND
            isSelectApp || tag == null -> {
                // New reader session: rebuild from the current content so edits apply on the next tap.
                tag = runCatching { Type4Tag(cfg.ndefMessage()) }.getOrNull()
                tag?.process(commandApdu) ?: Type4Tag.SW_NOT_FOUND
            }
            else -> tag!!.process(commandApdu)
        }
        TagStore.record(commandApdu, response)
        return response
    }

    override fun onDeactivated(reason: Int) {
        tag = null
    }
}
