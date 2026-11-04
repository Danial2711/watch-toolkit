package dev.kafli.rftoolkit.modules.survey

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Append-only JSONL writer. Producers call [write] from any thread; a single consumer
 * coroutine owns the file and flushes at least every [flushMs] so a crash loses little.
 */
class SurveyWriter(val file: File, scope: CoroutineScope, private val flushMs: Long = 5_000) {

    private val queue = Channel<String>(capacity = 10_000)
    val records = AtomicLong(0)
    val bytes = AtomicLong(0)
    private val out: BufferedWriter = file.bufferedWriter()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val job = scope.launch(Dispatchers.IO) {
        var lastFlush = System.currentTimeMillis()
        while (true) {
            val line = select<String?> {
                queue.onReceiveCatching { it.getOrNull() ?: CLOSED }
                onTimeout(flushMs) { null }
            }
            if (line === CLOSED) break
            if (line != null) {
                out.write(line)
                out.write('\n'.code)
                records.incrementAndGet()
                bytes.addAndGet(line.length + 1L)
            }
            val now = System.currentTimeMillis()
            if (now - lastFlush >= flushMs) {
                out.flush()
                lastFlush = now
            }
        }
        out.flush()
        out.close()
    }

    /** Non-blocking; drops the record if the queue is full (logged in totals as dropped). */
    val dropped = AtomicLong(0)

    fun write(o: JSONObject) {
        if (queue.trySend(o.toString()).isFailure) dropped.incrementAndGet()
    }

    /** Writes the final record, then drains and closes the file. */
    suspend fun close(last: JSONObject? = null) {
        last?.let { queue.trySend(it.toString()) }
        queue.close()
        withTimeoutOrNull(5_000) { job.join() }
    }

    private companion object {
        val CLOSED = String(charArrayOf('\u0000'))
    }
}
