package net.z841973620.colorosliquidglass.glass

import android.graphics.Bitmap

/** Main-thread cache for immutable OEM icon bitmaps, never for live capture/drag frames.
 * Returned bitmaps are borrowed: callers must not modify or recycle them.
 */
internal class DesktopIconBitmapCache @JvmOverloads constructor(
    private val maxBytes: Int = 16 * 1024 * 1024,
    private val maxEntries: Int = 128
) {
    private class Entry(source: Bitmap, val bitmap: Bitmap) {
        val bytes = bitmap.allocationByteCount
        private val generation = source.generationId
        private val density = source.density
        private val colorSpace = source.colorSpace
        private val alpha = source.hasAlpha()
        private val premultiplied = source.isPremultiplied

        fun matches(source: Bitmap): Boolean =
            generation == source.generationId && density == source.density &&
            colorSpace == source.colorSpace && alpha == source.hasAlpha() &&
            premultiplied == source.isPremultiplied
    }

    // Bitmap identity is the key. Budget both pixels and entry count, including tiny icons.
    private val entries = LinkedHashMap<Bitmap, Entry>(16, 0.75f, true)
    private var bytes = 0

    fun softwareIcon(source: Bitmap?): Bitmap? {
        if (source == null) return null
        if (source.isRecycled) {
            remove(source)
            return null
        }
        // Mutable software icons already work with this Canvas; never cache their pixels.
        if (source.config != Bitmap.Config.HARDWARE) return source
        val previous = entries[source]
        if (previous != null && previous.matches(source)) {
            PopupCaptureTrace.outcome("icon_bitmap", "cache_hit")
            return previous.bitmap
        }
        remove(source)
        PopupCaptureTrace.outcome("icon_bitmap", "copy")
        val copy = try {
            source.copy(Bitmap.Config.ARGB_8888, false)
        } catch (_: RuntimeException) {
            // OEM-owned source may be retired during readback; let the existing Drawable path
            // supply this frame. A failed copy must never become a cached null result.
            PopupCaptureTrace.outcome("icon_bitmap", "copy_failed")
            return null
        } catch (_: OutOfMemoryError) {
            clear()
            PopupCaptureTrace.outcome("icon_bitmap", "memory_pressure")
            return null
        } ?: return null
        val entry = Entry(source, copy)
        if (entry.bytes > maxBytes) {
            PopupCaptureTrace.outcome("icon_bitmap", "oversize")
            return copy
        }
        entries[source] = entry
        bytes += entry.bytes
        val iterator = entries.entries.iterator()
        while (bytes > maxBytes || entries.size > maxEntries) {
            val oldest = iterator.next()
            bytes -= oldest.value.bytes
            iterator.remove()
        }
        // Do not recycle evicted/replaced copies: a caller may still be drawing one.
        return copy
    }

    fun clear() {
        entries.clear()
        bytes = 0
    }

    private fun remove(source: Bitmap) {
        val previous = entries.remove(source) ?: return
        bytes -= previous.bytes
    }
}
