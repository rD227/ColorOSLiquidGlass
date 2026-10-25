package net.z841973620.colorosliquidglass.glass;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import net.z841973620.colorosliquidglass.hook.ColorOsVersion;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** 文件夹底板、预览图标和名称的合成，以及合成位图缓存。 */
final class FolderCompositeRenderer {
    private FolderCompositeRenderer() {}

    static final Map<View, FolderSnap> FOLDER_SNAPS = new WeakHashMap<>();

    static final class FolderSnap {
        Bitmap bitmap;
        Bitmap plateSource;
        int width;
        int height;
        boolean includeName;
        /** Cos14 options-menu: true once AGSL plate was baked with a real backdrop. */
        boolean popupGlassReady;
        /** HW composite sampled the shared wallpaper; there is no local plateSource bitmap. */
        boolean sharedLivePlate;
    }

    static void paintFolderIntoSample(View folderIcon, Canvas canvas,
            Matrix globalToTarget) {
        paintFolderIntoSample(folderIcon, canvas, globalToTarget, false);
    }

    static void paintFolderIntoSample(View folderIcon, Canvas canvas,
            Matrix globalToTarget, boolean popupSample) {
        paintFolderIntoSample(folderIcon, canvas, globalToTarget, popupSample, null);
    }

    static void paintFolderIntoSample(View folderIcon, Canvas canvas,
            Matrix globalToTarget, boolean popupSample, PopupFolderBatch batch) {
        // Accept/create delegated path: plate + shrunk glyphs are painted by
        // paintOneDelegatedPreview at CellLayout geometry. Skip resting FolderIcon composite
        // (would show full-size / unscaled preview icons).
        Object background = DesktopRenderUtils.fieldValue(folderIcon, "mBackground");
        if (FolderPreviewOverlay.isPreviewDrawingDelegated(background)) return;
        // Open folder: floating Folder page is painted separately.
        Object folder = DesktopRenderUtils.fieldValue(folderIcon, "mFolder");
        if (folder instanceof View) {
            Object open = DesktopRenderUtils.invokeNoArgs(folder, "isOpen");
            if (Boolean.TRUE.equals(open)
                    || Boolean.TRUE.equals(DesktopRenderUtils.fieldValue(folder, "mIsOpen"))) {
                return;
            }
        }
        int w = folderIcon.getWidth();
        int h = folderIcon.getHeight();
        if (w <= 0 || h <= 0) return;
        RectF dest = DesktopRenderUtils.mapItemRectToTarget(folderIcon, new Rect(0, 0, w, h), globalToTarget);
        if (dest == null || dest.width() < 1f || dest.height() < 1f) return;
        if (batch != null) {
            View plateHost = resolveFolderPlateHost(folderIcon);
            boolean liveGlass = plateHost != null && (GlassInstaller.get(plateHost) != null
                    || plateHost.getBackground() instanceof GlassDrawable);
            if (liveGlass && BackdropCapture.usesSharedWallpaper(plateHost)) {
                batch.addFolder(w, h, dest,
                        hardwareCanvas -> drawFolderComposite(folderIcon, plateHost, true, hardwareCanvas),
                        bitmap -> retainFolderComposite(folderIcon, bitmap, null, true, w, h, true, true));
            } else {
                // Local-bitmap caches and temporarily removed OEM plates keep their original
                // reuse/retention rules; only the always-live shared folders need GPU batching.
                batch.addSoftware(() -> paintFolderIntoSample(folderIcon, canvas, globalToTarget, popupSample));
            }
            return;
        }
        Bitmap composite = folderCompositeBitmap(folderIcon, w, h, true, popupSample);
        if (composite == null || composite.isRecycled()) return;
        DesktopRenderUtils.BITMAP_PAINT.setAlpha(255);
        canvas.drawBitmap(composite, null, dest, DesktopRenderUtils.BITMAP_PAINT);
    }

    /**
     * Folder plate for DragView — plate + previews only (no title), sized to the plate /
     * DragView visual, mapped through DragView local space to avoid BR offset.
     */
    static boolean paintFolderIntoSampleAt(View folderIcon, View positionHost, Canvas canvas,
            Matrix globalToTarget) {
        Rect plateLocal = folderPlateLocalBounds(folderIcon);
        int w;
        int h;
        if (plateLocal != null && !plateLocal.isEmpty()) {
            w = plateLocal.width();
            h = plateLocal.height();
        } else {
            w = folderIcon.getWidth() > 0 ? folderIcon.getWidth() : positionHost.getWidth();
            h = folderIcon.getHeight() > 0 ? folderIcon.getHeight() : positionHost.getHeight();
            // Strip approximate label row when we cannot resolve the plate view.
            TextView name = folderNameView(folderIcon);
            if (name != null && name.getVisibility() == View.VISIBLE && name.getHeight() > 0) {
                h = Math.max(1, h - name.getHeight());
            }
            plateLocal = new Rect(0, 0, w, h);
        }
        if (w <= 0 || h <= 0) return false;
        Bitmap composite = folderCompositeBitmap(folderIcon, w, h, false);
        if (composite == null || composite.isRecycled()) return false;
        RectF dest = DragViewOverlay.mapDragContentDest(positionHost, folderIcon, plateLocal, globalToTarget);
        if (dest == null || dest.width() < 1f || dest.height() < 1f) return false;
        DesktopRenderUtils.BITMAP_PAINT.setAlpha(255);
        canvas.drawBitmap(composite, null, dest, DesktopRenderUtils.BITMAP_PAINT);
        return true;
    }

    /** Plate bounds inside the FolderIcon (excludes the title TextView). */
    static Rect folderPlateLocalBounds(View folderIcon) {
        View bg = resolveFolderPlateHost(folderIcon);
        if (bg != null && bg.getWidth() > 0 && bg.getHeight() > 0) {
            return new Rect(bg.getLeft(), bg.getTop(),
                    bg.getLeft() + bg.getWidth(), bg.getTop() + bg.getHeight());
        }
        Object preview = DesktopRenderUtils.fieldValue(folderIcon, "mPreviewFrame");
        if (preview instanceof View) {
            View frame = (View) preview;
            if (frame.getWidth() > 0 && frame.getHeight() > 0) {
                return new Rect(frame.getLeft(), frame.getTop(),
                        frame.getLeft() + frame.getWidth(), frame.getTop() + frame.getHeight());
            }
        }
        Object background = DesktopRenderUtils.fieldValue(folderIcon, "mBackground");
        Object rectObj = DesktopRenderUtils.invokeNoArgs(background, "getBackgroundRect");
        if (rectObj instanceof Rect) {
            Rect plate = (Rect) rectObj;
            if (plate.width() > 0 && plate.height() > 0) return new Rect(plate);
        }
        return null;
    }

    /**
     * Folder plate glass host.
     * ColorOS 16: {@code PreviewBackground.mBgView}.
     * ColorOS 14: synthetic ImageView capture host (no {@code mBgView}).
     */
    private static View resolveFolderPlateHost(View folderIcon) {
        if (folderIcon == null) return null;
        Object background = DesktopRenderUtils.fieldValue(folderIcon, "mBackground");
        Object bgViewObj = DesktopRenderUtils.fieldValue(background, "mBgView");
        if (bgViewObj instanceof View) {
            View bg = (View) bgViewObj;
            if (bg.getWidth() > 0 && bg.getHeight() > 0) return bg;
            if (GlassInstaller.get(bg) != null || bg.getBackground() instanceof GlassDrawable) {
                return bg;
            }
        }
        View capture = findFolderGlassCaptureHost(folderIcon);
        if (capture != null) return capture;
        return bgViewObj instanceof View ? (View) bgViewObj : null;
    }

    /** ColorOS 14: LiquidGlass lives on an invisible ImageView child of FolderIcon. */
    static View findFolderGlassCaptureHost(View folderIcon) {
        if (!(folderIcon instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) folderIcon;
        View fallback = null;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child == null) continue;
            boolean hasGlass = GlassInstaller.get(child) != null
                    || child.getBackground() instanceof GlassDrawable;
            if (!hasGlass) continue;
            if (child instanceof android.widget.ImageView) return child;
            if (fallback == null) fallback = child;
        }
        return fallback;
    }

    private static Bitmap folderCompositeBitmap(View folderIcon, int width, int height) {
        return folderCompositeBitmap(folderIcon, width, height, true, false);
    }

    private static Bitmap folderCompositeBitmap(View folderIcon, int width, int height,
            boolean includeName) {
        return folderCompositeBitmap(folderIcon, width, height, includeName, false);
    }

    /**
     * @param popupSample when true (options-menu glass): Cos14 uses soft/warm sticky snaps to
     *                    avoid mid-release 花屏; Cos16 keeps live-plate {@link GlassHwRasterizer}
     *                    so folder LiquidGlass appears under the menu (f4ed435 behavior).
     */
    private static Bitmap folderCompositeBitmap(View folderIcon, int width, int height,
            boolean includeName, boolean popupSample) {
        View bgView = resolveFolderPlateHost(folderIcon);
        boolean liveGlass = bgView != null && (GlassInstaller.get(bgView) != null
                || bgView.getBackground() instanceof GlassDrawable);
        boolean cos14 = ColorOsVersion.detect() == ColorOsVersion.Flavor.LEGACY_13_14;
        // The Cos16 HW path draws GlassDrawable directly from the shared wallpaper texture.
        // snapshotOf() would synchronously create an unused local software wallpaper first.
        // Keep the Cos14 software path and drag paths unchanged.
        boolean sharedLivePlate = popupSample && !cos14 && liveGlass
                && BackdropCapture.usesSharedWallpaper(bgView);
        Bitmap plateSource = bgView == null || sharedLivePlate
                ? null : BackdropCapture.snapshotOf(bgView);
        FolderSnap cached = FOLDER_SNAPS.get(folderIcon);
        if (cached != null && cached.bitmap != null && !cached.bitmap.isRecycled()
                && cached.width == width && cached.height == height
                && cached.includeName == includeName) {
            if (popupSample && cos14) {
                // Cos14 SoftGlass (no backdrop) must not stick — frame looks missing under menu.
                if (cached.popupGlassReady) {
                    return cached.bitmap;
                }
            } else if (!sharedLivePlate && !cached.sharedLivePlate
                    && cached.plateSource == plateSource) {
                return cached.bitmap;
            } else if (plateSource == null && (cached.plateSource != null || cached.sharedLivePlate)
                    && !liveGlass) {
                // Folder options-menu OEM-only strips plate glass (plateSource→null). Keep the
                // last LiquidGlass composite so float-menu ashmem still shows glass under the menu.
                return cached.bitmap;
            }
        }
        // After OEM-only: do not rebuild/overwrite with an empty OEM plate.
        // ColorOS 14 has no mBgView snapshot — allow rebuild from the live glass host.
        if (plateSource == null && !liveGlass) {
            FolderSnap glassSnap = findRetainedGlassSnap(folderIcon, width, height, includeName);
            if (glassSnap != null) return glassSnap.bitmap;
            return null;
        }

        final int drawW = width;
        final int drawH = height;
        final boolean drawName = includeName;
        final View plateHost = bgView;
        Bitmap rendered;
        boolean glassReady = false;
        if (popupSample && cos14 && liveGlass && plateHost != null) {
            // Cos14-only: plate ImageView is INVISIBLE — PreDraw never warms it. Warm backdrop
            // once, then software-composite the plate (HW raster of the Cos14 folder plate
            // flowers the screen under menu glass — see 5ca1ad5). Never HW here.
            if (!GlassInstaller.hasBackdropFrame(plateHost)) {
                try {
                    GlassInstaller.forceCapturePreviewPlate(plateHost);
                } catch (Throwable ignored) { }
            }
            plateSource = BackdropCapture.snapshotOf(plateHost);
            if (GlassInstaller.hasBackdropFrame(plateHost)) {
                rendered = softFolderComposite(folderIcon, plateHost, plateSource, drawW, drawH,
                        drawName);
                glassReady = rendered != null && !rendered.isRecycled();
            } else {
                // No backdrop yet — glyphs-only soft; leave glassReady false to retry next pump.
                rendered = softFolderComposite(folderIcon, plateHost, null, drawW, drawH,
                        drawName);
            }
        } else if (popupSample && cos14) {
            rendered = softFolderComposite(folderIcon, plateHost, plateSource, drawW, drawH,
                    drawName);
            glassReady = plateSource != null;
        } else {
            // Cos16 menu / drag / float: same live-plate HW as f4ed435 (folder glass under menu).
            rendered = hwFolderComposite(folderIcon, plateHost, drawW, drawH, drawName);
            glassReady = true;
        }
        if (rendered == null || rendered.isRecycled()) return null;
        retainFolderComposite(folderIcon, rendered, plateSource, sharedLivePlate,
                width, height, includeName, glassReady);
        return rendered;
    }

    private static void retainFolderComposite(View folderIcon, Bitmap rendered, Bitmap plateSource,
            boolean sharedLivePlate, int width, int height, boolean includeName, boolean glassReady) {
        FolderSnap snap = new FolderSnap();
        snap.bitmap = rendered;
        snap.plateSource = plateSource;
        // A shared source has no local-bitmap identity as a revision key. Keep rendering live
        // previews/material/coordinates; null == null must never freeze these composites.
        snap.sharedLivePlate = sharedLivePlate;
        snap.width = width;
        snap.height = height;
        snap.includeName = includeName;
        snap.popupGlassReady = glassReady;
        FolderSnap previous = FOLDER_SNAPS.put(folderIcon, snap);
        if (previous != null && previous.bitmap != null && previous.bitmap != rendered
                && !previous.bitmap.isRecycled()) {
            previous.bitmap.recycle();
        }
    }

    private static Bitmap hwFolderComposite(View folderIcon, View plateHost, int drawW, int drawH,
            boolean drawName) {
        try (PopupCaptureTrace.Stage ignoredTrace = PopupCaptureTrace.section("folder_composite")) {
            return GlassHwRasterizer.render(drawW, drawH,
                    hwCanvas -> drawFolderComposite(folderIcon, plateHost, drawName, hwCanvas));
        }
    }

    private static void drawFolderComposite(View folderIcon, View plateHost,
            boolean drawName, Canvas hwCanvas) {
        try (PopupCaptureTrace.Stage ignoredTrace = PopupCaptureTrace.section("folder_record")) {
            DesktopRenderUtils.DEPTH.set(DesktopRenderUtils.DEPTH.get() + 1);
            try {
                int dx = 0;
                int dy = 0;
                if (!drawName && plateHost != null) {
                    dx = -plateHost.getLeft();
                    dy = -plateHost.getTop();
                }
                int save = hwCanvas.save();
                hwCanvas.translate(dx, dy);
                try {
                    drawFolderPlate(folderIcon, hwCanvas);
                    paintFolderPreviewBitmaps(folderIcon, hwCanvas);
                    if (drawName) {
                        paintFolderName(folderIcon, hwCanvas);
                    }
                } finally {
                    hwCanvas.restoreToCount(save);
                }
            } finally {
                DesktopRenderUtils.DEPTH.set(Math.max(0, DesktopRenderUtils.DEPTH.get() - 1));
            }
        }
    }

    /**
     * Software folder plate for options-menu sampling (no {@link GlassHwRasterizer}).
     * Prefer an already-captured plate bitmap when present so AGSL glass stays in the sample
     * without re-entering the live plate's HW pipeline.
     */
    private static Bitmap softFolderComposite(View folderIcon, View bgView, Bitmap plateSource,
            int width, int height, boolean includeName) {
        Bitmap bitmap;
        try (PopupCaptureTrace.Stage allocationTrace = PopupCaptureTrace.section("bitmap_alloc")) {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        }
        Canvas canvas = new Canvas(bitmap);
        int dx = 0;
        int dy = 0;
        if (!includeName && bgView != null) {
            dx = -bgView.getLeft();
            dy = -bgView.getTop();
        }
        int save = canvas.save();
        canvas.translate(dx, dy);
        try {
            if (plateSource != null && !plateSource.isRecycled() && bgView != null) {
                int left = bgView.getLeft();
                int top = bgView.getTop();
                int plateW = bgView.getWidth();
                int plateH = bgView.getHeight();
                if (plateW > 0 && plateH > 0) {
                    DesktopRenderUtils.BITMAP_PAINT.setAlpha(255);
                    canvas.drawBitmap(plateSource, null,
                            new RectF(left, top, left + plateW, top + plateH), DesktopRenderUtils.BITMAP_PAINT);
                } else {
                    drawFolderPlate(folderIcon, canvas);
                }
            } else {
                drawFolderPlate(folderIcon, canvas);
            }
            paintFolderPreviewBitmaps(folderIcon, canvas);
            if (includeName) {
                paintFolderName(folderIcon, canvas);
            }
        } catch (Throwable ignored) {
            canvas.restoreToCount(save);
            bitmap.recycle();
            return null;
        }
        canvas.restoreToCount(save);
        return bitmap;
    }

    /** Any retained LiquidGlass folder snap, including a shared-wallpaper HW composite. */
    private static FolderSnap findRetainedGlassSnap(View folderIcon, int width, int height,
            boolean includeName) {
        FolderSnap exact = FOLDER_SNAPS.get(folderIcon);
        if (exact != null && exact.bitmap != null && !exact.bitmap.isRecycled()
                && (exact.plateSource != null || exact.sharedLivePlate)
                && exact.width == width && exact.height == height
                && exact.includeName == includeName) {
            return exact;
        }
        if (exact != null && exact.bitmap != null && !exact.bitmap.isRecycled()
                && (exact.plateSource != null || exact.sharedLivePlate)) {
            return exact;
        }
        return null;
    }

    private static void drawFolderPlate(View folderIcon, Canvas canvas) {
        Object background = DesktopRenderUtils.fieldValue(folderIcon, "mBackground");
        // Accept/create path: plate is painted by paintDelegatedFolderPreviews at CellLayout
        // geometry (scaled). Skip the resting plate host to avoid a stale double draw.
        if (FolderPreviewOverlay.isPreviewDrawingDelegated(background)) return;
        View bgView = resolveFolderPlateHost(folderIcon);
        if (bgView == null) return;
        int left = bgView.getLeft();
        int top = bgView.getTop();
        int plateW = bgView.getWidth();
        int plateH = bgView.getHeight();
        if (plateW <= 0 || plateH <= 0) {
            Object rectObj = DesktopRenderUtils.invokeNoArgs(background, "getBackgroundRect");
            if (rectObj instanceof Rect) {
                Rect plate = (Rect) rectObj;
                if (plate.width() > 0 && plate.height() > 0) {
                    left = plate.left;
                    top = plate.top;
                    plateW = plate.width();
                    plateH = plate.height();
                }
            }
        }
        if (plateW <= 0 || plateH <= 0) return;
        int save = canvas.save();
        canvas.translate(left, top);
        try {
            GlassDrawable glass = GlassInstaller.get(bgView);
            if (glass == null && bgView.getBackground() instanceof GlassDrawable) {
                glass = (GlassDrawable) bgView.getBackground();
            }
            if (glass != null) {
                Rect old = new Rect(glass.getBounds());
                if (old.isEmpty() || old.width() != plateW || old.height() != plateH) {
                    glass.setBounds(0, 0, plateW, plateH);
                }
                glass.draw(canvas);
                if (old.isEmpty() || old.width() != plateW || old.height() != plateH) {
                    glass.setBounds(old);
                }
                return;
            }
            Drawable drawable = bgView.getBackground();
            if (drawable != null) {
                Rect old = new Rect(drawable.getBounds());
                if (old.isEmpty()) {
                    drawable.setBounds(0, 0, plateW, plateH);
                }
                drawable.draw(canvas);
                if (old.isEmpty()) drawable.setBounds(old);
            }
        } finally {
            canvas.restoreToCount(save);
        }
    }

    private static void paintFolderPreviewBitmaps(View folderIcon, Canvas canvas) {
        Object manager = DesktopRenderUtils.fieldValue(folderIcon, "mPreviewItemManager");
        if (manager == null) return;
        boolean cos14 = ColorOsVersion.detect() == ColorOsVersion.Flavor.LEGACY_13_14;
        // Prefer OEM PreviewItemManager.draw on HW — ColorOS 14 applies basePreviewOffset /
        // previewStyleBoundOffset there. Cos14 soft canvas: OEM draw often no-ops or misplaces
        // glyphs (top-left); use params + basePreviewOffset instead. Cos16 keeps OEM draw.
        if (!cos14 || canvas.isHardwareAccelerated()) {
            int saveOem = canvas.save();
            try {
                if (invokePreviewManagerDraw(manager, canvas)) return;
            } finally {
                canvas.restoreToCount(saveOem);
            }
        }

        List<?> params = resolvePreviewDrawingParams(manager);
        if (params == null || params.isEmpty()) return;
        Object intrinsic = DesktopRenderUtils.invokeNoArgs(manager, "getIntrinsicIconSize");
        float iconSize = intrinsic instanceof Number
                ? ((Number) intrinsic).floatValue()
                : 0f;
        Object background = DesktopRenderUtils.fieldValue(folderIcon, "mBackground");
        // Cos16 Oplus layout already folds basePreviewOffset into transX/Y.
        // Cos14 mFirstPageParams trans is plate-local — must add basePreviewOffset or
        // glyphs sit in the FolderIcon top-left under menu glass.
        float ox = cos14 ? DesktopRenderUtils.floatField(background, "basePreviewOffsetX") : 0f;
        float oy = cos14 ? DesktopRenderUtils.floatField(background, "basePreviewOffsetY") : 0f;
        int saveAll = canvas.save();
        try {
            if (ox != 0f || oy != 0f) canvas.translate(ox, oy);
            for (int i = params.size() - 1; i >= 0; i--) {
                Object param = params.get(i);
                if (param == null) continue;
                Object hidden = DesktopRenderUtils.fieldValue(param, "hidden");
                if (hidden instanceof Boolean && (Boolean) hidden) continue;
                Object d = DesktopRenderUtils.fieldValue(param, "drawable");
                if (!(d instanceof Drawable)) continue;
                Drawable drawable = (Drawable) d;
                float transX = DesktopRenderUtils.floatField(param, "transX");
                float transY = DesktopRenderUtils.floatField(param, "transY");
                float scale = DesktopRenderUtils.floatField(param, "scale");
                if (scale <= 0f) scale = 1f;
                Rect bounds = drawable.getBounds();
                float size = iconSize > 0f ? iconSize
                        : Math.max(1, bounds.isEmpty() ? 48 : bounds.width());
                Bitmap bitmap = DesktopItemRenderer.bitmapForCanvas(DesktopItemRenderer.fastBitmapOf(drawable), canvas);
                if (bitmap == null) {
                    bitmap = DesktopItemRenderer.rasterizeDrawable(drawable, Math.round(size), Math.round(size));
                }
                if (bitmap == null || bitmap.isRecycled()) continue;
                int save = canvas.save();
                canvas.translate(transX, transY);
                canvas.scale(scale, scale);
                DesktopRenderUtils.BITMAP_PAINT.setAlpha(255);
                canvas.drawBitmap(bitmap, null, new RectF(0f, 0f, size, size), DesktopRenderUtils.BITMAP_PAINT);
                canvas.restoreToCount(save);
            }
        } finally {
            canvas.restoreToCount(saveAll);
        }
    }

    /** ColorOS 14: public fields {@code mFirstPageParams}/{@code mCurrentPageParams}. */
    static List<?> resolvePreviewDrawingParams(Object manager) {
        if (manager == null) return null;
        Object paramsObj = DesktopRenderUtils.invokeNoArgs(manager, "getCurrentPageParams");
        if (paramsObj instanceof List && !((List<?>) paramsObj).isEmpty()) {
            return (List<?>) paramsObj;
        }
        paramsObj = DesktopRenderUtils.invokeNoArgs(manager, "getFirstPageDrawingParams");
        if (paramsObj instanceof List && !((List<?>) paramsObj).isEmpty()) {
            return (List<?>) paramsObj;
        }
        paramsObj = DesktopRenderUtils.fieldValue(manager, "mFirstPageParams");
        if (paramsObj instanceof List && !((List<?>) paramsObj).isEmpty()) {
            return (List<?>) paramsObj;
        }
        paramsObj = DesktopRenderUtils.fieldValue(manager, "mCurrentPageParams");
        if (paramsObj instanceof List) return (List<?>) paramsObj;
        return null;
    }

    static boolean invokePreviewManagerDraw(Object manager, Canvas canvas) {
        if (manager == null || canvas == null) return false;
        Class<?> c = manager.getClass();
        while (c != null) {
            try {
                java.lang.reflect.Method m = c.getDeclaredMethod("draw", Canvas.class);
                m.setAccessible(true);
                m.invoke(manager, canvas);
                return true;
            } catch (Throwable ignored) {
                c = c.getSuperclass();
            }
        }
        return false;
    }

    private static void paintFolderName(View folderIcon, Canvas canvas) {
        TextView name = folderNameView(folderIcon);
        if (name == null || name.getVisibility() != View.VISIBLE) return;
        CharSequence text = name.getText();
        if (TextUtils.isEmpty(text)) return;
        int save = canvas.save();
        canvas.translate(name.getLeft(), name.getTop());
        try {
            float previous = name.getAlpha();
            name.setAlpha(1f);
            name.draw(canvas);
            name.setAlpha(previous);
        } catch (Throwable ignored) {
            DesktopRenderUtils.TITLE_PAINT.setColor(name.getCurrentTextColor());
            DesktopRenderUtils.TITLE_PAINT.setTextSize(name.getTextSize());
            DesktopRenderUtils.TITLE_PAINT.setTypeface(name.getTypeface());
            DesktopRenderUtils.TITLE_PAINT.setTextAlign(Paint.Align.CENTER);
            float x = name.getWidth() * 0.5f;
            float y = name.getBaseline();
            if (y <= 0f) y = name.getPaddingTop() - DesktopRenderUtils.TITLE_PAINT.ascent();
            canvas.drawText(text.toString(), x, y, DesktopRenderUtils.TITLE_PAINT);
        }
        canvas.restoreToCount(save);
    }

    static TextView folderNameView(View folderIcon) {
        Object field = DesktopRenderUtils.fieldValue(folderIcon, "mFolderName");
        if (field instanceof TextView) return (TextView) field;
        Object got = DesktopRenderUtils.invokeNoArgs(folderIcon, "getFolderName");
        if (got instanceof TextView) return (TextView) got;
        Object title = DesktopRenderUtils.invokeNoArgs(folderIcon, "getTitle");
        if (title instanceof TextView) return (TextView) title;
        return null;
    }
}
