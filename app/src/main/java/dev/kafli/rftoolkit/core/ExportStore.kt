package dev.kafli.rftoolkit.core

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * All exports live in the app's private `files/export/` directory.
 * Pull them from the PC with `tools/pull.sh` (uses `adb exec-out run-as`).
 */
object ExportStore {
    fun dir(context: Context): File = File(context.filesDir, "export").apply { mkdirs() }

    /** Writes `<name>-<timestamp>.<ext>` and also overwrites `<name>-latest.<ext>`. */
    fun writeWithLatest(context: Context, name: String, ext: String, content: String): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir(context), "$name-$stamp.$ext")
        file.writeText(content)
        File(dir(context), "$name-latest.$ext").writeText(content)
        return file
    }
}
