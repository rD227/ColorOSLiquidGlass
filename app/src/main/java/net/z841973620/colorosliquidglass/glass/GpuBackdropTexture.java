package net.z841973620.colorosliquidglass.glass;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.HardwareRenderer;
import android.graphics.PixelFormat;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;

import java.util.function.Consumer;

/** One immutable GPU texture. Called on source/material changes, never for plate movement. */
final class GpuBackdropTexture {
    private GpuBackdropTexture() {}

    static Bitmap render(int width, int height, float radius, Consumer<Canvas> draw) {
        RenderNode node = new RenderNode("SharedGlassBackdrop");
        HardwareRenderer renderer = new HardwareRenderer();
        try (ImageReader reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888,
                1, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)) {
            node.setPosition(0, 0, width, height);
            if (radius > 0f) {
                node.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
            }
            Canvas canvas = node.beginRecording();
            try {
                draw.accept(canvas);
            } finally {
                node.endRecording();
            }
            renderer.setContentRoot(node);
            renderer.setSurface(reader.getSurface());
            int result = renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw();
            if (result != HardwareRenderer.SYNC_OK && result != HardwareRenderer.SYNC_REDRAW_REQUESTED) {
                throw new IllegalStateException("GPU backdrop render failed: " + result);
            }
            try (Image image = reader.acquireNextImage()) {
                if (image == null) throw new IllegalStateException("GPU backdrop produced no image");
                try (HardwareBuffer buffer = image.getHardwareBuffer()) {
                    if (buffer == null) throw new IllegalStateException("GPU backdrop has no buffer");
                    // wrapHardwareBuffer retains its own reference. No GPU→CPU readback or copy.
                    Bitmap bitmap = Bitmap.wrapHardwareBuffer(buffer, null);
                    if (bitmap == null) throw new IllegalStateException("GPU backdrop cannot be wrapped");
                    return bitmap;
                }
            }
        } finally {
            renderer.destroy();
            node.discardDisplayList();
        }
    }
}
