package net.z841973620.colorosliquidglass.glass;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.Layout;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;
import net.z841973620.colorosliquidglass.hook.ColorOsVersion;
import java.util.Map;
import java.util.WeakHashMap;

/** 应用图标、小组件、文字及硬件/软件位图的采样与缓存。 */
final class DesktopItemRenderer {
    private DesktopItemRenderer() {}

    static final Map<View, WidgetSnap> WIDGET_SNAPS = new WeakHashMap<>();
    /** HW snap of BubbleTextView / ShortcutContainer (icon + 角标 + label). */
    static final Map<View, AppSnap> APP_SNAPS = new WeakHashMap<>();
    private static final DesktopIconBitmapCache ICON_BITMAPS = new DesktopIconBitmapCache();

    static final class WidgetSnap {
        Bitmap bitmap;
        int width;
        int height;
    }

    static final class AppSnap {
        Bitmap bitmap;
        int width;
        int height;
    }

    static void paintAppIcon(View item, Canvas canvas, Matrix globalToTarget) {
        Drawable drawable = iconDrawableOf(item);
        Rect localBounds = iconLocalBounds(item, drawable);
        if (localBounds == null || localBounds.isEmpty()) return;
        RectF dest = DesktopRenderUtils.mapItemRectToTarget(item, localBounds, globalToTarget);
        if (dest == null || dest.width() < 1f || dest.height() < 1f) return;

        Bitmap bitmap = bitmapForCanvas(fastBitmapOf(drawable), canvas);
        if (bitmap == null) {
            bitmap = rasterizeDrawable(drawable, localBounds.width(), localBounds.height());
        }
        if (bitmap == null || bitmap.isRecycled()) return;
        DesktopRenderUtils.BITMAP_PAINT.setAlpha(255);
        canvas.drawBitmap(bitmap, null, dest, DesktopRenderUtils.BITMAP_PAINT);
    }

    /**
     * Options-menu sample: rasterize the shortcut host once (cached) so ColorOS 角标
     * drawn in {@code onDraw} / ViewOverlay appear under menu glass.
     * Cos16+: {@link GlassHwRasterizer}. Cos14: software {@link #rasterizeViewSoftware}
     * (HW {@code View.draw} on BubbleTextView/ShortcutContainer causes 花屏).
     * Falls back to {@link #paintAppIcon}+{@link #paintAppLabel} when raster fails.
     */
    static boolean paintAppMenuComposite(View cellChild, View iconHost,
            Canvas canvas, Matrix globalToTarget) {
        try (PopupCaptureTrace.Stage ignoredTrace = PopupCaptureTrace.section("app_composite")) {
            // Prefer CellLayout child (ShortcutContainer) so sibling/overlay badges are included.
            View target = cellChild != null && cellChild.getWidth() > 0 && cellChild.getHeight() > 0
                    ? cellChild : iconHost;
            if (target == null) return false;
            if (DesktopRenderUtils.DEPTH.get() > 0) return false;
            int w = target.getWidth();
            int h = target.getHeight();
            if (w <= 0 || h <= 0) return false;

            boolean cos14Software = ColorOsVersion.detect() == ColorOsVersion.Flavor.LEGACY_13_14;
            AppSnap cached = APP_SNAPS.get(target);
            Bitmap rendered = null;
            if (cached != null && cached.bitmap != null && !cached.bitmap.isRecycled()
                    && cached.width == w && cached.height == h) {
                rendered = cached.bitmap;
            } else if (cos14Software) {
                // Cos14: HW raster of BubbleTextView/ShortcutContainer glitches under menu glass.
                rendered = rasterizeViewSoftware(target, w, h);
                if (rendered == null || rendered.isRecycled()) {
                    if (iconHost != null && iconHost != target
                            && iconHost.getWidth() > 0 && iconHost.getHeight() > 0) {
                        return paintAppMenuComposite(iconHost, iconHost, canvas, globalToTarget);
                    }
                    return false;
                }
                AppSnap snap = new AppSnap();
                snap.bitmap = rendered;
                snap.width = w;
                snap.height = h;
                AppSnap previous = APP_SNAPS.put(target, snap);
                if (previous != null && previous.bitmap != null && previous.bitmap != rendered
                        && !previous.bitmap.isRecycled()) {
                    previous.bitmap.recycle();
                }
            } else {
                rendered = GlassHwRasterizer.render(w, h, hwCanvas -> {
                    DesktopRenderUtils.DEPTH.set(DesktopRenderUtils.DEPTH.get() + 1);
                    try {
                        target.draw(hwCanvas);
                    } finally {
                        DesktopRenderUtils.DEPTH.set(Math.max(0, DesktopRenderUtils.DEPTH.get() - 1));
                    }
                });
                if (rendered == null || rendered.isRecycled()) {
                    // Retry on BubbleTextView alone if the wrapper HW snap failed.
                    if (iconHost != null && iconHost != target
                            && iconHost.getWidth() > 0 && iconHost.getHeight() > 0) {
                        return paintAppMenuComposite(iconHost, iconHost, canvas, globalToTarget);
                    }
                    // Folder BubbleTextView HW snap can fail; software still includes 角标
                    // (same Cos14 path) — never fall through to FastBitmap-only.
                    rendered = rasterizeViewSoftware(target, w, h);
                    if (rendered == null || rendered.isRecycled()) return false;
                }
                AppSnap snap = new AppSnap();
                snap.bitmap = rendered;
                snap.width = w;
                snap.height = h;
                AppSnap previous = APP_SNAPS.put(target, snap);
                if (previous != null && previous.bitmap != null && previous.bitmap != rendered
                        && !previous.bitmap.isRecycled()) {
                    previous.bitmap.recycle();
                }
            }
            RectF dest = DesktopRenderUtils.mapItemRectToTarget(target, new Rect(0, 0, w, h), globalToTarget);
            if (dest == null || dest.width() < 1f || dest.height() < 1f) return false;
            DesktopRenderUtils.BITMAP_PAINT.setAlpha(255);
            canvas.drawBitmap(rendered, null, dest, DesktopRenderUtils.BITMAP_PAINT);
            return true;
        }
    }

    /**
     * Widgets have no FastBitmap. Offscreen HW rasterize (same idea as folder plates);
     * never hide DragView / PixelCopy the window.
     */
    static void paintWidget(View host, Canvas canvas, Matrix globalToTarget) {
        try (PopupCaptureTrace.Stage ignoredTrace = PopupCaptureTrace.section("widget_composite")) {
            if (host == null) return;
            View content = widgetContentView(host);
            int w = content.getWidth();
            int h = content.getHeight();
            if (w <= 0 || h <= 0) {
                content = host;
                w = host.getWidth();
                h = host.getHeight();
            }
            if (w <= 0 || h <= 0) return;
            Bitmap snap = widgetCompositeBitmap(host, content, w, h);
            if (snap == null || snap.isRecycled()) return;
            View mapped = (snap.getWidth() == host.getWidth() && snap.getHeight() == host.getHeight())
                    ? host : content;
            RectF dest = DesktopRenderUtils.mapItemRectToTarget(mapped,
                    new Rect(0, 0, snap.getWidth(), snap.getHeight()), globalToTarget);
            if (dest == null || dest.width() < 1f || dest.height() < 1f) return;
            DesktopRenderUtils.BITMAP_PAINT.setAlpha(255);
            canvas.drawBitmap(snap, null, dest, DesktopRenderUtils.BITMAP_PAINT);
        }
    }

    private static Bitmap widgetCompositeBitmap(View host, View content, int width, int height) {
        WidgetSnap cached = WIDGET_SNAPS.get(host);
        if (cached != null && cached.bitmap != null && !cached.bitmap.isRecycled()
                && cached.width == width && cached.height == height) {
            return cached.bitmap;
        }
        boolean cos14 = ColorOsVersion.detect() == ColorOsVersion.Flavor.LEGACY_13_14;
        final View drawTarget = content;
        Bitmap rendered;
        if (cos14) {
            // Cos14: HW raster of widget views flowers under menu glass (same as folders /
            // app shortcuts) — always software.
            rendered = rasterizeViewSoftware(drawTarget, width, height);
        } else {
            rendered = GlassHwRasterizer.render(width, height, drawTarget::draw);
            if (rendered == null || rendered.isRecycled() || isMostlyEmpty(rendered)) {
                if (rendered != null && !rendered.isRecycled()) rendered.recycle();
                rendered = rasterizeViewSoftware(drawTarget, width, height);
            }
        }
        if ((rendered == null || rendered.isRecycled() || isMostlyEmpty(rendered))
                && drawTarget != host) {
            if (rendered != null && !rendered.isRecycled()) rendered.recycle();
            if (cos14) {
                rendered = rasterizeViewSoftware(host, host.getWidth(), host.getHeight());
                if (rendered != null && !isMostlyEmpty(rendered)) {
                    width = host.getWidth();
                    height = host.getHeight();
                } else if (rendered != null) {
                    rendered.recycle();
                    rendered = null;
                }
            } else {
                rendered = GlassHwRasterizer.render(host.getWidth(), host.getHeight(), host::draw);
                if (rendered != null && !isMostlyEmpty(rendered)) {
                    width = host.getWidth();
                    height = host.getHeight();
                } else if (rendered != null) {
                    rendered.recycle();
                    rendered = null;
                }
            }
        }
        if (rendered == null || rendered.isRecycled() || isMostlyEmpty(rendered)) {
            if (rendered != null && !rendered.isRecycled()) rendered.recycle();
            return null;
        }
        WidgetSnap snap = new WidgetSnap();
        snap.bitmap = rendered;
        snap.width = width;
        snap.height = height;
        WidgetSnap previous = WIDGET_SNAPS.put(host, snap);
        if (previous != null && previous.bitmap != null && previous.bitmap != rendered
                && !previous.bitmap.isRecycled()) {
            previous.bitmap.recycle();
        }
        return rendered;
    }

    static boolean isMostlyEmpty(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return true;
        Bitmap soft = softwareBitmap(bitmap);
        if (soft == null || soft.isRecycled()) return true;
        int w = soft.getWidth();
        int h = soft.getHeight();
        if (w <= 0 || h <= 0) return true;
        int stepX = Math.max(1, w / 6);
        int stepY = Math.max(1, h / 6);
        int opaque = 0;
        try {
            for (int y = stepY / 2; y < h; y += stepY) {
                for (int x = stepX / 2; x < w; x += stepX) {
                    if (((soft.getPixel(x, y) >>> 24) & 0xff) > 16) opaque++;
                }
            }
        } catch (Throwable ignored) {
            return true;
        } finally {
            if (soft != bitmap && !soft.isRecycled()) {
                try { soft.recycle(); } catch (Throwable ignored) { }
            }
        }
        return opaque < 2;
    }

    static Bitmap rasterizeViewSoftware(View view, int width, int height) {
        try (PopupCaptureTrace.Stage ignoredTrace = PopupCaptureTrace.section("software_draw")) {
            if (view == null || width <= 0 || height <= 0) return null;
            Bitmap bitmap;
            try (PopupCaptureTrace.Stage allocationTrace = PopupCaptureTrace.section("bitmap_alloc")) {
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            }
            try {
                view.draw(new Canvas(bitmap));
            } catch (Throwable ignored) {
                bitmap.recycle();
                return null;
            }
            return bitmap;
        }
    }

    private static View widgetContentView(View host) {
        Object view = DesktopRenderUtils.invokeNoArgs(host, "getWidgetView");
        if (view instanceof View && ((View) view).getWidth() > 0
                && ((View) view).getHeight() > 0) {
            return (View) view;
        }
        view = DesktopRenderUtils.invokeNoArgs(host, "getWidgetChild");
        if (view instanceof View && ((View) view).getWidth() > 0
                && ((View) view).getHeight() > 0) {
            return (View) view;
        }
        return host;
    }

    static void paintAppLabel(View item, Canvas canvas, Matrix globalToTarget) {
        if (!(item instanceof TextView)) return;
        TextView tv = (TextView) item;
        CharSequence text = tv.getText();
        if (TextUtils.isEmpty(text) || tv.getVisibility() != View.VISIBLE) return;
        if (tv.getAlpha() <= 0.01f) return;

        Matrix itemToGlobal = new Matrix();
        item.transformMatrixToGlobal(itemToGlobal);
        Matrix itemToHost = new Matrix();
        itemToHost.setConcat(globalToTarget, itemToGlobal);
        int save = canvas.save();
        canvas.concat(itemToHost);
        try {
            Layout layout = tv.getLayout();
            if (layout != null) {
                canvas.translate(tv.getCompoundPaddingLeft(), tv.getExtendedPaddingTop());
                int previous = tv.getPaint().getAlpha();
                tv.getPaint().setAlpha(Math.round(255f * Math.min(1f, tv.getAlpha())));
                layout.draw(canvas);
                tv.getPaint().setAlpha(previous);
            } else {
                DesktopRenderUtils.TITLE_PAINT.set(tv.getPaint());
                DesktopRenderUtils.TITLE_PAINT.setColor(tv.getCurrentTextColor());
                DesktopRenderUtils.TITLE_PAINT.setTextAlign(Paint.Align.CENTER);
                float x = tv.getWidth() * 0.5f;
                float y = tv.getHeight() - tv.getPaddingBottom() - DesktopRenderUtils.TITLE_PAINT.descent();
                canvas.drawText(text.toString(), x, y, DesktopRenderUtils.TITLE_PAINT);
            }
        } finally {
            canvas.restoreToCount(save);
        }
    }

    /** Drawing on a hardware canvas needs no CPU readback, including preview fallback paths. */
    static Bitmap bitmapForCanvas(Bitmap bitmap, Canvas canvas) {
        if (canvas.isHardwareAccelerated()) {
            PopupCaptureTrace.outcome("icon_bitmap", "hardware_direct");
            return bitmap == null || bitmap.isRecycled() ? null : bitmap;
        }
        try (PopupCaptureTrace.Stage ignoredTrace = PopupCaptureTrace.section("bitmap_copy")) {
            return ICON_BITMAPS.softwareIcon(bitmap);
        }
    }

    /** Fresh, caller-owned conversion for pixel inspection and live drag snapshots. */
    static Bitmap softwareBitmap(Bitmap bitmap) {
        try (PopupCaptureTrace.Stage ignoredTrace = PopupCaptureTrace.section("bitmap_copy")) {
            if (bitmap == null || bitmap.isRecycled()) return null;
            if (Build.VERSION.SDK_INT >= 26 && bitmap.getConfig() == Bitmap.Config.HARDWARE) {
                try {
                    Bitmap copy = bitmap.copy(Bitmap.Config.ARGB_8888, false);
                    return copy == null || copy.isRecycled() ? null : copy;
                } catch (Throwable ignored) {
                    return null;
                }
            }
            return bitmap;
        }
    }

    static Bitmap rasterizeDrawable(Drawable drawable, int width, int height) {
        try (PopupCaptureTrace.Stage ignoredTrace = PopupCaptureTrace.section("software_draw")) {
            if (drawable == null || width <= 0 || height <= 0) return null;
            Bitmap bitmap;
            try (PopupCaptureTrace.Stage allocationTrace = PopupCaptureTrace.section("bitmap_alloc")) {
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            }
            Canvas canvas = new Canvas(bitmap);
            Rect old = new Rect(drawable.getBounds());
            int previous = drawable.getAlpha();
            drawable.setAlpha(255);
            drawable.setBounds(0, 0, width, height);
            try {
                drawable.draw(canvas);
            } catch (Throwable ignored) {
                bitmap.recycle();
                return null;
            } finally {
                drawable.setAlpha(previous);
                drawable.setBounds(old);
            }
            return bitmap;
        }
    }

    static Drawable iconDrawableOf(View iconView) {
        Object icon = DesktopRenderUtils.invokeNoArgs(iconView, "getIcon");
        if (icon instanceof Drawable) return (Drawable) icon;
        if (iconView instanceof TextView) {
            for (Drawable d : ((TextView) iconView).getCompoundDrawables()) {
                if (d != null) return d;
            }
        }
        return null;
    }

    static Bitmap fastBitmapOf(Drawable drawable) {
        if (drawable == null) return null;
        Object bitmap = DesktopRenderUtils.fieldValue(drawable, "mBitmap");
        if (bitmap instanceof Bitmap) return (Bitmap) bitmap;
        Object got = DesktopRenderUtils.invokeNoArgs(drawable, "getBitmap");
        if (got instanceof Bitmap) return (Bitmap) got;
        if (drawable instanceof android.graphics.drawable.BitmapDrawable) {
            return ((android.graphics.drawable.BitmapDrawable) drawable).getBitmap();
        }
        return null;
    }

    static Rect iconLocalBounds(View iconView, Drawable drawable) {
        Rect fromMethod = new Rect();
        if (invokeIconBounds(iconView, fromMethod) && !fromMethod.isEmpty()) {
            return fromMethod;
        }
        if (drawable != null) {
            Rect bounds = drawable.getBounds();
            if (bounds != null && !bounds.isEmpty()) return new Rect(bounds);
        }
        Object itemBounds = DesktopRenderUtils.invokeNoArgs(iconView, "getItemIconBounds");
        if (itemBounds instanceof Rect) {
            Rect r = (Rect) itemBounds;
            if (!r.isEmpty()) return new Rect(r);
        } else if (itemBounds instanceof RectF) {
            Rect r = new Rect();
            ((RectF) itemBounds).round(r);
            if (!r.isEmpty()) return r;
        }
        int edge = Math.min(iconView.getWidth(), iconView.getHeight());
        return edge > 0 ? new Rect(0, 0, edge, edge) : null;
    }

    private static boolean invokeIconBounds(View iconView, Rect out) {
        for (Class<?> c = iconView.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Method method = c.getDeclaredMethod("getIconBounds", Rect.class);
                method.setAccessible(true);
                method.invoke(iconView, out);
                return true;
            } catch (Throwable ignored) { }
            try {
                java.lang.reflect.Method method =
                        c.getDeclaredMethod("getIconBounds", int.class, Rect.class);
                method.setAccessible(true);
                Object size = DesktopRenderUtils.invokeNoArgs(iconView, "getIconSize");
                int iconSize = size instanceof Number
                        ? ((Number) size).intValue()
                        : Math.min(iconView.getWidth(), iconView.getHeight());
                method.invoke(iconView, iconSize, out);
                return true;
            } catch (Throwable ignored) { }
        }
        return false;
    }
}
