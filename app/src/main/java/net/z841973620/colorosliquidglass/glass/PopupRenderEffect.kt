package net.z841973620.colorosliquidglass.glass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader

/** Blur -> lens stays in the window's RenderThread; no synchronous GPU readback or CPU blur. */
internal class PopupRenderEffect {
    private val node = RenderNode("PopupLiquidGlass")
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun draw(canvas: Canvas, source: Bitmap, shader: RuntimeShader, radius: Float,
             width: Int, height: Int, alpha: Int, colorFilter: ColorFilter?) {
        node.setPosition(0, 0, width, height)
        val recording = node.beginRecording()
        try { recording.drawBitmap(source, 0f, 0f, paint) }
        finally { node.endRecording() }
        // An explicit bitmap input retains the entire capture, even for inset reveal bounds.
        var input = RenderEffect.createBitmapEffect(source)
        if (radius > 0f) input = RenderEffect.createBlurEffect(radius, radius, input, Shader.TileMode.CLAMP)
        var effect = RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(shader, "image"), input)
        if (colorFilter != null) effect = RenderEffect.createColorFilterEffect(colorFilter, effect)
        node.setRenderEffect(effect)
        node.alpha = alpha / 255f
        canvas.drawRenderNode(node)
    }
}
