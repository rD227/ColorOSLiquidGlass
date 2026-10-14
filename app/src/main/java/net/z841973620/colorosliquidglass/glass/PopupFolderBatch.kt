package net.z841973620.colorosliquidglass.glass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import java.util.function.Consumer

/** One capture's folder atlas. Positions stay local to each folder, including its title. */
internal class PopupFolderBatch(private val preferredWidth: Int) {
    private class Folder(
        val width: Int,
        val height: Int,
        val drawer: Consumer<Canvas>,
        val source: Rect = Rect()
    )

    private val folders = mutableListOf<Folder>()
    private val commands = mutableListOf<(Canvas, Bitmap?) -> Unit>()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    fun addFolder(width: Int, height: Int, destination: RectF, drawer: Consumer<Canvas>,
                  retain: Consumer<Bitmap>) {
        require(width > 0 && height > 0)
        val folder = Folder(width, height, drawer)
        val destinationCopy = RectF(destination)
        folders.add(folder)
        commands.add { canvas, atlas ->
            // Keep the existing last-good folder cache for OEM transitions that remove its
            // live glass. CPU cropping replaces six independent GPU waits/readbacks.
            val bitmap = PopupCaptureTrace.section("folder_batch_split").use {
                Bitmap.createBitmap(checkNotNull(atlas), folder.source.left, folder.source.top,
                    folder.width, folder.height)
            }
            retain.accept(bitmap) // Ownership transfers to FOLDER_SNAPS, not the batch.
            canvas.drawBitmap(bitmap, null, destinationCopy, paint)
        }
    }

    /** Preserve stacking: apps/widgets between folders must still draw between those folders. */
    fun addSoftware(drawer: Runnable) {
        commands.add { _, _ -> drawer.run() }
    }

    fun paintInto(canvas: Canvas): Boolean {
        // A menu over apps alone does not need a folder GPU pass.
        val atlas = if (folders.isEmpty()) null else renderAtlas() ?: return false
        try {
            for (command in commands) command(canvas, atlas)
        } finally {
            atlas?.recycle()
        }
        return true
    }

    private fun renderAtlas(): Bitmap? {
        // Shelf packing bounds the width to the popup (or one wider folder). A clear gutter
        // separates source rectangles when the final software blit uses linear filtering.
        val gutter = 1
        val widest = folders.maxOf { it.width + gutter * 2 }
        val width = maxOf(widest, minOf(preferredWidth, folders.sumOf { it.width + gutter * 2 }))
        var x = 0
        var y = 0
        var rowHeight = 0
        for (folder in folders) {
            val cellWidth = folder.width + gutter * 2
            val cellHeight = folder.height + gutter * 2
            if (x + cellWidth > width) {
                x = 0
                y += rowHeight
                rowHeight = 0
            }
            folder.source.set(x + gutter, y + gutter,
                x + gutter + folder.width, y + gutter + folder.height)
            x += cellWidth
            rowHeight = maxOf(rowHeight, cellHeight)
        }
        PopupCaptureTrace.outcome("folder_batch", "passes")
        repeat(folders.size) { PopupCaptureTrace.outcome("folder_batch", "items") }
        return GlassHwRasterizer.render(width, y + rowHeight) { hardwareCanvas ->
            hardwareCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            for (folder in folders) {
                val saved = hardwareCanvas.save()
                try {
                    hardwareCanvas.clipRect(folder.source)
                    hardwareCanvas.translate(folder.source.left.toFloat(), folder.source.top.toFloat())
                    folder.drawer.accept(hardwareCanvas)
                } finally {
                    hardwareCanvas.restoreToCount(saved)
                }
            }
        }
    }
}
