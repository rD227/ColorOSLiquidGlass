package net.z841973620.colorosliquidglass.hook;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import net.z841973620.colorosliquidglass.glass.DesktopBackdropSampler;
import net.z841973620.colorosliquidglass.glass.GlassDrawable;
import net.z841973620.colorosliquidglass.glass.GlassInstaller;
import net.z841973620.colorosliquidglass.ipc.DesktopBackdropHub;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import static net.z841973620.colorosliquidglass.hook.HookContext.TAG;

/** 文件夹背景及创建/接纳文件夹预览的绘制、半径和生命周期。 */
final class FolderPreviewHooks {
    private final HookContext hooks;
    private final LauncherHooks launcherHooks;

    FolderPreviewHooks(HookContext hooks, LauncherHooks launcherHooks) {
        this.hooks = hooks;
        this.launcherHooks = launcherHooks;
    }

    /** OEM {@code mBgDrawable} swapped out while LiquidGlass owns the preview plate. */
    private static final Map<Object, Drawable> SAVED_PREVIEW_OEM_BG =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final ColorDrawable TRANSPARENT_PREVIEW_BG = new ColorDrawable(Color.TRANSPARENT);
    /**
     * Resting plate radii from {@link GlassInstaller#installImage}/{@code detectRadii} captured
     * at {@code animateToAccept} start. Cancel must restore these — never PreviewBackground
     * {@code mRadius}*{@code mScale}, which looks enlarged vs the desktop default.
     */
    private static final Map<Object, float[]> RESTING_PLATE_RADII =
            Collections.synchronizedMap(new WeakHashMap<>());

    void hookFolderPreviewBackground(ClassLoader cl) {
        final String className = "com.android.launcher3.folder.OplusPreviewBackground";
        try {
            Class<?> c = Class.forName(className, false, cl);
            boolean found = false;
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("setBackground") || m.getParameterCount() != 1
                        || m.isBridge() || m.isSynthetic()) continue;
                found = true;
                hooks.hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        Object host = chain.getArg(0);
                        if (!HookReflection.isClassOrSubclass(host, "com.android.launcher3.folder.FolderIcon")) return result;
                        if (launcherHooks.popupHooks.isFolderPopupOemOnly(host)) return result;
                        launcherHooks.syncFolderPreview(host, chain.getThisObject());
                    } catch (Throwable e) {
                        hooks.log(5, TAG, className + ".setBackground apply failed", e);
                    }
                    return result;
                });
            }
            if (!found) hooks.log(5, TAG, "Method not found: " + className + ".setBackground");

            hooks.after(className, cl, "setRadius", o -> {
                Object host = HookReflection.field(o, "mInvalidateDelegate");
                if (HookReflection.isClassOrSubclass(host, "com.android.launcher3.folder.FolderIcon")) {
                    if (launcherHooks.popupHooks.isFolderPopupOemOnly(host)) return;
                    launcherHooks.syncFolderPreviewDeferred(host, o, false);
                }
            });
            hooks.after(className, cl, "updateBgColorFilter", o -> {
                Object host = HookReflection.field(o, "mInvalidateDelegate");
                if (HookReflection.isClassOrSubclass(host, "com.android.launcher3.folder.FolderIcon")) {
                    if (launcherHooks.popupHooks.isFolderPopupOemOnly(host)) return;
                    launcherHooks.syncFolderPreviewDeferred(host, o, false);
                }
            });

            // Drag-over accept / create-folder: OEM hides mBgView and paints via CellLayout.
            // Keep canvas glass only while delegated; restore FolderIcon plate on rest.
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
                    boolean createFolder = resolvePreviewFolderIcon(preview) == null;
                    // Before OEM grow: remember installImage/detectRadii corners (b73ba87).
                    if (!createFolder && methodName.equals("animateToAccept")) {
                        snapshotRestingPlateRadii(preview);
                    }
                    // CellLayout-parked glass host: OEM clearDrawingDelegate sets VISIBLE and the
                    // plate would remain as a drawn child — hide/detach before proceed.
                    if (methodName.equals("clearDrawingDelegate") && createFolder) {
                        hideAndDetachCreateFolderCaptureHost(preview);
                    }
                    Object result = chain.proceed();
                    try {
                        if (methodName.equals("clearDrawingDelegate")) {
                            if (createFolder) {
                                finishCreateFolderPreviewCleanup(preview);
                            } else {
                                restoreFolderPreviewAfterDelegate(preview);
                                keepHoverFolderGlassVisible(preview);
                                restoreRestingPlateRadii(preview);
                            }
                        } else if (methodName.equals("animateToAccept")
                                || methodName.equals("lambda$animateToAccept$0")
                                || methodName.equals("delegateDrawing")) {
                            placeCreateFolderBgForCapture(preview);
                            keepHoverFolderGlassVisible(preview);
                        }
                        if (DesktopBackdropHub.isLive()) {
                            DesktopBackdropSampler.invalidateCache();
                        }
                    } catch (Throwable e) {
                        hooks.log(5, TAG, className + "." + methodName + " hover glass failed", e);
                    }
                    return result;
                });
            }

            // animateToRest lives on PreviewBackground (not overridden) — cancel create-folder
            // immediately so glass does not linger through the 100ms rest animation / VISIBLE flash.
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
                            if (resolvePreviewFolderIcon(preview) == null) {
                                cleanupCreateFolderPreview(preview);
                            } else {
                                restoreFolderPreviewAfterDelegate(preview);
                                keepHoverFolderGlassVisible(preview);
                                restoreRestingPlateRadii(preview);
                            }
                            if (DesktopBackdropHub.isLive()) {
                                DesktopBackdropSampler.invalidateCache();
                            }
                        } catch (Throwable e) {
                            hooks.log(5, TAG, "PreviewBackground.animateToRest cleanup failed", e);
                        }
                        return result;
                    });
                }
            } catch (Throwable e) {
                hooks.log(5, TAG, "PreviewBackground.animateToRest hook unavailable", e);
            }

            // ColorOS paints translucent folder_icon_bg / LayerBlur via drawBackground while
            // mBgView is hidden. Replace with LiquidGlass and never fall through to OEM tint.
            try {
                Method drawBackground = c.getDeclaredMethod("drawBackground", Canvas.class, View.class);
                hooks.hookOnce(drawBackground, chain -> {
                    if (!hooks.enabled()) return chain.proceed();
                    // Capture re-entrancy: do not paint glass/OEM into the backdrop sample.
                    if (GlassInstaller.isPreviewBackdropCapturing()) return null;
                    Object preview = chain.getThisObject();
                    // Folder options-menu: full OEM long-press plate (no glass).
                    if (launcherHooks.popupHooks.isFolderPopupOemOnly(resolvePreviewFolderIcon(preview))) {
                        return chain.proceed();
                    }
                    try {
                        suppressOemPreviewDrawable(preview);
                        if (drawPreviewGlassOnCanvas(preview, (Canvas) chain.getArg(0))) {
                            return null;
                        }
                    } catch (Throwable e) {
                        hooks.log(5, TAG, "OplusPreviewBackground.drawBackground glass failed", e);
                    }
                    // Prefer empty plate over OEM translucent folder_icon_bg brightening glass.
                    suppressOemPreviewDrawable(preview);
                    return null;
                });
            } catch (Throwable e) {
                hooks.log(5, TAG, "OplusPreviewBackground.drawBackground hook unavailable", e);
            }

            // Resize-frame / convert path hides mBgView and draws LayerBlurDrawable via
            // drawBackground(Canvas, ConvertBgParams, ItemInfo). Replace that with glass.
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("drawBackground") || m.getParameterCount() != 3
                        || m.isBridge() || m.isSynthetic()) continue;
                if (m.getParameterTypes()[0] != Canvas.class) continue;
                hooks.hookOnce(m, chain -> {
                    if (!hooks.enabled()) return chain.proceed();
                    try {
                        Object preview = chain.getThisObject();
                        Object host = HookReflection.field(preview, "mInvalidateDelegate");
                        if (!HookReflection.isClassOrSubclass(host, "com.android.launcher3.folder.FolderIcon")) {
                            return chain.proceed();
                        }
                        if (drawGlassForConvertParams(preview, (Canvas) chain.getArg(0),
                                chain.getArg(1), host)) {
                            return null;
                        }
                    } catch (Throwable e) {
                        hooks.log(5, TAG, className + ".drawBackground(ConvertBgParams) apply failed", e);
                    }
                    return chain.proceed();
                });
            }
        } catch (Throwable e) {
            hooks.log(5, TAG, "Class unavailable: " + className, e);
        }
    }

    /**
     * Draws LiquidGlass using ConvertBgParams geometry used while the resize frame is active.
     * Relayouts mBgView to the animated rect so backdrop sampling tracks the intermediate size.
     * @return true if glass replaced the OEM LayerBlurDrawable draw
     */
    private boolean drawGlassForConvertParams(Object preview, Canvas canvas, Object params, Object host) {
        if (canvas == null || params == null || !(host instanceof View folderIcon)) return false;
        Object bgView = HookReflection.field(preview, "mBgView");
        if (!(bgView instanceof ImageView image)) return false;
        GlassInstaller.installImage(image, hooks.currentConfig());
        GlassDrawable glass = GlassInstaller.get(image);
        if (glass == null) return false;

        Object xObj = HookReflection.invokeNoArgs(params, "getX");
        Object yObj = HookReflection.invokeNoArgs(params, "getY");
        Object sizeXObj = HookReflection.invokeNoArgs(params, "getSizeX");
        Object sizeYObj = HookReflection.invokeNoArgs(params, "getSizeY");
        if (!(xObj instanceof Number) || !(yObj instanceof Number)
                || !(sizeXObj instanceof Number) || !(sizeYObj instanceof Number)) {
            return false;
        }
        float x = ((Number) xObj).floatValue();
        float y = ((Number) yObj).floatValue();
        int sizeX = ((Number) sizeXObj).intValue();
        int sizeY = ((Number) sizeYObj).intValue();
        if (sizeX <= 0 || sizeY <= 0) return false;

        int left;
        int top = Math.round(y);
        int right;
        int bottom = Math.round(y + sizeY);
        if (folderIcon.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
            left = Math.round((folderIcon.getWidth() - sizeX) - x);
            right = folderIcon.getWidth() - Math.round(x);
        } else {
            left = Math.round(x);
            right = Math.round(x + sizeX);
        }
        if (right <= left || bottom <= top) return false;

        // Match OEM LayerBlurDrawable bounds: lay out mBgView to the animated rect so
        // BackdropCapture samples the correct intermediate area each frame.
        // Corner radii stay whatever installImage/detectRadii already set (b73ba87 contract).
        image.measure(
                View.MeasureSpec.makeMeasureSpec(right - left, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(bottom - top, View.MeasureSpec.EXACTLY));
        image.layout(left, top, right, bottom);
        glass.setBounds(0, 0, right - left, bottom - top);
        GlassInstaller.forceCapture(image);

        int save = canvas.save();
        canvas.translate(left, top);
        glass.draw(canvas);
        canvas.restoreToCount(save);
        return true;
    }

    /**
     * Create-folder / folder-accept: OEM hides {@code mBgView} and paints via CellLayout
     * {@code drawBackground}. Install glass for that canvas path — never force mBgView
     * VISIBLE while delegated or while OEM hid it (that shows folder plate + canvas glass).
     * Never {@code layout(0,0)} an attached FolderIcon plate (that shifts glass to top-left).
     * Create-folder parks {@code mBgView} on CellLayout for capture; detach before FolderIcon
     * takes ownership (see {@link #preparePreviewTransferToFolder}).
     */
    void keepHoverFolderGlassVisible(Object previewBackground) {
        if (!hooks.enabled() || previewBackground == null) return;
        ensurePreviewGlassInstalled(previewBackground);
        Object folderHost = resolvePreviewFolderIcon(previewBackground);
        if (folderHost != null && LauncherHooks.isFolderOpen(folderHost)) return;
        if (launcherHooks.popupHooks.isFolderPopupOemOnly(folderHost)) return;

        Object bgView = HookReflection.field(previewBackground, "mBgView");
        boolean delegated = isPreviewDrawingDelegated(previewBackground);
        if (delegated) {
            placeCreateFolderBgForCapture(previewBackground);
            suppressOemPreviewDrawable(previewBackground);
            // Folder accept: keep OEM-hidden plate hidden — canvas glass only.
            if (folderHost != null && bgView instanceof View) {
                ((View) bgView).setVisibility(View.INVISIBLE);
            }
            Object delegate = HookReflection.field(previewBackground, "mDrawingDelegate");
            if (delegate instanceof View) ((View) delegate).invalidate();
            Object invalidate = HookReflection.field(previewBackground, "mInvalidateDelegate");
            if (invalidate instanceof View) ((View) invalidate).invalidate();
            Object createHost = HookReflection.field(previewBackground, "mHostView");
            if (createHost instanceof View) ((View) createHost).invalidate();
            if (DesktopBackdropHub.isLive()) {
                DesktopBackdropSampler.invalidateCache();
            }
            return;
        }

        // animateToAccept hides mBgView before delegateDrawing runs — do not fight OEM.
        if (bgView instanceof View && ((View) bgView).getVisibility() != View.VISIBLE) {
            Object delegate = HookReflection.field(previewBackground, "mDrawingDelegate");
            if (delegate instanceof View) ((View) delegate).invalidate();
            return;
        }

        // Resting FolderIcon: keep attached mBgView visible with glass.
        if (bgView instanceof View && ((View) bgView).getParent() != null
                && folderHost != null) {
            View view = (View) bgView;
            view.setVisibility(View.VISIBLE);
            if (GlassInstaller.get(view) != null) {
                GlassInstaller.forceCapture(view);
                view.invalidate();
            }
        }
        if (folderHost instanceof View) ((View) folderHost).invalidate();
    }

    private static boolean isPreviewDrawingDelegated(Object previewBackground) {
        Object delegated = HookReflection.invokeNoArgs(previewBackground, "drawingDelegated");
        if (delegated instanceof Boolean) return (Boolean) delegated;
        return HookReflection.field(previewBackground, "mDrawingDelegate") != null;
    }

    private static Object resolvePreviewFolderIcon(Object previewBackground) {
        Object host = HookReflection.field(previewBackground, "mInvalidateDelegate");
        if (HookReflection.isClassOrSubclass(host, "com.android.launcher3.folder.FolderIcon")) return host;
        Object createHost = HookReflection.field(previewBackground, "mHostView");
        if (HookReflection.isClassOrSubclass(createHost, "com.android.launcher3.folder.FolderIcon")) {
            return createHost;
        }
        return null;
    }

    /**
     * Install LiquidGlass on {@code OplusPreviewBackground.mBgView}. Create-folder parks the
     * ImageView on CellLayout for correct backdrop sampling; FolderIcon plates
     * keep OEM FrameLayout margins (never re-layout here).
     */
    private void ensurePreviewGlassInstalled(Object previewBackground) {
        if (!hooks.enabled() || previewBackground == null) return;
        Object folderHost = resolvePreviewFolderIcon(previewBackground);
        if (folderHost != null && LauncherHooks.isFolderOpen(folderHost)) return;
        if (launcherHooks.popupHooks.isFolderPopupOemOnly(folderHost)) return;

        Object bgView = HookReflection.field(previewBackground, "mBgView");
        if (!(bgView instanceof ImageView)) {
            try {
                Object ctx = HookReflection.field(previewBackground, "mContext");
                Object attachHost = folderHost != null ? folderHost
                        : HookReflection.field(previewBackground, "mInvalidateDelegate");
                if (ctx instanceof android.content.Context && attachHost instanceof View) {
                    HookReflection.invoke(previewBackground, "initBgViewIfNeed",
                            new Class<?>[]{android.content.Context.class, View.class},
                            ctx, attachHost);
                }
            } catch (Throwable ignored) { }
            bgView = HookReflection.field(previewBackground, "mBgView");
        }
        if (!(bgView instanceof ImageView)) {
            bgView = createDetachedPreviewBgView(previewBackground);
        }
        if (!(bgView instanceof ImageView)) return;
        ImageView image = (ImageView) bgView;
        // FolderIcon only: ensure child exists. Never re-layout resting / accept plates here.
        if (folderHost instanceof ViewGroup && image.getParent() == null) {
            try {
                ((ViewGroup) folderHost).addView(image, 0);
                invokeSetBackground(previewBackground, folderHost);
            } catch (Throwable ignored) { }
        }
        GlassInstaller.installImage(image, hooks.currentConfig());
        // b73ba87: keep installImage/detectRadii corners. Do NOT write PreviewBackground.mRadius
        // (or mRadius*mScale) — that looks enlarged vs the desktop default and sticks after cancel.
        if (image.getDrawable() != null) image.setImageDrawable(null);
        suppressOemPreviewDrawable(previewBackground);
    }

    private ImageView createDetachedPreviewBgView(Object previewBackground) {
        try {
            Object ctx = HookReflection.field(previewBackground, "mContext");
            if (!(ctx instanceof android.content.Context)) return null;
            float radius = 0f;
            Object r = HookReflection.field(previewBackground, "mRadius");
            if (r instanceof Number) radius = ((Number) r).floatValue();
            Class<?> cls = Class.forName("com.android.launcher3.folder.FolderRoundImageView",
                    false, previewBackground.getClass().getClassLoader());
            Object created = cls.getConstructor(float.class, android.content.Context.class)
                    .newInstance(radius, ctx);
            if (created instanceof ImageView) {
                HookReflection.setField(previewBackground, "mBgView", created);
                return (ImageView) created;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /**
     * Park create-folder {@code mBgView} on {@code CellLayout} at cell-local plate coords so
     * {@link GlassInstaller#forceCapture} samples the same screen region CellLayout paints.
     * DragLayer / detached {@code layout(0,0)} sampled the wrong region (top-left) and looked
     * bright. Detach before {@code FolderIcon.setPreviewBackground} (see preparePreviewTransfer).
     * CellLayout.onLayout only lays out ShortcutsAndWidgets, so this direct child keeps
     * manual {@code layout()}.
     */
    private void placeCreateFolderBgForCapture(Object preview) {
        if (preview == null) return;
        if (resolvePreviewFolderIcon(preview) != null) return;
        if (!isPreviewDrawingDelegated(preview)) return;
        Object bgView = HookReflection.field(preview, "mBgView");
        Object delegate = HookReflection.field(preview, "mDrawingDelegate");
        if (!(bgView instanceof ImageView) || !(delegate instanceof ViewGroup)) return;
        ImageView image = (ImageView) bgView;
        ViewGroup cellLayout = (ViewGroup) delegate;
        try {
            if (image.getParent() != cellLayout) {
                detachCreateFolderCaptureHost(preview);
                cellLayout.addView(image);
            }
            repositionCreateFolderBgIfParked(preview);
        } catch (Throwable ignored) {
            cellLayout.post(() -> {
                try {
                    if (!isPreviewDrawingDelegated(preview)) return;
                    placeCreateFolderBgForCapture(preview);
                    cellLayout.invalidate();
                } catch (Throwable ignored2) { }
            });
        }
    }

    /** Update capture-host layout when already parented to CellLayout (safe during onDraw). */
    private void repositionCreateFolderBgIfParked(Object preview) {
        if (preview == null || resolvePreviewFolderIcon(preview) != null) return;
        Object bgView = HookReflection.field(preview, "mBgView");
        Object delegate = HookReflection.field(preview, "mDrawingDelegate");
        if (!(bgView instanceof ImageView) || !(delegate instanceof ViewGroup)) return;
        ImageView image = (ImageView) bgView;
        ViewGroup cellLayout = (ViewGroup) delegate;
        if (image.getParent() != cellLayout) return;

        Object rectObj = HookReflection.invokeNoArgs(preview, "getBackgroundRect");
        if (!(rectObj instanceof android.graphics.Rect)) return;
        android.graphics.Rect r = (android.graphics.Rect) rectObj;
        if (r.width() <= 0 || r.height() <= 0) return;

        int cellX = 0;
        int cellY = 0;
        Object cx = HookReflection.field(preview, "mDelegateCellX");
        Object cy = HookReflection.field(preview, "mDelegateCellY");
        if (cx instanceof Number) cellX = ((Number) cx).intValue();
        if (cy instanceof Number) cellY = ((Number) cy).intValue();
        int[] cellPoint = new int[2];
        if (!cellToPoint(cellLayout, cellX, cellY, cellPoint)) return;

        try {
            // Same coordinate space as CellLayout.onDraw: translate(cellPoint) + getBackgroundRect.
            image.setVisibility(View.INVISIBLE);
            int left = cellPoint[0] + r.left;
            int top = cellPoint[1] + r.top;
            image.measure(
                    View.MeasureSpec.makeMeasureSpec(r.width(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(r.height(), View.MeasureSpec.EXACTLY));
            image.layout(left, top, left + r.width(), top + r.height());
            GlassDrawable glass = GlassInstaller.get(image);
            if (glass != null) glass.setBounds(0, 0, r.width(), r.height());
            applyPreviewRadius(preview, image);
        } catch (Throwable ignored) { }
    }

    private static boolean cellToPoint(View cellLayout, int cellX, int cellY, int[] out) {
        if (out == null || out.length < 2) return false;
        for (Class<?> c = cellLayout.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Method method = c.getDeclaredMethod("cellToPoint", int.class, int.class, int[].class);
                method.setAccessible(true);
                method.invoke(cellLayout, cellX, cellY, out);
                return true;
            } catch (Throwable ignored) { }
        }
        return false;
    }

    private void applyPreviewRadius(Object preview, ImageView image) {
        // Create-folder park host only. FolderIcon resting corners stay on installImage/detectRadii.
        GlassDrawable glass = GlassInstaller.get(image);
        if (glass == null) return;
        float radius = 0f;
        Object rv = HookReflection.field(preview, "mRadius");
        if (rv instanceof Number) radius = ((Number) rv).floatValue();
        // Never multiply mScale — accept enlarge would bake oversized corners into glass.
        if (radius > 0f) glass.setCornerRadii(radius, radius, radius, radius);
    }

    /** Read-only: remember detectRadii plate corners before accept grow. */
    private void snapshotRestingPlateRadii(Object preview) {
        if (preview == null) return;
        try {
            Object bg = HookReflection.field(preview, "mBgView");
            if (!(bg instanceof ImageView)) return;
            GlassDrawable glass = GlassInstaller.get((ImageView) bg);
            if (glass == null) return;
            float[] radii = glass.getCornerRadii();
            if (radii == null || radii.length < 4) return;
            RESTING_PLATE_RADII.put(preview, radii.clone());
        } catch (Throwable ignored) { }
    }

    /** Put back installImage/detectRadii corners after accept cancel (not mRadius). */
    private void restoreRestingPlateRadii(Object preview) {
        if (preview == null || resolvePreviewFolderIcon(preview) == null) return;
        float[] snap = RESTING_PLATE_RADII.get(preview);
        if (snap == null || snap.length < 4) return;
        Object bg = HookReflection.field(preview, "mBgView");
        if (!(bg instanceof ImageView)) return;
        ImageView image = (ImageView) bg;
        Runnable apply = () -> {
            try {
                GlassDrawable glass = GlassInstaller.get(image);
                if (glass == null) return;
                float[] radii = RESTING_PLATE_RADII.get(preview);
                if (radii == null || radii.length < 4) radii = snap;
                glass.setCornerRadii(radii[0], radii[1], radii[2], radii[3]);
                image.invalidate();
            } catch (Throwable ignored) { }
        };
        apply.run();
        image.post(apply);
    }

    /**
     * Replace OEM folder_icon_bg / LayerBlur with a transparent placeholder.
     * {@code animateToAccept → setBlurAlpha(0)} sets {@code mDefaultAlpha=1} and would paint a
     * bright translucent fill if the drawable remained installed.
     */
    private static void suppressOemPreviewDrawable(Object preview) {
        if (preview == null) return;
        Object current = HookReflection.field(preview, "mBgDrawable");
        if (current != TRANSPARENT_PREVIEW_BG && current instanceof Drawable) {
            SAVED_PREVIEW_OEM_BG.putIfAbsent(preview, (Drawable) current);
            HookReflection.setField(preview, "mBgDrawable", TRANSPARENT_PREVIEW_BG);
        }
        Object bgView = HookReflection.field(preview, "mBgView");
        if (bgView instanceof ImageView) {
            ImageView image = (ImageView) bgView;
            Drawable imageDrawable = image.getDrawable();
            if (imageDrawable != null && !(imageDrawable instanceof GlassDrawable)
                    && imageDrawable != TRANSPARENT_PREVIEW_BG) {
                image.setImageDrawable(null);
            }
        }
    }

    static void restoreOemPreviewDrawable(Object preview) {
        if (preview == null) return;
        Drawable saved = SAVED_PREVIEW_OEM_BG.remove(preview);
        if (saved != null) HookReflection.setField(preview, "mBgDrawable", saved);
    }

    private static void invokeSetBackground(Object preview, Object folderHost) {
        for (Class<?> c = preview.getClass(); c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("setBackground") || m.getParameterCount() != 1) continue;
                try {
                    m.setAccessible(true);
                    m.invoke(preview, folderHost);
                    return;
                } catch (Throwable ignored) { }
            }
        }
    }

    /** Detach create-folder capture host from DragLayer / CellLayout (never FolderIcon). */
    private void detachCreateFolderCaptureHost(Object preview) {
        if (preview == null) return;
        if (resolvePreviewFolderIcon(preview) != null) return;
        Object bgView = HookReflection.field(preview, "mBgView");
        if (!(bgView instanceof View)) return;
        View image = (View) bgView;
        if (!(image.getParent() instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) image.getParent();
        String pname = parent.getClass().getSimpleName();
        if (pname != null && pname.contains("FolderIcon")) return;
        try {
            parent.removeView(image);
        } catch (Throwable ignored) { }
    }

    /** Hide + detach before OEM clearDrawingDelegate sets the CellLayout park host VISIBLE. */
    private void hideAndDetachCreateFolderCaptureHost(Object preview) {
        if (preview == null || resolvePreviewFolderIcon(preview) != null) return;
        Object bgView = HookReflection.field(preview, "mBgView");
        if (bgView instanceof View) {
            try {
                ((View) bgView).setVisibility(View.INVISIBLE);
            } catch (Throwable ignored) { }
        }
        detachCreateFolderCaptureHost(preview);
    }

    /**
     * After create-folder delegate is cleared: ensure park host is gone, glass uninstalled, and
     * CellLayout invalidated so no residual plate remains.
     */
    private void finishCreateFolderPreviewCleanup(Object preview) {
        if (preview == null) return;
        if (resolvePreviewFolderIcon(preview) != null) return;
        if (preview == activeCreateFolderPreview) activeCreateFolderPreview = null;
        hideAndDetachCreateFolderCaptureHost(preview);
        Object bgView = HookReflection.field(preview, "mBgView");
        if (bgView instanceof View) {
            View image = (View) bgView;
            try {
                image.setVisibility(View.INVISIBLE);
            } catch (Throwable ignored) { }
            if (GlassInstaller.get(image) != null) {
                GlassInstaller.uninstall(image);
            }
        }
        restoreOemPreviewDrawable(preview);
        Object delegate = HookReflection.field(preview, "mDrawingDelegate");
        if (delegate instanceof View) ((View) delegate).invalidate();
        Object invalidate = HookReflection.field(preview, "mInvalidateDelegate");
        if (invalidate instanceof View) ((View) invalidate).invalidate();
        if (DesktopBackdropHub.isLive()) {
            DesktopBackdropSampler.invalidateCache();
        }
    }

    /**
     * Cancel create-folder preview: clear CellLayout delegate drawing and detach park host.
     * OEM onDragEnd often nulls {@code mGroupCreatePreviewBg} without clearDrawingDelegate.
     * Move-away cancel uses animateToRest → this path must remove glass immediately.
     */
    private void cleanupCreateFolderPreview(Object preview) {
        if (preview == null) return;
        if (resolvePreviewFolderIcon(preview) != null) return;
        hideAndDetachCreateFolderCaptureHost(preview);
        try {
            HookReflection.invokeNoArgs(preview, "clearDrawingDelegate");
        } catch (Throwable ignored) {
            try {
                HookReflection.invoke(preview, "clearDrawingDelegate", new Class<?>[]{}, new Object[]{});
            } catch (Throwable ignored2) { }
        }
        // clearDrawingDelegate hook also finishes cleanup; call again in case hook missed.
        finishCreateFolderPreviewCleanup(preview);
    }

    /** Before FolderIcon takes ownership of the create-folder PreviewBackground. */
    private void preparePreviewTransferToFolder(Object preview) {
        if (preview == null) return;
        detachCreateFolderCaptureHost(preview);
        try {
            HookReflection.invokeNoArgs(preview, "clearDrawingDelegate");
        } catch (Throwable ignored) { }
        restoreOemPreviewDrawable(preview);
        Object bgView = HookReflection.field(preview, "mBgView");
        if (bgView instanceof View && ((View) bgView).getParent() != null
                && resolvePreviewFolderIcon(preview) == null) {
            // Still parented somewhere unexpected — force detach.
            detachCreateFolderCaptureHost(preview);
            if (((View) bgView).getParent() instanceof ViewGroup) {
                try {
                    ((ViewGroup) ((View) bgView).getParent()).removeView((View) bgView);
                } catch (Throwable ignored) { }
            }
        }
    }

    /**
     * After accept ends: restore FolderIcon plate layout via OEM {@code setBackground},
     * and detach create-folder capture host from CellLayout.
     */
    private void restoreFolderPreviewAfterDelegate(Object preview) {
        if (preview == null) return;
        Object folderHost = resolvePreviewFolderIcon(preview);
        Object bgView = HookReflection.field(preview, "mBgView");

        if (folderHost == null) {
            finishCreateFolderPreviewCleanup(preview);
            return;
        }

        if (folderHost instanceof View) {
            View icon = (View) folderHost;
            restoreOemPreviewDrawable(preview);
            try {
                Class<?> igroup = Class.forName(
                        "com.android.launcher3.stack.view.IGroupView",
                        false, preview.getClass().getClassLoader());
                HookReflection.invoke(preview, "setBackground", new Class<?>[]{igroup}, folderHost);
            } catch (Throwable ignored) {
                try {
                    invokeSetBackground(preview, folderHost);
                } catch (Throwable ignored2) { }
            }
            LauncherHooks.setFolderBackgroundVisibility(preview, true);
            launcherHooks.syncFolderPreview(folderHost, preview);
            restoreRestingPlateRadii(preview);
            icon.requestLayout();
            icon.post(() -> {
                LauncherHooks.setFolderBackgroundVisibility(preview, true);
                launcherHooks.syncFolderPreview(folderHost, preview);
                restoreRestingPlateRadii(preview);
                if (bgView instanceof View) {
                    ((View) bgView).setVisibility(View.VISIBLE);
                    ((View) bgView).invalidate();
                }
                icon.invalidate();
            });
        }
        if (DesktopBackdropHub.isLive()) {
            DesktopBackdropSampler.invalidateCache();
        }
    }

    /** Draw installed preview glass into a CellLayout delegated canvas (create-folder / accept). */
    private boolean drawPreviewGlassOnCanvas(Object preview, Canvas canvas) {
        if (canvas == null || preview == null) return false;
        // After cancel / clearDrawingDelegate, never re-park or paint residual glass.
        if (!isPreviewDrawingDelegated(preview)) return false;
        ensurePreviewGlassInstalled(preview);
        boolean createFolder = resolvePreviewFolderIcon(preview) == null;
        if (createFolder) {
            // Must be parked on CellLayout at the plate before capture — detached layout(0,0)
            // samples screen top-left and looks bright (folder-accept path never hits this).
            placeCreateFolderBgForCapture(preview);
        }
        repositionCreateFolderBgIfParked(preview);
        suppressOemPreviewDrawable(preview);
        Object bgView = HookReflection.field(preview, "mBgView");
        if (!(bgView instanceof ImageView)) return false;
        ImageView image = (ImageView) bgView;
        GlassDrawable glass = GlassInstaller.get(image);
        if (glass == null) return false;
        Object rectObj = HookReflection.invokeNoArgs(preview, "getBackgroundRect");
        if (!(rectObj instanceof android.graphics.Rect)) return false;
        android.graphics.Rect r = (android.graphics.Rect) rectObj;
        if (r.width() <= 0 || r.height() <= 0) return false;

        if (createFolder && image.getParent() == null) {
            // No safe capture host yet — skip rather than paint bright AGSL-fallback / wrong sample.
            return false;
        }
        // FolderIcon: never image.layout(...) — that shifted plates to top-left.
        if (!createFolder && image.getParent() == null) {
            return false;
        }
        glass.setBounds(0, 0, r.width(), r.height());
        // Keep permanent corners on installImage/detectRadii (or accept-start snapshot).
        // Temporarily scale only for this canvas frame so OEM enlarge roundness matches;
        // never leave mRadius*mScale baked into the resting plate.
        float[] permanent = RESTING_PLATE_RADII.get(preview);
        if (permanent == null || permanent.length < 4) {
            try {
                permanent = glass.getCornerRadii();
            } catch (Throwable ignored) {
                permanent = null;
            }
        }
        float scale = 1f;
        Object scaleObj = HookReflection.field(preview, "mScale");
        if (scaleObj instanceof Number) scale = ((Number) scaleObj).floatValue();
        if (permanent != null && permanent.length >= 4 && scale > 1.001f) {
            glass.setCornerRadii(permanent[0] * scale, permanent[1] * scale,
                    permanent[2] * scale, permanent[3] * scale);
        }

        Object folderHost = resolvePreviewFolderIcon(preview);
        if (folderHost != null && isPreviewDrawingDelegated(preview)) {
            image.setVisibility(View.INVISIBLE);
        }

        int save = canvas.save();
        try {
            canvas.translate(r.left, r.top);
            // Skip re-entrant drawBackground while sampling — otherwise glass/fallback is baked
            // into the backdrop and the plate looks like a bright translucent OEM tint.
            GlassInstaller.forceCapturePreviewPlate(image);
            // Without a backdrop frame, GlassDrawable paints a bright translucent fallback.
            if (!GlassInstaller.hasBackdropFrame(image)) return false;
            glass.draw(canvas);
            return true;
        } catch (Throwable ignored) {
            return false;
        } finally {
            canvas.restoreToCount(save);
            if (permanent != null && permanent.length >= 4) {
                glass.setCornerRadii(permanent[0], permanent[1], permanent[2], permanent[3]);
            }
        }
    }

    void hookCreateFolderPreviewGlass(ClassLoader cl) {
        // BubbleTextView / ShortcutContainer receive the shared create-folder PreviewBackground.
        String[] hosts = {
                "com.android.launcher3.BubbleTextView",
                "com.android.launcher3.OplusBubbleTextView",
                "com.android.launcher3.viewcontainer.ShortcutContainer"
        };
        for (String className : hosts) {
            try {
                Class<?> c = Class.forName(className, false, cl);
                for (Method m : c.getDeclaredMethods()) {
                    if (!m.getName().equals("setFolderBG") || m.isBridge() || m.isSynthetic()) {
                        continue;
                    }
                    hooks.hookOnce(m, chain -> {
                        // Read previous before OEM clears it — cancel path passes null without
                        // always calling clearDrawingDelegate (see OplusWorkspace.onDragEnd).
                        Object self = chain.getThisObject();
                        Object before = HookReflection.field(self, "mFolderPreviewBackGround");
                        if (before == null) before = HookReflection.field(self, "previewBackground");
                        Object bg = chain.getArg(0);
                        Object result = chain.proceed();
                        try {
                            if (bg == null) {
                                cleanupCreateFolderPreview(before != null ? before : activeCreateFolderPreview);
                            } else {
                                activeCreateFolderPreview = bg;
                                ensurePreviewGlassInstalled(bg);
                                placeCreateFolderBgForCapture(bg);
                                keepHoverFolderGlassVisible(bg);
                            }
                        } catch (Throwable e) {
                            hooks.log(5, TAG, className + ".setFolderBG glass failed", e);
                        }
                        return result;
                    });
                }
            } catch (Throwable e) {
                hooks.log(5, TAG, "Class unavailable: " + className, e);
            }
        }

        // Folder create reuses the create-folder PreviewBackground — must detach any temp
        // CellLayout park before FolderIcon.addView(mBgView), else launcher crashes.
        try {
            Class<?> folderIcon = Class.forName("com.android.launcher3.folder.FolderIcon", false, cl);
            for (Method m : folderIcon.getDeclaredMethods()) {
                if (!m.getName().equals("setPreviewBackground") || m.isBridge() || m.isSynthetic()) {
                    continue;
                }
                hooks.hookOnce(m, chain -> {
                    Object preview = chain.getArg(0);
                    try {
                        preparePreviewTransferToFolder(preview);
                    } catch (Throwable e) {
                        hooks.log(5, TAG, "FolderIcon.setPreviewBackground prepare failed", e);
                    }
                    Object result = chain.proceed();
                    try {
                        Object host = chain.getThisObject();
                        if (preview != null && host instanceof View) {
                            activeCreateFolderPreview = null;
                            invokeSetBackground(preview, host);
                            LauncherHooks.setFolderBackgroundVisibility(preview, true);
                            launcherHooks.syncFolderPreview(host, preview);
                            ((View) host).requestLayout();
                            ((View) host).invalidate();
                        }
                    } catch (Throwable e) {
                        hooks.log(5, TAG, "FolderIcon.setPreviewBackground glass failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            hooks.log(5, TAG, "FolderIcon.setPreviewBackground hook unavailable", e);
        }
    }

    /** Shared create-folder PreviewBackground currently shown (for cancel cleanup). */
    private volatile Object activeCreateFolderPreview;
}
