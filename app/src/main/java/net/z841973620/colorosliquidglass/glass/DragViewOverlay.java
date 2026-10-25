package net.z841973620.colorosliquidglass.glass;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Map;

/** 拖拽对象的来源、轮廓、尺寸调整框和玻璃内容绘制。 */
final class DragViewOverlay {
    private DragViewOverlay() {}


    /** Paint active *DragView contents into a screen-rect sample (float-menu ashmem path). */
    static void paintDragViewsIntoScreenRect(Rect screenRect, Canvas canvas, View root) {
        if (screenRect == null || screenRect.isEmpty() || canvas == null || root == null) return;
        Matrix globalToTarget = new Matrix();
        globalToTarget.setTranslate(-screenRect.left, -screenRect.top);
        paintDragViewsInto(root.getRootView() != null ? root.getRootView() : root,
                canvas, globalToTarget, new RectF(screenRect));
    }

    static void paintDragViewsInto(View root, Canvas canvas, Matrix globalToTarget,
            RectF region) {
        if (root == null) return;
        if (isDragView(root) && DesktopRenderUtils.intersectsRegion(region, root)
                && root.getVisibility() != View.GONE
                && root.getWidth() > 0 && root.getHeight() > 0) {
            paintOneDragView(root, canvas, globalToTarget);
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                paintDragViewsInto(g.getChildAt(i), canvas, globalToTarget, region);
            }
        }
    }

    private static boolean isDragView(View view) {
        if (view == null) return false;
        String name = view.getClass().getSimpleName();
        return name != null && name.endsWith("DragView");
    }

    /**
     * DragView on ColorOS often keeps only an outline in software {@code draw()} while the
     * real icon lives in {@code mContent} / {@code mOriginView} behind {@code mBlurProp}.
     * Paint FastBitmap / folder plate at DragView-local coords, then overlay the OEM stroke.
     */
    private static void paintOneDragView(View dragView, Canvas canvas, Matrix globalToTarget) {
        Object blurProp = DesktopRenderUtils.fieldValue(dragView, "mBlurProp");
        boolean clearedBlur = clearField(dragView, "mBlurProp");
        try {
            View content = dragContentView(dragView);
            View origin = dragOriginView(dragView);
            View iconSrc = resolveDragIconSource(content, origin);
            View sizedSrc = null;
            if (content != null && (isDescendant(content, dragView) || content.getParent() == dragView)) {
                sizedSrc = content;
            } else if (iconSrc != null && isDescendant(iconSrc, dragView)) {
                sizedSrc = iconSrc;
            }

            boolean painted = false;
            if (iconSrc != null && DesktopRenderUtils.isFolderIcon(iconSrc)) {
                // Live LiquidGlass is on DragView content (handleFolderBackground), not the
                // OEM-stripped FolderIcon plate. Prefer HW-rasterizing that content.
                View glassHost = dragFolderGlassHost(content, sizedSrc, iconSrc);
                if (glassHost != null) {
                    painted = paintDragFolderGlassAt(glassHost, dragView, canvas, globalToTarget);
                }
                if (!painted) {
                    View folder = (sizedSrc != null && DesktopRenderUtils.isFolderIcon(sizedSrc)) ? sizedSrc : iconSrc;
                    painted = FolderCompositeRenderer.paintFolderIntoSampleAt(folder, dragView, canvas, globalToTarget);
                }
            }
            if (!painted && sizedSrc != null && !DesktopRenderUtils.isFolderIcon(sizedSrc)) {
                painted = paintAppIconAt(sizedSrc, dragView, canvas, globalToTarget);
            }
            if (!painted && origin != null && !DesktopRenderUtils.isFolderIcon(origin)) {
                painted = paintAppIconAt(origin, dragView, canvas, globalToTarget);
            }
            if (!painted && iconSrc != null && !DesktopRenderUtils.isFolderIcon(iconSrc)) {
                painted = paintAppIconAt(iconSrc, dragView, canvas, globalToTarget);
            }
            if (!painted && paintDragCachedBitmap(dragView, canvas, globalToTarget)) {
                painted = true;
            }
            if (!painted) {
                View drawSrc = sizedSrc != null ? sizedSrc : (content != null ? content : iconSrc);
                if (drawSrc != null && drawSrc != dragView) {
                    painted = paintViewRasterAt(drawSrc, dragView, canvas, globalToTarget);
                }
            }
            if (!painted) {
                paintViewRasterAt(dragView, dragView, canvas, globalToTarget);
            }
            // Selection stroke — folders often draw on DragView; apps often on content.
            paintDragOutline(dragView, content != null ? content : iconSrc, canvas, globalToTarget);
        } finally {
            if (clearedBlur && blurProp != null) {
                restoreField(dragView, "mBlurProp", blurProp);
            }
        }
    }

    /**
     * Collect DragView origins to skip as cell ghosts for the whole drag lifetime.
     * Do not gate on the float-menu sample: when the DragView is outside the glass,
     * painting the origin would wrongly place the icon at the home cell.
     */
    static void collectDragSources(View root, java.util.HashSet<View> out) {
        try (PopupCaptureTrace.Stage ignoredTrace = PopupCaptureTrace.section("collectDragSources")) {
            if (root == null || out == null) return;
            if (isDragView(root) && root.getVisibility() != View.GONE
                    && root.getWidth() > 0 && root.getHeight() > 0) {
                FolderPreviewOverlay.addSkipIcon(out, dragOriginView(root));
                View content = dragContentView(root);
                // Content still on the workspace (not re-parented) — skip to avoid ghost.
                if (content != null && !isDescendant(content, root)) {
                    FolderPreviewOverlay.addSkipIcon(out, content);
                }
            }
            if (root instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) root;
                for (int i = 0; i < g.getChildCount(); i++) {
                    collectDragSources(g.getChildAt(i), out);
                }
            }
        }
    }

    /**
     * ColorOS selection chrome: stroke ring + bottom-right resize handle from
     * {@code ResizeFrameStrokeManager} / {@code IResizeFramePainter}. OEM
     * {@code drawResizeFrame} often paints empty on a software Canvas (OplusPath), so we
     * reconstruct the chrome with standard Path/Paint using the live StrokeManager metrics.
     */
    private static void paintDragOutline(View dragView, View content, Canvas canvas,
            Matrix globalToTarget) {
        if (dragView == null) return;
        if (paintOplusResizeFrame(dragView, canvas, globalToTarget)) return;

        // Last-resort: content chrome (border only).
        java.util.ArrayList<View> hidden = new java.util.ArrayList<>();
        java.util.ArrayList<Float> alphas = new java.util.ArrayList<>();
        Drawable icon = null;
        int iconAlpha = 255;
        CharSequence oldText = null;
        TextView label = null;
        try {
            if (content != null && DesktopRenderUtils.isFolderIcon(content)) {
                suppressFolderFillForStroke(content, hidden, alphas);
                DesktopChromeOverlay.paintDesktopChrome(content, canvas, globalToTarget);
                return;
            }
            if (content != null) {
                icon = DesktopItemRenderer.iconDrawableOf(content);
                if (icon != null) {
                    iconAlpha = icon.getAlpha();
                    icon.setAlpha(0);
                }
                if (content instanceof TextView) {
                    label = (TextView) content;
                    oldText = label.getText();
                    try { label.setText(""); } catch (Throwable ignored) { }
                }
                if (content.getWidth() > 0 && content.getHeight() > 0
                        && (isDescendant(content, dragView) || content.getParent() == dragView)) {
                    DesktopChromeOverlay.paintDesktopChrome(content, canvas, globalToTarget);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (icon != null) {
                try { icon.setAlpha(iconAlpha); } catch (Throwable ignored) { }
            }
            if (label != null && oldText != null) {
                try { label.setText(oldText); } catch (Throwable ignored) { }
            }
            for (int i = 0; i < hidden.size(); i++) {
                try {
                    hidden.get(i).setAlpha(alphas.get(i));
                } catch (Throwable ignored) { }
            }
        }
    }

    /**
     * Paint selection stroke + BR resize handle using StrokeManager widths/alpha and
     * {@code getResizeFrameBounds()} / {@code getResizeFrameRadius()}.
     */
    private static boolean paintOplusResizeFrame(View dragView, Canvas canvas,
            Matrix globalToTarget) {
        if (dragView == null || canvas == null) return false;
        Object stroke = DesktopRenderUtils.fieldValue(dragView, "mStrokeManager");
        if (stroke == null) stroke = DesktopRenderUtils.invokeNoArgs(dragView, "getStrokeManager");
        if (stroke == null) return false;

        Object boundsObj = DesktopRenderUtils.invokeNoArgs(dragView, "getResizeFrameBounds");
        if (!(boundsObj instanceof Rect)) return false;
        Rect local = new Rect((Rect) boundsObj);
        if (local.isEmpty()) {
            local.set(0, 0, Math.max(1, dragView.getWidth()), Math.max(1, dragView.getHeight()));
        }

        float frameW = numberFloat(DesktopRenderUtils.invokeNoArgs(stroke, "getMFrameStrokeWidth"), 4f);
        float handleW = numberFloat(DesktopRenderUtils.invokeNoArgs(stroke, "getMHandleStrokeWidth"), frameW);
        float strokeAlpha = numberFloat(DesktopRenderUtils.invokeNoArgs(stroke, "getMStrokeAlpha"), 1f);
        float radius = numberFloat(DesktopRenderUtils.invokeNoArgs(dragView, "getResizeFrameRadius"),
                Math.min(local.width(), local.height()) * 0.22f);
        if (frameW < 0.5f) frameW = 4f;
        if (handleW < 0.5f) handleW = frameW;
        strokeAlpha = Math.max(0f, Math.min(1f, strokeAlpha));

        RectF dest = DesktopRenderUtils.mapItemRectToTarget(dragView, local, globalToTarget);
        if (dest == null || dest.width() < 2f || dest.height() < 2f) return false;
        float scale = dest.width() / Math.max(1f, local.width());
        float strokePx = Math.max(1.5f, frameW * scale);
        float handlePx = Math.max(1.5f, handleW * scale);
        float radiusPx = Math.max(2f, radius * scale);

        int frameColor = resizeFrameColor(dragView, true);
        int handleColor = resizeFrameColor(dragView, false);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(strokePx);
        paint.setColor(frameColor);
        paint.setAlpha(Math.round(android.graphics.Color.alpha(frameColor) * strokeAlpha));

        RectF ring = new RectF(dest);
        float inset = strokePx * 0.5f;
        ring.inset(-inset, -inset);
        canvas.drawRoundRect(ring, radiusPx + inset, radiusPx + inset, paint);

        // Bottom-right resize handle (arc), matching IResizeFramePainter.drawResizeHandle.
        float handleRadius = radiusPx * 2f;
        RectF handleOval = new RectF(
                dest.right - handleRadius,
                dest.bottom - handleRadius,
                dest.right,
                dest.bottom);
        handleOval.offset(strokePx * 0.5f, strokePx * 0.5f);
        paint.setStrokeWidth(handlePx);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(handleColor);
        paint.setAlpha(Math.round(255f * strokeAlpha));
        canvas.drawArc(handleOval, 0f, 90f, false, paint);
        return true;
    }

    private static float numberFloat(Object value, float fallback) {
        return value instanceof Number ? ((Number) value).floatValue() : fallback;
    }

    private static int resizeFrameColor(View dragView, boolean frame) {
        try {
            Object launcher = DesktopRenderUtils.fieldValue(dragView, "mActivity");
            if (launcher == null) {
                android.content.Context ctx = dragView.getContext();
                if (ctx instanceof android.content.ContextWrapper) {
                    // Activity may be wrapped; still try invoke getItemFrameColor / getHandleColor
                }
            }
            String method = frame ? "getItemFrameColor" : "getHandleColor";
            for (Class<?> c = dragView.getClass(); c != null; c = c.getSuperclass()) {
                for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                    if (!method.equals(m.getName()) || m.getParameterCount() != 1) continue;
                    try {
                        m.setAccessible(true);
                        Object color = m.invoke(dragView, launcher != null ? launcher
                                : dragView.getContext());
                        if (color instanceof Integer) return (Integer) color;
                    } catch (Throwable ignored) { }
                }
            }
        } catch (Throwable ignored) { }
        return frame ? 0xE6FFFFFF : 0xFFFFFFFF;
    }

    private static void paintFolderDragOutline(View dragView, View folder, Canvas canvas,
            Matrix globalToTarget) {
        java.util.ArrayList<View> hidden = new java.util.ArrayList<>();
        java.util.ArrayList<Float> alphas = new java.util.ArrayList<>();
        try {
            suppressFolderFillForStroke(folder, hidden, alphas);
            if (folder.getWidth() > 0 && folder.getHeight() > 0) {
                DesktopChromeOverlay.paintDesktopChrome(folder, canvas, globalToTarget);
            }
            paintNamedStrokeChildren(dragView, folder, canvas, globalToTarget);
        } catch (Throwable ignored) {
        } finally {
            for (int i = 0; i < hidden.size(); i++) {
                try {
                    hidden.get(i).setAlpha(alphas.get(i));
                } catch (Throwable ignored) { }
            }
        }
    }

    /** Sparse stroke/handle rings must not be rejected by {@link #isMostlyEmpty}. */
    private static boolean bitmapHasInk(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return false;
        Bitmap soft = DesktopItemRenderer.softwareBitmap(bitmap);
        if (soft == null || soft.isRecycled()) return false;
        int w = soft.getWidth();
        int h = soft.getHeight();
        if (w <= 0 || h <= 0) return false;
        int stepX = Math.max(1, w / 24);
        int stepY = Math.max(1, h / 24);
        try {
            for (int y = 0; y < h; y += stepY) {
                for (int x = 0; x < w; x += stepX) {
                    if (((soft.getPixel(x, y) >>> 24) & 0xff) > 8) return true;
                }
            }
            // Also probe corners / BR handle area.
            int[][] probes = {
                    {w / 2, 0}, {w / 2, h - 1}, {0, h / 2}, {w - 1, h / 2},
                    {w - 1, h - 1}, {w - 2, h - 2}, {Math.max(0, w - 8), Math.max(0, h - 8)}
            };
            for (int[] p : probes) {
                if (((soft.getPixel(p[0], p[1]) >>> 24) & 0xff) > 8) return true;
            }
            return false;
        } catch (Throwable ignored) {
            return true;
        } finally {
            if (soft != bitmap && !soft.isRecycled()) {
                try { soft.recycle(); } catch (Throwable ignored) { }
            }
        }
    }

    /** Hide plate / previews / title so only the selection stroke remains in FolderIcon.draw. */
    private static void suppressFolderFillForStroke(View folder,
            java.util.ArrayList<View> hidden, java.util.ArrayList<Float> alphas) {
        if (folder == null) return;
        Object background = DesktopRenderUtils.fieldValue(folder, "mBackground");
        Object bgViewObj = DesktopRenderUtils.fieldValue(background, "mBgView");
        if (bgViewObj instanceof View) {
            hideViewAlpha((View) bgViewObj, hidden, alphas);
        }
        TextView name = FolderCompositeRenderer.folderNameView(folder);
        if (name != null) hideViewAlpha(name, hidden, alphas);
        Object preview = DesktopRenderUtils.fieldValue(folder, "mPreviewFrame");
        if (preview instanceof View) hideViewAlpha((View) preview, hidden, alphas);
        if (folder instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) folder;
            for (int i = 0; i < g.getChildCount(); i++) {
                View child = g.getChildAt(i);
                if (child == null || isLikelyStrokeView(child)) continue;
                hideViewAlpha(child, hidden, alphas);
            }
        }
    }

    private static void paintNamedStrokeChildren(View dragView, View folder, Canvas canvas,
            Matrix globalToTarget) {
        if (!(dragView instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) dragView;
        for (int i = 0; i < g.getChildCount(); i++) {
            View child = g.getChildAt(i);
            if (child == null || child == folder) continue;
            if (!isLikelyStrokeView(child)) continue;
            if (child.getWidth() <= 0 || child.getHeight() <= 0) continue;
            if (child.getVisibility() != View.VISIBLE || child.getAlpha() < 0.01f) continue;
            try {
                DesktopChromeOverlay.paintDesktopChrome(child, canvas, globalToTarget);
            } catch (Throwable ignored) { }
        }
    }

    private static boolean isLikelyStrokeView(View view) {
        if (view == null) return false;
        String name = view.getClass().getSimpleName();
        if (name == null) return false;
        return name.contains("Stroke")
                || name.contains("Outline")
                || name.contains("Border")
                || name.contains("Ring")
                || name.contains("SelectFrame")
                || name.contains("Selection");
    }

    private static void hideViewAlpha(View view, java.util.ArrayList<View> hidden,
            java.util.ArrayList<Float> alphas) {
        if (view == null) return;
        for (int i = 0; i < hidden.size(); i++) {
            if (hidden.get(i) == view) return;
        }
        hidden.add(view);
        alphas.add(view.getAlpha());
        view.setAlpha(0f);
    }

    private static void hideAllChildren(View root, java.util.ArrayList<View> hidden,
            java.util.ArrayList<Float> alphas) {
        if (!(root instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) root;
        for (int i = 0; i < g.getChildCount(); i++) {
            View child = g.getChildAt(i);
            if (child == null) continue;
            hideViewAlpha(child, hidden, alphas);
        }
    }

    private static void hideDescendantsForOutline(View root, View content,
            java.util.ArrayList<View> hidden, java.util.ArrayList<Float> alphas) {
        hideAllChildren(root, hidden, alphas);
    }

    private static boolean clearField(Object target, String name) {
        try {
            java.lang.reflect.Field f = null;
            for (Class<?> c = target.getClass(); c != null && f == null; c = c.getSuperclass()) {
                try { f = c.getDeclaredField(name); } catch (Throwable ignored) { }
            }
            if (f == null) return false;
            f.setAccessible(true);
            if (f.get(target) == null) return false;
            f.set(target, null);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void restoreField(Object target, String name, Object value) {
        try {
            java.lang.reflect.Field f = null;
            for (Class<?> c = target.getClass(); c != null && f == null; c = c.getSuperclass()) {
                try { f = c.getDeclaredField(name); } catch (Throwable ignored) { }
            }
            if (f != null) {
                f.setAccessible(true);
                f.set(target, value);
            }
        } catch (Throwable ignored) { }
    }

    private static boolean paintDragCachedBitmap(View dragView, Canvas canvas,
            Matrix globalToTarget) {
        Bitmap bitmap = null;
        for (String field : new String[]{"mBitmap", "mDragBitmap", "mPreviewBitmap"}) {
            Object v = DesktopRenderUtils.fieldValue(dragView, field);
            if (v instanceof Bitmap) {
                bitmap = DesktopItemRenderer.softwareBitmap((Bitmap) v);
                if (bitmap != null && !bitmap.isRecycled() && !DesktopItemRenderer.isMostlyEmpty(bitmap)) break;
                bitmap = null;
            }
        }
        if (bitmap == null) {
            Object got = DesktopRenderUtils.invokeNoArgs(dragView, "getBitmap");
            if (got instanceof Bitmap) bitmap = DesktopItemRenderer.softwareBitmap((Bitmap) got);
        }
        if (bitmap == null || bitmap.isRecycled() || DesktopItemRenderer.isMostlyEmpty(bitmap)) return false;
        int bw = Math.max(1, bitmap.getWidth());
        int bh = Math.max(1, bitmap.getHeight());
        int pw = Math.max(1, dragView.getWidth());
        int ph = Math.max(1, dragView.getHeight());
        // Center intrinsic bitmap in DragView local space (avoids BR inset from icon bounds).
        int dw = Math.min(bw, pw);
        int dh = Math.min(bh, ph);
        int left = Math.max(0, (pw - dw) / 2);
        int top = Math.max(0, (ph - dh) / 2);
        RectF dest = DesktopRenderUtils.mapItemRectToTarget(dragView, new Rect(left, top, left + dw, top + dh),
                globalToTarget);
        if (dest == null || dest.width() < 1f || dest.height() < 1f) return false;
        DesktopRenderUtils.BITMAP_PAINT.setAlpha(255);
        canvas.drawBitmap(bitmap, null, dest, DesktopRenderUtils.BITMAP_PAINT);
        return true;
    }

    static View dragContentView(View dragView) {
        Object content = DesktopRenderUtils.fieldValue(dragView, "mContent");
        if (content instanceof View) return (View) content;
        content = DesktopRenderUtils.invokeNoArgs(dragView, "getContentView");
        if (content instanceof View) return (View) content;
        return null;
    }

    static View dragOriginView(View dragView) {
        Object origin = DesktopRenderUtils.fieldValue(dragView, "mOriginView");
        if (origin instanceof View) return (View) origin;
        origin = DesktopRenderUtils.fieldValue(dragView, "mOriginalView");
        if (origin instanceof View) return (View) origin;
        origin = DesktopRenderUtils.fieldValue(dragView, "mSourceView");
        if (origin instanceof View) return (View) origin;
        return null;
    }

    static View resolveDragIconSource(View content, View origin) {
        if (content != null && (DesktopRenderUtils.isFolderIcon(content) || DesktopItemRenderer.iconDrawableOf(content) != null)) {
            return content;
        }
        if (origin != null && (DesktopRenderUtils.isFolderIcon(origin) || DesktopItemRenderer.iconDrawableOf(origin) != null)) {
            return origin;
        }
        if (content instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) content;
            for (int i = 0; i < g.getChildCount(); i++) {
                View child = g.getChildAt(i);
                if (child != null && (DesktopRenderUtils.isFolderIcon(child) || DesktopItemRenderer.iconDrawableOf(child) != null)) {
                    return child;
                }
            }
        }
        return content != null ? content : origin;
    }

    private static View dragFolderGlassHost(View content, View sizedSrc, View iconSrc) {
        if (content != null && GlassInstaller.get(content) != null) return content;
        if (sizedSrc != null && GlassInstaller.get(sizedSrc) != null) return sizedSrc;
        if (iconSrc != null && GlassInstaller.get(iconSrc) != null) return iconSrc;
        return null;
    }

    /**
     * HW-rasterize DragView folder content that carries live LiquidGlass. Software
     * {@code View.draw} only yields GlassDrawable's soft fallback (looks empty under float).
     */
    private static boolean paintDragFolderGlassAt(View glassHost, View dragView, Canvas canvas,
            Matrix globalToTarget) {
        if (glassHost == null || dragView == null) return false;
        int w = glassHost.getWidth() > 0 ? glassHost.getWidth() : dragView.getWidth();
        int h = glassHost.getHeight() > 0 ? glassHost.getHeight() : dragView.getHeight();
        if (w <= 0 || h <= 0) return false;
        try {
            if (!GlassInstaller.hasBackdropFrame(glassHost)) {
                GlassInstaller.forceCapture(glassHost);
            }
        } catch (Throwable ignored) { }
        float oldAlpha = glassHost.getAlpha();
        int oldVis = glassHost.getVisibility();
        try {
            if (oldVis != View.VISIBLE) glassHost.setVisibility(View.VISIBLE);
            if (oldAlpha < 0.99f) glassHost.setAlpha(1f);
            Bitmap rendered = GlassHwRasterizer.render(w, h, hwCanvas -> {
                DesktopRenderUtils.DEPTH.set(DesktopRenderUtils.DEPTH.get() + 1);
                try {
                    glassHost.draw(hwCanvas);
                } finally {
                    DesktopRenderUtils.DEPTH.set(Math.max(0, DesktopRenderUtils.DEPTH.get() - 1));
                }
            });
            if (rendered == null || rendered.isRecycled() || DesktopItemRenderer.isMostlyEmpty(rendered)) {
                if (rendered != null && !rendered.isRecycled()) rendered.recycle();
                // Backdrop may be missing: still blit the GlassDrawable plate alone.
                GlassDrawable glass = GlassInstaller.get(glassHost);
                if (glass == null) return false;
                rendered = GlassHwRasterizer.render(w, h, hwCanvas -> {
                    Rect old = new Rect(glass.getBounds());
                    if (old.isEmpty()) glass.setBounds(0, 0, w, h);
                    try {
                        glass.draw(hwCanvas);
                    } finally {
                        if (old.isEmpty()) glass.setBounds(old);
                    }
                });
            }
            if (rendered == null || rendered.isRecycled() || DesktopItemRenderer.isMostlyEmpty(rendered)) {
                if (rendered != null && !rendered.isRecycled()) rendered.recycle();
                return false;
            }
            try {
                RectF dest = mapDragContentDest(dragView, glassHost,
                        new Rect(0, 0, w, h), globalToTarget);
                if (dest == null || dest.width() < 1f || dest.height() < 1f) return false;
                DesktopRenderUtils.BITMAP_PAINT.setAlpha(255);
                canvas.drawBitmap(rendered, null, dest, DesktopRenderUtils.BITMAP_PAINT);
                return true;
            } finally {
                if (!rendered.isRecycled()) rendered.recycle();
            }
        } catch (Throwable ignored) {
            return false;
        } finally {
            try { glassHost.setAlpha(oldAlpha); } catch (Throwable ignored) { }
            try { glassHost.setVisibility(oldVis); } catch (Throwable ignored) { }
        }
    }

    private static boolean paintAppIconAt(View iconSrc, View positionHost, Canvas canvas,
            Matrix globalToTarget) {
        Drawable drawable = DesktopItemRenderer.iconDrawableOf(iconSrc);
        Rect localBounds = DesktopItemRenderer.iconLocalBounds(iconSrc, drawable);
        if (localBounds == null || localBounds.isEmpty()) {
            int iw = Math.max(1, iconSrc.getWidth() > 0 ? iconSrc.getWidth() : positionHost.getWidth());
            int ih = Math.max(1, iconSrc.getHeight() > 0 ? iconSrc.getHeight() : positionHost.getHeight());
            localBounds = new Rect(0, 0, iw, ih);
        }
        Bitmap bitmap = DesktopItemRenderer.bitmapForCanvas(DesktopItemRenderer.fastBitmapOf(drawable), canvas);
        if ((bitmap == null || bitmap.isRecycled() || DesktopItemRenderer.isMostlyEmpty(bitmap)) && drawable != null) {
            bitmap = DesktopItemRenderer.rasterizeDrawable(drawable,
                    Math.max(1, localBounds.width()), Math.max(1, localBounds.height()));
        }
        if (bitmap == null || bitmap.isRecycled() || DesktopItemRenderer.isMostlyEmpty(bitmap)) return false;

        // Always map through DragView local space. Using iconSrc global + icon inset, or
        // DragView + icon inset from a detached origin, both push the glyph down-right.
        RectF dest = mapDragContentDest(positionHost, iconSrc, localBounds, globalToTarget);
        if (dest == null || dest.width() < 1f || dest.height() < 1f) return false;
        DesktopRenderUtils.BITMAP_PAINT.setAlpha(255);
        canvas.drawBitmap(bitmap, null, dest, DesktopRenderUtils.BITMAP_PAINT);
        return true;
    }

    /**
     * Map a content rect into the sample using {@code dragView}'s matrix only.
     * Local rect is content.left/top + bounds when content is a child; otherwise the
     * intrinsic size is centered in the DragView (no icon-padding false offset).
     */
    static RectF mapDragContentDest(View dragView, View content, Rect localInContent,
            Matrix globalToTarget) {
        if (dragView == null || localInContent == null || localInContent.isEmpty()) return null;
        int iw = Math.max(1, localInContent.width());
        int ih = Math.max(1, localInContent.height());
        Rect localInDrag;
        if (content != null && content != dragView
                && (isDescendant(content, dragView) || content.getParent() == dragView)) {
            int l = content.getLeft() + localInContent.left;
            int t = content.getTop() + localInContent.top;
            localInDrag = new Rect(l, t, l + iw, t + ih);
        } else {
            int pw = Math.max(1, dragView.getWidth());
            int ph = Math.max(1, dragView.getHeight());
            int l = Math.max(0, (pw - iw) / 2);
            int t = Math.max(0, (ph - ih) / 2);
            localInDrag = new Rect(l, t, l + iw, t + ih);
        }
        return DesktopRenderUtils.mapItemRectToTarget(dragView, localInDrag, globalToTarget);
    }

    private static boolean paintViewRasterAt(View drawSrc, View positionHost, Canvas canvas,
            Matrix globalToTarget) {
        if (drawSrc == null || positionHost == null) return false;
        int w = drawSrc.getWidth() > 0 ? drawSrc.getWidth() : positionHost.getWidth();
        int h = drawSrc.getHeight() > 0 ? drawSrc.getHeight() : positionHost.getHeight();
        if (w <= 0 || h <= 0) return false;
        float oldAlpha = drawSrc.getAlpha();
        int oldVis = drawSrc.getVisibility();
        try {
            if (oldVis != View.VISIBLE) drawSrc.setVisibility(View.VISIBLE);
            if (oldAlpha < 0.99f) drawSrc.setAlpha(1f);
            Bitmap rendered = null;
            // LiquidGlass hosts: software draw only yields SoftGlass fallback — prefer HW.
            if (GlassInstaller.get(drawSrc) != null) {
                try {
                    if (!GlassInstaller.hasBackdropFrame(drawSrc)) {
                        GlassInstaller.forceCapture(drawSrc);
                    }
                } catch (Throwable ignored) { }
                rendered = GlassHwRasterizer.render(w, h, drawSrc::draw);
            }
            if (rendered == null || rendered.isRecycled() || DesktopItemRenderer.isMostlyEmpty(rendered)) {
                if (rendered != null && !rendered.isRecycled()) rendered.recycle();
                rendered = DesktopItemRenderer.rasterizeViewSoftware(drawSrc, w, h);
            }
            if (rendered == null || rendered.isRecycled() || DesktopItemRenderer.isMostlyEmpty(rendered)) {
                if (rendered != null && !rendered.isRecycled()) rendered.recycle();
                rendered = GlassHwRasterizer.render(w, h, drawSrc::draw);
            }
            if (rendered == null || rendered.isRecycled()) return false;
            try {
                RectF dest = mapDragContentDest(positionHost, drawSrc,
                        new Rect(0, 0, w, h), globalToTarget);
                if (dest == null || dest.width() < 1f || dest.height() < 1f) return false;
                DesktopRenderUtils.BITMAP_PAINT.setAlpha(255);
                canvas.drawBitmap(rendered, null, dest, DesktopRenderUtils.BITMAP_PAINT);
                return !DesktopItemRenderer.isMostlyEmpty(rendered);
            } finally {
                if (!rendered.isRecycled()) rendered.recycle();
            }
        } catch (Throwable ignored) {
            return false;
        } finally {
            try { drawSrc.setAlpha(oldAlpha); } catch (Throwable ignored) { }
            try { drawSrc.setVisibility(oldVis); } catch (Throwable ignored) { }
        }
    }

    /** Skip the dragged folder / glass host's own FolderIcon. */
    static boolean isDragSourceOrSelf(View glassHost, View item) {
        if (item == glassHost) return true;
        if (isDescendant(glassHost, item)) return true;
        View dragView = findDragView(glassHost);
        if (dragView == null) return false;
        if (isDescendant(item, dragView)) return true;
        Object content = DesktopRenderUtils.invokeNoArgs(dragView, "getContentView");
        if (content == item) return true;
        if (content instanceof View) {
            View contentView = (View) content;
            if (isDescendant(item, contentView) || isDescendant(contentView, item)) return true;
        }
        return false;
    }

    static boolean isDescendant(View child, View ancestor) {
        for (View current = child; current != null; ) {
            if (current == ancestor) return true;
            ViewParent parent = current.getParent();
            if (!(parent instanceof View)) break;
            current = (View) parent;
        }
        return false;
    }

    /** Match DragView and OEM subclasses (OplusDragView, …). */
    static View findDragView(View start) {
        for (View current = start; current != null; ) {
            String name = current.getClass().getSimpleName();
            if (name != null && name.endsWith("DragView")) return current;
            ViewParent parent = current.getParent();
            if (!(parent instanceof View)) break;
            current = (View) parent;
        }
        return null;
    }

    private static boolean hasActiveDragView(View seed) {
        View root = seed != null
                ? (seed.getRootView() != null ? seed.getRootView() : seed)
                : null;
        return findDragViewInTree(root) != null;
    }

    static View findDragViewInTree(View root) {
        if (root == null) return null;
        if (isDragView(root) && root.getVisibility() != View.GONE
                && root.getWidth() > 0 && root.getHeight() > 0) {
            return root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = findDragViewInTree(g.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
}
