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

/** 已有桌面弹窗的背景叠加；处理坐标转换、透明度和采样递归。 */
final class DesktopPopupOverlay {
    private DesktopPopupOverlay() {}


    /** True when a desktop options-menu LiquidGlass host is live (force ashmem rebuild). */
    static boolean hasDesktopPopupGlass(View seed) {
        View root = seed != null
                ? (seed.getRootView() != null ? seed.getRootView() : seed)
                : null;
        return findDesktopPopupGlass(root) != null;
    }

    private static View findDesktopPopupGlass(View view) {
        if (view == null || view.getVisibility() != View.VISIBLE) return null;
        if ("colg_desktop_popup".equals(view.getTag())
                && view.getWidth() > 0 && view.getHeight() > 0
                && GlassInstaller.get(view) != null) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = findDesktopPopupGlass(g.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * Float-menu ashmem: HW-rasterize {@code colg_desktop_popup} LiquidGlass plates.
     * Soft {@code view.draw} cannot run AGSL ({@link GlassDrawable} falls back to a faint
     * fill), so options-menu glass vanished under float glass.
     */
    static void paintDesktopPopupGlassIntoScreenRect(Rect screenRect, Canvas canvas) {
        paintDesktopPopupGlassIntoScreenRect(screenRect, canvas, null);
    }

    static void paintDesktopPopupGlassIntoScreenRect(Rect screenRect, Canvas canvas, View seedRoot) {
        if (screenRect == null || screenRect.isEmpty() || canvas == null) return;
        View root = seedRoot;
        if (root == null) {
            View seed = DesktopIconOverlay.resolveWorkspaceSeed();
            root = seed != null && seed.getRootView() != null ? seed.getRootView() : seed;
        } else if (root.getRootView() != null) {
            root = root.getRootView();
        }
        if (root == null) return;
        collectAndPaintDesktopPopupGlass(root, screenRect, canvas);
    }

    private static void collectAndPaintDesktopPopupGlass(View view, Rect crop, Canvas canvas) {
        if (view == null || view.getVisibility() != View.VISIBLE) return;
        if ("colg_desktop_popup".equals(view.getTag())
                && view.getWidth() > 0 && view.getHeight() > 0
                && GlassInstaller.get(view) != null) {
            paintOneDesktopPopupGlass(view, crop, canvas);
            return; // children painted inside paintOneDesktopPopupGlass
        }
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectAndPaintDesktopPopupGlass(g.getChildAt(i), crop, canvas);
            }
        }
    }

    /**
     * Paint other {@code colg_desktop_popup} plates under {@code skipHost} (e.g. primary
     * options menu under More-functions submenu glass). Submenu lives in a PopupWindow, so
     * walking the launcher root finds the primary plate and never the self host.
     */
    static boolean paintOtherDesktopPopupGlass(View view, View skipHost, Canvas canvas,
            Matrix globalToTarget, RectF region) {
        if (view == null || view.getVisibility() != View.VISIBLE || canvas == null) return false;
        if ("colg_desktop_popup".equals(view.getTag())
                && view != skipHost
                && !DragViewOverlay.isDescendant(view, skipHost)
                && !DragViewOverlay.isDescendant(skipHost, view)
                && view.getWidth() > 0 && view.getHeight() > 0
                && GlassInstaller.get(view) != null
                && DesktopRenderUtils.intersectsRegion(region, view)) {
            paintOneDesktopPopupGlassIntoTarget(view, canvas, globalToTarget);
            return true;
        }
        boolean painted = false;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (paintOtherDesktopPopupGlass(group.getChildAt(i), skipHost, canvas,
                        globalToTarget, region)) {
                    painted = true;
                }
            }
        }
        return painted;
    }

    /**
     * HW-rasterize a primary options-menu LiquidGlass plate into another popup's
     * target-local backdrop sample (submenu refraction).
     */
    private static void paintOneDesktopPopupGlassIntoTarget(View host, Canvas canvas,
            Matrix globalToTarget) {
        int w = host.getWidth();
        int h = host.getHeight();
        if (w <= 0 || h <= 0 || canvas == null || globalToTarget == null) return;
        try {
            if (!GlassInstaller.hasBackdropFrame(host)) {
                GlassInstaller.forceCapture(host);
            }
        } catch (Throwable ignored) { }

        GlassDrawable glass = GlassInstaller.get(host);
        if (glass == null) return;

        Matrix hostToGlobal = new Matrix();
        host.transformMatrixToGlobal(hostToGlobal);
        Matrix hostToTarget = new Matrix();
        hostToTarget.setConcat(globalToTarget, hostToGlobal);
        RectF dest = new RectF(0f, 0f, w, h);
        hostToTarget.mapRect(dest);
        float alpha = effectiveDrawAlpha(host);
        if (alpha <= 0.01f) return;

        Bitmap plate = null;
        try {
            final int pw = w;
            final int ph = h;
            plate = GlassHwRasterizer.render(pw, ph, hwCanvas -> {
                DesktopRenderUtils.DEPTH.set(DesktopRenderUtils.DEPTH.get() + 1);
                try {
                    Rect old = new Rect(glass.getBounds());
                    glass.setBounds(0, 0, pw, ph);
                    try {
                        glass.draw(hwCanvas);
                    } finally {
                        glass.setBounds(old);
                    }
                } finally {
                    DesktopRenderUtils.DEPTH.set(Math.max(0, DesktopRenderUtils.DEPTH.get() - 1));
                }
            });
            DesktopRenderUtils.BITMAP_PAINT.setAlpha(Math.round(255f * Math.min(1f, alpha)));
            if (plate != null && !plate.isRecycled() && !DesktopItemRenderer.isMostlyEmpty(plate)) {
                canvas.drawBitmap(plate, null, dest, DesktopRenderUtils.BITMAP_PAINT);
            } else {
                Bitmap snap = BackdropCapture.snapshotOf(host);
                if (snap != null && !snap.isRecycled()) {
                    int save = canvas.save();
                    try {
                        canvas.clipRect(dest);
                        canvas.drawBitmap(snap, null, dest, DesktopRenderUtils.BITMAP_PAINT);
                    } finally {
                        canvas.restoreToCount(save);
                    }
                }
            }
            DesktopRenderUtils.BITMAP_PAINT.setAlpha(255);
        } finally {
            if (plate != null && !plate.isRecycled()) plate.recycle();
        }

        // Draw shortcut rows only — never null the live GlassDrawable. Submenu open-anim
        // pumps this every frame; setBackground(null) flickered / hid on-screen options.
        try {
            int save = canvas.save();
            try {
                canvas.concat(hostToTarget);
                if (alpha < 0.99f) {
                    canvas.saveLayerAlpha(0f, 0f, w, h, Math.round(255f * Math.min(1f, alpha)));
                }
                if (host instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) host;
                    for (int i = 0; i < group.getChildCount(); i++) {
                        View child = group.getChildAt(i);
                        if (child == null || child.getVisibility() != View.VISIBLE) continue;
                        int csave = canvas.save();
                        try {
                            canvas.translate(child.getLeft(), child.getTop());
                            child.draw(canvas);
                        } catch (Throwable ignored) {
                        } finally {
                            canvas.restoreToCount(csave);
                        }
                    }
                }
            } finally {
                canvas.restoreToCount(save);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Multiply view + ancestor alphas (OEM dims primary menu to ~0.6 under submenu). */
    private static float effectiveDrawAlpha(View view) {
        float alpha = 1f;
        for (View current = view; current != null; ) {
            alpha *= current.getAlpha();
            if (alpha <= 0.01f) return 0f;
            ViewParent parent = current.getParent();
            if (!(parent instanceof View)) break;
            current = (View) parent;
        }
        return Math.min(1f, alpha);
    }

    /**
     * Same strategy as selected-folder glass under float: HW-rasterize {@link GlassDrawable}
     * alone (AGSL), then software-draw children (labels/icons) on top. Full {@code host.draw}
     * often fails to land AGSL into ashmem while SoftGlass fallback looks like “no glass”.
     */
    private static void paintOneDesktopPopupGlass(View host, Rect crop, Canvas canvas) {
        int[] loc = new int[2];
        host.getLocationOnScreen(loc);
        int w = host.getWidth();
        int h = host.getHeight();
        if (w <= 0 || h <= 0) return;
        Rect bounds = new Rect(loc[0], loc[1], loc[0] + w, loc[1] + h);
        if (!Rect.intersects(bounds, crop)) return;
        try {
            if (!GlassInstaller.hasBackdropFrame(host)) {
                GlassInstaller.forceCapture(host);
            }
        } catch (Throwable ignored) { }

        GlassDrawable glass = GlassInstaller.get(host);
        if (glass == null) return;
        RectF dest = new RectF(loc[0] - crop.left, loc[1] - crop.top,
                loc[0] - crop.left + w, loc[1] - crop.top + h);

        Bitmap plate = null;
        try {
            final int pw = w;
            final int ph = h;
            plate = GlassHwRasterizer.render(pw, ph, hwCanvas -> {
                DesktopRenderUtils.DEPTH.set(DesktopRenderUtils.DEPTH.get() + 1);
                try {
                    Rect old = new Rect(glass.getBounds());
                    glass.setBounds(0, 0, pw, ph);
                    try {
                        glass.draw(hwCanvas);
                    } finally {
                        glass.setBounds(old);
                    }
                } finally {
                    DesktopRenderUtils.DEPTH.set(Math.max(0, DesktopRenderUtils.DEPTH.get() - 1));
                }
            });
            if (plate != null && !plate.isRecycled() && !DesktopItemRenderer.isMostlyEmpty(plate)) {
                DesktopRenderUtils.BITMAP_PAINT.setAlpha(255);
                canvas.drawBitmap(plate, null, dest, DesktopRenderUtils.BITMAP_PAINT);
            } else {
                // Backdrop lag: still paint a readable plate from the last snapshot if any.
                Bitmap snap = BackdropCapture.snapshotOf(host);
                if (snap != null && !snap.isRecycled()) {
                    int save = canvas.save();
                    try {
                        canvas.clipRect(dest);
                        canvas.drawBitmap(snap, null, dest, DesktopRenderUtils.BITMAP_PAINT);
                    } finally {
                        canvas.restoreToCount(save);
                    }
                }
            }
        } finally {
            if (plate != null && !plate.isRecycled()) plate.recycle();
        }

        // Menu labels / shortcut rows — software draw with glass background suppressed so
        // SoftGlass fallback cannot cover the HW plate.
        Drawable previousBg = host.getBackground();
        try {
            host.setBackground(null);
            int save = canvas.save();
            try {
                canvas.translate(dest.left, dest.top);
                float sx = dest.width() / Math.max(1f, w);
                float sy = dest.height() / Math.max(1f, h);
                if (Math.abs(sx - 1f) > 0.01f || Math.abs(sy - 1f) > 0.01f) {
                    canvas.scale(sx, sy);
                }
                if (host instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) host;
                    for (int i = 0; i < group.getChildCount(); i++) {
                        View child = group.getChildAt(i);
                        if (child == null || child.getVisibility() != View.VISIBLE) continue;
                        int csave = canvas.save();
                        try {
                            canvas.translate(child.getLeft(), child.getTop());
                            child.draw(canvas);
                        } catch (Throwable ignored) {
                        } finally {
                            canvas.restoreToCount(csave);
                        }
                    }
                }
            } finally {
                canvas.restoreToCount(save);
            }
        } catch (Throwable ignored) {
        } finally {
            try { host.setBackground(previousBg); } catch (Throwable ignored) { }
        }
    }

    /** True when this view is or contains a {@code colg_desktop_popup} LiquidGlass host. */
    static boolean isOrContainsDesktopPopupGlass(View view) {
        if (view == null) return false;
        if ("colg_desktop_popup".equals(view.getTag()) && GlassInstaller.get(view) != null) {
            return true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (isOrContainsDesktopPopupGlass(g.getChildAt(i))) return true;
            }
        }
        return false;
    }
}
