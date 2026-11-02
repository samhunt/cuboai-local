package com.cuboai.local.data

import android.util.Log
import androidx.annotation.Keep
import java.util.ArrayDeque

/**
 * In-process MPEG-TS ring buffer. The Python muxer pushes chunks; ExoPlayer reads
 * them through [TsHubDataSource] so the live feed never depends on loopback HTTP.
 */
@Keep
class TsHub private constructor() {
    private val lock = Object()
    private val chunks = ArrayDeque<ByteArray>()
    private var buffered = 0
    private var head = 0L
    private var tail = 0L
    private var pushCount = 0L

    @Volatile
    var running: Boolean = false

    fun clear() {
        synchronized(lock) {
            chunks.clear()
            buffered = 0
            head = 0L
            tail = 0L
            pushCount = 0L
            lock.notifyAll()
        }
    }

    fun available(): Long = synchronized(lock) { tail - head }

    fun head(): Long = synchronized(lock) { head }

    fun push(chunk: ByteArray) {
        if (chunk.isEmpty()) return
        synchronized(lock) {
            chunks.addLast(chunk)
            buffered += chunk.size
            tail += chunk.size
            while (buffered > CAP && chunks.isNotEmpty()) {
                val old = chunks.removeFirst()
                buffered -= old.size
                head += old.size.toLong()
            }
            val pushes = ++pushCount
            if (pushes <= 8L || pushes % 50L == 0L) {
                Log.i(
                    "TsHub",
                    "push#$pushes +${chunk.size} buf=$buffered sync=${chunk.firstOrNull()?.toUByte()}",
                )
            }
            lock.notifyAll()
        }
    }

    /**
     * Copy up to [length] bytes starting at [posRef][0]. Updates [posRef] to the
     * next absolute offset. Blocks until data arrives, the timeout elapses, or
     * [running] is false.
     */
    fun read(posRef: LongArray, dst: ByteArray, offset: Int, length: Int, timeoutMs: Long): Int {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            var cursor = posRef[0]
            while (tail <= cursor && running && System.currentTimeMillis() < deadline) {
                val wait = deadline - System.currentTimeMillis()
                if (wait <= 0L) break
                lock.wait(wait)
            }
            if (cursor < head) cursor = head
            if (tail <= cursor) {
                posRef[0] = cursor
                return 0
            }
            var skip = (cursor - head).toInt()
            var written = 0
            for (chunk in chunks) {
                if (written >= length) break
                if (skip >= chunk.size) {
                    skip -= chunk.size
                    continue
                }
                val start = skip
                skip = 0
                val n = minOf(chunk.size - start, length - written)
                System.arraycopy(chunk, start, dst, offset + written, n)
                written += n
            }
            posRef[0] = cursor + written
            return written
        }
    }

    companion object {
        private const val CAP = 2 * 1024 * 1024

        @JvmField
        val INSTANCE = TsHub()
    }
}
