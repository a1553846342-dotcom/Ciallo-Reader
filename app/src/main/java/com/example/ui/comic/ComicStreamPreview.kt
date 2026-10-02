package com.example.ui.comic

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okio.Buffer
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Semaphore

/** Request-local JPEG preview: no second request, no partial bytes in the final cache. */
internal class ComicStreamPreview(
    private val scope: CoroutineScope,
    private val publish: (Bitmap) -> Unit,
) {
    companion object {
        private val decodeGate = Semaphore(1)
        private const val MAX_PREFIX = 2 * 1024 * 1024
    }
    private val closed = AtomicBoolean(false)
    private val rejected = AtomicBoolean(false)
    private var nextBytes = 16L * 1024
    private var job: Job? = null

    /** Called by the download thread; decoding runs separately and never blocks the socket. */
    fun offer(buffer: Buffer) {
        if (closed.get() || rejected.get() || buffer.size < nextBytes || nextBytes > MAX_PREFIX || job?.isActive == true) return
        if (buffer.size >= 2 && ((buffer[0].toInt() and 255) != 255 || (buffer[1].toInt() and 255) != 216)) {
            rejected.set(true)
            return
        }
        if (!decodeGate.tryAcquire()) return
        val bytes = buffer.clone().readByteArray(minOf(buffer.size, MAX_PREFIX.toLong()))
        nextBytes = maxOf(nextBytes * 2, bytes.size.toLong() + 1)
        // A queued coroutine may be cancelled before it starts: its completion hook owns the permit.
        job = scope.launch(Dispatchers.Default) {
            try {
                val bmp = decode(bytes) ?: return@launch
                if (!closed.get()) publish(bmp)
            } catch (_: Exception) { /* Unsupported/truncated input stays on the normal full-image path. */ }
        }.also { it.invokeOnCompletion { decodeGate.release() } }
    }

    fun close() { closed.set(true); job?.cancel() }

    internal fun decode(bytes: ByteArray): Bitmap? {
        val prefix = completeProgressiveScan(bytes) { rejected.set(true) } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(prefix, 0, prefix.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 720) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bmp = BitmapFactory.decodeByteArray(prefix, 0, prefix.size, options) ?: return null
        val exif = runCatching { ExifInterface(ByteArrayInputStream(prefix)) }.getOrNull() ?: return bmp
        val matrix = Matrix()
        when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
            2 -> matrix.setScale(-1f, 1f)
            3 -> matrix.setRotate(180f)
            4 -> { matrix.setRotate(180f); matrix.postScale(-1f, 1f) }
            5 -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            6 -> matrix.setRotate(90f)
            7 -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            8 -> matrix.setRotate(-90f)
            else -> return bmp
        }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
    }
}

/** Keep only COMPLETE entropy scans of progressive JPEGs. Baseline partial pages are rejected. */
internal fun completeProgressiveScan(bytes: ByteArray, onBaseline: () -> Unit = {}): ByteArray? {
    fun u(i: Int) = bytes[i].toInt() and 255
    if (bytes.size < 4 || u(0) != 255 || u(1) != 216) return null
    var progressive = false
    var scan = false
    var completedEnd = -1
    var pos = 2
    while (pos + 1 < bytes.size) {
        if (scan) {
            if (u(pos) != 255) { pos++; continue }
            var codeAt = pos + 1
            while (codeAt < bytes.size && u(codeAt) == 255) codeAt++
            if (codeAt >= bytes.size) break
            val marker = u(codeAt)
            if (marker == 0 || marker in 208..215) { pos = codeAt + 1; continue }
            completedEnd = pos
            scan = false
        }
        if (u(pos) != 255) break
        var codeAt = pos + 1
        while (codeAt < bytes.size && u(codeAt) == 255) codeAt++
        if (codeAt >= bytes.size) break
        val marker = u(codeAt)
        if (marker == 217) break
        if (marker == 194) progressive = true
        if (marker == 192 || marker == 193) { onBaseline(); return null }
        val lengthAt = codeAt + 1
        if (lengthAt + 1 >= bytes.size) break
        val length = (u(lengthAt) shl 8) or u(lengthAt + 1)
        if (length < 2 || lengthAt + length > bytes.size) break
        pos = lengthAt + length
        if (marker == 218) scan = true
    }
    if (!progressive || completedEnd < 0) return null
    return bytes.copyOf(completedEnd + 2).also {
        it[completedEnd] = 255.toByte()
        it[completedEnd + 1] = 217.toByte()
    }
}
