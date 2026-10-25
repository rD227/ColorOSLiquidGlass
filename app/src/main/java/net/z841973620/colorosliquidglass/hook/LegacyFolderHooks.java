package net.z841973620.colorosliquidglass.hook;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import net.z841973620.colorosliquidglass.glass.DesktopBackdropSampler;
import net.z841973620.colorosliquidglass.glass.GlassDrawable;
import net.z841973620.colorosliquidglass.glass.GlassInstaller;
import net.z841973620.colorosliquidglass.ipc.DesktopBackdropHub;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** ColorOS 13/14 文件夹预览：背景采样宿主、布局、圆角和绘制。 */
final class LegacyFolderHooks {
    private final HookContext hooks;
    private final LegacyLauncherHooks launcherHooks;

    LegacyFolderHooks(HookContext hooks, LegacyLauncherHooks launcherHooks) {
        this.hooks = hooks;
        this.launcherHooks = launcherHooks;
    }

    private static final ColorDrawable TRANSPARENT = new ColorDrawable(Color.TRANSPARENT);
    private static final Object HOST_TAG = new Object();
    private final Map<Object, ImageView> previewHosts =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Object, Drawable> savedOemBg =
            Collections.synchronizedMap(new WeakHashMap<>());

    // ── Folder preview (mBgDrawable / drawBackground(Canvas)) ───────────────

    void hookFolderPreviewBackground(ClassLoader cl) {
        final String className = "com.android.launcher3.folder.OplusPreviewBackground";
        try {
            Class<?> c = Class.forName(className, false, cl);

            // setup(...) — attach capture host + install glass after OEM builds mBgDrawable.
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("setup") || m.isBridge() || m.isSynthetic()) continue;
                hooks.hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        Object preview = chain.getThisObject();
                        Object host = HookReflection.field(preview, "mInvalidateDelegate");
                        if (LegacyLauncherHooks.isFolderIcon(host)) syncFolderPreview(host, preview);
                    } catch (Throwable e) {
                        hooks.log(5, "Os14 setup glass failed", e);
                    }
                    return result;
                });
            }

            hooks.after(cl, className, "updateBgColorFilter", preview -> {
                // Long-press resetPressAnimStateForLongClick restores OEM white via this
                // method — always strip it while the module is on.
                if (hooks.enabled()) suppressOemDrawable(preview);
                Object host = HookReflection.field(preview, "mInvalidateDelegate");
                if (LegacyLauncherHooks.isFolderIcon(host)) syncFolderPreview(host, preview);
            });
            // BigFolderBackground overrides updateBgColorFilter — hook it too.
            try {
                Class<?> bigBg = Class.forName(
                        "com.android.launcher3.folder.big.BigFolderBackground", false, cl);
                for (Method m : bigBg.getDeclaredMethods()) {
                    if (!m.getName().equals("updateBgColorFilter") || m.isBridge() || m.isSynthetic()) {
                        continue;
                    }
                    hooks.hookOnce(m, chain -> {
                        Object result = chain.proceed();
                        try {
                            if (hooks.enabled()) suppressOemDrawable(chain.getThisObject());
                        } catch (Throwable ignored) { }
                        return result;
                    });
                }
                for (Method m : bigBg.getDeclaredMethods()) {
                    if (!m.getName().equals("drawBackground") || m.getParameterCount() < 1
                            || m.isBridge() || m.isSynthetic()) continue;
                    hooks.hookOnce(m, chain -> {
                        if (!hooks.enabled()) return chain.proceed();
                        Object preview = chain.getThisObject();
                        suppressOemDrawable(preview);
                        // Only skip canvas glass for the dragged folder (DragView bake).
                        // Sibling folders must keep drawing glass while one folder moves.
                        if (launcherHooks.folderDragActive && !isCreateFolderPreview(preview)
                                && launcherHooks.isDragSourcePreview(preview)) {
                            return null;
                        }
                        if (chain.getArgs().size() >= 1 && chain.getArg(0) instanceof Canvas
                                && drawPreviewGlassOnCanvas(preview, (Canvas) chain.getArg(0))) {
                            return null;
                        }
                        suppressOemDrawable(preview);
                        return null;
                    });
                }
            } catch (Throwable e) {
                hooks.log(5, "Os14 BigFolderBackground glass unavailable", e);
            }

            // Resting FolderIcon + delegated CellLayout create/accept all call drawBackground(Canvas).
            try {
                Method drawBg = c.getDeclaredMethod("drawBackground", Canvas.class);
                hooks.hookOnce(drawBg, chain -> {
                    if (!hooks.enabled()) return chain.proceed();
                    if (GlassInstaller.isPreviewBackdropCapturing()) return null;
                    Object preview = chain.getThisObject();
                    try {
                        suppressOemDrawable(preview);
                        // DragView bake: suppress white only for the dragged folder.
                        // Create-folder CellLayout preview must still draw glass on canvas.
                        if (launcherHooks.folderDragActive && !isCreateFolderPreview(preview)
                                && launcherHooks.isDragSourcePreview(preview)) {
                            return null;
                        }
                        if (drawPreviewGlassOnCanvas(preview, (Canvas) chain.getArg(0))) {
                            return null;
                        }
                    } catch (Throwable e) {
                        hooks.log(5, "Os14 drawBackground glass failed", e);
                    }
                    // Prefer empty plate over OEM white brightening the glass.
                    suppressOemDrawable(preview);
                    return null;
                });
            } catch (Throwable e) {
                hooks.log(5, "Os14 drawBackground(Canvas) hook unavailable", e);
            }

            // Keep glass alive through accept / create-folder delegate transitions.
            for (Method m : c.getDeclaredMethods()) {
                if (m.isBridge() || m.isSynthetic()) continue;
                String name = m.getName();
                if (!(name.equals("animateToAccept")
                        || name.equals("clearDrawingDelegate")
                        || name.equals("delegateDrawing")
                        || name.equals("lambda$animateToAccept$0"))) continue;
                final String methodName = name;
                hooks.hookOnce(m, chain -> {
                    Object preview = chain.getThisObject();
                    boolean createFolder = isCreateFolderPreview(preview)
                            || LegacyLauncherHooks.resolveFolderIcon(preview) == null;
                    if (methodName.equals("clearDrawingDelegate") && createFolder) {
                        detachCreateFolderCaptureHost(preview);
                    }
                    Object result = chain.proceed();
                    try {
                        if (methodName.equals("clearDrawingDelegate")) {
                            if (createFolder || LegacyLauncherHooks.resolveFolderIcon(preview) == null) {
                                detachCreateFolderCaptureHost(preview);
                                suppressOemDrawable(preview);
                            } else {
                                Object host = LegacyLauncherHooks.resolveFolderIcon(preview);
                                if (host != null) syncFolderPreview(host, preview);
                            }
                        } else if (methodName.equals("animateToAccept")
                                || methodName.equals("lambda$animateToAccept$0")
                                || methodName.equals("delegateDrawing")) {
                            suppressOemDrawable(preview);
                            if (LegacyLauncherHooks.resolveFolderIcon(preview) == null) {
                                placeCreateFolderCaptureHost(preview);
                            } else {
                                ensureCaptureHost(preview);
                            }
                            Object host = LegacyLauncherHooks.resolveFolderIcon(preview);
                            if (host instanceof View) ((View) host).invalidate();
                            Object delegate = HookReflection.field(preview, "mDrawingDelegate");
                            if (delegate instanceof View) ((View) delegate).invalidate();
                        }
                        if (DesktopBackdropHub.isLive()) DesktopBackdropSampler.invalidateCache();
                    } catch (Throwable e) {
                        hooks.log(5, "Os14 " + methodName + " glass failed", e);
                    }
                    return result;
                });
            }

            try {
                Class<?> previewBg = Class.forName(
                        "com.android.launcher3.folder.PreviewBackground", false, cl);
                for (Method m : previewBg.getDeclaredMethods()) {
                    if (!m.getName().equals("animateToRest") || m.isBridge() || m.isSynthetic()) {
                        continue;
                    }
                    hooks.hookOnce(m, chain -> {
                        Object preview = chain.getThisObject();
                        Object result = chain.proceed();
                        try {
                            Object host = LegacyLauncherHooks.resolveFolderIcon(preview);
                            if (host == null) {
                                detachCreateFolderCaptureHost(preview);
                                suppressOemDrawable(preview);
                            } else {
                                syncFolderPreview(host, preview);
                            }
                            if (DesktopBackdropHub.isLive()) {
                                DesktopBackdropSampler.invalidateCache();
                            }
                        } catch (Throwable e) {
                            hooks.log(5, "Os14 animateToRest glass failed", e);
                        }
                        return result;
                    });
                }
            } catch (Throwable e) {
                hooks.log(5, "Os14 PreviewBackground.animateToRest unavailable", e);
            }
        } catch (Throwable e) {
            hooks.log(5, "Os14 OplusPreviewBackground unavailable", e);
        }

        // FolderIcon.setFolderBackground — used when create-folder becomes a real folder.
        hooks.after(cl, "com.android.launcher3.folder.FolderIcon", "setFolderBackground", icon -> {
            Object preview = HookReflection.field(icon, "mBackground");
            if (preview != null) syncFolderPreview(icon, preview);
        });
        hooks.after(cl, "com.android.launcher3.folder.OplusFolderIcon", "setFolderBackground", icon -> {
            Object preview = HookReflection.field(icon, "mBackground");
            if (preview != null) syncFolderPreview(icon, preview);
        });
    }

    private boolean drawPreviewGlassOnCanvas(Object preview, Canvas canvas) {
        if (canvas == null || preview == null) return false;
        logFolderGlassCall(preview);
        Object folderHost = LegacyLauncherHooks.resolveFolderIcon(preview);
        if (folderHost != null && LegacyLauncherHooks.isFolderOpen(folderHost)) return false;

        boolean createFolder = isCreateFolderPreview(preview);
        if (createFolder) {
            // Park on DragLayer (never CellLayout / ShortcutsAndWidgets — wrong LayoutParams crash).
            placeCreateFolderCaptureHost(preview);
        }

        ImageView host = ensureCaptureHost(preview);
        if (host == null) return false;
        if (createFolder) {
            placeCreateFolderCaptureHost(preview);
            host = previewHosts.get(preview);
            if (host == null) return false;
        }

        suppressOemDrawable(preview);
        GlassInstaller.installImage(host, hooks.currentConfig());
        GlassDrawable glass = GlassInstaller.get(host);
        if (glass == null) return false;

        Rect plate = backgroundRect(preview);
        if (plate == null || plate.width() <= 0 || plate.height() <= 0) return false;

        if (!createFolder) {
            layoutCaptureHost(preview, host, plate);
        }
        applyPreviewRadius(preview, host, plate);
        glass.setBounds(0, 0, plate.width(), plate.height());
        host.setVisibility(View.INVISIBLE);

        // Create-folder: require an attached DragLayer host; otherwise skip bright fallback.
        if (createFolder && (host.getParent() == null || !host.isAttachedToWindow())) {
            invalidatePreviewDelegate(preview);
            return false;
        }

        int save = canvas.save();
        try {
            canvas.translate(plate.left, plate.top);
            // Never force-capture here. forceCapturePreviewPlate runs root.draw() which
            // re-enters this FolderIcon's dispatchDraw in the same frame (the 2x callsInFrame
            // seen in logcat) and hides the folder (owner.setAlpha(0)) for the capture,
            // producing the disappearing / stacked-glass symptoms. The backdrop is sampled by
            // BackdropCapture.onPreDraw after register() marks the host dirty, so the plate
            // gets a frame one traversal later without re-entering the draw pass.
            // Without a frame yet, GlassDrawable.draw paints its translucent fallback instead
            // of leaving an empty plate (which would look like the folder vanished).
            if (canvas.isHardwareAccelerated()) {
                glass.draw(canvas);
            } else {
                // CellLayout draw passes can hand a software canvas here; AGSL can only run
                // on hardware, which would fall back to GlassDrawable's white translucent
                // rounded rect (white plate under the glass). Render through the HW
                // rasterizer and blit the glass bitmap instead.
                int gw = Math.max(1, plate.width());
                int gh = Math.max(1, plate.height());
                android.graphics.Bitmap rendered = GlassInstaller.renderDrawableHw(glass, gw, gh);
                if (rendered != null && !rendered.isRecycled()) {
                    canvas.drawBitmap(rendered, null,
                            new android.graphics.RectF(0f, 0f, gw, gh),
                            new android.graphics.Paint(
                                    android.graphics.Paint.FILTER_BITMAP_FLAG
                                            | android.graphics.Paint.ANTI_ALIAS_FLAG));
                    if (!rendered.isRecycled()) rendered.recycle();
                }
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        } finally {
            canvas.restoreToCount(save);
        }
    }

    /** True while Workspace mFolderCreateBg is delegated onto CellLayout (no FolderIcon yet). */
    private boolean isCreateFolderPreview(Object preview) {
        if (preview == null) return false;
        if (LegacyLauncherHooks.resolveFolderIcon(preview) != null) return false;
        return HookReflection.field(preview, "mDrawingDelegate") != null;
    }

    /**
     * Park create-folder capture host on DragLayer at the plate's window position.
     * Never addView to CellLayout / ShortcutAndWidgetContainer — they require
     * {@code CellLayout.LayoutParams} and ClassCastException restarts Launcher.
     */
    private void placeCreateFolderCaptureHost(Object preview) {
        if (!hooks.enabled() || preview == null) return;
        if (LegacyLauncherHooks.resolveFolderIcon(preview) != null) return;
        Object delegate = HookReflection.field(preview, "mDrawingDelegate");
        if (!(delegate instanceof ViewGroup)) return;
        ViewGroup cellLayout = (ViewGroup) delegate;
        ViewGroup dragLayer = resolveDragLayer(cellLayout);
        if (dragLayer == null) return;

        ImageView host = ensureCaptureHost(preview);
        if (host == null) return;

        Rect plate = backgroundRect(preview);
        if (plate == null || plate.width() <= 0 || plate.height() <= 0) return;

        int cellX = 0;
        int cellY = 0;
        Object cx = HookReflection.field(preview, "mDelegateCellX");
        Object cy = HookReflection.field(preview, "mDelegateCellY");
        if (cx instanceof Number) cellX = ((Number) cx).intValue();
        if (cy instanceof Number) cellY = ((Number) cy).intValue();
        int[] cellPoint = new int[2];
        if (!cellToPoint(cellLayout, cellX, cellY, cellPoint)) return;

        int[] cellLoc = new int[2];
        int[] dlLoc = new int[2];
        try {
            cellLayout.getLocationInWindow(cellLoc);
            dragLayer.getLocationInWindow(dlLoc);
        } catch (Throwable ignored) {
            return;
        }

        int left = cellLoc[0] + cellPoint[0] + plate.left - dlLoc[0];
        int top = cellLoc[1] + cellPoint[1] + plate.top - dlLoc[1];
        int width = plate.width();
        int height = plate.height();

        try {
            if (host.getParent() != null && host.getParent() != dragLayer) {
                detachCreateFolderCaptureHost(preview);
                host = ensureCaptureHost(preview);
                if (host == null) return;
            }
            if (host.getParent() != dragLayer) {
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, height);
                lp.leftMargin = left;
                lp.topMargin = top;
                dragLayer.addView(host, lp);
            } else {
                ViewGroup.LayoutParams raw = host.getLayoutParams();
                if (raw instanceof FrameLayout.LayoutParams) {
                    FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
                    lp.width = width;
                    lp.height = height;
                    lp.leftMargin = left;
                    lp.topMargin = top;
                    host.setLayoutParams(lp);
                }
            }
            host.setVisibility(View.INVISIBLE);
            host.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            host.layout(left, top, left + width, top + height);
            applyPreviewRadius(preview, host, plate);
            // Sample the create-folder backdrop after the host is attached and laid out.
            // Posting defers the root.draw out of the drawBackground stack (a synchronous
            // capture here hid the whole desktop); the preview-backdrop flag keeps the
            // folder glass out of the sample.
            final ImageView parkHost = host;
            final ViewGroup parkCellLayout = cellLayout;
            final int parkCellX = cellX;
            final int parkCellY = cellY;
            final Rect parkPlate = plate;
            try {
                parkHost.post(() -> {
                    if (parkHost.isAttachedToWindow() && parkHost.getWidth() > 0
                            && parkHost.getHeight() > 0) {
                        int[] cellPointNow = new int[2];
                        if (!cellToPoint(parkCellLayout, parkCellX, parkCellY, cellPointNow)) {
                            return;
                        }
                        // Map the plate's CellLayout-local rect into window space with the
                        // full CellLayout/Workspace transform (scale/scroll). This is the
                        // same space the glass is drawn in. The parked DragLayer host's own
                        // getLocationInWindow misses those transforms, so sampling it drifts
                        // the backdrop toward the bottom-right.
                        android.graphics.Matrix m = new android.graphics.Matrix();
                        parkCellLayout.transformMatrixToGlobal(m);
                        float[] pts = new float[]{
                                parkPlate.left + cellPointNow[0],
                                parkPlate.top + cellPointNow[1],
                                parkPlate.right + cellPointNow[0],
                                parkPlate.bottom + cellPointNow[1]};
                        m.mapPoints(pts);
                        GlassInstaller.forceCapturePreviewPlateAt(parkHost,
                                Math.round(pts[0]), Math.round(pts[1]));
                    }
                });
            } catch (Throwable ignored) { }
        } catch (Throwable e) {
            hooks.log(5, "Os14 placeCreateFolderCaptureHost failed", e);
        }
    }

    private void detachCreateFolderCaptureHost(Object preview) {
        if (preview == null) return;
        ImageView host = previewHosts.get(preview);
        if (host == null) return;
        try {
            GlassInstaller.setOverlaySource(host, null);
        } catch (Throwable ignored) { }
        try {
            ViewGroup parent = host.getParent() instanceof ViewGroup
                    ? (ViewGroup) host.getParent() : null;
            // Only detach DragLayer parks — never remove FolderIcon children here.
            if (parent != null && !LegacyLauncherHooks.isFolderIcon(parent)) {
                parent.removeView(host);
            }
        } catch (Throwable ignored) { }
        try {
            GlassInstaller.uninstall(host);
        } catch (Throwable ignored) { }
        previewHosts.remove(preview);
    }

    private static ViewGroup resolveDragLayer(View from) {
        for (View current = from; current != null; ) {
            if (HookReflection.isClassOrSubclass(current, "com.android.launcher3.dragndrop.DragLayer")
                    || HookReflection.isClassOrSubclass(current, "com.android.launcher3.views.BaseDragLayer")
                    || HookReflection.isClassOrSubclass(current, "com.android.launcher3.OplusDragLayer")) {
                return current instanceof ViewGroup ? (ViewGroup) current : null;
            }
            Object parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    private static void invalidatePreviewDelegate(Object preview) {
        Object delegate = HookReflection.field(preview, "mDrawingDelegate");
        if (delegate instanceof View) {
            ((View) delegate).postInvalidateOnAnimation();
        }
    }

    private void syncFolderPreview(Object folderIcon, Object preview) {
        if (!hooks.enabled() || preview == null) return;
        if (folderIcon != null && LegacyLauncherHooks.isFolderOpen(folderIcon)) {
            ImageView host = previewHosts.get(preview);
            if (host != null) host.setVisibility(View.INVISIBLE);
            return;
        }
        ensureCaptureHost(preview);
        suppressOemDrawable(preview);
        if (folderIcon instanceof View) {
            ((View) folderIcon).invalidate();
            ((View) folderIcon).post(() -> {
                ensureCaptureHost(preview);
                suppressOemDrawable(preview);
                ((View) folderIcon).invalidate();
            });
        }
    }

    void syncFolderIcon(Object folderIcon) {
        Object preview = HookReflection.field(folderIcon, "mBackground");
        if (preview != null) syncFolderPreview(folderIcon, preview);
    }

    private ImageView ensureCaptureHost(Object preview) {
        if (preview == null) return null;
        ImageView existing = previewHosts.get(preview);
        if (existing != null && existing.getTag() == HOST_TAG) {
            GlassInstaller.installImage(existing, hooks.currentConfig());
            return existing;
        }

        Context ctx = null;
        Object ctxObj = HookReflection.field(preview, "mContext");
        if (ctxObj instanceof Context) ctx = (Context) ctxObj;
        Object attach = LegacyLauncherHooks.resolveFolderIcon(preview);
        if (attach == null) attach = HookReflection.field(preview, "mInvalidateDelegate");
        if (attach == null) attach = HookReflection.field(preview, "mDrawingDelegate");
        if (!(attach instanceof ViewGroup)) return null;
        if (ctx == null) ctx = ((View) attach).getContext();
        if (ctx == null) return null;

        ImageView host = new ImageView(ctx);
        host.setTag(HOST_TAG);
        host.setVisibility(View.INVISIBLE);
        host.setClickable(false);
        host.setFocusable(false);
        try {
            ViewGroup parent = (ViewGroup) attach;
            // Resting FolderIcon only. Never addView to CellLayout — it requires
            // CellLayout.LayoutParams and will crash on layout/measure.
            if (LegacyLauncherHooks.isFolderIcon(attach) && host.getParent() == null) {
                parent.addView(host, 0, new FrameLayout.LayoutParams(0, 0));
            } else if (host.getParent() == null && LegacyLauncherHooks.isFolderIcon(attach)) {
                parent.addView(host, 0);
            } else if (host.getParent() == null && !HookReflection.isClassOrSubclass(attach,
                    "com.android.launcher3.CellLayout")
                    && !HookReflection.isClassOrSubclass(attach, "com.android.launcher3.OplusCellLayout")) {
                parent.addView(host, 0);
            }
            // Create-folder delegated to CellLayout: keep host detached; canvas path skips
            // until a FolderIcon owns the preview.
        } catch (Throwable e) {
            hooks.log(5, "Os14 attach capture host failed", e);
            return null;
        }
        previewHosts.put(preview, host);
        GlassInstaller.installImage(host, hooks.currentConfig());
        Rect plate = backgroundRect(preview);
        if (plate != null) layoutCaptureHost(preview, host, plate);
        return host;
    }

    private void layoutCaptureHost(Object preview, ImageView host, Rect plate) {
        if (host == null || plate == null) return;
        Object delegate = HookReflection.field(preview, "mDrawingDelegate");
        ViewGroup parent = host.getParent() instanceof ViewGroup
                ? (ViewGroup) host.getParent() : null;
        int left = plate.left;
        int top = plate.top;
        int right = plate.right;
        int bottom = plate.bottom;
        // When parked on CellLayout, getBackgroundRect is cell-local; offset by cell origin.
        if (delegate instanceof ViewGroup && parent == delegate) {
            int cellX = 0;
            int cellY = 0;
            Object cx = HookReflection.field(preview, "mDelegateCellX");
            Object cy = HookReflection.field(preview, "mDelegateCellY");
            if (cx instanceof Number) cellX = ((Number) cx).intValue();
            if (cy instanceof Number) cellY = ((Number) cy).intValue();
            int[] cellPoint = new int[2];
            if (cellToPoint(delegate, cellX, cellY, cellPoint)) {
                left = cellPoint[0] + plate.left;
                top = cellPoint[1] + plate.top;
                right = cellPoint[0] + plate.right;
                bottom = cellPoint[1] + plate.bottom;
            }
        }
        try {
            host.measure(
                    View.MeasureSpec.makeMeasureSpec(Math.max(1, right - left), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(Math.max(1, bottom - top), View.MeasureSpec.EXACTLY));
            host.layout(left, top, right, bottom);
            // While the folder is pushed to another cell (drop reflow), its host window
            // position changes every frame. Re-sample the backdrop at the new position so
            // the glass shows the new cell content live. This runs inside the preview
            // backdrop-capture flag (see BackdropCapture.capture), so the drawBackground
            // hook returns early and this does not re-enter the folder draw.
            // A detached host (create-folder before it is parked on DragLayer) reports
            // window (0,0) — never sample that or the screen top-left is baked in.
            if (!host.isAttachedToWindow()) return;
            int[] gl = new int[2];
            host.getLocationInWindow(gl);
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastFolderGlassRecapture < 40L) return;
            Integer lastX = folderGlassWindowX.get(host);
            Integer lastY = folderGlassWindowY.get(host);
            if (lastX == null || lastY == null || lastX != gl[0] || lastY != gl[1]) {
                folderGlassWindowX.put(host, gl[0]);
                folderGlassWindowY.put(host, gl[1]);
                lastFolderGlassRecapture = now;
                GlassInstaller.forceCapturePreviewPlate(host);
            }
            logFolderGlassPosition(host, left, top, right, bottom);
        } catch (Throwable ignored) { }
    }

    private final Map<View, Integer> folderGlassWindowX =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<View, Integer> folderGlassWindowY =
            Collections.synchronizedMap(new WeakHashMap<>());
    private long lastFolderGlassRecapture;

    private long lastFolderGlassPosLog;

    private void logFolderGlassPosition(View host, int left, int top, int right, int bottom) {
        try {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastFolderGlassPosLog < 250L) return;
            lastFolderGlassPosLog = now;
            int[] gl = new int[2];
            host.getLocationInWindow(gl);
            hooks.log(3, "folderGlassPos local=" + left + "," + top + "," + right + "," + bottom
                    + " window=" + gl[0] + "," + gl[1]
                    + " hostSize=" + host.getWidth() + "x" + host.getHeight()
                    + " hasFrame=" + GlassInstaller.hasBackdropFrame(host));
        } catch (Throwable ignored) { }
    }

    private void applyPreviewRadius(Object preview, ImageView host, Rect plate) {
        float radius = 0f;
        Object r = HookReflection.field(preview, "mRadius");
        if (r instanceof Number) radius = ((Number) r).floatValue();
        if (radius <= 0f && plate != null) {
            // Match default icon corner when OEM has not set mRadius yet.
            radius = Math.min(plate.width(), plate.height()) * 0.22f;
        }
        Object scale = HookReflection.field(preview, "mScale");
        if (scale instanceof Number) radius *= ((Number) scale).floatValue();
        GlassDrawable glass = GlassInstaller.get(host);
        if (glass != null && radius > 0f) {
            glass.setCornerRadii(radius, radius, radius, radius);
        }
    }

    void suppressOemDrawable(Object preview) {
        if (preview == null) return;
        Object bg = HookReflection.field(preview, "mBgDrawable");
        if (bg instanceof Drawable && bg != TRANSPARENT) {
            savedOemBg.putIfAbsent(preview, (Drawable) bg);
            HookReflection.setField(preview, "mBgDrawable", TRANSPARENT);
        } else if (bg == null) {
            HookReflection.setField(preview, "mBgDrawable", TRANSPARENT);
        }
        // Parent PreviewBackground may still hold an opaque mBgColor for AOSP paint path.
        Object color = HookReflection.field(preview, "mBgColor");
        if (color instanceof Number && ((Number) color).intValue() != Color.TRANSPARENT) {
            HookReflection.setField(preview, "mBgColor", Color.TRANSPARENT);
        }
    }

    private static Rect backgroundRect(Object preview) {
        Object rectObj = HookReflection.invokeNoArgs(preview, "getBackgroundRect");
        if (rectObj instanceof Rect) return new Rect((Rect) rectObj);
        Object bounds = new Rect();
        try {
            Method getBounds = null;
            Class<?> c = preview.getClass();
            while (c != null && getBounds == null) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals("getBounds") && m.getParameterCount() == 1
                            && m.getParameterTypes()[0] == Rect.class) {
                        getBounds = m;
                        break;
                    }
                }
                c = c.getSuperclass();
            }
            if (getBounds != null) {
                getBounds.setAccessible(true);
                getBounds.invoke(preview, bounds);
                return (Rect) bounds;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /** True while this preview is delegated onto a CellLayout (folder-accept). */
    private boolean isPreviewDrawingDelegated(Object preview) {
        return preview != null && HookReflection.field(preview, "mDrawingDelegate") != null;
    }

    private long folderGlassCalls;
    private long folderGlassLastLog;

    /** Rate-limited context for the Cos14 folder glass double-draw diagnosis. */
    private void logFolderGlassCall(Object preview) {
        try {
            folderGlassCalls++;
            long now = android.os.SystemClock.uptimeMillis();
            if (now - folderGlassLastLog < 300L) return;
            folderGlassLastLog = now;
            String hostName = "";
            Object host = LegacyLauncherHooks.resolveFolderIcon(preview);
            if (host instanceof View) hostName = host.getClass().getSimpleName();
            Object fh = HookReflection.field(preview, "mDrawingDelegate");
            boolean delegated = fh != null;
            hooks.log(3, "folderGlass host=" + hostName
                    + " delegated=" + delegated
                    + " capturing=" + GlassInstaller.isPreviewBackdropCapturing()
                    + " calls=" + folderGlassCalls);
        } catch (Throwable ignored) { }
    }

    private static boolean cellToPoint(Object cellLayout, int cellX, int cellY, int[] out) {
        try {
            Method m = null;
            Class<?> c = cellLayout.getClass();
            while (c != null) {
                for (Method cand : c.getDeclaredMethods()) {
                    if (!cand.getName().equals("cellToPoint") || cand.getParameterCount() != 3) {
                        continue;
                    }
                    Class<?>[] p = cand.getParameterTypes();
                    if (p[0] == int.class && p[1] == int.class && p[2] == int[].class) {
                        m = cand;
                        break;
                    }
                }
                if (m != null) break;
                c = c.getSuperclass();
            }
            if (m == null) return false;
            m.setAccessible(true);
            m.invoke(cellLayout, cellX, cellY, out);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
