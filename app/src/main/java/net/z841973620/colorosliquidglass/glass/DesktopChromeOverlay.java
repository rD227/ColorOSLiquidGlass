package net.z841973620.colorosliquidglass.glass;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

/** 桌面分页指示器等附加控件的绘制。 */
final class DesktopChromeOverlay {
    private DesktopChromeOverlay() {}


    /**
     * Rasterize a non-icon desktop chrome View (page indicator dots) into the sample.
     * Prefer software draw. Skip {@link #isMostlyEmpty}: padded wrap_content + few dots
     * is mostly transparent by design. Hide nested {@code mBlurBgView} LiquidGlass and the
     * OEM frosted pill ({@code mBgAlpha} / {@code drawBackgroundIfNeeded}) so only the dots
     * remain — otherwise float-menu glass bakes an opaque indicator plate.
     */
    static void paintDesktopChrome(View host, Canvas canvas, Matrix globalToTarget) {
        try (PopupCaptureTrace.Stage ignoredTrace = PopupCaptureTrace.section("desktop_chrome")) {
            if (host == null) return;
            int w = host.getWidth();
            int h = host.getHeight();
            if (w <= 0 || h <= 0) return;

            View blurBg = null;
            float prevBlurAlpha = 1f;
            int prevBlurVis = View.VISIBLE;
            Object prevBgAlpha = DesktopRenderUtils.fieldValue(host, "mBgAlpha");
            Object blurObj = DesktopRenderUtils.fieldValue(host, "mBlurBgView");
            if (blurObj instanceof View) {
                blurBg = (View) blurObj;
                prevBlurAlpha = blurBg.getAlpha();
                prevBlurVis = blurBg.getVisibility();
            }

            Bitmap rendered = null;
            GlassInstaller.beginPageIndicatorDotSample();
            try {
                if (blurBg != null) {
                    blurBg.setAlpha(0f);
                    blurBg.setVisibility(View.INVISIBLE);
                }
                if (prevBgAlpha instanceof Number) {
                    DesktopRenderUtils.assignField(host, "mBgAlpha", 0);
                }
                // Software only: HW path can still bake GlassDrawable into an opaque plate.
                rendered = DesktopItemRenderer.rasterizeViewSoftware(host, w, h);
            } finally {
                if (prevBgAlpha instanceof Number) {
                    DesktopRenderUtils.assignField(host, "mBgAlpha", prevBgAlpha);
                }
                if (blurBg != null) {
                    try { blurBg.setAlpha(prevBlurAlpha); } catch (Throwable ignored) { }
                    try { blurBg.setVisibility(prevBlurVis); } catch (Throwable ignored) { }
                }
                GlassInstaller.endPageIndicatorDotSample();
            }
            if (rendered == null || rendered.isRecycled()) return;
            try {
                RectF dest = DesktopRenderUtils.mapItemRectToTarget(host,
                        new Rect(0, 0, rendered.getWidth(), rendered.getHeight()), globalToTarget);
                if (dest == null || dest.width() < 1f || dest.height() < 1f) return;
                int alpha = Math.round(255f * Math.max(0f, Math.min(1f, host.getAlpha())));
                DesktopRenderUtils.BITMAP_PAINT.setAlpha(alpha);
                canvas.drawBitmap(rendered, null, dest, DesktopRenderUtils.BITMAP_PAINT);
            } finally {
                if (!rendered.isRecycled()) rendered.recycle();
            }
        }
    }
}
