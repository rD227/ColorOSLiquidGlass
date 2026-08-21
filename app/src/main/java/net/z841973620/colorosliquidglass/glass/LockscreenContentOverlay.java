package net.z841973620.colorosliquidglass.glass;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * In-process lock-screen chrome for volume glass: clock + plain notification rows.
 * Wallpaper is sampled separately; this paints SystemUI views that sit above it.
 */
final class LockscreenContentOverlay {
    private static final ThreadLocal<Boolean> SAMPLING = new ThreadLocal<>();
    private static final Paint FALLBACK_CONTAINER_PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint BITMAP_PAINT = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

    private LockscreenContentOverlay() {}

    static boolean isSampling() {
        return Boolean.TRUE.equals(SAMPLING.get());
    }

    static boolean paintIntoTargetLocal(View glassHost, Canvas canvas) {
        if (glassHost == null || canvas == null) return false;
        List<View> sources = collectSources(glassHost);
        if (sources.isEmpty()) return false;

        Matrix targetToGlobal = new Matrix();
        glassHost.transformMatrixToGlobal(targetToGlobal);
        Matrix globalToTarget = new Matrix();
        if (!targetToGlobal.invert(globalToTarget)) {
            globalToTarget.reset();
            int[] loc = new int[2];
            glassHost.getLocationOnScreen(loc);
            globalToTarget.setTranslate(-loc[0], -loc[1]);
        }
        SAMPLING.set(Boolean.TRUE);
        boolean painted = false;
        try {
            for (View source : sources) {
                if (source == null || source == glassHost) continue;
                if (!source.isShown() || source.getWidth() <= 0 || source.getHeight() <= 0) continue;
                int save = canvas.save();
                try {
                    Matrix sourceToGlobal = new Matrix();
                    source.transformMatrixToGlobal(sourceToGlobal);
                    Matrix sourceToTarget = new Matrix();
                    sourceToTarget.setConcat(globalToTarget, sourceToGlobal);
                    canvas.concat(sourceToTarget);
                    drawViewDeep(source, canvas);
                    painted = true;
                } catch (Throwable ignored) {
                } finally {
                    canvas.restoreToCount(save);
                }
            }
        } finally {
            SAMPLING.remove();
        }
        return painted;
    }

    private static List<View> collectSources(View glassHost) {
        List<View> collected = new ArrayList<>();
        collectClockViews(glassHost, collected);
        collectNotificationRows(glassHost, collected);
        return collected;
    }

    private static void collectClockViews(View seed, List<View> out) {
        try {
            Object ctrl = keyguardStyleClockController(seed);
            if (ctrl != null) {
                View frame = readViewField(ctrl, "keyguardStyleClock");
                if (frame == null) frame = readViewField(ctrl, "mView");
                addUnique(out, frame);
                Object theme = null;
                try {
                    theme = ctrl.getClass().getField("oplusKeyguardStyleTheme").get(ctrl);
                } catch (Throwable ignored) { }
                if (theme == null && frame != null) {
                    try {
                        Method getTheme = frame.getClass().getMethod("getTheme");
                        theme = getTheme.invoke(frame);
                    } catch (Throwable ignored) { }
                }
                if (theme != null) {
                    Method getView = null;
                    for (Method m : theme.getClass().getMethods()) {
                        if ("getView".equals(m.getName()) && m.getParameterCount() == 1
                                && m.getParameterTypes()[0] == int.class) {
                            getView = m;
                            break;
                        }
                    }
                    if (getView != null) {
                        Object v0 = getView.invoke(theme, 0);
                        Object v1 = getView.invoke(theme, 1);
                        if (v0 instanceof View) addUnique(out, (View) v0);
                        if (v1 instanceof View) addUnique(out, (View) v1);
                    }
                }
            }
        } catch (Throwable ignored) { }
    }

    /**
     * Plain lock-screen notifications (stack style, not bottom capsules) live inside the
     * NotificationStackScrollLayout. Resolve it through the injected controller instead of
     * walking window roots (non-SDK-blocked in SystemUI). Only the visible rows are drawn:
     * NSSL's own dispatchDraw records into a RenderNode blur that software canvases cannot
     * replay.
     */
    private static void collectNotificationRows(View seed, List<View> out) {
        if (seed == null) return;
        try {
            ClassLoader cl = seed.getContext().getClassLoader();
            Class<?> depClass = Class.forName("com.android.systemui.Dependency", false, cl);
            Field sd = depClass.getField("sDependency");
            Object dep = sd.get(null);
            if (dep == null) return;
            Class<?> ctrlClass = Class.forName(
                    "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController",
                    false, cl);
            Object ctrl = depClass.getMethod("getDependencyInner", Object.class)
                    .invoke(dep, ctrlClass);
            if (ctrl == null) return;
            View nssl = readViewField(ctrl, "mView");
            if (nssl == null) return;
            Object visible = invokeNoArgs(nssl, "getVisibleChildren");
            if (visible instanceof List) {
                for (Object row : (List<?>) visible) {
                    if (row instanceof View) {
                        addUnique(out, (View) row);
                    }
                }
            }
        } catch (Throwable ignored) { }
    }

    private static Object keyguardStyleClockController(View seed) {
        if (seed == null) return null;
        try {
            ClassLoader cl = seed.getContext().getClassLoader();
            Class<?> depExClass = Class.forName("com.android.systemui.DependencyEx", false, cl);
            Field sDep = depExClass.getDeclaredField("sDependency");
            sDep.setAccessible(true);
            Object depEx = sDep.get(null);
            if (depEx == null) return null;
            Class<?> kgdClass = Class.forName(
                    "com.android.keyguard.OplusKeyguardDependencyEx", false, cl);
            Object kgd = depExClass.getMethod("getDependency", Class.class)
                    .invoke(depEx, kgdClass);
            if (kgd == null) return null;
            return kgd.getClass().getMethod("getKeyguardStyleClockController").invoke(kgd);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void drawViewDeep(View view, Canvas canvas) {
        if (view.getVisibility() != View.VISIBLE || view.getAlpha() < 0.01f) return;
        if (view instanceof TextureView) {
            drawTexture((TextureView) view, canvas);
            return;
        }
        // Notification rows: draw the background container manually (OEM draws it via a
        // platform-blur drawable that a software canvas cannot render), then the content.
        View bg = readViewField(view, "mBackgroundNormal");
        if (bg == null) bg = findBackgroundView(view);
        if (bg != null && bg != view && bg.getWidth() > 0 && bg.getHeight() > 0
                && bg.getVisibility() != View.GONE) {
            drawNotificationRowWithContainer(view, bg, canvas);
            return;
        }
        Drawable glassBg = null;
        if (view.getBackground() instanceof GlassDrawable) {
            glassBg = view.getBackground();
            view.setBackground(null);
        }
        try {
            view.draw(canvas);
        } catch (Throwable ignored) {
        } finally {
            if (glassBg != null) {
                try { view.setBackground(glassBg); } catch (Throwable ignored) { }
            }
        }
        overlayTextureViews(view, canvas, 0f, 0f);
    }

    /**
     * Draws a notification row as: background container (manual) + content children.
     * The container's OEM onDraw replaces the base drawable with a blur proxy drawable
     * (ViewBlurManager), which is a no-op on a software canvas; drawing the base drawable
     * directly restores the card look.
     */
    private static void drawNotificationRowWithContainer(View row, View bg, Canvas canvas) {
        int prevVisibility = bg.getVisibility();
        // Make the background visible so the manual drawable pass runs, then hide it while
        // row.draw() renders the content (avoids the OEM blur-drawable background no-op).
        try { bg.setVisibility(View.VISIBLE); } catch (Throwable ignored) { }
        try {
            int save = canvas.save();
            // bg is a child of the row — map its offset inside the row-local frame.
            canvas.translate(bg.getLeft() + bg.getTranslationX(),
                    bg.getTop() + bg.getTranslationY());
            drawNotificationBackgroundContainer(row, bg, canvas);
            canvas.restoreToCount(save);
        } catch (Throwable ignored) {
        } finally {
            try { bg.setVisibility(View.INVISIBLE); } catch (Throwable ignored) { }
        }
        try {
            row.draw(canvas);
        } catch (Throwable ignored) {
        } finally {
            try { bg.setVisibility(prevVisibility); } catch (Throwable ignored) { }
        }
        overlayTextureViews(row, canvas, 0f, 0f);
    }

    /**
     * Manually paints {@code NotificationBackgroundView}'s container.
     * <p>
     * OEM paints the card through a platform-blur drawable (ViewBlurManager) that only a
     * hardware canvas can render, so the base drawable alone produces the wrong (solid)
     * look. Recreate the default frosted card exactly like the system does:
     * blurred lock wallpaper clipped to the card rounded-rect, then a translucent white
     * overlay, then the OEM corner radii / clip amounts.
     */
    private static void drawNotificationBackgroundContainer(View row, View bg, Canvas canvas) {
        try {
            int width = bg.getWidth();
            if (width <= 0) return;
            int height = actualHeight(bg);
            if (height <= 0) return;
            int actualWidth = actualWidth(bg);
            boolean alignedRight = isAlignedToRight(bg);
            int left = alignedRight ? width - actualWidth : 0;
            int right = alignedRight ? width : actualWidth;

            float[] radii = readRadii(bg);
            float topLeft = radii[0];
            float topRight = radii[2];
            float bottomRight = radii[4];
            float bottomLeft = radii[6];

            // Clip to the card rounded-rect (plus OEM top/bottom clip amounts).
            int clipTop = intField(bg, "mClipTopAmount");
            int clipBottom = intField(bg, "mClipBottomAmount");
            int clipLeft = Math.max(0, left);
            int clipRight = Math.min(width, right);
            int clipTopY = Math.max(0, clipTop);
            int clipBottomY = Math.min(height, height - clipBottom);

            int save = canvas.save();
            Path clip = new Path();
            RectF card = new RectF(clipLeft, clipTopY, clipRight, clipBottomY);
            clip.addRoundRect(card,
                    new float[] { topLeft, topLeft, topRight, topRight,
                            bottomRight, bottomRight, bottomLeft, bottomLeft },
                    Path.Direction.CW);
            canvas.clipPath(clip);
            try {
                // Frosted backdrop: lock wallpaper cover-scaled to the root, sampled at the
                // card's screen position, heavily downsampled for a blur look.
                int[] loc = new int[2];
                bg.getLocationOnScreen(loc);
                int screenLeft = loc[0];
                int screenTop = loc[1];
                View root = row.getRootView();
                if (root == null) root = bg.getRootView();
                drawBlurredWallpaperRegion(bg, canvas, screenLeft + left, screenTop,
                        clipRight - clipLeft, clipBottomY - clipTopY, root);
                // Translucent white overlay (system default card tint).
                FALLBACK_CONTAINER_PAINT.setColor(CARD_OVERLAY_COLOR);
                FALLBACK_CONTAINER_PAINT.setStyle(Paint.Style.FILL);
                canvas.drawRect(clipLeft, clipTopY, clipRight, clipBottomY,
                        FALLBACK_CONTAINER_PAINT);
            } finally {
                canvas.restoreToCount(save);
            }
        } catch (Throwable ignored) { }
    }

    private static final int CARD_OVERLAY_COLOR = 0xE6FFFFFF;

    /**
     * Paints the lock wallpaper into the card region, blurred by downsampling to ~1/8 then
     * upscaling — a software approximation of the platform-blur frost the OEM applies.
     */
    private static void drawBlurredWallpaperRegion(View bg, Canvas canvas,
            int screenLeft, int screenTop, int regionW, int regionH, View root) {
        if (regionW <= 0 || regionH <= 0) return;
        android.graphics.drawable.Drawable wallpaper =
                BackdropCapture.lockWallpaperDrawable(bg.getContext());
        if (wallpaper == null) return;
        try {
            int srcW = Math.max(1, wallpaper.getIntrinsicWidth());
            int srcH = Math.max(1, wallpaper.getIntrinsicHeight());
            int rootW = root != null ? Math.max(1, root.getWidth())
                    : Math.max(1, regionW);
            int rootH = root != null ? Math.max(1, root.getHeight())
                    : Math.max(1, regionH);
            float cover = Math.max(rootW / (float) srcW, rootH / (float) srcH);
            int drawW = Math.round(srcW * cover);
            int drawH = Math.round(srcH * cover);
            int wallLeft = (rootW - drawW) / 2;
            int wallTop = (rootH - drawH) / 2;

            Bitmap region = Bitmap.createBitmap(regionW, regionH, Bitmap.Config.ARGB_8888);
            Canvas rc = new Canvas(region);
            wallpaper.setBounds(wallLeft, wallTop, wallLeft + drawW, wallTop + drawH);
            // The region canvas is card-local; move it so wallpaper aligns to the screen.
            rc.translate(-screenLeft, -screenTop);
            wallpaper.draw(rc);
            wallpaper.setBounds(wallLeft, wallTop, wallLeft + drawW, wallTop + drawH);

            Bitmap blurred = downsampleBlur(region);
            canvas.drawBitmap(blurred, 0f, 0f, BITMAP_PAINT);
            if (blurred != region) region.recycle();
            if (blurred != null && !blurred.isRecycled()) blurred.recycle();
        } catch (Throwable ignored) { }
    }

    private static Bitmap downsampleBlur(Bitmap src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int sw = Math.max(1, w / 8);
        int sh = Math.max(1, h / 8);
        Bitmap small = Bitmap.createScaledBitmap(src, sw, sh, true);
        Bitmap out = Bitmap.createScaledBitmap(small, w, h, true);
        if (small != out) small.recycle();
        return out;
    }

    private static float[] readRadii(View bg) {
        Object r = readField(bg, "mCornerRadii");
        if (r instanceof float[] && ((float[]) r).length >= 8) return (float[]) r;
        float def = 18f * bg.getResources().getDisplayMetrics().density;
        return new float[] { def, def, def, def, def, def, def, def };
    }

    private static int actualHeight(View bg) {
        try {
            Object o = invokeNoArgs(bg, "getActualHeight");
            if (o instanceof Number && ((Number) o).intValue() > 0) return ((Number) o).intValue();
        } catch (Throwable ignored) { }
        int height = bg.getHeight();
        Boolean bottomRounded = (Boolean) readField(bg, "mBottomIsRounded");
        Boolean bottomClips = (Boolean) readField(bg, "mBottomAmountClips");
        Boolean expandRunning = (Boolean) readField(bg, "mExpandAnimationRunning");
        int clipBottom = intField(bg, "mClipBottomAmount");
        if (Boolean.TRUE.equals(bottomRounded) && Boolean.TRUE.equals(bottomClips)
                && !Boolean.TRUE.equals(expandRunning)) {
            height -= clipBottom;
        }
        return height;
    }

    private static int actualWidth(View bg) {
        try {
            Object o = invokeNoArgs(bg, "getActualWidth");
            if (o instanceof Number && ((Number) o).intValue() > 0) return ((Number) o).intValue();
        } catch (Throwable ignored) { }
        return bg.getWidth();
    }

    private static boolean isAlignedToRight(View bg) {
        try {
            Object o = invokeNoArgs(bg, "isAlignedToRight");
            return Boolean.TRUE.equals(o);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Locates the NotificationBackgroundView child of a notification row by class name. */
    private static View findBackgroundView(View row) {
        if (row == null || !(row instanceof ViewGroup)) return null;
        ViewGroup g = (ViewGroup) row;
        for (int i = 0; i < g.getChildCount(); i++) {
            View child = g.getChildAt(i);
            String name = child.getClass().getName();
            if (name != null && name.contains("NotificationBackgroundView")) return child;
        }
        return null;
    }

    private static void overlayTextureViews(View view, Canvas canvas, float ox, float oy) {
        if (view == null || view.getVisibility() != View.VISIBLE) return;
        if (view instanceof TextureView) {
            int save = canvas.save();
            canvas.translate(ox, oy);
            drawTexture((TextureView) view, canvas);
            canvas.restoreToCount(save);
            return;
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) view;
        float sx = g.getScrollX();
        float sy = g.getScrollY();
        for (int i = 0; i < g.getChildCount(); i++) {
            View child = g.getChildAt(i);
            overlayTextureViews(child, canvas, ox + child.getX() - sx, oy + child.getY() - sy);
        }
    }

    private static void drawTexture(TextureView tv, Canvas canvas) {
        try {
            Bitmap bitmap = tv.getBitmap();
            if (bitmap == null || bitmap.isRecycled()) return;
            canvas.drawBitmap(bitmap, 0f, 0f, null);
            if (!bitmap.isRecycled()) bitmap.recycle();
        } catch (Throwable ignored) { }
    }

    private static void addUnique(List<View> out, View view) {
        if (view != null && !out.contains(view)) out.add(view);
    }

    private static View readViewField(Object object, String name) {
        Object v = readField(object, name);
        return v instanceof View ? (View) v : null;
    }

    private static int intField(Object object, String name) {
        Object v = readField(object, name);
        if (v instanceof Number) return ((Number) v).intValue();
        return 0;
    }

    private static Object readField(Object object, String name) {
        if (object == null) return null;
        for (Class<?> c = object.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(object);
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private static Object invokeNoArgs(Object object, String name) {
        if (object == null) return null;
        try {
            return object.getClass().getMethod(name).invoke(object);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
