package net.z841973620.colorosliquidglass.hook;

import android.animation.ObjectAnimator;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

import net.z841973620.colorosliquidglass.GlassConfig;
import net.z841973620.colorosliquidglass.glass.BehindDisplayCapture;
import net.z841973620.colorosliquidglass.glass.DesktopBackdropSampler;
import net.z841973620.colorosliquidglass.glass.GlassDrawable;
import net.z841973620.colorosliquidglass.glass.GlassInstaller;
import net.z841973620.colorosliquidglass.glass.LauncherAshmemMode;
import net.z841973620.colorosliquidglass.glass.NotificationCardGlass;
import net.z841973620.colorosliquidglass.glass.PopupCaptureTrace;
import net.z841973620.colorosliquidglass.glass.NotificationPlateDrawable;
import net.z841973620.colorosliquidglass.glass.HeadsUpState;
import net.z841973620.colorosliquidglass.glass.TaskContentOverlay;
import net.z841973620.colorosliquidglass.glass.WallpaperScaleTracker;
import net.z841973620.colorosliquidglass.glass.VolumePanelGlass;
import net.z841973620.colorosliquidglass.ipc.DesktopBackdropClient;
import net.z841973620.colorosliquidglass.ipc.DesktopBackdropHub;

import java.lang.ref.WeakReference;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ModuleMain extends XposedModule {
    private static final String TAG = "ColorOSLiquidGlass";
    private final Set<Executable> hooked = new HashSet<>();
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
    private SharedPreferences remotePreferences;
    /**
     * FolderIcons currently showing the options menu — OEM long-press visuals only (no plate glass,
     * no menu glass). Prevents corner-radius corruption after cancel.
     */
    private final Set<Object> folderPopupOemOnly =
            Collections.newSetFromMap(new WeakHashMap<>());
    /** True while a folder DragView is active; also used with TOGGLE_BAR blur suppression. */
    private volatile boolean folderDragActive;
    /** True from Folder.animateOpen until close; suppresses wallpaper depth blur. */
    private volatile boolean folderOpenActive;
    /** True while page-indicator press-drag expands the frosted pill behind the dots. */
    private volatile boolean pageIndicatorFrameActive;
    /**
     * After leaving widget / PAGE_PREVIEW, the indicator pill keeps fading (and may shrink when
     * the blank drop-target page is removed). Hold glass through that window so OEM LayerBlur /
     * frosted paint cannot flash on an already-NORMAL desktop.
     */
    private volatile boolean pageIndicatorExitHold;
    private View pageIndicatorExitReleaseTarget;
    private Runnable pageIndicatorExitRelease;
    /** Bumps when a new unlock/resume refresh burst starts so older delayed posts are ignored. */
    private volatile int unlockGlassRefreshToken;
    /** Delayed float-menu glass applies — cancelled on dismiss so they cannot reopen ashmem. */
    private final java.util.ArrayList<Runnable> pendingFloatMenuGlass = new java.util.ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** ColorOS 13/14 backend; null when using the built-in ColorOS 15+ Hook path. */
    private Os14HookBackend legacyBackend;
    @Override public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        String pkg = param.getPackageName();
        if (!pkg.equals("com.android.launcher") && !pkg.equals("com.android.systemui")) return;
        try {
            remotePreferences = getRemotePreferences(GlassConfig.PREFS);
            GlassConfig loaded = GlassConfig.read(remotePreferences);
            log(4, TAG, "package=" + pkg + " first=" + param.isFirstPackage()
                    + " enabled=" + loaded.enabled
                    + " updatedAt=" + remotePreferences.getLong("updated_at", 0L));
        } catch (Throwable e) {
            remotePreferences = null;
            log(5, TAG, "Cannot read remote preferences; defaults are used", e);
        }
        // Do not return for !isFirstPackage or enabled=false. ColorOS may expose the useful
        // ClassLoader in a later callback, and hooks must already exist when the next config is read.
        ClassLoader cl = param.getDefaultClassLoader();
        ColorOsVersion.Flavor flavor = ColorOsVersion.detect(cl);
        boolean useLegacy = ColorOsVersion.usesLegacyLauncherApis(cl);
        log(4, TAG, "ColorOS flavor=" + flavor
                + " major=" + ColorOsVersion.majorVersion()
                + " useLegacyApis=" + useLegacy);
        if (pkg.equals("com.android.systemui")) {
            hookNotificationCardGlass(cl);
            hookNotificationGroupHeader(cl);
            hookFullscreenBanner(cl);
            // e9e3cd0 behavior: always mount the full FlexibleMenu / BehindDisplayCapture chain.
            // Class-not-found only logs; never let OS14 COUI stub replace this path on ColorOS 16.
            hookSystemUiFloatMenus(cl);
            hookSystemUiVolumeGlass(cl);
            DesktopBackdropClient.warmUp();
            // Cos16 SystemUI CL often lacks FlexibleMenuManager → useLegacy true would wrongly
            // take Os14 AOSP updateButton and never install Cos16 glass.
            boolean hasCos16Keyguard = hasClass(cl,
                    "com.oplus.systemui.keyguard.ui.binder.OplusKeyguardBottomAreaViewBinder");
            boolean cos14SysUi = useLegacy
                    && !hasClass(cl, "com.oplus.flexibletask.menu.FlexibleMenuManager")
                    && !hasCos16Keyguard;
            log(4, TAG, "SystemUI keyguard route cos14SysUi=" + cos14SysUi
                    + " hasCos16Keyguard=" + hasCos16Keyguard);
            if (cos14SysUi) {
                if (legacyBackend == null) legacyBackend = new Os14HookBackend();
                legacyBackend.hookSystemUi(this, cl, remotePreferences);
            } else {
                hookKeyguardAffordanceGlass(cl);
                hookLockScreenCapsuleGlass(cl);
            }
            return;
        }
        if (useLegacy) {
            if (legacyBackend == null) legacyBackend = new Os14HookBackend();
            legacyBackend.hookLauncher(this, cl, remotePreferences);
            return;
        }
        hookLauncher(cl);
    }

    private static boolean hasClass(ClassLoader cl, String name) {
        try {
            Class.forName(name, false, cl);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void hookNotificationCardGlass(ClassLoader cl) {
        try {
            Class<?> background = Class.forName(
                    "com.android.systemui.statusbar.notification.row.NotificationBackgroundView",
                    false, cl);
            NotificationCardGlass renderer = new NotificationCardGlass(background);
            Method draw = background.getDeclaredMethod("onDraw", Canvas.class);
            hookOnce(draw, chain -> {
                GlassConfig config = currentConfig();
                View view = (View) chain.getThisObject();
                if (!config.enabled || !config.notificationGlassEnabled || !renderer.shouldDraw(view, config)) {
                    renderer.remove(view);
                    return chain.proceed();
                }
                renderer.draw(view, (Canvas) chain.getArg(0), config);
                return null;
            });
            log(4, TAG, "Notification card background glass hook installed");
        } catch (ReflectiveOperationException e) {
            // OEM SystemUI is an external API: keep its original rendering if its
            // notification geometry contract differs from the supported layout.
            log(5, TAG, "Notification card glass unavailable: incompatible background API", e);
        }
    }

    private void hookNotificationGroupHeader(ClassLoader cl) {
        try {
            Class<?> extension = Class.forName(
                    "com.oplus.systemui.statusbar.notification.stack.NotificationChildrenContainerExtImp", false, cl);
            Class<?> row = Class.forName(
                    "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow", false, cl);
            Method factory = extension.getDeclaredMethod("getHeaderBgDrawable", View.class, boolean.class, row);
            Method radius = row.getMethod("getTopCornerRadius");
            HeadsUpState detector = new HeadsUpState(cl);
            net.z841973620.colorosliquidglass.glass.NotificationRefractionScale refractionScale =
                    new net.z841973620.colorosliquidglass.glass.NotificationRefractionScale(cl);
            hookOnce(factory, chain -> {
                GlassConfig config = currentConfig();
                View host = (View) chain.getArg(0);
                View owner = (View) chain.getArg(2);
                if (!config.enabled || !config.notificationGlassEnabled
                        || (!config.headsUpGlassEnabled && detector.isHeadsUp(owner))) return chain.proceed();
                // Experiment: expansion completion installs a new, header-sized plate.
                // Return no background at that handoff instead of introducing another lens.
                if ((Boolean) chain.getArg(1)) {
                    if (net.z841973620.colorosliquidglass.glass.NotificationGlassTrace.enabled()) {
                        android.util.Log.d("ColgNotifTrace", "expandedHeaderBackgroundSuppressed");
                    }
                    return null;
                }
                // The OEM factory installs a separate blur drawable for headers. Replace that
                // factory result directly, preserving NotificationChildrenContainer's clipping.
                NotificationPlateDrawable plate = new NotificationPlateDrawable(host, config, () -> detector.isHeadsUp(owner),
                        () -> notificationCornerRadius(radius, owner), true);
                plate.setRefractionReferenceSize(() -> refractionScale.referenceSize(owner));
                net.z841973620.colorosliquidglass.glass.NotificationGlassTrace.headerCreated(
                        host, owner, (Boolean) chain.getArg(1), plate);
                return plate;
            });
            log(4, TAG, "Notification group header glass hook installed");
        } catch (ReflectiveOperationException e) {
            log(5, TAG, "Notification group header API unavailable", e);
        }
    }

    private static double notificationCornerRadius(Method method, View owner) {
        try {
            return ((Number) method.invoke(owner)).doubleValue();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Resolved notification corner API failed", e);
        }
    }

    private void hookFullscreenBanner(ClassLoader cl) {
        try {
            Class<?> container = Class.forName(
                    "com.oplus.systemui.notification.interruption.fullscreenbanner.view.FullScreenBannerContainer", false, cl);
            Field bannerField = container.getDeclaredField("mFullScreenBanner");
            Field backgroundField = container.getDeclaredField("mFullScreenBannerBg");
            Field usesBlur = container.getDeclaredField("mUseBlurBannerBg");
            bannerField.setAccessible(true);
            backgroundField.setAccessible(true);
            usesBlur.setAccessible(true);
            hookOnce(container.getDeclaredMethod("onViewAttachedToWindow", View.class), chain -> {
                GlassConfig config = currentConfig();
                if (!config.enabled || !config.notificationGlassEnabled || !config.headsUpGlassEnabled) return chain.proceed();
                Object self = chain.getThisObject();
                View banner = (View) bannerField.get(self);
                if (banner == null) return chain.proceed(); // OEM may attach before its content is assigned.
                int radiusId = banner.getResources().getIdentifier("fullscreen_banner_corner_radius", "dimen", "com.android.systemui");
                if (radiusId == 0) return chain.proceed(); // Different ROM: retain the original shape/material.
                double radius = banner.getResources().getDimension(radiusId);
                NotificationPlateDrawable drawable = new NotificationPlateDrawable(banner, config,
                        () -> true, () -> radius, false);
                backgroundField.set(self, drawable);
                usesBlur.setBoolean(self, false);
                banner.setBackground(drawable);
                return null;
            });
            log(4, TAG, "Fullscreen landscape notification glass hook installed");
        } catch (ReflectiveOperationException e) {
            log(5, TAG, "Fullscreen notification API unavailable", e);
        }
    }

    private void hookLauncher(ClassLoader cl) {
        // Closed folder icons own a dedicated FolderRoundImageView. Never style Folder/OplusFolder:
        // FolderIcon still dispatches this child while the open animation is running, so a glass
        // background on the container obscures the opened folder's icons.
        hookFolderPreviewBackground(cl);
        hookCreateFolderPreviewGlass(cl);
        hookFolderVisibility(cl);
        hookFolderRefreshEvents(cl);
        hookFolderPopupBlur(cl);
        hookMoreFunctionsSubMenuGlass(cl);
        hookFolderDragPreview(cl);
        hookFolderDragDepthBlur(cl);
        hookRecentsClearButton(cl);
        hookRecentsTaskShortcuts(cl);
        hookToggleBarChrome(cl);
        hookDragViewMove(cl);
        hookWorkspaceDragOver(cl);
        hookUnlockGlassRefresh(cl);
        hookWallpaperScaleTracking(cl);
        hookDesktopBackdropIpc(cl);
        after("com.android.launcher3.folder.FolderIcon", cl, "onFolderClose", o -> {
            endFolderOpen(o);
            syncFolderIconDeferred(o, true);
        });
    }

    /**
     * Export folder-glass-equivalent desktop tiles to SystemUI float menus via SharedMemory.
     */
    private void hookDesktopBackdropIpc(ClassLoader cl) {
        DesktopBackdropHub.start();
        after("com.android.launcher3.Launcher", cl, "onResume", launcher -> {
            attachDesktopBackdropRoot(launcher);
            DesktopBackdropSampler.invalidateCache();
        });
        after("com.android.launcher.Launcher", cl, "onResume", launcher -> {
            attachDesktopBackdropRoot(launcher);
            DesktopBackdropSampler.invalidateCache();
        });
        after("com.android.launcher3.Launcher", cl, "onAttachedToWindow", this::attachDesktopBackdropRoot);
        after("com.android.launcher.Launcher", cl, "onAttachedToWindow", this::attachDesktopBackdropRoot);
        after("com.android.launcher3.Workspace", cl, "scrollTo", o ->
                DesktopBackdropSampler.invalidateAll());
        after("com.android.launcher3.OplusWorkspace", cl, "scrollTo", o ->
                DesktopBackdropSampler.invalidateAll());
        after("com.android.launcher3.Workspace", cl, "onScrollChanged", o ->
                DesktopBackdropSampler.invalidateAll());
        after("com.android.launcher3.OplusWorkspace", cl, "onScrollChanged", o ->
                DesktopBackdropSampler.invalidateAll());
        after("com.android.launcher3.CellLayout", cl, "onLayout", o ->
                DesktopBackdropSampler.invalidateCache());
        after("com.android.launcher3.CellLayout", cl, "onViewAdded", o ->
                DesktopBackdropSampler.invalidateCache());
        after("com.android.launcher3.CellLayout", cl, "onViewRemoved", o ->
                DesktopBackdropSampler.invalidateCache());
        after("com.android.launcher3.hotseat.OplusHotseat", cl, "onLayout", o ->
                DesktopBackdropSampler.invalidateCache());
        after("com.android.launcher3.dragndrop.DragView", cl, "move", o -> {
            if (DesktopBackdropHub.isLive()) DesktopBackdropSampler.invalidateCache();
        });
        after("com.android.launcher3.dragndrop.OplusDragView", cl, "move", o -> {
            if (DesktopBackdropHub.isLive()) DesktopBackdropSampler.invalidateCache();
        });
        // ColorOS may reposition via translation/layout instead of move() — keep glass streaming.
        after("com.android.launcher3.dragndrop.DragView", cl, "setTranslationX", o -> {
            if (DesktopBackdropHub.isLive()) DesktopBackdropSampler.invalidateCache();
        });
        after("com.android.launcher3.dragndrop.DragView", cl, "setTranslationY", o -> {
            if (DesktopBackdropHub.isLive()) DesktopBackdropSampler.invalidateCache();
        });
        after("com.android.launcher3.dragndrop.OplusDragView", cl, "setTranslationX", o -> {
            if (DesktopBackdropHub.isLive()) DesktopBackdropSampler.invalidateCache();
        });
        after("com.android.launcher3.dragndrop.OplusDragView", cl, "setTranslationY", o -> {
            if (DesktopBackdropHub.isLive()) DesktopBackdropSampler.invalidateCache();
        });
        after("com.android.launcher3.popup.OplusPopupContainerWithArrow", cl, "onLayout", o -> {
            if (DesktopBackdropHub.isLive()) DesktopBackdropSampler.invalidateCache();
            if (enabled()) keepPopupBlurTransparent(o);
            if (menuStyleEnabled()) {
                reassertDesktopPopupGlass(o);
            }
        });
        after("com.android.launcher3.popup.PopupContainerWithArrow", cl, "onLayout", o -> {
            if (DesktopBackdropHub.isLive()) DesktopBackdropSampler.invalidateCache();
            if (enabled()) keepPopupBlurTransparent(o);
            if (menuStyleEnabled()) {
                reassertDesktopPopupGlass(o);
            }
        });
        after("com.android.launcher.layout.ItemResizeFrame", cl, "dispatchDraw", o -> {
            if (DesktopBackdropHub.isLive()) DesktopBackdropSampler.invalidateCache();
        });
        after("com.android.launcher.layout.ItemResizeFrame", cl, "onLayout", o -> {
            if (DesktopBackdropHub.isLive()) DesktopBackdropSampler.invalidateCache();
        });
    }

    private void attachDesktopBackdropRoot(Object launcher) {
        try {
            if (launcher instanceof android.app.Activity) {
                View decor = ((android.app.Activity) launcher).getWindow().getDecorView();
                DesktopBackdropSampler.setLauncherRoot(decor);
            } else {
                Object window = invokeNoArgs(launcher, "getWindow");
                Object decor = invokeNoArgs(window, "getDecorView");
                if (decor instanceof View) DesktopBackdropSampler.setLauncherRoot((View) decor);
            }
            syncDesktopHomeValidForLauncher(launcher);
        } catch (Throwable e) {
            log(5, TAG, "attachDesktopBackdropRoot failed", e);
        }
    }

    private void hookFolderPreviewBackground(ClassLoader cl) {
        final String className = "com.android.launcher3.folder.OplusPreviewBackground";
        try {
            Class<?> c = Class.forName(className, false, cl);
            boolean found = false;
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("setBackground") || m.getParameterCount() != 1
                        || m.isBridge() || m.isSynthetic()) continue;
                found = true;
                hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        Object host = chain.getArg(0);
                        if (!isClassOrSubclass(host, "com.android.launcher3.folder.FolderIcon")) return result;
                        if (isFolderPopupOemOnly(host)) return result;
                        syncFolderPreview(host, chain.getThisObject());
                    } catch (Throwable e) {
                        log(5, TAG, className + ".setBackground apply failed", e);
                    }
                    return result;
                });
            }
            if (!found) log(5, TAG, "Method not found: " + className + ".setBackground");

            after(className, cl, "setRadius", o -> {
                Object host = field(o, "mInvalidateDelegate");
                if (isClassOrSubclass(host, "com.android.launcher3.folder.FolderIcon")) {
                    if (isFolderPopupOemOnly(host)) return;
                    syncFolderPreviewDeferred(host, o, false);
                }
            });
            after(className, cl, "updateBgColorFilter", o -> {
                Object host = field(o, "mInvalidateDelegate");
                if (isClassOrSubclass(host, "com.android.launcher3.folder.FolderIcon")) {
                    if (isFolderPopupOemOnly(host)) return;
                    syncFolderPreviewDeferred(host, o, false);
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
                hookOnce(m, chain -> {
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
                        log(5, TAG, className + "." + methodName + " hover glass failed", e);
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
                    hookOnce(m, chain -> {
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
                            log(5, TAG, "PreviewBackground.animateToRest cleanup failed", e);
                        }
                        return result;
                    });
                }
            } catch (Throwable e) {
                log(5, TAG, "PreviewBackground.animateToRest hook unavailable", e);
            }

            // ColorOS paints translucent folder_icon_bg / LayerBlur via drawBackground while
            // mBgView is hidden. Replace with LiquidGlass and never fall through to OEM tint.
            try {
                Method drawBackground = c.getDeclaredMethod("drawBackground", Canvas.class, View.class);
                hookOnce(drawBackground, chain -> {
                    if (!enabled()) return chain.proceed();
                    // Capture re-entrancy: do not paint glass/OEM into the backdrop sample.
                    if (GlassInstaller.isPreviewBackdropCapturing()) return null;
                    Object preview = chain.getThisObject();
                    // Folder options-menu: full OEM long-press plate (no glass).
                    if (isFolderPopupOemOnly(resolvePreviewFolderIcon(preview))) {
                        return chain.proceed();
                    }
                    try {
                        suppressOemPreviewDrawable(preview);
                        if (drawPreviewGlassOnCanvas(preview, (Canvas) chain.getArg(0))) {
                            return null;
                        }
                    } catch (Throwable e) {
                        log(5, TAG, "OplusPreviewBackground.drawBackground glass failed", e);
                    }
                    // Prefer empty plate over OEM translucent folder_icon_bg brightening glass.
                    suppressOemPreviewDrawable(preview);
                    return null;
                });
            } catch (Throwable e) {
                log(5, TAG, "OplusPreviewBackground.drawBackground hook unavailable", e);
            }

            // Resize-frame / convert path hides mBgView and draws LayerBlurDrawable via
            // drawBackground(Canvas, ConvertBgParams, ItemInfo). Replace that with glass.
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("drawBackground") || m.getParameterCount() != 3
                        || m.isBridge() || m.isSynthetic()) continue;
                if (m.getParameterTypes()[0] != Canvas.class) continue;
                hookOnce(m, chain -> {
                    if (!enabled()) return chain.proceed();
                    try {
                        Object preview = chain.getThisObject();
                        Object host = field(preview, "mInvalidateDelegate");
                        if (!isClassOrSubclass(host, "com.android.launcher3.folder.FolderIcon")) {
                            return chain.proceed();
                        }
                        if (drawGlassForConvertParams(preview, (Canvas) chain.getArg(0),
                                chain.getArg(1), host)) {
                            return null;
                        }
                    } catch (Throwable e) {
                        log(5, TAG, className + ".drawBackground(ConvertBgParams) apply failed", e);
                    }
                    return chain.proceed();
                });
            }
        } catch (Throwable e) {
            log(5, TAG, "Class unavailable: " + className, e);
        }
    }

    /**
     * Draws LiquidGlass using ConvertBgParams geometry used while the resize frame is active.
     * Relayouts mBgView to the animated rect so backdrop sampling tracks the intermediate size.
     * @return true if glass replaced the OEM LayerBlurDrawable draw
     */
    private boolean drawGlassForConvertParams(Object preview, Canvas canvas, Object params, Object host) {
        if (canvas == null || params == null || !(host instanceof View folderIcon)) return false;
        Object bgView = field(preview, "mBgView");
        if (!(bgView instanceof ImageView image)) return false;
        GlassInstaller.installImage(image, currentConfig());
        GlassDrawable glass = GlassInstaller.get(image);
        if (glass == null) return false;

        Object xObj = invokeNoArgs(params, "getX");
        Object yObj = invokeNoArgs(params, "getY");
        Object sizeXObj = invokeNoArgs(params, "getSizeX");
        Object sizeYObj = invokeNoArgs(params, "getSizeY");
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

    private void hookFolderPopupBlur(ClassLoader cl) {
        final String className = "com.android.launcher3.popup.OplusPopupContainerWithArrow";
        try {
            Class<?> c = Class.forName(className, false, cl);
            for (Method m : c.getDeclaredMethods()) {
                String name = m.getName();
                if (m.isBridge() || m.isSynthetic()) continue;
                // Cover open / reorder paths used by folders, app icons, and widgets alike.
                if ((name.equals("reorderAndShow")
                        || name.equals("onCreateOpenAnimation")
                        || name.equals("animateOpen")
                        || name.equals("populateAndShow")
                        || name.equals("showEditPopupContainer"))
                        && m.getParameterCount() <= 6) {
                    hookOnce(m, chain -> {
                        // Strip folder plate glass before OEM open — keeps resting corners intact.
                        // Menu glass still mounts on mAllPopupShortcutContainer below.
                        if (m.getParameterCount() >= 1) {
                            beginFolderPopupOemOnly(chain.getArg(0));
                        }
                        // Blur is always stripped while the module is on; glass is optional.
                        if (enabled()) keepPopupBlurTransparent(chain.getThisObject());
                        Object result = chain.proceed();
                        if (enabled()) keepPopupBlurTransparent(chain.getThisObject());
                        Object popup = chain.getThisObject();
                        Object longPressed = field(popup, "mLongPressedView");
                        beginFolderPopupOemOnly(longPressed);
                        if (menuStyleEnabled()) {
                            applyDesktopPopupGlass(popup);
                        }
                        return result;
                    });
                }
            }
            // Static showForIcon is the shared entry for BubbleTextView / widget / folder menus.
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("showForIcon") || m.isBridge() || m.isSynthetic()) continue;
                hookOnce(m, chain -> {
                    Object icon = chain.getArg(0);
                    beginFolderPopupOemOnly(icon);
                    Object result = chain.proceed();
                    try {
                        if (result != null && enabled()) {
                            keepPopupBlurTransparent(result);
                            if (menuStyleEnabled()) {
                                applyDesktopPopupGlass(result);
                            }
                        }
                    } catch (Throwable e) {
                        log(5, TAG, "showForIcon popup glass failed", e);
                    }
                    return result;
                });
            }
            // ColorOS 16 writes popup_container_background onto mAllPopupShortcutContainer
            // before fade — reclaim glass so white never flashes.
            for (Method m : c.getDeclaredMethods()) {
                if (m.isBridge() || m.isSynthetic()) continue;
                String name = m.getName();
                if (!(name.equals("getOpenCloseAnimatorWithoutPendingCard")
                        || name.equals("getOpenCloseAnimator")
                        || name.equals("animateClose"))) continue;
                if (name.startsWith("getOpenClose") && m.getParameterCount() < 1) continue;
                hookOnce(m, chain -> {
                    Object popup = chain.getThisObject();
                    boolean closing = name.equals("animateClose")
                            || (m.getParameterCount() >= 1 && Boolean.FALSE.equals(chain.getArg(0)));
                    Object result = chain.proceed();
                    try {
                        if (menuStyleEnabled() && closing) {
                            retainDesktopPopupGlassThroughClose(popup);
                        }
                    } catch (Throwable e) {
                        log(5, TAG, className + "." + name + " close glass retain failed", e);
                    }
                    return result;
                });
            }
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("closeComplete") || m.isBridge() || m.isSynthetic()) continue;
                hookOnce(m, chain -> {
                    Object popup = chain.getThisObject();
                    Object longPressed = field(popup, "mLongPressedView");
                    Object result = chain.proceed();
                    try {
                        finishDesktopPopupGlass(popup);
                    } catch (Throwable e) {
                        log(5, TAG, "closeComplete popup glass cleanup failed", e);
                    }
                    try {
                        endFolderPopupOemOnly(longPressed);
                    } catch (Throwable e) {
                        log(5, TAG, "closeComplete folder OEM restore failed", e);
                    }
                    return result;
                });
            }
            // Capture desktop under the menu once open anim finishes (scale/alpha = 1).
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("openAnimationEnd") || m.isBridge() || m.isSynthetic()) {
                    continue;
                }
                hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        if (menuStyleEnabled()) {
                            Object popup = chain.getThisObject();
                            captureDesktopPopupGlassAfterOpen(popup);
                        }
                    } catch (Throwable e) {
                        log(5, TAG, "openAnimationEnd popup glass capture failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            log(5, TAG, "Popup blur hook unavailable", e);
        }
        // Belt-and-suspenders: open anim would fade PopupBlurView to 1 even if we zeroed alpha.
        try {
            Class<?> blur = Class.forName("com.android.launcher3.popup.PopupBlurView", false, cl);
            for (Method m : blur.getDeclaredMethods()) {
                if (!m.getName().equals("createBlurAnim") || m.getParameterCount() != 1
                        || m.isBridge() || m.isSynthetic()) continue;
                hookOnce(m, chain -> {
                    // Always kill OEM fullscreen blur when the module is enabled.
                    if (enabled() && Boolean.TRUE.equals(chain.getArg(0))) {
                        Object self = chain.getThisObject();
                        if (self instanceof View) ((View) self).setAlpha(0f);
                        Object anim = chain.proceed();
                        if (anim instanceof ObjectAnimator oa) {
                            oa.setFloatValues(0f, 0f);
                            oa.setDuration(0L);
                        }
                        return anim;
                    }
                    return chain.proceed();
                });
            }
        } catch (Throwable e) {
            log(5, TAG, "PopupBlurView.createBlurAnim hook unavailable", e);
        }
    }

    /** Hides the fullscreen PopupBlurView for any long-press options menu (icon / widget / folder). */
    private void keepPopupBlurTransparent(Object popup) {
        if (!enabled() || popup == null) return;
        // Keep the PopupBlurView object alive so popup close/drag-resize bookkeeping still runs.
        // Only skip the open alpha animation that makes the full-screen blur visible.
        setField(popup, "mAddPopupBlurView", false);
        Object blur = field(popup, "mPopBlurView");
        if (blur instanceof View) ((View) blur).setAlpha(0f);
    }

    private boolean isFolderPopupOemOnly(Object folderIcon) {
        return folderIcon != null && folderPopupOemOnly.contains(folderIcon);
    }

    /**
     * Folder options-menu open: strip plate glass so ColorOS long-press corner anim stays OEM.
     * Menu chrome still receives LiquidGlass via {@link #applyDesktopPopupGlass}.
     */
    private void beginFolderPopupOemOnly(Object icon) {
        if (!isFolderDragSource(icon)) return;
        if (!folderPopupOemOnly.add(icon)) return;
        try {
            Object preview = field(icon, "mBackground");
            Object bgView = field(preview, "mBgView");
            restoreOemPreviewDrawable(preview);
            Drawable oem = field(preview, "mBgDrawable") instanceof Drawable
                    ? (Drawable) field(preview, "mBgDrawable") : null;
            if (bgView instanceof ImageView image) {
                if (GlassInstaller.get(image) != null) {
                    GlassInstaller.restoreImage(image, oem);
                } else {
                    GlassInstaller.uninstall(image);
                    if (oem != null) image.setImageDrawable(oem);
                }
                image.setVisibility(View.VISIBLE);
                image.invalidate();
            }
            if (icon instanceof View) ((View) icon).invalidate();
        } catch (Throwable e) {
            log(5, TAG, "beginFolderPopupOemOnly failed", e);
        }
    }

    /** Folder options-menu close: drop OEM-only flag and reinstall plate glass via normal sync. */
    private void endFolderPopupOemOnly(Object icon) {
        if (!isFolderDragSource(icon)) return;
        folderPopupOemOnly.remove(icon);
        try {
            Object preview = field(icon, "mBackground");
            Object bgView = field(preview, "mBgView");
            if (bgView instanceof ImageView && GlassInstaller.get((ImageView) bgView) != null) {
                GlassInstaller.uninstall((ImageView) bgView);
            }
            setFolderBackgroundVisibility(preview, true);
            syncFolderPreview(icon, preview);
            if (icon instanceof View v) {
                v.invalidate();
                v.post(() -> {
                    if (isFolderPopupOemOnly(icon)) return;
                    syncFolderIconDeferred(icon, true);
                });
            }
        } catch (Throwable e) {
            log(5, TAG, "endFolderPopupOemOnly failed", e);
        }
    }

    private static final String TAG_DESKTOP_POPUP = "colg_desktop_popup";

    /**
     * LiquidGlass on desktop long-press option panels ({@code OplusPopupContainerWithArrow}).
     * Glass replaces the panel background (not a covering overlay). Backdrop samples desktop
     * icons via {@link DesktopIconOverlay} so settle-frame chrome cannot erase apps/folders.
     */
    private void applyDesktopPopupGlass(Object popup) {
        if (!menuStyleEnabled() || !(popup instanceof ViewGroup)) return;
        try {
            applyDesktopPopupGlassNow(popup);
            // One follow-up after OEM may restore white fills mid-open animation.
            if (popup instanceof View root) {
                root.post(() -> {
                    if (menuStyleEnabled() && root.isAttachedToWindow()) {
                        reassertDesktopPopupGlass(popup);
                    }
                });
            }
        } catch (Throwable e) {
            log(5, TAG, "applyDesktopPopupGlass failed", e);
        }
    }

    /** Light pass: strip restored row whites; reinstall only if OEM replaced glass. */
    private void reassertDesktopPopupGlass(Object popup) {
        if (!menuStyleEnabled() || !(popup instanceof ViewGroup)) return;
        keepPopupBlurTransparent(popup);
        clearDesktopPopupDividers((ViewGroup) popup);
        for (View host : collectDesktopPopupPanelHosts((ViewGroup) popup)) {
            clearDeepShortcutRowFills(host);
            if (!(host.getBackground() instanceof GlassDrawable)) {
                installDesktopPopupPanelGlass(host);
            } else {
                // Do not forceCapture on every onLayout settle — a wallpaper-only sample would
                // overwrite a good open-animation frame once the menu goes fully opaque.
                host.invalidate();
            }
        }
    }

    /**
     * OEM close writes {@code popup_container_background} back onto the all-container before
     * fade-out. Put LiquidGlass back immediately so close never flashes white OEM chrome.
     */
    private void retainDesktopPopupGlassThroughClose(Object popup) {
        if (!menuStyleEnabled() || !(popup instanceof ViewGroup)) return;
        keepPopupBlurTransparent(popup);
        clearDesktopPopupDividers((ViewGroup) popup);
        for (View host : collectDesktopPopupPanelHosts((ViewGroup) popup)) {
            clearDeepShortcutRowFills(host);
            GlassDrawable glass = GlassInstaller.get(host);
            if (glass != null) {
                if (host.getBackground() != glass) host.setBackground(glass);
                host.setTag(TAG_DESKTOP_POPUP);
                GlassInstaller.requestPopupCapture(host);
                host.invalidate();
            } else {
                installDesktopPopupPanelGlass(host);
            }
        }
    }

    /** After close animation: drop popup glass bookkeeping (host is leaving the tree). */
    private void finishDesktopPopupGlass(Object popup) {
        if (!(popup instanceof ViewGroup root)) return;
        java.util.ArrayList<View> hosts = collectDesktopPopupPanelHosts(root);
        for (View host : hosts) {
            try {
                GlassInstaller.setOverlaySource(host, null);
                if (GlassInstaller.get(host) != null || host.getBackground() instanceof GlassDrawable) {
                    GlassInstaller.uninstall(host);
                }
                if (TAG_DESKTOP_POPUP.equals(host.getTag())) host.setTag(null);
            } catch (Throwable ignored) { }
        }
        uninstallNestedPopupGlass(root, new java.util.ArrayList<>());
    }

    private void applyDesktopPopupGlassNow(Object popup) {
        if (!menuStyleEnabled() || !(popup instanceof ViewGroup root)) return;
        keepPopupBlurTransparent(popup);
        clearDesktopPopupDividers(root);
        java.util.ArrayList<View> hosts = collectDesktopPopupPanelHosts(root);
        uninstallNestedPopupGlass(root, hosts);
        for (View host : hosts) {
            installDesktopPopupPanelGlass(host);
        }
    }

    private static java.util.ArrayList<View> collectDesktopPopupPanelHosts(ViewGroup root) {
        java.util.ArrayList<View> hosts = new java.util.ArrayList<>();
        Object popup = root;
        // ColorOS 16: solid white plate lives on mAllPopupShortcutContainer — glass replaces it.
        Object all = field(popup, "mAllPopupShortcutContainer");
        if (all instanceof View && ((View) all).getVisibility() == View.VISIBLE) {
            hosts.add((View) all);
            return hosts;
        }
        addPopupPanelHost(hosts, field(popup, "mAppShortcutContainer"), root);
        addPopupPanelHost(hosts, field(popup, "mSystemShortcutContainer"), root);
        addPopupPanelHost(hosts, field(popup, "mDeepShortcutContainer"), root);
        addPopupPanelHost(hosts, field(popup, "mNotificationContainer"), root);
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child == null || hosts.contains(child) || child.getVisibility() != View.VISIBLE) {
                continue;
            }
            if (isUnderAnyHost(child, hosts)) continue;
            if (isLikelyPopupShortcutPanel(child)) hosts.add(child);
        }
        return hosts;
    }

    private static boolean isUnderAnyHost(View child, java.util.ArrayList<View> hosts) {
        for (View host : hosts) {
            if (host instanceof ViewGroup && isDescendantOf(child, (ViewGroup) host)) return true;
        }
        return false;
    }

    private static boolean isDescendantOf(View child, ViewGroup ancestor) {
        for (Object p = child.getParent(); p instanceof View; p = ((View) p).getParent()) {
            if (p == ancestor) return true;
        }
        return false;
    }

    private static void addPopupPanelHost(
            java.util.ArrayList<View> hosts, Object candidate, ViewGroup popupRoot) {
        if (!(candidate instanceof View view)) return;
        if (view == popupRoot) return;
        if (view.getVisibility() != View.VISIBLE) return;
        if (!hosts.contains(view)) hosts.add(view);
    }

    private static boolean isLikelyPopupShortcutPanel(View view) {
        if (!(view instanceof android.widget.LinearLayout)) return false;
        String name = view.getClass().getName();
        if (name.contains("PendingCard") || name.contains("Arrow") || name.contains("Space")) {
            return false;
        }
        Drawable bg = view.getBackground();
        return bg != null || GlassInstaller.get(view) != null;
    }

    private static void uninstallNestedPopupGlass(ViewGroup root, java.util.ArrayList<View> keep) {
        uninstallNestedPopupGlassRecurse(root, keep);
    }

    private static void uninstallNestedPopupGlassRecurse(
            ViewGroup group, java.util.ArrayList<View> keep) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child == null) continue;
            if (!keep.contains(child) && (child.getBackground() instanceof GlassDrawable
                    || GlassInstaller.get(child) != null
                    || TAG_DESKTOP_POPUP.equals(child.getTag()))) {
                try {
                    GlassInstaller.setOverlaySource(child, null);
                    GlassInstaller.uninstall(child);
                    if (TAG_DESKTOP_POPUP.equals(child.getTag())) child.setTag(null);
                } catch (Throwable ignored) { }
            }
            if (child instanceof ViewGroup) {
                uninstallNestedPopupGlassRecurse((ViewGroup) child, keep);
            }
        }
    }

    private void installDesktopPopupPanelGlass(View host) {
        if (host == null || !menuStyleEnabled()) return;
        if (host.getAlpha() < 1f) host.setAlpha(1f);
        clearDeepShortcutRowFills(host);
        Runnable install = () -> {
            long trace = PopupCaptureTrace.installStart();
            try {
                if (!menuStyleEnabled() || !host.isAttachedToWindow()) return;
                // Several nested OEM entry points reach installation during one open.
                GlassDrawable existing = GlassInstaller.get(host);
                if (existing != null && host.getBackground() == existing) {
                    GlassInstaller.requestPopupCapture(host);
                    return;
                }
                clearDeepShortcutRowFills(host);
                float[] radii = readPopupPanelRadii(host);
                if (radii == null && host.getBackground() instanceof GlassDrawable) {
                    try {
                        radii = ((GlassDrawable) host.getBackground()).getCornerRadii();
                    } catch (Throwable ignored) { }
                }
                suppressPopupPanelOemLayer(host);
                host.setTag(TAG_DESKTOP_POPUP);
                View workspace = findWorkspaceNear(host);
                if (workspace != null) {
                    GlassInstaller.setOverlaySource(host, workspace);
                }
                GlassInstaller.installBackground(host, currentConfig());
                GlassDrawable glass = GlassInstaller.get(host);
                if (glass == null) return;
                if (radii != null) {
                    glass.setCornerRadii(radii);
                } else {
                    float r = resolvePopupCornerFallback(host);
                    glass.setCornerRadii(r, r, r, r);
                }
                // Let layout/animation geometry settle for this frame, then sample once in PreDraw.
                if (host.getWidth() <= 0 || host.getHeight() <= 0) return;
                GlassInstaller.requestPopupCapture(host);
                host.invalidate();
            } finally {
                PopupCaptureTrace.installEnd(trace, host);
            }
        };
        if (host.getWidth() > 0 && host.getHeight() > 0) {
            install.run();
        } else {
            host.post(install);
        }
    }

    /** After open anim reaches alpha/scale 1 — one high-quality desktop sample under the menu. */
    private void captureDesktopPopupGlassAfterOpen(Object popup) {
        if (!menuStyleEnabled() || !(popup instanceof ViewGroup)) return;
        try {
            keepPopupBlurTransparent(popup);
            for (View host : collectDesktopPopupPanelHosts((ViewGroup) popup)) {
                if (!(host.getBackground() instanceof GlassDrawable)
                        && GlassInstaller.get(host) == null) {
                    installDesktopPopupPanelGlass(host);
                }
                if (host.getWidth() > 0 && host.getHeight() > 0) {
                    View workspace = findWorkspaceNear(host);
                    if (workspace != null) GlassInstaller.setOverlaySource(host, workspace);
                    GlassInstaller.requestPopupCapture(host);
                    host.invalidate();
                }
            }
        } catch (Throwable e) {
            log(5, TAG, "captureDesktopPopupGlassAfterOpen failed", e);
        }
    }

    private static float resolvePopupCornerFallback(View host) {
        try {
            android.util.TypedValue tv = new android.util.TypedValue();
            int attrId = host.getResources().getIdentifier(
                    "couiRoundCornerM", "attr", host.getContext().getPackageName());
            if (attrId != 0 && host.getContext().getTheme().resolveAttribute(attrId, tv, true)) {
                float dim = tv.getDimension(host.getResources().getDisplayMetrics());
                if (dim > 0f) return dim;
            }
        } catch (Throwable ignored) { }
        return 16f * host.getResources().getDisplayMetrics().density;
    }

    private static View findWorkspaceNear(View from) {
        for (View current = from; current != null; ) {
            if (isClassOrSubclass(current, "com.android.launcher3.Workspace")
                    || isClassOrSubclass(current, "com.android.launcher3.OplusWorkspace")) {
                return current;
            }
            Object parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        try {
            Object launcher = null;
            for (View current = from; current != null; ) {
                Object ctx = unwrapContext(current.getContext());
                if (ctx != null && isClassOrSubclass(ctx, "com.android.launcher3.Launcher")) {
                    launcher = ctx;
                    break;
                }
                Object parent = current.getParent();
                current = parent instanceof View ? (View) parent : null;
            }
            Object ws = invokeNoArgs(launcher, "getWorkspace");
            if (ws instanceof View) return (View) ws;
        } catch (Throwable ignored) { }
        try {
            Class<?> launcherClass = Class.forName("com.android.launcher3.Launcher");
            Object tracker = launcherClass.getField("ACTIVITY_TRACKER").get(null);
            Object launcher = tracker.getClass().getMethod("getCreatedActivity").invoke(tracker);
            Object ws = invokeNoArgs(launcher, "getWorkspace");
            return ws instanceof View ? (View) ws : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object unwrapContext(Object ctx) {
        Object cur = ctx;
        for (int i = 0; i < 6 && cur != null; i++) {
            if (isClassOrSubclass(cur, "com.android.launcher3.Launcher")) return cur;
            if (cur instanceof android.content.ContextWrapper) {
                cur = ((android.content.ContextWrapper) cur).getBaseContext();
            } else {
                break;
            }
        }
        return ctx;
    }

    /** Strip white DeepShortcut row fills only — keep BubbleTextView / icon artwork intact. */
    private static void clearDeepShortcutRowFills(View host) {
        if (!(host instanceof ViewGroup)) return;
        clearDesktopPopupDividers((ViewGroup) host);
        ViewGroup group = (ViewGroup) host;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child == null) continue;
            if (isDeepShortcutRow(child) || child instanceof android.widget.ScrollView
                    || popupClassContains(child, "PopupShortcutScroll")) {
                Drawable bg = child.getBackground();
                if (bg != null && !(bg instanceof GlassDrawable)) {
                    child.setBackground(null);
                }
            } else if (child instanceof ViewGroup
                    && !(child instanceof android.widget.ImageView)
                    && isOpaquePanelFill(child.getBackground())) {
                child.setBackground(null);
            }
            if (child instanceof ViewGroup) clearDeepShortcutRowFills(child);
        }
    }

    private static void clearDesktopPopupDividers(ViewGroup root) {
        if (root == null) return;
        float density = root.getResources().getDisplayMetrics().density;
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child == null) continue;
            if (child instanceof android.widget.Space) {
                child.setVisibility(View.GONE);
                continue;
            }
            String idName = null;
            try {
                int id = child.getId();
                if (id != View.NO_ID) {
                    idName = child.getResources().getResourceEntryName(id);
                }
            } catch (Throwable ignored) { }
            boolean namedDivide = idName != null && (idName.contains("divider")
                    || idName.contains("divide") || idName.contains("separator"));
            ViewGroup.LayoutParams lp = child.getLayoutParams();
            int lpH = lp != null ? lp.height : 0;
            int measuredH = child.getHeight();
            boolean thinStrip = child instanceof ImageView
                    && ((ImageView) child).getDrawable() == null
                    && ((measuredH > 0 && measuredH <= 8f * density)
                    || (lpH > 0 && lpH <= 8f * density));
            String className = child.getClass().getName();
            boolean divideLayout = className.contains("divide") || className.contains("Divide");
            if (namedDivide || thinStrip || divideLayout) {
                child.setVisibility(View.GONE);
            }
            if (child instanceof ViewGroup) clearDesktopPopupDividers((ViewGroup) child);
        }
    }

    private static boolean popupClassContains(View view, String token) {
        String name = view.getClass().getName();
        return name != null && name.contains(token);
    }

    private static boolean isOpaquePanelFill(Drawable bg) {
        if (bg == null || bg instanceof GlassDrawable) return false;
        String name = bg.getClass().getName();
        if (name.contains("LayerBlur") || name.contains("BlurDrawable")) return true;
        if (bg instanceof android.graphics.drawable.ColorDrawable) {
            int c = ((android.graphics.drawable.ColorDrawable) bg).getColor();
            return android.graphics.Color.alpha(c) > 200;
        }
        return bg instanceof android.graphics.drawable.GradientDrawable
                || bg instanceof android.graphics.drawable.RippleDrawable
                || name.contains("Smooth") || name.contains("RoundRect");
    }

    private static boolean isDeepShortcutRow(View view) {
        String name = view.getClass().getName();
        return name.contains("DeepShortcutView") || name.contains("DeepShortcut");
    }

    private static void suppressPopupPanelOemLayer(View host) {
        Drawable bg = host.getBackground();
        if (bg == null || bg instanceof GlassDrawable) return;
        // Drop OEM plate on the glass host (OS16 popup_container_background may be LayerDrawable).
        host.setBackground(null);
    }

    /** Prefer OEM GradientDrawable corner array (top-only / bottom-only popup panels). */
    private static float[] readPopupPanelRadii(View host) {
        Drawable bg = host.getBackground();
        if (bg instanceof android.graphics.drawable.GradientDrawable) {
            try {
                float[] corners = ((android.graphics.drawable.GradientDrawable) bg).getCornerRadii();
                if (corners != null && corners.length >= 8) {
                    return new float[] {
                            Math.max(corners[0], corners[1]),
                            Math.max(corners[2], corners[3]),
                            Math.max(corners[4], corners[5]),
                            Math.max(corners[6], corners[7])
                    };
                }
                float single = ((android.graphics.drawable.GradientDrawable) bg).getCornerRadius();
                if (single > 0f) {
                    return new float[] { single, single, single, single };
                }
            } catch (Throwable ignored) { }
        }
        if (bg instanceof android.graphics.drawable.RippleDrawable) {
            try {
                android.graphics.drawable.RippleDrawable ripple =
                        (android.graphics.drawable.RippleDrawable) bg;
                if (ripple.getNumberOfLayers() > 0) {
                    Drawable layer = ripple.getDrawable(0);
                    if (layer instanceof android.graphics.drawable.GradientDrawable) {
                        float[] corners = ((android.graphics.drawable.GradientDrawable) layer)
                                .getCornerRadii();
                        if (corners != null && corners.length >= 8) {
                            return new float[] {
                                    Math.max(corners[0], corners[1]),
                                    Math.max(corners[2], corners[3]),
                                    Math.max(corners[4], corners[5]),
                                    Math.max(corners[6], corners[7])
                            };
                        }
                    }
                }
            } catch (Throwable ignored) { }
        }
        try {
            android.graphics.Outline outline = new android.graphics.Outline();
            if (host.getOutlineProvider() != null) {
                host.getOutlineProvider().getOutline(host, outline);
                float r = outline.getRadius();
                if (r > 0f) return new float[] { r, r, r, r };
            }
        } catch (Throwable ignored) { }
        // COUI RoundFrameLayout (更多功能二级菜单) keeps radius on mRadius.
        Object round = field(host, "mRadius");
        if (round instanceof Number && ((Number) round).floatValue() > 0f) {
            float r = ((Number) round).floatValue();
            return new float[] { r, r, r, r };
        }
        return null;
    }

    /**
     * ColorOS 16「更多功能」opens MoreFunctionsPopupListWindow — white plate is
     * {@code mSubMenuRoundFrameLayout}.
     * <p>
     * OEM paints white in the constructor, then {@code j()} makes the plate visible.
     * Install glass at {@code setSubPopWindow} (still invisible) and again before
     * {@code j()} so the first drawn frame is already glass — never a white flash.
     * One-shot only: no mid-anim {@code setBackground} / post-reinstall.
     */
    private void hookMoreFunctionsSubMenuGlass(ClassLoader cl) {
        try {
            Class<?> more = Class.forName(
                    "com.android.launcher3.popup.MoreFunctionsPopupListWindow", false, cl);
            for (Method m : more.getDeclaredMethods()) {
                if (m.isBridge() || m.isSynthetic()) continue;
                String name = m.getName();
                if (name.equals("onMainMenuListViewClick")) {
                    hookOnce(m, chain -> {
                        Object window = chain.getThisObject();
                        Object result = chain.proceed();
                        try {
                            if (menuStyleEnabled()) {
                                beginMoreFunctionsSubMenuTransition(window);
                            }
                        } catch (Throwable e) {
                            log(5, TAG, "MoreFunctions.onMainMenuListViewClick glass failed", e);
                        }
                        return result;
                    });
                } else if (name.equals("dismissImmediately") || name.equals("dismiss")
                        || name.equals("superDismiss")) {
                    hookOnce(m, chain -> {
                        Object window = chain.getThisObject();
                        Object result = chain.proceed();
                        try {
                            finishMoreFunctionsSubMenuGlass(window);
                        } catch (Throwable e) {
                            log(5, TAG, "MoreFunctions." + name + " glass cleanup failed", e);
                        }
                        return result;
                    });
                }
            }
        } catch (Throwable e) {
            log(5, TAG, "MoreFunctionsPopupListWindow glass hook unavailable", e);
        }
        // Constructor ends here after setBackgroundColor(white) — replace before first draw.
        after("com.android.launcher3.popup.OplusPopupContainerWithArrow", cl, "setSubPopWindow",
                popup -> {
                    if (!menuStyleEnabled()) return;
                    try {
                        Object window = field(popup, "mSubPopWindow");
                        View host = resolveMoreFunctionsSubMenuHost(window);
                        if (host != null) installSubMenuGlassOnce(host);
                    } catch (Throwable e) {
                        log(5, TAG, "setSubPopWindow early submenu glass failed", e);
                    }
                });
        // Install before j() so its setBounds(f15656b) lands on GlassDrawable, not white.
        hookCouiSubMenuOpenStart(cl, "com.coui.appcompat.poplist.SmallScreenAnimationController");
        hookCouiSubMenuOpenStart(cl, "com.coui.appcompat.poplist.DefaultScreenAnimationController");
    }

    private static final Set<View> SUBMENU_GLASS_INSTALLED =
            Collections.newSetFromMap(new WeakHashMap<>());

    private void hookCouiSubMenuOpenStart(ClassLoader cl, String controllerName) {
        try {
            Class<?> ctrl = Class.forName(controllerName, false, cl);
            for (Method m : ctrl.getDeclaredMethods()) {
                if (!m.getName().equals("j") || m.isBridge() || m.isSynthetic()) continue;
                if (m.getParameterTypes().length != 0) continue;
                hookOnce(m, chain -> {
                    try {
                        if (menuStyleEnabled()) {
                            GlassInstaller.setMoreFunctionsSubMenuActive(true);
                            Object sub = field(chain.getThisObject(), "f15455g");
                            if (sub instanceof View) {
                                installSubMenuGlassOnce((View) sub);
                            }
                        }
                    } catch (Throwable e) {
                        log(5, TAG, controllerName + ".j pre-glass failed", e);
                    }
                    Object result = chain.proceed();
                    try {
                        // j() just seeded f15656b — pin glass bounds to the reveal rect.
                        Object sub = field(chain.getThisObject(), "f15455g");
                        if (sub instanceof View) syncCouiRoundFrameRevealBounds((View) sub);
                    } catch (Throwable ignored) { }
                    return result;
                });
            }
        } catch (Throwable e) {
            log(5, TAG, controllerName + " open-start glass hook unavailable", e);
        }
    }

    private void beginMoreFunctionsSubMenuTransition(Object window) {
        if (window == null) return;
        GlassInstaller.setMoreFunctionsSubMenuActive(true);
        View host = resolveMoreFunctionsSubMenuHost(window);
        if (host == null) return;
        installSubMenuGlassOnce(host);
        syncCouiRoundFrameRevealBounds(host);
        // Workspace overlay may be missing before attach — fill in once attached.
        if (host.isAttachedToWindow()) {
            ensureSubMenuOverlaySource(host);
        } else {
            host.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View v) {
                    v.removeOnAttachStateChangeListener(this);
                    ensureSubMenuOverlaySource(v);
                    syncCouiRoundFrameRevealBounds(v);
                    if (v.getWidth() > 0 && v.getHeight() > 0) {
                        GlassInstaller.forceCapture(v);
                        v.invalidate();
                    }
                }
                @Override public void onViewDetachedFromWindow(View v) { }
            });
        }
    }

    private void ensureSubMenuOverlaySource(View host) {
        if (host == null) return;
        try {
            View workspace = findWorkspaceNear(host);
            if (workspace != null) GlassInstaller.setOverlaySource(host, workspace);
        } catch (Throwable ignored) { }
    }

    /**
     * One-shot submenu glass. May run before attach (setSubPopWindow) so the white OEM
     * plate never paints. No setAlpha(1), no post(reinstall).
     */
    private void installSubMenuGlassOnce(View host) {
        if (!menuStyleEnabled() || host == null) return;
        if (SUBMENU_GLASS_INSTALLED.contains(host)) {
            syncCouiRoundFrameRevealBounds(host);
            return;
        }
        if (GlassInstaller.get(host) != null || host.getBackground() instanceof GlassDrawable) {
            SUBMENU_GLASS_INSTALLED.add(host);
            syncCouiRoundFrameRevealBounds(host);
            return;
        }
        try {
            if (host instanceof ViewGroup && ((ViewGroup) host).getChildCount() > 0) {
                View child = ((ViewGroup) host).getChildAt(0);
                if (child != null) clearDeepShortcutRowFills(child);
            }
            clearDeepShortcutRowFills(host);
            float[] radii = readPopupPanelRadii(host);
            suppressPopupPanelOemLayer(host);
            host.setTag(TAG_DESKTOP_POPUP);
            ensureSubMenuOverlaySource(host);
            GlassInstaller.installBackground(host, currentConfig());
            GlassDrawable glass = GlassInstaller.get(host);
            if (glass == null) return;
            if (radii != null) {
                glass.setCornerRadii(radii);
            } else {
                float r = resolvePopupCornerFallback(host);
                glass.setCornerRadii(r, r, r, r);
            }
            syncCouiRoundFrameRevealBounds(host);
            SUBMENU_GLASS_INSTALLED.add(host);
            if (host.isAttachedToWindow() && host.getWidth() > 0 && host.getHeight() > 0) {
                GlassInstaller.forceCapture(host);
                host.invalidate();
            }
        } catch (Throwable e) {
            log(5, TAG, "installSubMenuGlassOnce failed", e);
        }
    }

    private static void syncCouiRoundFrameRevealBounds(View host) {
        if (host == null) return;
        try {
            android.graphics.drawable.Drawable bg = host.getBackground();
            if (bg == null) return;
            for (Class<?> c = host.getClass(); c != null; c = c.getSuperclass()) {
                String name = c.getName();
                if (name == null || !name.endsWith(".RoundFrameLayout")) continue;
                Field rectField;
                try {
                    rectField = c.getDeclaredField("f15656b");
                } catch (NoSuchFieldException ignored) {
                    try {
                        rectField = c.getDeclaredField("mRevealRect");
                    } catch (NoSuchFieldException ignored2) {
                        return;
                    }
                }
                rectField.setAccessible(true);
                Object value = rectField.get(host);
                if (value instanceof android.graphics.Rect) {
                    android.graphics.Rect rect = (android.graphics.Rect) value;
                    if (!rect.isEmpty()) bg.setBounds(rect);
                }
                return;
            }
        } catch (Throwable ignored) { }
    }

    private void finishMoreFunctionsSubMenuGlass(Object window) {
        View host = resolveMoreFunctionsSubMenuHost(window);
        try {
            GlassInstaller.setMoreFunctionsSubMenuActive(false);
            if (host != null) {
                SUBMENU_GLASS_INSTALLED.remove(host);
                GlassInstaller.setOverlaySource(host, null);
                if (GlassInstaller.get(host) != null
                        || host.getBackground() instanceof GlassDrawable) {
                    GlassInstaller.uninstall(host);
                }
                if (TAG_DESKTOP_POPUP.equals(host.getTag())) host.setTag(null);
            }
        } catch (Throwable ignored) { }
    }

    private static View resolveMoreFunctionsSubMenuHost(Object window) {
        if (window == null) return null;
        Object frame = field(window, "mSubMenuRoundFrameLayout");
        if (frame instanceof View) return (View) frame;
        Object list = field(window, "mSubMenuListView");
        if (list == null) list = invokeNoArgs(window, "getSubMenuListView");
        if (list instanceof View) {
            ViewParent parent = ((View) list).getParent();
            if (parent instanceof View) return (View) parent;
        }
        return null;
    }

    /**
     * Folder-only drag preview glass (ported from 408f380 onto the pre-widget-chrome tree).
     * Widget/card DragViews must never receive folder move glass via install/refresh paths.
     */
    private static boolean isFolderDragSource(Object source) {
        return source != null && (
                isClassOrSubclass(source, "com.android.launcher3.folder.FolderIcon")
                        || isClassOrSubclass(source, "com.android.launcher3.folder.FlexibleFolderIcon")
                        || source.getClass().getName().contains("FolderIcon"));
    }

    /** DragView content that belongs to a widget/card — must never receive folder drag glass. */
    private static boolean isWidgetDragContent(View content) {
        if (content == null) return false;
        String n = content.getClass().getName();
        if (n.contains("AppWidgetHostView") || n.contains("WrapWidget")
                || n.contains("LauncherCard") || n.contains("WrapCard")
                || n.contains("WrapAdaptive") || n.contains("WrapMultiSize")
                || n.contains("TitleCard") || n.contains("StackGroup")) {
            return true;
        }
        for (Class<?> c = content.getClass(); c != null; c = c.getSuperclass()) {
            String simple = c.getSimpleName();
            if (simple != null && simple.contains("AppWidgetHostView")) return true;
        }
        return false;
    }

    /** Strip any mistaken DragView glass from a non-folder drag preview. */
    private void stripWidgetDragViewGlass(Object dragView) {
        if (dragView == null) return;
        try {
            Object content = invokeNoArgs(dragView, "getContentView");
            if (content instanceof View) {
                View view = (View) content;
                GlassInstaller.setOverlaySource(view, null);
                if (GlassInstaller.get(view) != null) {
                    GlassInstaller.uninstall(view);
                } else if (view.getBackground() instanceof GlassDrawable) {
                    view.setBackground(null);
                }
                if (view instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) view;
                    for (int i = 0; i < group.getChildCount(); i++) {
                        View child = group.getChildAt(i);
                        if (child == null) continue;
                        GlassInstaller.setOverlaySource(child, null);
                        if (GlassInstaller.get(child) != null) GlassInstaller.uninstall(child);
                        else if (child.getBackground() instanceof GlassDrawable) {
                            child.setBackground(null);
                        }
                    }
                }
            }
            if (dragView instanceof View) {
                View dv = (View) dragView;
                GlassInstaller.setOverlaySource(dv, null);
                if (GlassInstaller.get(dv) != null) GlassInstaller.uninstall(dv);
                else if (dv.getBackground() instanceof GlassDrawable) dv.setBackground(null);
            }
        } catch (Throwable ignored) { }
    }

    private void hookFolderDragPreview(ClassLoader cl) {
        final String className = "com.android.launcher3.Workspace";
        try {
            Class<?> c = Class.forName(className, false, cl);
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("handleFolderBackground") || m.getParameterCount() != 2
                        || m.isBridge() || m.isSynthetic()) continue;
                hookOnce(m, chain -> {
                    Object source = chain.getArg(0);
                    Object dragView = chain.getArg(1);
                    // ColorOS copies LayerBlurDrawable onto the drag preview here. Skip that
                    // OEM blur layer entirely and let LiquidGlass own the dragged folder look.
                    if (enabled() && isFolderDragSource(source)) {
                        try {
                            Object content = invokeNoArgs(dragView, "getContentView");
                            // Never install folder-drag glass onto widget/card DragView content.
                            if (content instanceof View && isWidgetDragContent((View) content)) {
                                stripWidgetDragViewGlass(dragView);
                                return chain.proceed();
                            }
                            beginFolderDrag(source);
                            clearDragBlurProp(dragView);
                            if (content instanceof View) {
                                suppressLayerBlur((View) content);
                                GlassInstaller.installBackground((View) content, currentConfig());
                                // Keep glass opaque. Desktop icons under the folder are drawn
                                // live on the HW canvas in GlassDrawable (DesktopIconOverlay),
                                // not via translucent glass or software sample paste.
                                GlassDrawable glass = GlassInstaller.get((View) content);
                                if (glass != null) glass.setAlpha(255);
                                Object workspace = chain.getThisObject();
                                View seed = workspace instanceof View
                                        ? (View) workspace
                                        : desktopPageUnderDrag(workspace);
                                if (seed != null) {
                                    GlassInstaller.setOverlaySource((View) content, seed);
                                }
                            }
                        } catch (Throwable e) {
                            log(5, TAG, className + ".handleFolderBackground apply failed", e);
                        }
                        return null;
                    }
                    Object result = chain.proceed();
                    // Non-folder: strip any folder-drag glass that leaked onto the preview.
                    if (enabled() && dragView != null) {
                        stripWidgetDragViewGlass(dragView);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            log(5, TAG, "Folder drag preview hook unavailable", e);
        }

        // Belt: strip widget/card DragView glass on every onDragStart.
        try {
            for (String dragViewClass : new String[] {
                    "com.android.launcher3.dragndrop.OplusDragView",
                    "com.android.launcher3.dragndrop.DragView"
            }) {
                Class<?> c = Class.forName(dragViewClass, false, cl);
                for (Method m : c.getDeclaredMethods()) {
                    if (m.isBridge() || m.isSynthetic()) continue;
                    if (!m.getName().equals("onDragStart") || m.getParameterCount() > 2) continue;
                    hookOnce(m, chain -> {
                        Object result = chain.proceed();
                        try {
                            if (!enabled()) return result;
                            Object dragView = chain.getThisObject();
                            Object content = invokeNoArgs(dragView, "getContentView");
                            if (content instanceof View && isWidgetDragContent((View) content)) {
                                stripWidgetDragViewGlass(dragView);
                                if (folderDragActive && !isFolderDragSource(folderDragSource)) {
                                    folderDragActive = false;
                                    folderDragSource = null;
                                }
                            } else if (content instanceof View && !isFolderDragSource(folderDragSource)
                                    && GlassInstaller.get((View) content) != null
                                    && !folderDragActive) {
                                // Stale glass on a non-folder preview — drop it.
                                stripWidgetDragViewGlass(dragView);
                            }
                        } catch (Throwable e) {
                            log(5, TAG, dragViewClass + ".onDragStart strip widget glass failed", e);
                        }
                        return result;
                    });
                }
            }
        } catch (Throwable e) {
            log(5, TAG, "Widget drag-start strip hook unavailable", e);
        }
    }

    /**
     * ColorOS applies wallpaper/background blur via OplusDepthController for TOGGLE_BAR /
     * PAGE_PREVIEW / OVERVIEW, and also during the swipe-up-to-Recents hand-follow spring
     * (SwipeToRecentAnimationHelper → createWallpaperBlurHandFollowAnim) while still in
     * NORMAL. Zero every wallpaper/icon blur write while the module is enabled so that
     * gesture transition never shows the frosted layer either.
     */
    private void hookFolderDragDepthBlur(ClassLoader cl) {
        try {
            Class<?> depth = Class.forName(
                    "com.android.launcher3.uioverrides.states.OplusDepthController", false, cl);
            for (Method m : depth.getDeclaredMethods()) {
                String name = m.getName();
                if (!(name.equals("setBlur") || name.equals("setBlurWithoutAnim")
                        || name.equals("setIconBlur") || name.equals("setIconBlurWithoutAnim"))) {
                    continue;
                }
                if (m.getParameterCount() < 1 || m.getParameterTypes()[0] != float.class) continue;
                if (m.isBridge() || m.isSynthetic()) continue;
                hookOnce(m, chain -> {
                    // Always clamp while enabled: gesture blur runs before OVERVIEW is settled.
                    if (enabled()) {
                        java.util.List<Object> list = chain.getArgs();
                        Object[] args = list.toArray(new Object[0]);
                        if (args.length > 0 && args[0] instanceof Number) {
                            args[0] = 0f;
                        }
                        return chain.proceed(args);
                    }
                    return chain.proceed();
                });
            }
            // Folder open uses getFolderBlur()==1 in state handlers / onDraw recovery.
            for (Method m : depth.getDeclaredMethods()) {
                if (!m.getName().equals("getFolderBlur") || m.getParameterCount() != 0
                        || m.isBridge() || m.isSynthetic()) continue;
                hookOnce(m, chain -> {
                    if (!enabled()) return chain.proceed();
                    if (folderOpenActive || hasOpenFolder(findActiveLauncher())) return 0f;
                    return chain.proceed();
                });
            }
        } catch (Throwable e) {
            log(5, TAG, "DepthController blur hook unavailable", e);
        }

        // Catch wallpaper blur writes that bypass OplusDepthController.setBlur (e.g. onDraw),
        // and rewrite hand-follow / state-switch blur animations used by swipe-to-Recents.
        try {
            Class<?> anim = Class.forName(
                    "com.android.quickstep.util.animation.DepthAnimImpl", false, cl);
            for (Method m : anim.getDeclaredMethods()) {
                String name = m.getName();
                if (m.isBridge() || m.isSynthetic()) continue;
                if (name.equals("setWallpaperBlurWithoutAnim") || name.equals("setMirrorBlurWithoutAnim")
                        || name.equals("setIconBlurWithoutAnim")) {
                    if (m.getParameterCount() < 1 || m.getParameterTypes()[0] != float.class) continue;
                    hookOnce(m, chain -> {
                        if (enabled()) {
                            java.util.List<Object> list = chain.getArgs();
                            Object[] args = list.toArray(new Object[0]);
                            if (args.length > 0 && args[0] instanceof Number) {
                                args[0] = 0f;
                            }
                            return chain.proceed(args);
                        }
                        return chain.proceed();
                    });
                } else if (name.equals("stateSwitchForWallpaperBlur")
                        || name.equals("stateSwitchForMirrorBlur")) {
                    if (m.getParameterCount() < 2 || m.getParameterTypes()[1] != float.class) continue;
                    hookOnce(m, chain -> {
                        if (!enabled()) return chain.proceed();
                        java.util.List<Object> list = chain.getArgs();
                        Object[] args = list.toArray(new Object[0]);
                        args[1] = 0f;
                        return chain.proceed(args);
                    });
                } else if (name.equals("createWallpaperBlurHandFollowAnim")
                        || name.equals("createWallpaperBlurAnim")
                        || name.equals("createWallpaperBlurSpringAnim")
                        || name.equals("createIconBlurHandFollowAnim")
                        || name.equals("createIconBlurAnim")
                        || name.equals("createIconBlurSpringAnim")
                        || name.equals("createMirrorBlurAnim")
                        || name.equals("createMirrorBlurSpringAnim")) {
                    if (m.getParameterCount() < 1) continue;
                    hookOnce(m, chain -> {
                        if (enabled()) zeroAnimInfoEndValue(chain.getArg(0));
                        return chain.proceed();
                    });
                }
            }
        } catch (Throwable e) {
            log(5, TAG, "DepthAnimImpl wallpaper blur hook unavailable", e);
        }

        // Swipe-to-Recents hardcodes AnimInfo(..., 1f) and animateToFinalPosition(1f) while still
        // in NORMAL. Clamp those targets and force blur writes to 0 for the whole gesture.
        try {
            Class<?> helper = Class.forName(
                    "com.android.quickstep.touch.SwipeToRecentAnimationHelper", false, cl);
            for (Method m : helper.getDeclaredMethods()) {
                if (m.isBridge() || m.isSynthetic()) continue;
                String name = m.getName();
                if (!(name.equals("createBlurAndDragLayerAlphaAnim")
                        || name.equals("doBackGroundAnim")
                        || name.equals("initState")
                        || name.equals("updatePaused"))) {
                    continue;
                }
                hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    if (!enabled()) return result;
                    Object self = chain.getThisObject();
                    setField(self, "wallpaperBlurEndValue", 0f);
                    Object blurAnim = field(self, "mWallpaperBlurAnim");
                    invoke(blurAnim, "animateToFinalPosition",
                            new Class<?>[] { float.class }, 0f);
                    Object launcher = field(self, "mLauncher");
                    if (launcher != null) forceDepthBlur(launcher, 0f);
                    return result;
                });
            }
        } catch (Throwable e) {
            log(5, TAG, "SwipeToRecent blur clamp unavailable", e);
        }

        // Keep state blur target at 0 in edit modes and Recents (Overview).
        for (String stateClass : new String[] {
                "com.android.launcher3.states.ToggleBarState",
                "com.android.launcher3.states.PagePreviewState",
                "com.android.launcher3.uioverrides.states.OverviewState",
                "com.android.launcher3.uioverrides.states.BackgroundAppState"
        }) {
            try {
                Class<?> c = Class.forName(stateClass, false, cl);
                for (Method m : c.getDeclaredMethods()) {
                    if (!m.getName().equals("getBlurUnchecked") || m.getParameterCount() != 1
                            || m.isBridge() || m.isSynthetic()) continue;
                    hookOnce(m, chain -> {
                        Object result = chain.proceed();
                        if (enabled()) return 0f;
                        return result;
                    });
                }
            } catch (Throwable e) {
                log(5, TAG, stateClass + ".getBlurUnchecked hook unavailable", e);
            }
        }

        after("com.android.launcher3.folder.FlexibleFolderIcon", cl, "onDragStart", o -> {
            if (enabled()) beginFolderDrag(o);
        });
        after("com.android.launcher3.folder.FlexibleFolderIcon", cl, "onDragEnd", o -> {
            endFolderDrag(o);
        });
        after("com.android.launcher3.dragndrop.DragController", cl, "endDrag", o -> {
            if (folderDragActive) endFolderDrag(field(o, "mActivity"));
        });
        after("com.android.launcher3.dragndrop.DragController", cl, "cancelDrag", o -> {
            if (folderDragActive) endFolderDrag(field(o, "mActivity"));
        });
        // Click-open / spring-load folder: FolderAnimUtil animates wallpaper blur 0->1; clear it.
        // Also invalidate ashmem so float-menu glass switches to the opened folder page.
        for (String folderClass : new String[] {
                "com.android.launcher3.folder.Folder",
                "com.android.launcher3.folder.OplusFolder"
        }) {
            try {
                Class<?> folder = Class.forName(folderClass, false, cl);
                for (Method m : folder.getDeclaredMethods()) {
                    String name = m.getName();
                    if (m.isBridge() || m.isSynthetic()) continue;
                    if (!(name.equals("animateOpen")
                            || name.equals("beginExternalDrag")
                            || name.equals("onDropCompleted")
                            || name.equals("bind"))) {
                        continue;
                    }
                    if (name.equals("bind") && m.getParameterCount() == 0) continue;
                    final String hooked = name;
                    hookOnce(m, chain -> {
                        Object result = chain.proceed();
                        if (!enabled()) return result;
                        try {
                            Object self = chain.getThisObject();
                            Object open = invokeNoArgs(self, "isOpen");
                            boolean isOpen = open instanceof Boolean ? (Boolean) open
                                    : Boolean.TRUE.equals(field(self, "mIsOpen"));
                            if (hooked.equals("animateOpen") || hooked.equals("beginExternalDrag")
                                    || isOpen) {
                                beginFolderOpen(self);
                                forceDepthBlur(self, 0f);
                                if (DesktopBackdropHub.isLive()) {
                                    DesktopBackdropSampler.invalidateCache();
                                }
                            }
                        } catch (Throwable e) {
                            log(5, TAG, folderClass + "." + hooked + " open glass failed", e);
                        }
                        return result;
                    });
                }
            } catch (Throwable e) {
                log(5, TAG, folderClass + " open hooks unavailable", e);
            }
        }
        // Long-press edit / Recents enter states with wallpaper blur=1; clear it.
        after("com.android.launcher3.statemanager.StateManager", cl, "goToState", o -> {
            if (!enabled()) return;
            Object activity = field(o, "mActivity");
            if (activity == null) activity = field(o, "mContext");
            if (shouldSuppressDepthBlurForLauncher(activity)) forceDepthBlur(activity, 0f);
            syncPagePreviewFrameGlassForLauncher(activity);
            syncDesktopHomeValidForLauncher(activity);
        });
        after("com.android.launcher3.statemanager.StateManager", cl, "onStateTransitionEnd", o -> {
            if (!enabled()) return;
            Object activity = field(o, "mActivity");
            if (activity == null) activity = field(o, "mContext");
            if (shouldSuppressDepthBlurForLauncher(activity)) forceDepthBlur(activity, 0f);
            syncPagePreviewFrameGlassForLauncher(activity);
            syncDesktopHomeValidForLauncher(activity);
        });
    }

    /**
     * Recents "清除" is a PressFeedbackButton that self-draws a solid/src fill. Replace that
     * chrome with LiquidGlass while keeping the label and press-scale feedback.
     */
    private void hookRecentsClearButton(ClassLoader cl) {
        after("com.oplus.quickstep.views.OplusClearAllPanelView", cl, "onFinishInflate", o -> {
            applyClearButtonGlass(o);
        });
        after("com.oplus.quickstep.views.OplusClearAllPanelView", cl, "onAttachedToWindow", o -> {
            applyClearButtonGlass(o);
        });
        try {
            Class<?> btn = Class.forName("com.android.launcher.views.PressFeedbackButton", false, cl);
            for (Method m : btn.getDeclaredMethods()) {
                if (!m.getName().equals("setSrcDrawable") || m.isBridge() || m.isSynthetic()) continue;
                hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        View view = (View) chain.getThisObject();
                        if (enabled() && GlassInstaller.get(view) != null) {
                            setField(view, "mSrcDrawable", null);
                            view.invalidate();
                        }
                    } catch (Throwable e) {
                        log(5, TAG, "PressFeedbackButton.setSrcDrawable glass reassert failed", e);
                    }
                    return result;
                });
            }
            for (Method m : btn.getDeclaredMethods()) {
                if (!m.getName().equals("onDraw") || m.getParameterCount() != 1
                        || m.isBridge() || m.isSynthetic()) continue;
                hookOnce(m, chain -> {
                    Object self = chain.getThisObject();
                    if (!(self instanceof View) || !enabled() || GlassInstaller.get((View) self) == null) {
                        return chain.proceed();
                    }
                    // OEM fills an opaque-ish rounded rect before text; zero the fill so glass shows.
                    Object handler = field(self, "mPressFeedbackHandler");
                    Object savedAlpha = handler != null ? field(handler, "mCurrentColorAlpha") : null;
                    if (handler != null) setField(handler, "mCurrentColorAlpha", 0);
                    setField(self, "mSrcDrawable", null);
                    try {
                        return chain.proceed();
                    } finally {
                        if (handler != null && savedAlpha != null) {
                            setField(handler, "mCurrentColorAlpha", savedAlpha);
                        }
                    }
                });
            }
        } catch (Throwable e) {
            log(5, TAG, "PressFeedbackButton clear-glass hooks unavailable", e);
        }
    }

    /**
     * Recents "分屏" / "浮窗" chrome — two different surfaces:
     * <ol>
     *   <li>RapidReaction ({@code MultiTriggerPanelView}): capsules shown only while
     *       gesture-swiping up from an app into Overview.</li>
     *   <li>{@code OplusTaskMenuViewImpl}: ··· app-options popup (also lists 分屏/浮窗).</li>
     * </ol>
     * Phone header ImageButtons stay GONE without large-display features; still glass
     * them when VISIBLE on tablet / fold.
     */
    private void hookRecentsTaskShortcuts(ClassLoader cl) {
        hookRecentsRapidReactionGlass(cl);
        after("com.android.quickstep.views.OplusTaskMenuViewImpl", cl, "addMenuOptions",
                this::applyTaskMenuGlass);
        after("com.android.quickstep.views.OplusTaskMenuViewImpl", cl, "animateOpen",
                this::applyTaskMenuGlass);
        try {
            Class<?> menu = Class.forName(
                    "com.android.quickstep.views.OplusTaskMenuViewImpl", false, cl);
            for (Method m : menu.getDeclaredMethods()) {
                String name = m.getName();
                if (m.isBridge() || m.isSynthetic()) continue;
                if (!(name.equals("populateAndShowForTask") || name.equals("animateOpenOrClosed"))) {
                    continue;
                }
                final String hooked = name;
                hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        if (!menuStyleEnabled()) return result;
                        if (hooked.equals("animateOpenOrClosed")
                                && Boolean.TRUE.equals(chain.getArg(0))) {
                            return result;
                        }
                        applyTaskMenuGlass(chain.getThisObject());
                        if (hooked.equals("animateOpenOrClosed")) {
                            scheduleTaskMenuGlassSettle(chain.getThisObject());
                        }
                    } catch (Throwable e) {
                        log(5, TAG, "OplusTaskMenuViewImpl." + hooked + " glass failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            log(5, TAG, "OplusTaskMenuViewImpl glass hooks unavailable", e);
        }

        after("com.oplus.quickstep.views.OplusTaskHeaderView", cl, "onFinishInflate",
                this::applyTaskHeaderShortcutGlass);
        try {
            Class<?> header = Class.forName(
                    "com.oplus.quickstep.views.OplusTaskHeaderView", false, cl);
            for (Method m : header.getDeclaredMethods()) {
                if (!m.getName().equals("showWindowIcon") || m.isBridge() || m.isSynthetic()) {
                    continue;
                }
                hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        if (!enabled()) return result;
                        Object arg0 = chain.getArg(0);
                        if (arg0 instanceof View) {
                            applyTaskHeaderShortcutButtonGlass((View) arg0);
                        }
                        applyTaskHeaderShortcutGlass(chain.getThisObject());
                    } catch (Throwable e) {
                        log(5, TAG, "OplusTaskHeaderView.showWindowIcon glass failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            log(5, TAG, "OplusTaskHeaderView shortcut glass hooks unavailable", e);
        }
    }

    /**
     * From-app swipe RapidReaction capsules (分屏 / 浮窗). OEM paints opaque rounded rects
     * in {@code MultiRectangleBackgroundView}; replace those fills with LiquidGlass hosts
     * sized to each {@code RectPaint} and keep icon/title content above.
     */
    private void hookRecentsRapidReactionGlass(ClassLoader cl) {
        after("com.oplus.quickstep.rapidreaction.widget.MultiTriggerPanelView", cl,
                "updateViewVisibility", this::applyRapidReactionGlass);
        after("com.oplus.quickstep.rapidreaction.widget.MultiTriggerPanelView", cl,
                "onLayout", this::applyRapidReactionGlass);
        try {
            Class<?> panel = Class.forName(
                    "com.oplus.quickstep.rapidreaction.widget.MultiTriggerPanelView", false, cl);
            for (Method m : panel.getDeclaredMethods()) {
                String name = m.getName();
                if (m.isBridge() || m.isSynthetic()) continue;
                if (!(name.equals("updateAppSupportState")
                        || name.equals("updateProgress")
                        || name.equals("initAnimation")
                        || name.equals("setVisibility"))) {
                    continue;
                }
                final String hooked = name;
                hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        if (enabled()) applyRapidReactionGlass(chain.getThisObject());
                    } catch (Throwable e) {
                        log(5, TAG, "MultiTriggerPanelView." + hooked + " glass failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            log(5, TAG, "MultiTriggerPanelView glass hooks unavailable", e);
        }
        try {
            Class<?> bg = Class.forName(
                    "com.oplus.quickstep.rapidreaction.widget.MultiRectangleBackgroundView",
                    false, cl);
            for (Method m : bg.getDeclaredMethods()) {
                if (!m.getName().equals("onDraw") || m.getParameterCount() != 1
                        || m.isBridge() || m.isSynthetic()) continue;
                hookOnce(m, chain -> {
                    try {
                        if (enabled()) clearRapidReactionOpaqueFills(chain.getThisObject());
                    } catch (Throwable ignored) { }
                    return chain.proceed();
                });
            }
        } catch (Throwable e) {
            log(5, TAG, "MultiRectangleBackgroundView.onDraw glass hook unavailable", e);
        }
    }

    private void applyRapidReactionGlass(Object panel) {
        if (!enabled() || !(panel instanceof ViewGroup)) return;
        final ViewGroup host = (ViewGroup) panel;
        if (host.getVisibility() != View.VISIBLE) return;
        try {
            Object bg = field(panel, "hintDoubleRectBackgroundView");
            if (!(bg instanceof View)) return;
            Object paints = field(bg, "mBgRectPaints");
            if (!(paints instanceof Iterable<?>)) return;
            float radius = readRapidReactionRadius((View) bg);
            clearRapidReactionOpaqueFills(bg);
            for (Object rectPaint : (Iterable<?>) paints) {
                if (rectPaint == null) continue;
                Object linked = invokeNoArgs(rectPaint, "getMLinkedEntranceType");
                if (!isRapidSplitOrFloat(linked)) continue;
                android.graphics.RectF rect = rapidPaintDrawRect(rectPaint);
                if (rect == null || rect.width() < 2f || rect.height() < 2f) continue;
                // RectPaint coords are local to MultiRectangleBackgroundView.
                rect.offset(((View) bg).getLeft(), ((View) bg).getTop());
                String typeKey = String.valueOf(linked);
                View glassHost = findOrCreateRapidGlassHost(host, (View) bg, typeKey);
                int prevW = glassHost.getWidth();
                int prevH = glassHost.getHeight();
                layoutRapidGlassHost(glassHost, rect);
                // Capsules sit under the live app surface — sample desktop/wallpaper only.
                // Never attach TaskView overlay (that regression was introduced in 0778e1c).
                GlassInstaller.setOverlaySource(glassHost, null);
                boolean firstInstall = GlassInstaller.get(glassHost) == null;
                GlassInstaller.installBackground(glassHost, currentConfig());
                GlassDrawable live = GlassInstaller.get(glassHost);
                if (live != null && radius > 0f) {
                    live.setCornerRadii(radius, radius, radius, radius);
                }
                boolean sizeChanged = glassHost.getWidth() != prevW || glassHost.getHeight() != prevH;
                if (firstInstall || sizeChanged) {
                    GlassInstaller.forceCapture(glassHost);
                }
                glassHost.invalidate();
            }
        } catch (Throwable e) {
            log(5, TAG, "applyRapidReactionGlass failed", e);
        }
    }

    private static boolean isRapidSplitOrFloat(Object selectionOptions) {
        if (selectionOptions == null) return false;
        String name = selectionOptions.toString();
        return name.contains("SPLIT_WINDOW") || name.contains("FLOATING_WINDOW");
    }

    private static void clearRapidReactionOpaqueFills(Object bgView) {
        Object paints = field(bgView, "mBgRectPaints");
        if (!(paints instanceof Iterable<?>)) return;
        for (Object rectPaint : (Iterable<?>) paints) {
            if (rectPaint == null) continue;
            Object linked = invokeNoArgs(rectPaint, "getMLinkedEntranceType");
            if (!isRapidSplitOrFloat(linked)) continue;
            Object paint = invokeNoArgs(rectPaint, "getMPaint");
            if (paint instanceof android.graphics.Paint) {
                ((android.graphics.Paint) paint).setColor(0);
            }
        }
    }

    private static android.graphics.RectF rapidPaintDrawRect(Object rectPaint) {
        Object draw = field(rectPaint, "mDrawRect");
        if (draw instanceof android.graphics.RectF) {
            android.graphics.RectF r = (android.graphics.RectF) draw;
            if (r.width() > 1f && r.height() > 1f) return new android.graphics.RectF(r);
        }
        Object base = invokeNoArgs(rectPaint, "getMBaseRect");
        if (!(base instanceof android.graphics.RectF)) base = field(rectPaint, "mBaseRect");
        if (!(base instanceof android.graphics.RectF)) return null;
        android.graphics.RectF rect = new android.graphics.RectF((android.graphics.RectF) base);
        Object tx = invokeNoArgs(rectPaint, "getMTranslationX");
        Object ty = invokeNoArgs(rectPaint, "getMTranslationY");
        float ox = tx instanceof Number ? ((Number) tx).floatValue() : 0f;
        float oy = ty instanceof Number ? ((Number) ty).floatValue() : 0f;
        rect.offset(ox, oy);
        Object sx = invokeNoArgs(rectPaint, "getMScaleX");
        Object sy = invokeNoArgs(rectPaint, "getMScaleY");
        float scaleX = sx instanceof Number ? ((Number) sx).floatValue() : 1f;
        float scaleY = sy instanceof Number ? ((Number) sy).floatValue() : 1f;
        if (Math.abs(scaleX - 1f) > 0.001f || Math.abs(scaleY - 1f) > 0.001f) {
            float cx = rect.centerX();
            float cy = rect.centerY();
            float w = rect.width() * scaleX;
            float h = rect.height() * scaleY;
            rect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
        }
        return rect;
    }

    private static final String RAPID_GLASS_TAG_PREFIX = "colg_rapid_";

    private static View findOrCreateRapidGlassHost(ViewGroup panel, View bgView, String typeKey) {
        String tag = RAPID_GLASS_TAG_PREFIX + typeKey;
        for (int i = 0; i < panel.getChildCount(); i++) {
            View child = panel.getChildAt(i);
            if (child != null && tag.equals(child.getTag())) return child;
        }
        android.widget.FrameLayout host = new android.widget.FrameLayout(panel.getContext());
        host.setTag(tag);
        host.setClickable(false);
        host.setFocusable(false);
        host.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int index = panel.indexOfChild(bgView);
        if (index >= 0) panel.addView(host, index + 1);
        else panel.addView(host, 0);
        return host;
    }

    private static void layoutRapidGlassHost(View host, android.graphics.RectF rect) {
        int left = Math.round(rect.left);
        int top = Math.round(rect.top);
        int right = Math.round(rect.right);
        int bottom = Math.round(rect.bottom);
        int width = Math.max(1, right - left);
        int height = Math.max(1, bottom - top);
        ViewGroup.LayoutParams lp = host.getLayoutParams();
        if (lp == null) {
            host.setLayoutParams(new ViewGroup.LayoutParams(width, height));
        } else {
            lp.width = width;
            lp.height = height;
            host.setLayoutParams(lp);
        }
        host.layout(left, top, left + width, top + height);
        host.setVisibility(View.VISIBLE);
    }

    private static float readRapidReactionRadius(View view) {
        try {
            int id = view.getResources().getIdentifier(
                    "rapid_reaction_double_rect_background_smooth_corner_radius",
                    "dimen", view.getContext().getPackageName());
            if (id != 0) return view.getResources().getDimension(id);
        } catch (Throwable ignored) { }
        return 9f * view.getResources().getDisplayMetrics().density;
    }

    private View findRunningTaskViewForOverlay(Object panelOrMenu) {
        try {
            Object swipeRef = field(panelOrMenu, "swipeUpHandlerRef");
            Object swipe = swipeRef instanceof java.lang.ref.WeakReference
                    ? ((java.lang.ref.WeakReference<?>) swipeRef).get()
                    : null;
            Object recents = swipe != null ? field(swipe, "mRecentsView") : null;
            if (recents == null) recents = invokeNoArgs(swipe, "getRecentsView");
            if (recents == null) {
                Object launcher = findActiveLauncher();
                recents = invokeNoArgs(launcher, "getOverviewPanel");
            }
            Object running = invokeNoArgs(recents, "getRunningTaskView");
            if (running instanceof View) return (View) running;
            Object current = invokeNoArgs(recents, "getCurrentPageTaskView");
            if (current instanceof View) return (View) current;
        } catch (Throwable ignored) { }
        return null;
    }

    /**
     * Menu open anim scales 0.9→1 over ~400ms. Re-bake protect and forceCapture when it
     * lands so the glass sample is not left at a ~0.99-scale freeze (upward jump / shrink).
     */
    private void scheduleTaskMenuGlassSettle(Object menu) {
        if (!(menu instanceof View)) return;
        final View host = (View) menu;
        Object listObj = field(menu, "mListView");
        final View glassHost = listObj instanceof View ? (View) listObj : host;
        final View taskView = resolveTaskMenuTaskView(menu);
        Runnable settle = new Runnable() {
            int tries = 0;
            @Override public void run() {
                try {
                    if (!enabled() || !glassHost.isAttachedToWindow()) return;
                    if ((Math.abs(host.getScaleX() - 1f) > 0.02f
                            || Math.abs(host.getScaleY() - 1f) > 0.02f)
                            && tries++ < 12) {
                        host.postDelayed(this, 50L);
                        return;
                    }
                    if (taskView != null) {
                        TaskContentOverlay.clearProtectCache(taskView);
                        TaskContentOverlay.prebakeProtect(taskView);
                    }
                    GlassInstaller.forceCapture(glassHost);
                    glassHost.invalidate();
                } catch (Throwable t) {
                    log(5, TAG, "task menu glass settle failed", t);
                }
            }
        };
        host.postDelayed(settle, 400L);
    }

    /** LiquidGlass behind the Recents ··· popup that also lists 分屏 / 浮窗. */
    private void applyTaskMenuGlass(Object menu) {
        if (!menuStyleEnabled() || !(menu instanceof View)) return;
        final View host = (View) menu;
        try {
            Object listObj = field(menu, "mListView");
            final View listView = listObj instanceof View ? (View) listObj : null;
            clearTaskMenuOpaqueChrome(menu, listView);

            // Prefer ListView as glass host: it owns the rounded popup panel bounds.
            final View glassHost = listView != null ? listView : host;
            if (listView != null && host.getBackground() instanceof GlassDrawable) {
                // Avoid double glass if we previously installed on the LinearLayout wrapper.
                GlassInstaller.uninstall(host);
            }
            if (host != glassHost) host.setBackground(null);

            GlassInstaller.installBackground(glassHost, currentConfig());
            View taskView = resolveTaskMenuTaskView(menu);
            if (taskView != null) {
                GlassInstaller.setOverlaySource(glassHost, taskView);
                // Bake protect content-layer BEFORE open animation so glass never shows an
                // empty/wrong plate over the real mask (ghosting) or rebakes at expand-max.
                TaskContentOverlay.prebakeProtect(taskView);
            }
            final View taskOverlay = taskView;
            Runnable refresh = () -> {
                clearTaskMenuOpaqueChrome(menu, listView);
                if (!(glassHost.getBackground() instanceof GlassDrawable)) {
                    GlassInstaller.installBackground(glassHost, currentConfig());
                }
                GlassDrawable live = GlassInstaller.get(glassHost);
                if (live == null) return;
                float radius = readTaskMenuRadius(glassHost);
                if (radius > 0f) live.setCornerRadii(radius, radius, radius, radius);
                if (glassHost.getWidth() <= 0 || glassHost.getHeight() <= 0) return;
                if (taskOverlay != null) {
                    GlassInstaller.setOverlaySource(glassHost, taskOverlay);
                }
                GlassInstaller.forceCapture(glassHost);
                glassHost.invalidate();
                log(4, TAG, "TaskMenu glass ready size="
                        + glassHost.getWidth() + "x" + glassHost.getHeight()
                        + " radius=" + radius
                        + " taskOverlay=" + (taskOverlay != null)
                        + " host=" + glassHost.getClass().getSimpleName());
            };
            if (glassHost.getWidth() > 0 && glassHost.getHeight() > 0) {
                refresh.run();
                // One follow-up after first layout — protect cache is locked so this cannot jump.
                glassHost.post(refresh);
            } else {
                glassHost.post(refresh);
                glassHost.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                    @Override
                    public void onLayoutChange(View v, int left, int top, int right, int bottom,
                            int oldLeft, int oldTop, int oldRight, int oldBottom) {
                        if (v.getWidth() > 0 && v.getHeight() > 0) {
                            v.removeOnLayoutChangeListener(this);
                            if (taskOverlay != null) {
                                TaskContentOverlay.prebakeProtect(taskOverlay);
                            }
                            refresh.run();
                        }
                    }
                });
            }
        } catch (Throwable e) {
            log(5, TAG, "applyTaskMenuGlass failed", e);
        }
    }

    private View resolveTaskMenuTaskView(Object menu) {
        Object taskView = field(menu, "mTaskView");
        if (taskView instanceof View) return (View) taskView;
        Object via = invokeNoArgs(menu, "getTaskView");
        if (via instanceof View) return (View) via;
        Object container = field(menu, "mTaskContainer");
        Object fromContainer = invokeNoArgs(container, "getTaskView");
        return fromContainer instanceof View ? (View) fromContainer : null;
    }

    /**
     * Strip opaque OEM chrome that sits above LiquidGlass: ListView popup drawable,
     * RoundFrameLayout white fill, and the #ebebeb group divider between 分屏 and 锁定.
     */
    private void clearTaskMenuOpaqueChrome(Object menu, View listView) {
        if (listView != null) {
            // Never wipe an already-installed GlassDrawable — that left a clear hole
            // showing only the TaskView content underneath.
            if (!(listView.getBackground() instanceof GlassDrawable)) {
                listView.setBackground(null);
            }
            Object parent = listView.getParent();
            if (parent instanceof View) {
                View roundFrame = (View) parent;
                if (!(roundFrame.getBackground() instanceof GlassDrawable)) {
                    roundFrame.setBackground(null);
                }
                try {
                    // clipMode 1 forces setBackgroundColor(-1); keep clip but no opaque fill.
                    invoke(roundFrame, "setClipMode", new Class<?>[] { int.class }, 0);
                } catch (Throwable ignored) { }
                if (!(roundFrame.getBackground() instanceof GlassDrawable)) {
                    roundFrame.setBackground(null);
                }
            }
            if (listView instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) listView;
                clearTaskMenuDividerFills(group);
                if (group.getTag() != TAG_TASK_MENU_HIERARCHY) {
                    group.setTag(TAG_TASK_MENU_HIERARCHY);
                    group.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
                        @Override
                        public void onChildViewAdded(View parent, View child) {
                            clearTaskMenuDividerFillsIn(child);
                        }

                        @Override
                        public void onChildViewRemoved(View parent, View child) { }
                    });
                }
            }
        }
        if (menu instanceof View) {
            View menuView = (View) menu;
            // OEM solid shape on the wrapper would flash white around the ListView glass.
            if (!(menuView.getBackground() instanceof GlassDrawable)) {
                menuView.setBackground(null);
            }
        }
    }

    private static final Object TAG_TASK_MENU_HIERARCHY = new Object();

    private static void clearTaskMenuDividerFills(ViewGroup listView) {
        for (int i = 0; i < listView.getChildCount(); i++) {
            clearTaskMenuDividerFillsIn(listView.getChildAt(i));
        }
    }

    private static void clearTaskMenuDividerFillsIn(View row) {
        if (row == null) return;
        View groupDivider = findDescendantByIdName(row, "menu_divider");
        if (groupDivider != null) {
            groupDivider.setBackground(null);
            if (groupDivider instanceof ImageView) {
                ((ImageView) groupDivider).setImageDrawable(null);
            }
            groupDivider.setVisibility(View.INVISIBLE);
        }
        View itemDivider = findDescendantByIdName(row, "divider");
        if (itemDivider != null) {
            itemDivider.setBackground(null);
            if (itemDivider instanceof ImageView) {
                ((ImageView) itemDivider).setImageDrawable(null);
            }
        }
    }

    private static View findDescendantByIdName(View root, String idName) {
        if (root == null || idName == null) return null;
        try {
            int id = root.getResources().getIdentifier(
                    idName, "id", root.getContext().getPackageName());
            if (id != 0) {
                View found = root.findViewById(id);
                if (found != null) return found;
            }
        } catch (Throwable ignored) { }
        if (!(root instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            View found = findDescendantByIdName(group.getChildAt(i), idName);
            if (found != null) return found;
        }
        return null;
    }

    private static float readTaskMenuRadius(View view) {
        try {
            int id = view.getResources().getIdentifier(
                    "coui_round_corner_m", "dimen", view.getContext().getPackageName());
            if (id != 0) return view.getResources().getDimension(id);
        } catch (Throwable ignored) { }
        return 12f * view.getResources().getDisplayMetrics().density;
    }

    /**
     * ColorOS 16 lock-screen notification <b>capsule</b> style only
     * ({@code CapsuleBackgroundView}). Do not touch notification-list / stack rows, and never
     * route through {@link Os14HookBackend}.
     * <p>
     * OEM paints material blur in {@code CapsuleBackgroundHelper.drawCapsuleBg}; skip that once
     * LiquidGlass is installed as the view background and sample lock wallpaper only.
     */
    private void hookLockScreenCapsuleGlass(ClassLoader cl) {
        final String bgClass =
                "com.oplus.systemui.notification.lockscreen.capsule.CapsuleBackgroundView";
        if (!hasClass(cl, bgClass)) {
            log(4, TAG, "hookLockScreenCapsuleGlass skipped (no CapsuleBackgroundView)");
            return;
        }
        log(4, TAG, "hookLockScreenCapsuleGlass Cos16");
        after(bgClass, cl, "onAttachedToWindow", this::applyLockScreenCapsuleGlass);
        after(bgClass, cl, "onLayout", this::applyLockScreenCapsuleGlass);
        try {
            Class<?> helper = Class.forName(
                    "com.oplus.systemui.notification.lockscreen.capsule.CapsuleBackgroundHelper",
                    false, cl);
            for (Method m : helper.getDeclaredMethods()) {
                if (!"drawCapsuleBg".equals(m.getName()) || m.isBridge() || m.isSynthetic()) {
                    continue;
                }
                Class<?>[] params = m.getParameterTypes();
                if (params.length != 2 || !View.class.isAssignableFrom(params[0])) continue;
                hookOnce(m, chain -> {
                    try {
                        java.util.List<Object> args = chain.getArgs();
                        if (args != null && !args.isEmpty() && args.get(0) instanceof View
                                && GlassInstaller.get((View) args.get(0)) != null) {
                            GlassConfig config = currentConfig();
                            if (!config.enabled || !config.notificationGlassEnabled) {
                                GlassInstaller.uninstall((View) args.get(0));
                                return chain.proceed();
                            }
                            // Glass is already the background — skip OEM blur / solid fill.
                            return null;
                        }
                    } catch (Throwable ignored) { }
                    return chain.proceed();
                });
            }
        } catch (Throwable e) {
            log(5, TAG, "CapsuleBackgroundHelper.drawCapsuleBg hook unavailable", e);
        }
    }

    private void applyLockScreenCapsuleGlass(Object bgObj) {
        if (!(bgObj instanceof View)) return;
        final View bg = (View) bgObj;
        GlassConfig config = currentConfig();
        if (!config.enabled || !config.notificationGlassEnabled) {
            if (GlassInstaller.get(bg) != null) GlassInstaller.uninstall(bg);
            return;
        }
        if (!isClassOrSubclass(bg,
                "com.oplus.systemui.notification.lockscreen.capsule.CapsuleBackgroundView")) {
            return;
        }
        // Capsule cards only — never style unrelated SystemUI views that might share helpers.
        if (!isUnderLockScreenCapsuleItem(bg)) return;
        if (bg.getVisibility() == View.GONE) return;
        try {
            GlassInstaller.markLockWallpaperOnly(bg);
            boolean firstInstall = GlassInstaller.get(bg) == null;
            if (firstInstall) {
                GlassInstaller.installBackground(bg, currentConfig());
            }
            GlassDrawable live = GlassInstaller.get(bg);
            if (live == null) return;
            Rect clip = readLockScreenCapsuleClipRect(bg);
            float radius = readLockScreenCapsuleRadius(bg, clip);
            if (radius > 0f) {
                live.setCornerRadii(radius, radius, radius, radius);
            }
            // GlassDrawable.draw re-applies clipRect bounds each frame (list→capsule shrink).
            int clipW = clip != null ? clip.width() : bg.getWidth();
            int clipH = clip != null ? clip.height() : bg.getHeight();
            int prevW = lastCapsuleClipW.get(bg) == null ? -1 : lastCapsuleClipW.get(bg);
            int prevH = lastCapsuleClipH.get(bg) == null ? -1 : lastCapsuleClipH.get(bg);
            boolean clipChanged = clipW > 0 && clipH > 0
                    && (clipW != prevW || clipH != prevH);
            if (clipW > 0 && clipH > 0) {
                lastCapsuleClipW.put(bg, clipW);
                lastCapsuleClipH.put(bg, clipH);
            }
            if (bg.getWidth() > 0 && bg.getHeight() > 0) {
                if (firstInstall || clipChanged) {
                    GlassInstaller.forceCapture(bg);
                }
                bg.invalidate();
            } else {
                bg.post(() -> applyLockScreenCapsuleGlass(bg));
            }
        } catch (Throwable e) {
            log(5, TAG, "applyLockScreenCapsuleGlass failed", e);
        }
    }

    private final Map<View, Integer> lastCapsuleClipW =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<View, Integer> lastCapsuleClipH =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static boolean isUnderLockScreenCapsuleItem(View view) {
        for (View current = view; current != null; ) {
            if (isClassOrSubclass(current,
                    "com.oplus.systemui.notification.lockscreen.capsule.CapsuleItemView")) {
                return true;
            }
            if (isClassOrSubclass(current,
                    "com.oplus.systemui.notification.lockscreen.capsule.NotificationCapsuleContainer")) {
                return true;
            }
            ViewParent parent = current.getParent();
            if (!(parent instanceof View)) break;
            current = (View) parent;
        }
        return false;
    }

    private static Rect readLockScreenCapsuleClipRect(View bg) {
        try {
            Object helper = invokeNoArgs(bg, "getCapsuleHelper");
            Object pathProvider = field(helper, "pathProvider");
            Object clip = invokeNoArgs(pathProvider, "getClipRect");
            if (clip instanceof Rect && !((Rect) clip).isEmpty()) {
                return new Rect((Rect) clip);
            }
        } catch (Throwable ignored) { }
        try {
            for (View current = bg; current != null; ) {
                if (isClassOrSubclass(current,
                        "com.oplus.systemui.notification.lockscreen.capsule.CapsuleItemView")) {
                    Object location = field(current, "location");
                    Object clip = invokeNoArgs(location, "getClipRect");
                    if (clip instanceof Rect && !((Rect) clip).isEmpty()) {
                        return new Rect((Rect) clip);
                    }
                    break;
                }
                ViewParent parent = current.getParent();
                if (!(parent instanceof View)) break;
                current = (View) parent;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static float readLockScreenCapsuleRadius(View bg, Rect clip) {
        if (clip != null && clip.width() > 0 && clip.height() > 0) {
            return Math.min(clip.width(), clip.height()) / 2f;
        }
        try {
            Object helper = invokeNoArgs(bg, "getCapsuleHelper");
            Object pathProvider = field(helper, "pathProvider");
            if (pathProvider != null) {
                Object meta = field(pathProvider, "pathMetaData");
                Object radius = meta != null ? field(meta, "radius") : null;
                if (radius == null) radius = invokeNoArgs(pathProvider, "getRadius");
                if (radius instanceof Number && ((Number) radius).floatValue() > 0f) {
                    return ((Number) radius).floatValue();
                }
            }
        } catch (Throwable ignored) { }
        try {
            Class<?> cfg = Class.forName(
                    "com.oplus.systemui.notification.lockscreen.config.CapsuleConfig",
                    false, bg.getClass().getClassLoader());
            java.lang.reflect.Field f = cfg.getDeclaredField("defaultRadius");
            f.setAccessible(true);
            float radius = f.getFloat(null);
            if (radius > 0f) return radius;
            Object instance = cfg.getField("INSTANCE").get(null);
            Object height = invokeNoArgs(instance, "getItemHeight");
            if (height instanceof Number && ((Number) height).intValue() > 0) {
                return ((Number) height).intValue() / 2f;
            }
        } catch (Throwable ignored) { }
        int h = bg.getHeight();
        if (h > 0) return h / 2f;
        try {
            int id = bg.getResources().getIdentifier(
                    "capsule_item_height", "dimen", bg.getContext().getPackageName());
            if (id != 0) return bg.getResources().getDimension(id) / 2f;
        } catch (Throwable ignored) { }
        return 24f * bg.getResources().getDisplayMetrics().density;
    }

    private static float readLockScreenCapsuleRadius(View bg) {
        return readLockScreenCapsuleRadius(bg, readLockScreenCapsuleClipRect(bg));
    }

    /**
     * ColorOS 16 lock-screen 快捷入口. Cos14 uses {@link Os14HookBackend}.
     * <p>
     * Cos16 binder only exposes Kotlin synthetic {@code access$updateButton}, paints a material
     * {@code BackgroundTintList} onto whatever background we install, and keeps the resting disc
     * in src LayerDrawable layers. Sample wallpaper-only (keyguard root.draw is empty/scrim).
     */
    private final Map<ImageView, Drawable> keyguardAffordancePunched =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final ThreadLocal<Boolean> keyguardAffordanceApplying =
            ThreadLocal.withInitial(() -> false);
    /** True briefly after KeyguardAffordanceHelper.reset — block OEM default chrome. */
    private final AtomicBoolean keyguardAffordanceCancelGuard = new AtomicBoolean(false);
    private Runnable keyguardAffordanceCancelGuardClear;
    private WeakReference<ImageView> keyguardAffordanceStartBtn = new WeakReference<>(null);
    private WeakReference<ImageView> keyguardAffordanceEndBtn = new WeakReference<>(null);
    private final ThreadLocal<Boolean> keyguardAffordanceSuppressingDiscs =
            ThreadLocal.withInitial(() -> false);

    private void hookKeyguardAffordanceGlass(ClassLoader cl) {
        log(4, TAG, "hookKeyguardAffordanceGlass Cos16");
        after("com.oplus.systemui.keyguard.view.OplusLaunchableImageView", cl, "onAttachedToWindow",
                button -> applyKeyguardAffordanceGlass(button, false));
        try {
            Class<?> viewClass = Class.forName(
                    "com.oplus.systemui.keyguard.view.OplusLaunchableImageView", false, cl);
            for (Method m : viewClass.getDeclaredMethods()) {
                if (m.isBridge() || m.isSynthetic()) continue;
                String name = m.getName();
                if (name.equals("setImageDrawable") || name.equals("setImageResource")) {
                    hookOnce(m, chain -> {
                        Object self = chain.getThisObject();
                        try {
                            // Our icon-only wrapper must always be allowed through.
                            if (name.equals("setImageDrawable")
                                    && !chain.getArgs().isEmpty()
                                    && chain.getArg(0)
                                    instanceof KeyguardAffordanceIconOnlyDrawable) {
                                return chain.proceed();
                            }
                            // reset() → Flow → updateButton → setImage(OEM LayerDrawable).
                            // Block even if glass was briefly stolen — requiring hasGlass let
                            // OEM discs land and flash after the white circle hit 0.
                            if (shouldBlockKeyguardAffordanceImageRebind(self)) {
                                assertKeyguardAffordanceGlassChrome(self, false);
                                return null;
                            }
                        } catch (Throwable e) {
                            log(5, TAG, "OplusLaunchableImageView." + name
                                    + " cancel block failed", e);
                        }
                        Object result = chain.proceed();
                        try {
                            if (keyguardAffordanceCancelGuard.get()
                                    && isKeyguardAffordanceButton(self)) {
                                suppressKeyguardAffordanceSrcDiscs(self);
                                assertKeyguardAffordanceGlassChrome(self, false);
                            }
                            if (Boolean.TRUE.equals(keyguardAffordanceApplying.get())
                                    || Boolean.TRUE.equals(
                                            keyguardAffordanceSuppressingDiscs.get())) {
                                return result;
                            }
                            applyKeyguardAffordanceGlass(self, false);
                        } catch (Throwable e) {
                            log(5, TAG, "OplusLaunchableImageView." + name + " glass failed", e);
                        }
                        return result;
                    });
                } else if (name.equals("setImageAlpha") && m.getParameterTypes().length >= 1) {
                    hookOnce(m, chain -> {
                        Object result = chain.proceed();
                        try {
                            restoreKeyguardGlassDrawableAlpha(chain.getThisObject());
                        } catch (Throwable e) {
                            log(5, TAG, "setImageAlpha glass alpha restore failed", e);
                        }
                        return result;
                    });
                } else if (name.equals("setBackground") || name.equals("setBackgroundDrawable")) {
                    hookOnce(m, chain -> {
                        Object result = chain.proceed();
                        try {
                            restoreKeyguardGlassIfBackgroundStolen(chain.getThisObject());
                        } catch (Throwable e) {
                            log(5, TAG, name + " glass restore failed", e);
                        }
                        return result;
                    });
                } else if (name.equals("onDraw") && m.getParameterTypes().length == 1) {
                    // Before super.onDraw: src must be icon-only so radius→0 cannot uncover OEM
                    // discs. DeadDisc-via-setDrawable was defeated by LayerDrawable.mutate().
                    hookOnce(m, chain -> {
                        try {
                            Object self = chain.getThisObject();
                            if (enabled()
                                    && isKeyguardAffordanceButton(self)
                                    && (hasKeyguardAffordanceGlass(self)
                                            || keyguardAffordanceCancelGuard.get())) {
                                suppressKeyguardAffordanceSrcDiscs(self);
                                if (keyguardAffordanceCancelGuard.get()) {
                                    restoreKeyguardGlassDrawableAlpha(self);
                                }
                            }
                        } catch (Throwable ignored) { }
                        return chain.proceed();
                    });
                } else if (name.equals("updateIconColor") || name.equals("updateIconColor$1")
                        || name.startsWith("updateIconColor")) {
                    hookOnce(m, chain -> {
                        try {
                            Object self = chain.getThisObject();
                            // Strip discs BEFORE OEM mutate+SRC_ATOP on LayerDrawable children.
                            if (enabled() && isKeyguardAffordanceButton(self)) {
                                suppressKeyguardAffordanceSrcDiscs(self);
                            }
                        } catch (Throwable ignored) { }
                        Object result = chain.proceed();
                        try {
                            Object self = chain.getThisObject();
                            suppressKeyguardAffordanceSrcDiscs(self);
                            if (keyguardAffordanceCancelGuard.get()
                                    && self instanceof ImageView) {
                                clearKeyguardAffordanceBackgroundTint((ImageView) self);
                                restoreKeyguardGlassDrawableAlpha(self);
                                ensureGlassWhenAffordanceCircleGone(self);
                            }
                        } catch (Throwable e) {
                            log(5, TAG, name + " disc rehide failed", e);
                        }
                        return result;
                    });
                } else if (name.equals("setCircleRadius")
                        || name.equals("setCircleRadiusWithoutAnimation")) {
                    hookOnce(m, chain -> {
                        try {
                            Object self = chain.getThisObject();
                            if (enabled() && isKeyguardAffordanceButton(self)) {
                                suppressKeyguardAffordanceSrcDiscs(self);
                            }
                        } catch (Throwable ignored) { }
                        Object result = chain.proceed();
                        try {
                            ensureGlassWhenAffordanceCircleGone(chain.getThisObject());
                        } catch (Throwable e) {
                            log(5, TAG, name + " glass after circle failed", e);
                        }
                        return result;
                    });
                }
            }
        } catch (Throwable e) {
            log(5, TAG, "OplusLaunchableImageView glass hooks unavailable", e);
        }
        try {
            Method imageAlpha = ImageView.class.getDeclaredMethod("setImageAlpha", int.class);
            hookOnce(imageAlpha, chain -> {
                Object result = chain.proceed();
                try {
                    Object self = chain.getThisObject();
                    if (isKeyguardAffordanceButton(self) && hasKeyguardAffordanceGlass(self)) {
                        restoreKeyguardGlassDrawableAlpha(self);
                    }
                } catch (Throwable ignored) { }
                return result;
            });
        } catch (Throwable e) {
            log(5, TAG, "ImageView#setImageAlpha affordance hook unavailable", e);
        }
        // updateButton calls getDrawable().setTint AFTER setImage — re-suppress discs then.
        try {
            Method setTint = Drawable.class.getDeclaredMethod("setTint", int.class);
            hookOnce(setTint, chain -> {
                Object result = chain.proceed();
                try {
                    if (keyguardAffordanceCancelGuard.get()
                            && !Boolean.TRUE.equals(keyguardAffordanceSuppressingDiscs.get())) {
                        rehideTrackedKeyguardAffordanceDiscs();
                    }
                } catch (Throwable ignored) { }
                return result;
            });
            Method setTintList = Drawable.class.getDeclaredMethod(
                    "setTintList", ColorStateList.class);
            hookOnce(setTintList, chain -> {
                Object result = chain.proceed();
                try {
                    if (keyguardAffordanceCancelGuard.get()
                            && !Boolean.TRUE.equals(keyguardAffordanceSuppressingDiscs.get())) {
                        rehideTrackedKeyguardAffordanceDiscs();
                    }
                } catch (Throwable ignored) { }
                return result;
            });
        } catch (Throwable e) {
            log(5, TAG, "Drawable#setTint affordance cancel hooks unavailable", e);
        }
        hookKeyguardAffordanceUpdateButton(cl,
                "com.oplus.systemui.keyguard.ui.binder.OplusKeyguardBottomAreaViewBinder",
                "access$updateButton", 2, true);
        hookKeyguardAffordanceUpdateButton(cl,
                "com.oplus.systemui.keyguard.ui.binder.OplusKeyguardBottomAreaViewBinder",
                "updateButton", -1, false);
        after("com.oplus.systemui.keyguard.OplusKeyguardBottomAreaController", cl,
                "bindKeyguardBottomAreaView", controller -> {
                    try {
                        if (!enabled()) return;
                        applyKeyguardAffordanceGlass(
                                invokeNoArgs(controller, "findStartButton"), true);
                        applyKeyguardAffordanceGlass(
                                invokeNoArgs(controller, "findEndButton"), true);
                    } catch (Throwable e) {
                        log(5, TAG, "bindKeyguardBottomAreaView glass failed", e);
                    }
                });
        // BEFORE reset body: cancelGuard must be on before canLaunch* Flow → updateButton →
        // setImage, which runs synchronously inside reset().
        try {
            Class<?> helperClz = Class.forName(
                    "com.oplus.systemui.keyguard.KeyguardAffordanceHelper", false, cl);
            boolean foundReset = false;
            for (Method m : helperClz.getDeclaredMethods()) {
                if (!m.getName().equals("reset") || m.isBridge() || m.isSynthetic()) continue;
                foundReset = true;
                hookOnce(m, chain -> {
                    try {
                        if (enabled()) {
                            Object helper = chain.getThisObject();
                            beginKeyguardAffordanceCancelGuard();
                            Object style = field(helper, "oplusKeyguardStyleBaseUIController");
                            Object start = style != null
                                    ? invokeNoArgs(style, "getStartButton") : null;
                            Object end = style != null
                                    ? invokeNoArgs(style, "getEndButton") : null;
                            rememberKeyguardAffordanceButtons(start, end);
                            suppressKeyguardAffordanceSrcDiscs(start);
                            suppressKeyguardAffordanceSrcDiscs(end);
                            assertKeyguardAffordanceGlassChrome(start, false);
                            assertKeyguardAffordanceGlassChrome(end, false);
                        }
                    } catch (Throwable e) {
                        log(5, TAG, "KeyguardAffordanceHelper.reset before glass failed", e);
                    }
                    Object result = chain.proceed();
                    try {
                        if (!enabled()) return result;
                        Object helper = chain.getThisObject();
                        Object style = field(helper, "oplusKeyguardStyleBaseUIController");
                        Object start = style != null
                                ? invokeNoArgs(style, "getStartButton") : null;
                        Object end = style != null
                                ? invokeNoArgs(style, "getEndButton") : null;
                        suppressKeyguardAffordanceSrcDiscs(start);
                        suppressKeyguardAffordanceSrcDiscs(end);
                        assertKeyguardAffordanceGlassChrome(start, false);
                        assertKeyguardAffordanceGlassChrome(end, false);
                        settleKeyguardAffordanceGlassAfterCancel(start);
                        settleKeyguardAffordanceGlassAfterCancel(end);
                    } catch (Throwable e) {
                        log(5, TAG, "KeyguardAffordanceHelper.reset after glass failed", e);
                    }
                    return result;
                });
            }
            if (!foundReset) {
                log(5, TAG, "Method not found: KeyguardAffordanceHelper.reset");
            }
        } catch (Throwable e) {
            log(5, TAG, "KeyguardAffordanceHelper.reset glass hook unavailable", e);
        }
        try {
            Method tint = View.class.getDeclaredMethod(
                    "setBackgroundTintList", ColorStateList.class);
            hookOnce(tint, chain -> {
                Object self = chain.getThisObject();
                Object tintArg = chain.getArgs().isEmpty() ? null : chain.getArgs().get(0);
                if (tintArg != null
                        && enabled()
                        && isKeyguardAffordanceButton(self)
                        && (hasKeyguardAffordanceGlass(self)
                        || keyguardAffordanceCancelGuard.get())) {
                    if (keyguardAffordanceCancelGuard.get()) {
                        restoreKeyguardGlassIfBackgroundStolen(self);
                    }
                    return null;
                }
                return chain.proceed();
            });
            log(4, TAG, "Hooked View#setBackgroundTintList for keyguard affordance glass");
        } catch (Throwable e) {
            log(5, TAG, "setBackgroundTintList affordance hook unavailable", e);
        }
    }

    private boolean shouldBlockKeyguardAffordanceImageRebind(Object buttonObj) {
        if (!keyguardAffordanceCancelGuard.get()) return false;
        if (Boolean.TRUE.equals(keyguardAffordanceApplying.get())) return false;
        if (Boolean.TRUE.equals(keyguardAffordanceSuppressingDiscs.get())) return false;
        if (!enabled() || !isKeyguardAffordanceButton(buttonObj)) return false;
        // Do not require hasGlass — glass can be briefly missing while OEM still rebinds.
        return true;
    }

    /**
     * When the finger-follow white circle hits 0, keep existing glass + icon-only src.
     * Never forceCapture here — wallpaper-only forced capture promotes a new frame and
     * briefly exposes OEM discs (looks like default → glass reinstall).
     */
    private void ensureGlassWhenAffordanceCircleGone(Object buttonObj) {
        if (!keyguardAffordanceCancelGuard.get()) return;
        if (!enabled() || !(buttonObj instanceof ImageView)) return;
        if (!isKeyguardAffordanceButton(buttonObj)) return;
        Object radius = field(buttonObj, "mCircleRadius");
        if (!(radius instanceof Number)) return;
        if (((Number) radius).floatValue() > 1f) return;
        ImageView button = (ImageView) buttonObj;
        suppressKeyguardAffordanceSrcDiscs(button);
        clearKeyguardAffordanceBackgroundTint(button);
        restoreKeyguardGlassDrawableAlpha(button);
        if (GlassInstaller.get(button) == null
                && !(unwrapInsetDrawable(button.getBackground()) instanceof GlassDrawable)) {
            applyKeyguardAffordanceGlass(button, true);
        } else {
            assertKeyguardAffordanceGlassChrome(button, false);
        }
    }

    private void beginKeyguardAffordanceCancelGuard() {
        keyguardAffordanceCancelGuard.set(true);
        if (keyguardAffordanceCancelGuardClear != null) {
            mainHandler.removeCallbacks(keyguardAffordanceCancelGuardClear);
        }
        keyguardAffordanceCancelGuardClear = () -> {
            keyguardAffordanceCancelGuard.set(false);
            try {
                ImageView start = keyguardAffordanceStartBtn.get();
                ImageView end = keyguardAffordanceEndBtn.get();
                if (start != null) {
                    suppressKeyguardAffordanceSrcDiscs(start);
                    assertKeyguardAffordanceGlassChrome(start, false);
                }
                if (end != null) {
                    suppressKeyguardAffordanceSrcDiscs(end);
                    assertKeyguardAffordanceGlassChrome(end, false);
                }
            } catch (Throwable ignored) { }
        };
        // Cover async Flow rebinds after reset's synchronous updateButton.
        mainHandler.postDelayed(keyguardAffordanceCancelGuardClear, 1200L);
    }

    private void rememberKeyguardAffordanceButtons(Object start, Object end) {
        trackKeyguardAffordanceButton(start);
        trackKeyguardAffordanceButton(end);
    }

    private void trackKeyguardAffordanceButton(Object buttonObj) {
        if (!(buttonObj instanceof ImageView)) return;
        ImageView iv = (ImageView) buttonObj;
        String name = null;
        try {
            int id = iv.getId();
            if (id != View.NO_ID) name = iv.getResources().getResourceEntryName(id);
        } catch (Throwable ignored) { }
        if ("start_button".equals(name)) {
            keyguardAffordanceStartBtn = new WeakReference<>(iv);
        } else if ("end_button".equals(name)) {
            keyguardAffordanceEndBtn = new WeakReference<>(iv);
        } else if (isKeyguardAffordanceButton(iv)) {
            if (keyguardAffordanceStartBtn.get() == null) {
                keyguardAffordanceStartBtn = new WeakReference<>(iv);
            } else if (keyguardAffordanceEndBtn.get() == null
                    && keyguardAffordanceStartBtn.get() != iv) {
                keyguardAffordanceEndBtn = new WeakReference<>(iv);
            }
        }
    }

    private void rehideTrackedKeyguardAffordanceDiscs() {
        suppressKeyguardAffordanceSrcDiscs(keyguardAffordanceStartBtn.get());
        suppressKeyguardAffordanceSrcDiscs(keyguardAffordanceEndBtn.get());
    }

    private static boolean hasKeyguardAffordanceGlass(Object buttonObj) {
        if (!(buttonObj instanceof ImageView)) return false;
        ImageView button = (ImageView) buttonObj;
        return GlassInstaller.get(button) != null
                || unwrapInsetDrawable(button.getBackground()) instanceof GlassDrawable;
    }

    private void hookKeyguardAffordanceUpdateButton(ClassLoader cl, String className,
            String methodName, int imageViewArgIndex, boolean allowSynthetic) {
        try {
            Class<?> c = Class.forName(className, false, cl);
            boolean found = false;
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(methodName) || m.isBridge()) continue;
                if (m.isSynthetic() && !allowSynthetic) continue;
                found = true;
                final int fixedIndex = imageViewArgIndex;
                hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        if (!enabled()) return result;
                        Object button = null;
                        java.util.List<Object> args = chain.getArgs();
                        if (fixedIndex >= 0 && fixedIndex < args.size()
                                && args.get(fixedIndex) instanceof ImageView) {
                            button = args.get(fixedIndex);
                        } else {
                            for (Object arg : args) {
                                if (arg instanceof ImageView && isKeyguardAffordanceButton(arg)) {
                                    button = arg;
                                    break;
                                }
                            }
                            if (button == null) {
                                for (Object arg : args) {
                                    if (arg instanceof ImageView) {
                                        button = arg;
                                        break;
                                    }
                                }
                            }
                        }
                        // Trust the binder ImageView (plugin clock may not match id/class filters).
                        if (button != null) {
                            trackKeyguardAffordanceButton(button);
                            applyKeyguardAffordanceGlass(button, true);
                            // setTint runs inside updateButton after setImage — suppress again.
                            if (keyguardAffordanceCancelGuard.get()) {
                                suppressKeyguardAffordanceSrcDiscs(button);
                                assertKeyguardAffordanceGlassChrome(button, false);
                            }
                        }
                    } catch (Throwable e) {
                        log(5, TAG, className + "." + methodName + " glass failed", e);
                    }
                    return result;
                });
                log(4, TAG, "Hooked " + className + "#" + methodName
                        + " synthetic=" + m.isSynthetic());
            }
            if (!found) {
                log(5, TAG, "Method not found: " + className + "." + methodName
                        + " allowSynthetic=" + allowSynthetic);
            }
        } catch (Throwable e) {
            log(5, TAG, "Class unavailable: " + className, e);
        }
    }

    private static boolean isKeyguardAffordanceButton(Object viewObj) {
        if (!(viewObj instanceof View)) return false;
        View view = (View) viewObj;
        if (isClassOrSubclass(view, "com.oplus.systemui.keyguard.view.OplusLaunchableImageView")
                || isClassOrSubclass(view,
                "com.android.systemui.animation.view.LaunchableImageView")) {
            return true;
        }
        try {
            int id = view.getId();
            if (id != View.NO_ID) {
                String name = view.getResources().getResourceEntryName(id);
                if ("start_button".equals(name) || "end_button".equals(name)) return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private void applyKeyguardAffordanceGlass(Object buttonObj) {
        applyKeyguardAffordanceGlass(buttonObj, false);
    }

    private void applyKeyguardAffordanceGlass(Object buttonObj, boolean trustBinderView) {
        if (!enabled() || !(buttonObj instanceof ImageView)) return;
        if (Boolean.TRUE.equals(keyguardAffordanceApplying.get())) return;
        final ImageView button = (ImageView) buttonObj;
        if (!trustBinderView && !isKeyguardAffordanceButton(button)) return;
        if (button.getVisibility() != View.VISIBLE) return;
        trackKeyguardAffordanceButton(button);
        keyguardAffordanceApplying.set(true);
        try {
            suppressKeyguardAffordanceOemChrome(button);
            clearKeyguardAffordanceBackgroundTint(button);
            GlassInstaller.markLockWallpaperOnly(button);
            // First install only — reinstall peels InsetDrawable / detectRadii.
            if (GlassInstaller.get(button) == null) {
                GlassInstaller.installBackground(button, currentConfig());
            }
            clearKeyguardAffordanceBackgroundTint(button);
            Runnable refresh = () -> {
                if (!enabled() || button.getVisibility() != View.VISIBLE) return;
                if (Boolean.TRUE.equals(keyguardAffordanceApplying.get())) return;
                keyguardAffordanceApplying.set(true);
                try {
                    suppressKeyguardAffordanceOemChrome(button);
                    clearKeyguardAffordanceBackgroundTint(button);
                    GlassInstaller.markLockWallpaperOnly(button);
                    if (GlassInstaller.get(button) == null
                            && !(button.getBackground() instanceof GlassDrawable)
                            && !(button.getBackground() instanceof InsetDrawable)) {
                        GlassInstaller.installBackground(button, currentConfig());
                    }
                    ensureKeyguardAffordanceGlassPlate(button,
                            !keyguardAffordanceCancelGuard.get());
                } finally {
                    keyguardAffordanceApplying.set(false);
                }
            };
            // One layout settle + one post — avoid 120/400 multi forceCapture on lock screen.
            scheduleKeyguardAffordanceGlassRefresh(button, refresh);
        } catch (Throwable e) {
            log(5, TAG, "applyKeyguardAffordanceGlass failed", e);
        } finally {
            keyguardAffordanceApplying.set(false);
        }
    }

    private void settleKeyguardAffordanceGlassAfterCancel(Object buttonObj) {
        if (!(buttonObj instanceof ImageView)) return;
        final ImageView button = (ImageView) buttonObj;
        if (button.getVisibility() != View.VISIBLE) return;
        if (!hasKeyguardAffordanceGlass(button) && GlassInstaller.get(button) == null) return;
        // Keep the already-sampled plate. Capture on this path caused OEM flash at circle-end.
        Runnable light = () -> assertKeyguardAffordanceGlassChrome(button, false);
        light.run();
        button.post(light);
        button.postDelayed(light, 50L);
        button.postDelayed(light, 160L);
        button.postDelayed(light, 320L);
    }

    /**
     * Keep glass chrome without replacing the ImageView src (preserves setImageScale).
     * @param capture whether to force wallpaper sample (skip on hot cancel path).
     */
    private void assertKeyguardAffordanceGlassChrome(Object buttonObj, boolean capture) {
        if (!(buttonObj instanceof ImageView)) return;
        if (!enabled()) return;
        ImageView button = (ImageView) buttonObj;
        if (button.getVisibility() != View.VISIBLE) return;
        if (GlassInstaller.get(button) == null
                && !(unwrapInsetDrawable(button.getBackground()) instanceof GlassDrawable)) {
            return;
        }
        boolean nested = Boolean.TRUE.equals(keyguardAffordanceApplying.get());
        if (!nested) keyguardAffordanceApplying.set(true);
        try {
            suppressKeyguardAffordanceSrcDiscs(button);
            clearKeyguardAffordanceBackgroundTint(button);
            restoreKeyguardGlassDrawableAlpha(button);
            ensureKeyguardAffordanceGlassPlate(button, capture);
            clearKeyguardAffordanceBackgroundTint(button);
        } catch (Throwable ignored) {
        } finally {
            if (!nested) keyguardAffordanceApplying.set(false);
        }
    }

    private void restoreKeyguardGlassIfBackgroundStolen(Object buttonObj) {
        if (!(buttonObj instanceof ImageView)) return;
        ImageView button = (ImageView) buttonObj;
        GlassDrawable live = GlassInstaller.get(button);
        if (live == null) return;
        Drawable bg = button.getBackground();
        Drawable inner = unwrapInsetDrawable(bg);
        if (inner == live || bg == live) return;
        log(5, TAG, "keyguard affordance background replaced → restoring glass "
                + (bg == null ? "null" : bg.getClass().getName()));
        // Always put glass back — even when nested under apply/assert (OEM setBackground
        // during cancel must not stick until delayed settle).
        boolean nested = Boolean.TRUE.equals(keyguardAffordanceApplying.get());
        if (!nested) keyguardAffordanceApplying.set(true);
        try {
            int pl = button.getPaddingLeft();
            int pt = button.getPaddingTop();
            int pr = button.getPaddingRight();
            int pb = button.getPaddingBottom();
            if ((pl | pt | pr | pb) != 0) {
                button.setBackground(new InsetDrawable(live, pl, pt, pr, pb));
            } else {
                button.setBackground(live);
            }
            clearKeyguardAffordanceBackgroundTint(button);
            restoreKeyguardGlassDrawableAlpha(button);
        } finally {
            if (!nested) keyguardAffordanceApplying.set(false);
        }
    }

    /** Content-box radii from padding; never calls installBackground. */
    private void ensureKeyguardAffordanceGlassPlate(ImageView button) {
        ensureKeyguardAffordanceGlassPlate(button, true);
    }

    private void ensureKeyguardAffordanceGlassPlate(ImageView button, boolean capture) {
        GlassDrawable live = GlassInstaller.get(button);
        if (live == null) {
            Drawable inner = unwrapInsetDrawable(button.getBackground());
            if (inner instanceof GlassDrawable) live = (GlassDrawable) inner;
        }
        if (live == null) return;
        restoreKeyguardGlassDrawableAlpha(button);
        int pl = button.getPaddingLeft();
        int pt = button.getPaddingTop();
        int pr = button.getPaddingRight();
        int pb = button.getPaddingBottom();
        boolean insetToContent = (pl | pt | pr | pb) != 0;
        int boxW = insetToContent ? button.getWidth() - pl - pr : button.getWidth();
        int boxH = insetToContent ? button.getHeight() - pt - pb : button.getHeight();
        float radius;
        if (boxW > 0 && boxH > 0) {
            radius = Math.min(boxW, boxH) / 2f;
        } else {
            radius = 24f * button.getResources().getDisplayMetrics().density;
        }
        live.setCornerRadii(radius, radius, radius, radius);
        if (insetToContent) {
            Drawable bg = button.getBackground();
            if (!(bg instanceof InsetDrawable) || unwrapInsetDrawable(bg) != live) {
                button.setBackground(new InsetDrawable(live, pl, pt, pr, pb));
            }
        } else if (button.getBackground() != live) {
            button.setBackground(live);
        }
        clearKeyguardAffordanceBackgroundTint(button);
        if (button.getWidth() <= 0 || button.getHeight() <= 0) return;
        if (capture) {
            GlassInstaller.forceCapture(button);
        }
        button.invalidate();
        log(4, TAG, "Keyguard affordance glass ready "
                + button.getClass().getSimpleName()
                + " size=" + button.getWidth() + "x" + button.getHeight()
                + " box=" + boxW + "x" + boxH
                + " inset=" + insetToContent
                + " radius=" + radius
                + " capture=" + capture);
    }

    /** OEM updateButton applies material BackgroundTintList after we install GlassDrawable. */
    private static void clearKeyguardAffordanceBackgroundTint(ImageView button) {
        try { button.setBackgroundTintList(null); } catch (Throwable ignored) { }
        Drawable bg = button.getBackground();
        Drawable inner = unwrapInsetDrawable(bg);
        if (inner != null) {
            try { inner.setTintList(null); } catch (Throwable ignored) { }
            try { inner.clearColorFilter(); } catch (Throwable ignored) { }
        }
        if (bg != null && bg != inner) {
            try { bg.setTintList(null); } catch (Throwable ignored) { }
            try { bg.clearColorFilter(); } catch (Throwable ignored) { }
        }
    }

    private static void restoreKeyguardGlassDrawableAlpha(Object buttonObj) {
        if (!(buttonObj instanceof ImageView)) return;
        ImageView button = (ImageView) buttonObj;
        if (GlassInstaller.get(button) == null
                && !(unwrapInsetDrawable(button.getBackground()) instanceof GlassDrawable)) {
            return;
        }
        Drawable bg = button.getBackground();
        Drawable inner = unwrapInsetDrawable(bg);
        if (inner instanceof GlassDrawable) {
            try { inner.setAlpha(255); } catch (Throwable ignored) { }
        }
        if (bg != null && bg != inner) {
            try { bg.setAlpha(255); } catch (Throwable ignored) { }
        }
    }

    private static Drawable unwrapInsetDrawable(Drawable drawable) {
        if (!(drawable instanceof InsetDrawable)) return drawable;
        try {
            return ((InsetDrawable) drawable).getDrawable();
        } catch (Throwable ignored) {
            return drawable;
        }
    }

    /**
     * Strip Cos16 OEM disc chrome by replacing src {@link LayerDrawable} with an icon-only
     * drawable. In-place DeadDisc lost to {@code LayerDrawable.mutate()} recreating vectors.
     */
    private boolean suppressKeyguardAffordanceOemChrome(ImageView button) {
        return suppressKeyguardAffordanceSrcDiscs(button);
    }

    private boolean suppressKeyguardAffordanceSrcDiscs(Object buttonObj) {
        if (!(buttonObj instanceof ImageView)) return false;
        if (!enabled()) return false;
        if (Boolean.TRUE.equals(keyguardAffordanceSuppressingDiscs.get())) return false;
        ImageView button = (ImageView) buttonObj;
        Drawable drawable = button.getDrawable();
        if (drawable == null) return false;
        if (drawable instanceof KeyguardAffordanceIconOnlyDrawable) return true;
        keyguardAffordanceSuppressingDiscs.set(true);
        try {
            if (drawable instanceof LayerDrawable) {
                KeyguardAffordanceIconOnlyDrawable only =
                        KeyguardAffordanceIconOnlyDrawable.from(
                                (LayerDrawable) drawable, button.getResources());
                button.setImageDrawable(only);
                keyguardAffordancePunched.put(button, only);
                return true;
            }
            if (keyguardAffordancePunched.get(button) == drawable) return true;
            if (keyguardAffordanceCancelGuard.get()) return false;
            Drawable punched = punchTranslucentCircleFromIcon(button, drawable);
            if (punched != null && punched != drawable) {
                button.setImageDrawable(punched);
                keyguardAffordancePunched.put(button, punched);
                return true;
            }
            return punched == drawable && keyguardAffordancePunched.get(button) == drawable;
        } finally {
            keyguardAffordanceSuppressingDiscs.set(false);
        }
    }

    private void rehideKeyguardAffordanceDiscLayers(Object buttonObj) {
        suppressKeyguardAffordanceSrcDiscs(buttonObj);
    }

    /**
     * Cos16 affordance_*_new.xml = [circle, stroke, foreground]. After {@code mCircleRadius→0}
     * the discs are the OEM flash. Keep only the foreground at the LayerDrawable intrinsic size
     * so {@code scaleType=center} / {@code mImageScale} stay unchanged.
     */
    private static final class KeyguardAffordanceIconOnlyDrawable extends Drawable {
        private final Drawable foreground;
        private final int intrinsicW;
        private final int intrinsicH;

        static KeyguardAffordanceIconOnlyDrawable from(LayerDrawable layers, Resources res) {
            int n = layers.getNumberOfLayers();
            int fgIndex = Math.max(0, n - 1);
            int fgId = 0;
            try {
                fgId = res.getIdentifier("affordance_foreground_icon", "id", "com.android.systemui");
            } catch (Throwable ignored) { }
            if (fgId != 0) {
                for (int i = 0; i < n; i++) {
                    try {
                        if (layers.getId(i) == fgId) {
                            fgIndex = i;
                            break;
                        }
                    } catch (Throwable ignored) { }
                }
            }
            Drawable fg = layers.getDrawable(fgIndex);
            Drawable copy = fg;
            try {
                Drawable.ConstantState cs = fg != null ? fg.getConstantState() : null;
                if (cs != null) {
                    copy = cs.newDrawable(res).mutate();
                } else if (fg != null) {
                    copy = fg.mutate();
                }
            } catch (Throwable ignored) {
                copy = fg;
            }
            int w = layers.getIntrinsicWidth();
            int h = layers.getIntrinsicHeight();
            if (w <= 0 || h <= 0) {
                w = copy != null ? copy.getIntrinsicWidth() : -1;
                h = copy != null ? copy.getIntrinsicHeight() : -1;
            }
            if (w <= 0 || h <= 0) {
                float d = res != null ? res.getDisplayMetrics().density : 3f;
                w = Math.round(48f * d);
                h = w;
            }
            return new KeyguardAffordanceIconOnlyDrawable(copy, w, h);
        }

        KeyguardAffordanceIconOnlyDrawable(Drawable foreground, int intrinsicW, int intrinsicH) {
            this.foreground = foreground;
            this.intrinsicW = Math.max(1, intrinsicW);
            this.intrinsicH = Math.max(1, intrinsicH);
        }

        @Override public void draw(Canvas canvas) {
            if (foreground == null) return;
            Rect b = getBounds();
            int fw = foreground.getIntrinsicWidth();
            int fh = foreground.getIntrinsicHeight();
            if (fw <= 0) fw = b.width();
            if (fh <= 0) fh = b.height();
            int left = b.left + (b.width() - fw) / 2;
            int top = b.top + (b.height() - fh) / 2;
            foreground.setBounds(left, top, left + fw, top + fh);
            foreground.draw(canvas);
        }

        @Override public void setAlpha(int alpha) {
            if (foreground != null) foreground.setAlpha(alpha);
        }

        @Override public void setColorFilter(ColorFilter colorFilter) {
            if (foreground != null) foreground.setColorFilter(colorFilter);
        }

        @Override public void setTint(int tintColor) {
            if (foreground != null) foreground.setTint(tintColor);
        }

        @Override public void setTintList(ColorStateList tint) {
            if (foreground != null) foreground.setTintList(tint);
        }

        @Override public int getOpacity() {
            return foreground != null ? foreground.getOpacity() : PixelFormat.TRANSLUCENT;
        }

        @Override public int getIntrinsicWidth() {
            return intrinsicW;
        }

        @Override public int getIntrinsicHeight() {
            return intrinsicH;
        }

        @Override protected boolean onStateChange(int[] state) {
            return foreground != null && foreground.setState(state);
        }

        @Override public boolean isStateful() {
            return foreground != null && foreground.isStateful();
        }
    }

    private static Drawable punchTranslucentCircleFromIcon(ImageView host, Drawable source) {
        try {
            int w = source.getIntrinsicWidth();
            int h = source.getIntrinsicHeight();
            if (w <= 0 || h <= 0) {
                w = Math.max(1, host.getWidth() - host.getPaddingLeft() - host.getPaddingRight());
                h = Math.max(1, host.getHeight() - host.getPaddingTop() - host.getPaddingBottom());
            }
            if (w <= 0 || h <= 0) return null;
            Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            ColorFilter oldFilter = source.getColorFilter();
            Rect oldBounds = source.copyBounds();
            try {
                source.setColorFilter(null);
                source.setBounds(0, 0, w, h);
                source.draw(canvas);
            } finally {
                source.setColorFilter(oldFilter);
                source.setBounds(oldBounds);
            }
            int[] pixels = new int[w * h];
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
            boolean changed = false;
            for (int i = 0; i < pixels.length; i++) {
                int alpha = (pixels[i] >>> 24) & 0xff;
                // Cos14 vectors bake a ~0.2-alpha disc under the glyph.
                if (alpha > 0 && alpha < 100) {
                    pixels[i] = 0;
                    changed = true;
                }
            }
            if (!changed) {
                bitmap.recycle();
                return source;
            }
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
            BitmapDrawable out = new BitmapDrawable(host.getResources(), bitmap);
            if (oldFilter != null) out.setColorFilter(oldFilter);
            return out;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Live float app-options menus run in SystemUI (WM Shell), not Launcher:
     * {@code FlexibleMenuManager} → {@code COUIIsolatedPopupListWindow}
     * ({@code RoundFrameLayout mMainMenuWrapper}).
     * Split-screen menus are intentionally skipped for now.
     */
    private void hookSystemUiFloatMenus(ClassLoader cl) {
        after("com.oplus.flexibletask.menu.FlexibleMenuManager", cl, "showPopupWindowMenu",
                this::scheduleFlexibleMenuGlass);
        // Real addView happens in lambda$showPopupWindowMenu$3 — glass after that.
        try {
            Class<?> mgr = Class.forName(
                    "com.oplus.flexibletask.menu.FlexibleMenuManager", false, cl);
            for (Method m : mgr.getDeclaredMethods()) {
                String name = m.getName();
                if (m.isBridge() || m.isSynthetic()) continue;
                if (!(name.contains("showPopupWindowMenu") || name.contains("lambda$showPopupWindowMenu"))
                        || name.equals("showPopupWindowMenu")) {
                    continue;
                }
                hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        if (menuStyleEnabled()) applyFlexibleMenuGlass(chain.getThisObject());
                    } catch (Throwable e) {
                        log(5, TAG, "FlexibleMenuManager." + name + " glass failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            log(5, TAG, "FlexibleMenuManager lambda glass hooks unavailable", e);
        }
        // Backup: prepareShowMainMenu runs right before WM.addView for float menus.
        after("com.coui.appcompat.poplist.COUIIsolatedPopupListWindow", cl, "prepareShowMainMenu",
                this::scheduleCouiPopupListGlass);
        // Re-bind DESKTOP/APP when the float bounds update while the menu is open.
        after("com.oplus.flexibletask.menu.FlexibleMenuManager", cl, "updateInfo", manager -> {
            try {
                if (!menuStyleEnabled() || !DesktopBackdropClient.isReady()) return;
                Object popup = field(manager, "mPopupWindow");
                View wrapper = asView(field(popup, "mMainMenuWrapper"));
                if (wrapper == null || !wrapper.isAttachedToWindow()) return;
                if (!BehindDisplayCapture.isSysUiMenuGlass(wrapper)) return;
                bindFloatMenuCapture(wrapper, manager);
                GlassInstaller.forceCapture(wrapper);
            } catch (Throwable ignored) { }
        });
        // ColorOS 16 dismiss API is dismissPopupWindowMenu (not dismiss/hide).
        after("com.oplus.flexibletask.menu.FlexibleMenuManager", cl, "dismissPopupWindowMenu",
                this::stopFloatMenuAshmem);
        after("com.oplus.flexibletask.menu.FlexibleMenuManager", cl, "dismiss",
                this::stopFloatMenuAshmem);
        after("com.oplus.flexibletask.menu.FlexibleMenuManager", cl, "hidePopupWindow",
                this::stopFloatMenuAshmem);
        after("com.oplus.flexibletask.menu.FlexibleMenuManager", cl, "hide",
                this::stopFloatMenuAshmem);
        after("com.coui.appcompat.poplist.COUIIsolatedPopupListWindow", cl, "dismiss",
                this::stopFloatMenuAshmem);
        after("com.coui.appcompat.poplist.COUIIsolatedPopupListWindow", cl, "dismissAllowingStateLoss",
                this::stopFloatMenuAshmem);
        after("android.widget.PopupWindow", cl, "dismiss", popup -> {
            try {
                if (popup != null && popup.getClass().getName().contains("COUIIsolatedPopupListWindow")) {
                    stopFloatMenuAshmem(popup);
                }
            } catch (Throwable ignored) { }
        });
    }

    /**
     * ColorOS 16 volume indicator + expanded panel: strip OEM blur and install live glass.
     * Capture is the float-menu split (ashmem on launcher pages, captureDisplay on apps/lock).
     */
    private void hookSystemUiVolumeGlass(ClassLoader cl) {
        after("com.oplus.systemui.volume.view.OplusVolumeDialogView", cl, "initDialog",
                this::scheduleVolumePanelGlass);
        after("com.oplus.systemui.volume.view.OplusVolumeDialogView", cl, "showSliderVolume",
                this::scheduleVolumePanelGlass);
        after("com.oplus.systemui.volume.view.OplusVolumeDialogView", cl, "expandPanel",
                this::scheduleVolumePanelGlass);
        after("com.oplus.systemui.volume.view.OplusVolumeDialogView", cl, "updateRowsH",
                this::scheduleVolumePanelGlass);
        after("com.oplus.systemui.volume.view.OplusVolumeDialogView", cl, "checkSettingButtonIsVisible",
                this::scheduleVolumePanelGlass);
        after("com.oplus.systemui.volume.view.OplusVolumeDialogView", cl, "dismissH",
                this::releaseVolumePanelGlass);
        after("com.oplus.systemui.volume.OplusVolumeDialogImpl", cl, "showH", impl -> {
            scheduleVolumePanelGlass(field(impl, "mOplusVolumeDialogView"));
        });
        after("com.oplus.systemui.volume.OplusVolumeDialogImpl", cl, "expandPanel", impl -> {
            scheduleVolumePanelGlass(field(impl, "mOplusVolumeDialogView"));
        });
        after("com.oplus.systemui.volume.OplusVolumeDialogImpl", cl, "dismissH", impl -> {
            releaseVolumePanelGlass(field(impl, "mOplusVolumeDialogView"));
        });

        try {
            Class<?> blur = Class.forName(
                    "com.oplus.systemui.volume.utils.VolumeBlurManager", false, cl);
            for (Method m : blur.getDeclaredMethods()) {
                if (m.isBridge()) continue;
                String name = m.getName();
                if ("getVolumeBarBackground".equals(name)
                        || "getVolumePanelBackground".equals(name)
                        || "tryTakeScreenshotForBlur".equals(name)) {
                    hookOnce(m, chain -> {
                        if (!enabled()) return chain.proceed();
                        if ("tryTakeScreenshotForBlur".equals(name)) {
                            return null;
                        }
                        if ("getVolumeBarBackground".equals(name)) {
                            return VolumePanelGlass.transparentBarBackground();
                        }
                        return VolumePanelGlass.transparentPanelBackground();
                    });
                }
            }
        } catch (Throwable e) {
            log(5, TAG, "VolumeBlurManager hooks unavailable", e);
        }

        try {
            Class<?> seek = Class.forName(
                    "com.coui.appcompat.seekbar.COUIVerticalSeekBar", false, cl);
            for (Method m : seek.getDeclaredMethods()) {
                if (!"onDraw".equals(m.getName()) || m.getParameterCount() != 1) continue;
                hookOnce(m, chain -> {
                    try {
                        VolumePanelGlass.clearSeekBarOemFill(chain.getThisObject());
                    } catch (Throwable ignored) { }
                    return chain.proceed();
                });
            }
        } catch (Throwable e) {
            log(5, TAG, "COUIVerticalSeekBar.onDraw volume fill hook unavailable", e);
        }

        hookNotificationCenterBlur(cl);
        hookCloseAllButtonGlass(cl);
    }

    /**
     * Cancel the frosted blur that appears behind the notification list when sliding a
     * card up into the message list. The blur is applied to the behind scrim by
     * {@code ScrimControllerExImp.showBehindScrim}; keep that call for QS / Bouncer paths
     * but zero the amount on the keyguard notification-center transition.
     */
    private void hookNotificationCenterBlur(ClassLoader cl) {
        try {
            Class<?> c = Class.forName(
                    "com.oplus.systemui.statusbar.phone.ScrimControllerExImp", false, cl);
            for (Method m : c.getDeclaredMethods()) {
                if (!"showBehindScrim".equals(m.getName()) || m.isBridge() || m.isSynthetic()) {
                    continue;
                }
                Class<?>[] params = m.getParameterTypes();
                if (params.length != 2 || params[0] != float.class
                        || params[1] != boolean.class) {
                    continue;
                }
                hookOnce(m, chain -> {
                    if (!enabled()) return chain.proceed();
                    try {
                        Object self = chain.getThisObject();
                        java.util.List<Object> args = chain.getArgs();
                        if (args.size() == 2 && Boolean.FALSE.equals(args.get(1))
                                && args.get(0) instanceof Float
                                && ((Float) args.get(0)).floatValue() > 0f
                                && isKgNotificationCenter(self)) {
                            // Card slide-up into the message list: no blur / no scrim scale.
                            return chain.proceed(new Object[] { 0f, args.get(1) });
                        }
                    } catch (Throwable ignored) { }
                    return chain.proceed();
                });
                break;
            }
        } catch (Throwable e) {
            log(5, TAG, "showBehindScrim notification-center blur hook unavailable", e);
        }
    }

    private static boolean isKgNotificationCenter(Object scrimEx) {
        if (scrimEx == null) return false;
        try {
            Object r = scrimEx.getClass().getMethod("isKgNotificationCenter").invoke(scrimEx);
            return Boolean.TRUE.equals(r);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Install glass on the lock-screen message-list "收起" button ({@code OplusCloseAllButton}).
     * The OEM background is a {@code MaskBlurDrawable} from {@code OplusCloseAllController};
     * once glass is installed, ignore further non-glass background sets and re-assert on
     * attach / layout.
     */
    private void hookCloseAllButtonGlass(ClassLoader cl) {
        final String btnClass = "com.oplus.systemui.statusbar.notification.view.OplusCloseAllButton";
        if (!hasClass(cl, btnClass)) {
            log(4, TAG, "hookCloseAllButtonGlass skipped (no OplusCloseAllButton)");
            return;
        }
        after("com.oplus.systemui.statusbar.notification.OplusCloseAllController", cl, "bindView",
                this::applyCloseAllButtonGlass);
        after(btnClass, cl, "onAttachedToWindow", this::applyCloseAllButtonGlass);
        after(btnClass, cl, "onLayout", this::applyCloseAllButtonGlass);
        try {
            Method setBackground = View.class.getMethod("setBackground", Drawable.class);
            hookOnce(setBackground, chain -> {
                if (!enabled()) return chain.proceed();
                try {
                    Object self = chain.getThisObject();
                    java.util.List<Object> args = chain.getArgs();
                    if (self instanceof View && isCloseAllButton((View) self)
                            && GlassInstaller.get((View) self) != null
                            && !args.isEmpty()
                            && !(args.get(0) instanceof GlassDrawable)) {
                        return null;
                    }
                } catch (Throwable ignored) { }
                return chain.proceed();
            });
        } catch (Throwable e) {
            log(5, TAG, "OplusCloseAllButton setBackground hook unavailable", e);
        }
    }

    private static boolean isCloseAllButton(View view) {
        if (view == null) return false;
        String name = view.getClass().getName();
        return name != null && name.contains("OplusCloseAllButton");
    }

    private void applyCloseAllButtonGlass(Object obj) {
        if (!enabled()) return;
        View btn = null;
        if (obj instanceof View && isCloseAllButton((View) obj)) {
            btn = (View) obj;
        } else {
            Object f = field(obj, "oplusCloseAllButton");
            if (f instanceof View && isCloseAllButton((View) f)) btn = (View) f;
        }
        if (btn == null || !btn.isAttachedToWindow()) return;
        if (btn.getWidth() <= 0 || btn.getHeight() <= 0) return;
        try {
            GlassInstaller.markLockWallpaperOnly(btn);
            boolean firstInstall = GlassInstaller.get(btn) == null;
            if (firstInstall) {
                GlassInstaller.installBackground(btn, currentConfig());
            }
            GlassDrawable live = GlassInstaller.get(btn);
            if (live == null) return;
            if (firstInstall) {
                GlassInstaller.forceCapture(btn);
            }
            btn.invalidate();
        } catch (Throwable e) {
            log(5, TAG, "applyCloseAllButtonGlass failed", e);
        }
    }

    private void scheduleVolumePanelGlass(Object dialogView) {
        if (!enabled() || dialogView == null) return;
        if (!isClassOrSubclass(dialogView, "com.oplus.systemui.volume.view.OplusVolumeDialogView")) {
            Object nested = field(dialogView, "mOplusVolumeDialogView");
            if (nested != null) dialogView = nested;
        }
        VolumePanelGlass.schedule(dialogView, currentConfig());
    }

    private void releaseVolumePanelGlass(Object dialogView) {
        if (dialogView == null) return;
        if (!isClassOrSubclass(dialogView, "com.oplus.systemui.volume.view.OplusVolumeDialogView")) {
            Object nested = field(dialogView, "mOplusVolumeDialogView");
            if (nested != null) dialogView = nested;
        }
        try {
            VolumePanelGlass.release(dialogView);
        } catch (Throwable t) {
            log(5, TAG, "volume glass release failed", t);
        }
    }

    private void cancelPendingFloatMenuGlass() {
        synchronized (pendingFloatMenuGlass) {
            for (Runnable r : pendingFloatMenuGlass) {
                mainHandler.removeCallbacks(r);
            }
            pendingFloatMenuGlass.clear();
        }
    }

    private void trackFloatMenuGlass(Runnable r) {
        synchronized (pendingFloatMenuGlass) {
            pendingFloatMenuGlass.add(r);
        }
    }

    private void stopFloatMenuAshmem(Object ignored) {
        try {
            cancelPendingFloatMenuGlass();
            BehindDisplayCapture.stopAllSessions();
        } catch (Throwable t) {
            log(5, TAG, "stopFloatMenuAshmem failed", t);
        }
    }

    private void scheduleFlexibleMenuGlass(Object manager) {
        if (!menuStyleEnabled() || manager == null) return;
        cancelPendingFloatMenuGlass();
        final int epoch = DesktopBackdropClient.sessionEpoch();
        Runnable apply = () -> {
            try {
                if (DesktopBackdropClient.sessionEpoch() != epoch) return;
                applyFlexibleMenuGlass(manager);
            } catch (Throwable e) {
                log(5, TAG, "applyFlexibleMenuGlass failed", e);
            }
        };
        trackFloatMenuGlass(apply);
        mainHandler.post(apply);
        mainHandler.postDelayed(apply, 80L);
        mainHandler.postDelayed(apply, 220L);
        mainHandler.postDelayed(apply, 480L);
    }

    private void applyFlexibleMenuGlass(Object manager) {
        if (!menuStyleEnabled() || manager == null) return;
        Object popup = field(manager, "mPopupWindow");
        if (popup == null) popup = invokeNoArgs(manager, "getPopupWindow");
        applyCouiPopupListGlass(popup, manager);
    }

    private void scheduleCouiPopupListGlass(Object popup) {
        if (!menuStyleEnabled() || popup == null) return;
        cancelPendingFloatMenuGlass();
        final int epoch = DesktopBackdropClient.sessionEpoch();
        Runnable apply = () -> {
            try {
                if (DesktopBackdropClient.sessionEpoch() != epoch) return;
                applyCouiPopupListGlass(popup, null);
            } catch (Throwable e) {
                log(5, TAG, "applyCouiPopupListGlass failed", e);
            }
        };
        trackFloatMenuGlass(apply);
        mainHandler.post(apply);
        mainHandler.postDelayed(apply, 80L);
        mainHandler.postDelayed(apply, 220L);
    }

    private void applyCouiPopupListGlass(Object popup, Object flexibleManager) {
        if (!menuStyleEnabled() || popup == null) return;
        try {
            invoke(popup, "setUseBackgroundBlur", new Class<?>[] { boolean.class }, false);
        } catch (Throwable ignored) { }
        View resolvedWrapper = asView(field(popup, "mMainMenuWrapper"));
        View resolvedList = asView(field(popup, "mMainListView"));
        if (resolvedList == null) resolvedList = asView(invokeNoArgs(popup, "getMainMenuListView"));
        if (resolvedWrapper == null && resolvedList != null) {
            Object parent = resolvedList.getParent();
            if (parent instanceof View) resolvedWrapper = (View) parent;
        }
        if (resolvedWrapper == null) {
            View content = asView(invokeNoArgs(popup, "getContentView"));
            if (content instanceof ViewGroup && ((ViewGroup) content).getChildCount() > 0) {
                View child = ((ViewGroup) content).getChildAt(0);
                if (child != null && child.getClass().getName().contains("RoundFrameLayout")) {
                    resolvedWrapper = child;
                }
            }
        }
        final View wrapper = resolvedWrapper;
        final View listView = resolvedList;
        clearCouiPopupOpaqueChrome(wrapper, listView);
        final View glassHost = wrapper != null ? wrapper : listView;
        if (glassHost == null) return;
        if (!glassHost.isAttachedToWindow()) return;
        BehindDisplayCapture.tagHost(glassHost);
        if (listView != null && listView != glassHost
                && listView.getBackground() instanceof GlassDrawable) {
            GlassInstaller.uninstall(listView);
            listView.setBackground(null);
        }
        bindFloatMenuCapture(glassHost, flexibleManager);
        try { glassHost.setLayerType(View.LAYER_TYPE_NONE, null); } catch (Throwable ignored) { }
        GlassInstaller.installBackground(glassHost, currentConfig());
        final int epoch = DesktopBackdropClient.sessionEpoch();
        Runnable refresh = () -> {
            if (DesktopBackdropClient.sessionEpoch() != epoch) return;
            if (!glassHost.isAttachedToWindow()) return;
            try {
                invoke(popup, "setUseBackgroundBlur", new Class<?>[] { boolean.class }, false);
            } catch (Throwable ignored) { }
            clearCouiPopupOpaqueChrome(wrapper, listView);
            if (!(glassHost.getBackground() instanceof GlassDrawable)) {
                GlassInstaller.installBackground(glassHost, currentConfig());
            }
            GlassDrawable live = GlassInstaller.get(glassHost);
            if (live == null) return;
            float radius = readCouiPopupRadius(glassHost);
            if (radius > 0f) live.setCornerRadii(radius, radius, radius, radius);
            bindFloatMenuCapture(glassHost, flexibleManager);
            if (glassHost.getWidth() <= 0 || glassHost.getHeight() <= 0) return;
            GlassInstaller.forceCapture(glassHost);
            glassHost.invalidate();
            log(4, TAG, "Flexible/COUI popup glass ready size="
                    + glassHost.getWidth() + "x" + glassHost.getHeight()
                    + " radius=" + radius
                    + " host=" + glassHost.getClass().getSimpleName());
        };
        trackFloatMenuGlass(refresh);
        scheduleGlassRefresh(glassHost, refresh);
    }

    /**
     * Ashmem only when the menu opens above the float <b>and</b> the launcher is visible
     * under/around the float. Otherwise {@code captureDisplay} (same as downward menu).
     */
    private void bindFloatMenuCapture(View glassHost, Object flexibleManager) {
        if (glassHost == null || !glassHost.isAttachedToWindow()) return;
        try {
            if (flexibleManager == null) {
                BehindDisplayCapture.bindApp(glassHost, null);
                return;
            }
            Object boundsObj = field(flexibleManager, "mCurrentBounds");
            android.graphics.Rect floatBounds = boundsObj instanceof android.graphics.Rect
                    ? new android.graphics.Rect((android.graphics.Rect) boundsObj) : null;
            if (floatBounds != null && floatBounds.isEmpty()) floatBounds = null;

            boolean above = floatBounds != null
                    && isFloatMenuAbove(glassHost, floatBounds, flexibleManager);
            boolean launcherUnder = isLauncherUnderFloat(glassHost, flexibleManager);
            // Prefer Launcher ashmem while home or Overview is showing. PAGE_PREVIEW /
            // TOGGLE_BAR clear desktopHomeValid so we fall back to captureDisplay.
            boolean homeDesktop = launcherUnder && DesktopBackdropClient.isDesktopHomeValid();

            if (above && homeDesktop) {
                android.graphics.Rect maxMenu = computeMaxMenuRegion(
                        glassHost, flexibleManager, floatBounds, true);
                if (maxMenu == null || maxMenu.isEmpty()) {
                    BehindDisplayCapture.bindApp(glassHost, floatBounds);
                    log(4, TAG, "float menu capture APP (no max menu region)");
                    return;
                }
                BehindDisplayCapture.bindDesktop(glassHost, floatBounds, maxMenu, true);
                log(4, TAG, "float menu capture DESKTOP region=" + maxMenu.toShortString());
            } else {
                BehindDisplayCapture.bindApp(glassHost, floatBounds);
                log(4, TAG, "float menu capture APP above=" + above
                        + " launcherUnder=" + launcherUnder
                        + " homeDesktop=" + homeDesktop);
            }
        } catch (Throwable t) {
            log(5, TAG, "bindFloatMenuCapture failed", t);
        }
    }

    /** True when home/launcher is visible behind the float (ashmem path is meaningful). */
    private static boolean isLauncherUnderFloat(View host, Object flexibleManager) {
        if (isFloatTaskLauncher(flexibleManager)) return true;
        if (host == null) return false;
        try {
            android.app.ActivityManager am = (android.app.ActivityManager)
                    host.getContext().getSystemService(android.content.Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            @SuppressWarnings("deprecation")
            java.util.List<android.app.ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(12);
            if (tasks == null) return false;
            for (android.app.ActivityManager.RunningTaskInfo task : tasks) {
                if (task == null) continue;
                android.content.ComponentName top = task.topActivity;
                if (top == null) top = task.baseActivity;
                if (top != null && isLauncherPackage(top.getPackageName())) {
                    try {
                        if (task.isVisible()) return true;
                    } catch (Throwable ignored) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private static boolean isFloatTaskLauncher(Object flexibleManager) {
        if (flexibleManager == null) return false;
        try {
            Object info = field(flexibleManager, "mTaskInfo");
            if (info == null) return false;
            Object top = field(info, "topActivity");
            if (!(top instanceof android.content.ComponentName)) {
                top = field(info, "realActivity");
            }
            if (!(top instanceof android.content.ComponentName)) {
                top = field(info, "baseActivity");
            }
            if (top instanceof android.content.ComponentName) {
                return isLauncherPackage(((android.content.ComponentName) top).getPackageName());
            }
            Object intent = field(info, "baseIntent");
            if (intent instanceof android.content.Intent) {
                android.content.ComponentName cn = ((android.content.Intent) intent).getComponent();
                if (cn != null) return isLauncherPackage(cn.getPackageName());
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private static boolean isLauncherPackage(String pkg) {
        if (pkg == null) return false;
        return pkg.equals("com.android.launcher")
                || pkg.equals("com.android.launcher3")
                || pkg.equals("com.oppo.launcher")
                || pkg.equals("com.oplus.launcher")
                || pkg.equals("com.coloros.launcher");
    }

    /**
     * Fixed max menu screen rect for one OPEN signal.
     * Prefer OEM mMenuWidth/Height + float anchor (stable) over animated host bounds so the
     * ashmem region matches 1:1 screen pixels and does not skew aspect.
     */
    private static android.graphics.Rect computeMaxMenuRegion(View glassHost, Object flexibleManager,
            android.graphics.Rect floatBounds, boolean above) {
        int mw = 0;
        int mh = 0;
        Object wObj = field(flexibleManager, "mMenuWidth");
        Object hObj = field(flexibleManager, "mMenuHeight");
        if (wObj instanceof Integer) mw = (Integer) wObj;
        if (hObj instanceof Integer) mh = (Integer) hObj;
        if (mw <= 0 && glassHost.getWidth() > 0) mw = glassHost.getWidth();
        if (mh <= 0 && glassHost.getHeight() > 0) mh = glassHost.getHeight();
        if (mw <= 0 || mh <= 0) return null;

        float density = glassHost.getResources().getDisplayMetrics().density;
        int screenW = glassHost.getResources().getDisplayMetrics().widthPixels;
        int screenH = glassHost.getResources().getDisplayMetrics().heightPixels;

        // Prefer laid-out host origin when size already matches OEM max (post-animation).
        if (glassHost.isAttachedToWindow() && glassHost.getWidth() > 0 && glassHost.getHeight() > 0
                && Math.abs(glassHost.getWidth() - mw) <= 2
                && Math.abs(glassHost.getHeight() - mh) <= 2) {
            int[] loc = new int[2];
            glassHost.getLocationOnScreen(loc);
            return new android.graphics.Rect(loc[0], loc[1], loc[0] + mw, loc[1] + mh);
        }

        if (floatBounds == null || floatBounds.isEmpty()) {
            if (glassHost.isAttachedToWindow() && glassHost.getWidth() > 0) {
                int[] loc = new int[2];
                glassHost.getLocationOnScreen(loc);
                return new android.graphics.Rect(loc[0], loc[1], loc[0] + mw, loc[1] + mh);
            }
            return null;
        }

        int left = Math.max(0, Math.min(screenW - mw, floatBounds.centerX() - mw / 2));
        int top;
        if (above) {
            int gap = Math.round(8f * density);
            top = Math.max(0, floatBounds.top - gap - mh);
        } else {
            top = floatBounds.top + Math.round(8f * density);
        }
        if (top + mh > screenH) top = Math.max(0, screenH - mh);
        return new android.graphics.Rect(left, top, left + mw, top + mh);
    }

    /**
     * Default: menu anchors at {@code float.top + 8dp} and opens downward over the float.
     * It flips above when there is not enough room below that anchor. Never treat an
     * unlaid-out host at (0,0) as "above" — that sticky-locked DESKTOP and blanked glass.
     */
    private static boolean isFloatMenuAbove(View glassHost, android.graphics.Rect floatBounds,
            Object flexibleManager) {
        if (glassHost == null || floatBounds == null || floatBounds.isEmpty()) return false;
        float density = glassHost.getResources().getDisplayMetrics().density;
        int screenH = glassHost.getResources().getDisplayMetrics().heightPixels;
        int menuH = 0;
        Object menuHObj = field(flexibleManager, "mMenuHeight");
        if (menuHObj instanceof Integer) menuH = (Integer) menuHObj;
        int anchorY = floatBounds.top + Math.round(8f * density);
        int bottomMargin = Math.round(16f * density);
        if (menuH > 0 && anchorY + menuH > screenH - bottomMargin) return true;

        int w = glassHost.getWidth();
        int h = glassHost.getHeight();
        if (w <= 0 || h <= 0 || !glassHost.isAttachedToWindow()) return false;
        int[] loc = new int[2];
        glassHost.getLocationOnScreen(loc);
        if (loc[1] == 0 && loc[0] == 0 && floatBounds.top > h) return false;
        return loc[1] < floatBounds.top;
    }

    private void clearCouiPopupOpaqueChrome(View wrapper, View listView) {
        if (listView != null && !(listView.getBackground() instanceof GlassDrawable)) {
            listView.setBackground(null);
        }
        if (wrapper != null) {
            // ColorOS 16 RoundFrameLayout applies COUI background blur in onAttachedToWindow —
            // that OEM plate covers LiquidGlass unless disabled first.
            disableCouiBackgroundBlur(wrapper);
            try {
                invoke(wrapper, "setClipMode", new Class<?>[] { int.class }, 0);
            } catch (Throwable ignored) { }
            if (!(wrapper.getBackground() instanceof GlassDrawable)) {
                wrapper.setBackground(null);
            }
        }
    }

    private static void disableCouiBackgroundBlur(View wrapper) {
        if (wrapper == null) return;
        try {
            Object builder = field(wrapper, "mBackgroundBlurBuilder");
            if (builder != null) {
                setField(builder, "mUseBackgroundBlur", false);
                try { invokeNoArgs(builder, "release"); } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
    }

    private void scheduleGlassRefresh(View glassHost, Runnable refresh) {
        if (glassHost == null || refresh == null) return;
        if (glassHost.getWidth() > 0 && glassHost.getHeight() > 0) {
            refresh.run();
            glassHost.post(refresh);
            glassHost.postDelayed(refresh, 120L);
            glassHost.postDelayed(refresh, 400L);
        } else {
            glassHost.post(refresh);
            glassHost.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int left, int top, int right, int bottom,
                        int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    if (v.getWidth() > 0 && v.getHeight() > 0) {
                        v.removeOnLayoutChangeListener(this);
                        refresh.run();
                        v.postDelayed(refresh, 120L);
                        v.postDelayed(refresh, 400L);
                    }
                }
            });
        }
    }

    /**
     * Lock affordance: single plate bake after size is known.
     * Multi delayed forceCapture (120/400) re-rasterized lock wallpaper and janked Keyguard.
     */
    private void scheduleKeyguardAffordanceGlassRefresh(View glassHost, Runnable refresh) {
        if (glassHost == null || refresh == null) return;
        if (glassHost.getWidth() > 0 && glassHost.getHeight() > 0) {
            // One post so padding/inset settle before wallpaper-only capture.
            glassHost.post(refresh);
            return;
        }
        glassHost.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                    int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if (v.getWidth() > 0 && v.getHeight() > 0) {
                    v.removeOnLayoutChangeListener(this);
                    v.post(refresh);
                }
            }
        });
    }

    private static View asView(Object obj) {
        return obj instanceof View ? (View) obj : null;
    }

    private static float readCouiPopupRadius(View view) {
        try {
            int id = view.getResources().getIdentifier(
                    "coui_round_corner_m", "dimen", "com.support.appcompat");
            if (id == 0) {
                id = view.getResources().getIdentifier(
                        "coui_round_corner_m", "dimen", view.getContext().getPackageName());
            }
            if (id != 0) return view.getResources().getDimension(id);
        } catch (Throwable ignored) { }
        return 16f * view.getResources().getDisplayMetrics().density;
    }

    private void applyTaskHeaderShortcutGlass(Object header) {
        if (!enabled() || header == null) return;
        applyTaskHeaderShortcutButtonGlass(field(header, "splitWindowBtn"));
        applyTaskHeaderShortcutButtonGlass(invokeNoArgs(header, "getSplitWindowBtn"));
        applyTaskHeaderShortcutButtonGlass(field(header, "miniWindowBtn"));
        applyTaskHeaderShortcutButtonGlass(invokeNoArgs(header, "getMiniWindowBtn"));
    }

    /**
     * Large-display header 分屏 / 浮窗 ImageButtons. Skip GONE hosts — phones keep them
     * invisible forever behind {@code hasLargeDisplayFeatures()}.
     */
    private void applyTaskHeaderShortcutButtonGlass(Object buttonObj) {
        if (!enabled() || !(buttonObj instanceof ImageView)) return;
        final View button = (View) buttonObj;
        if (button.getVisibility() != View.VISIBLE) return;
        try {
            GlassInstaller.installBackground(button, currentConfig());
            Runnable refresh = () -> {
                if (button.getVisibility() != View.VISIBLE) return;
                GlassDrawable live = GlassInstaller.get(button);
                if (live == null) return;
                float radius = Math.min(button.getWidth(), button.getHeight()) / 2f;
                if (radius <= 0f) {
                    radius = 14f * button.getResources().getDisplayMetrics().density;
                }
                live.setCornerRadii(radius, radius, radius, radius);
                if (button.getWidth() <= 0 || button.getHeight() <= 0) return;
                GlassInstaller.forceCapture(button);
                button.invalidate();
                log(4, TAG, "TaskHeader shortcut glass ready "
                        + button.getClass().getSimpleName()
                        + " size=" + button.getWidth() + "x" + button.getHeight());
            };
            if (button.getWidth() > 0 && button.getHeight() > 0) refresh.run();
            else {
                button.post(refresh);
                button.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                    @Override
                    public void onLayoutChange(View v, int left, int top, int right, int bottom,
                            int oldLeft, int oldTop, int oldRight, int oldBottom) {
                        if (v.getVisibility() == View.VISIBLE
                                && v.getWidth() > 0 && v.getHeight() > 0) {
                            v.removeOnLayoutChangeListener(this);
                            refresh.run();
                        }
                    }
                });
            }
        } catch (Throwable e) {
            log(5, TAG, "applyTaskHeaderShortcutButtonGlass failed", e);
        }
    }

    /**
     * ToggleBar chrome: main menu circles (插件 / 壁纸与个性化 / …), top toolbar buttons
     * (完成 / 取消 / 添加卡片…), and bottom page-preview thumbnails that can be dragged.
     */
    private void hookToggleBarChrome(ClassLoader cl) {
        after("com.android.launcher.togglebar.views.PressFeedbackLinearLayout", cl, "onFinishInflate",
                this::applyToggleBarItemGlass);
        after("com.android.launcher.togglebar.views.PressFeedbackLinearLayout", cl, "onAttachedToWindow",
                this::applyToggleBarItemGlass);
        after("com.android.launcher.togglebar.views.ToggleStateToolbar", cl, "onFinishInflate",
                this::applyToggleBarToolbarGlass);
        after("com.android.launcher.togglebar.views.ToggleStateToolbar", cl, "onAttachedToWindow",
                this::applyToggleBarToolbarGlass);
        hookPagePreviewFrameGlass(cl);
        try {
            Class<?> adapter = Class.forName(
                    "com.android.launcher.togglebar.adapter.ToggleBarMainUIAdapter", false, cl);
            for (Method m : adapter.getDeclaredMethods()) {
                if (!m.getName().equals("onBindViewHolder") || m.isBridge() || m.isSynthetic()) continue;
                hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        if (!enabled()) return result;
                        applyToggleBarItemGlass(field(chain.getArg(0), "itemView"));
                    } catch (Throwable e) {
                        log(5, TAG, "ToggleBarMainUIAdapter.onBindViewHolder glass failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            log(5, TAG, "ToggleBarMainUIAdapter glass hook unavailable", e);
        }
        // OEM fills are redrawn every frame; skip them only when glass owns that view's chrome.
        try {
            Class<?> handler = Class.forName(
                    "com.android.launcher.togglebar.animation.PressFeedbackHandler", false, cl);
            for (Method m : handler.getDeclaredMethods()) {
                String name = m.getName();
                if (!(name.equals("drawCircleColor") || name.equals("drawPressedColor"))
                        || m.isBridge() || m.isSynthetic()) continue;
                hookOnce(m, chain -> {
                    if (enabled()) {
                        Object view = field(chain.getThisObject(), "mView");
                        if (view instanceof View && (GlassInstaller.get((View) view) != null
                                || (isClassOrSubclass(view,
                                "com.android.launcher.pagepreview.PagePreviewItemView")
                                && isPagePreviewChromeActive(view)))) {
                            return null;
                        }
                    }
                    return chain.proceed();
                });
            }
        } catch (Throwable e) {
            log(5, TAG, "PressFeedbackHandler glass fill skip unavailable", e);
        }
    }

    /**
     * Page-preview thumbnails (drawPressedColor frosted fill) + page-indicator expanded pill
     * (LayerBlur TYPE_PAGE_INDICATOR). Thumbnails install/remove with PAGE_PREVIEW / TOGGLE_BAR.
     * Indicator glass is held through the exit fade — including the blank-page dot shrink that
     * happens after the launcher is already back on NORMAL.
     */
    private void hookPagePreviewFrameGlass(ClassLoader cl) {
        after("com.android.launcher.pagepreview.PagePreviewRoot", cl, "onStateEnabled",
                this::applyPagePreviewRootGlass);
        after("com.android.launcher.pagepreview.PagePreviewRoot", cl, "onStateTransitionEnd",
                this::applyPagePreviewRootGlass);
        after("com.android.launcher.pagepreview.PagePreviewRoot", cl, "onStateDisabled",
                this::removePagePreviewRootGlass);
        after("com.android.launcher.pagepreview.PagePreviewRoot", cl, "onStateDisableTransitionEnd",
                this::removePagePreviewRootGlass);
        after("com.android.launcher.pagepreview.PagePreviewListContainer", cl, "onPagePreviewStateEnable",
                this::applyPagePreviewListGlass);
        after("com.android.launcher.pagepreview.PagePreviewListContainer", cl, "onPagePreviewStateDisable",
                this::removePagePreviewListGlass);
        try {
            Class<?> adapter = Class.forName(
                    "com.android.launcher.pagepreview.PagePreviewAdapter", false, cl);
            for (Method m : adapter.getDeclaredMethods()) {
                if (!m.getName().equals("onBindViewHolder") || m.isBridge() || m.isSynthetic()) continue;
                hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        if (!enabled() || !isPagePreviewChromeActive(chain.getThisObject())) return result;
                        Object holder = chain.getArg(0);
                        applyPagePreviewItemGlass(field(holder, "itemView"));
                        applyPagePreviewItemGlass(field(holder, "mTwoPanelView1"));
                        applyPagePreviewItemGlass(field(holder, "mTwoPanelView2"));
                        applyPagePreviewItemGlass(invokeNoArgs(holder, "getMTwoPanelView1"));
                        applyPagePreviewItemGlass(invokeNoArgs(holder, "getMTwoPanelView2"));
                    } catch (Throwable e) {
                        log(5, TAG, "PagePreviewAdapter.onBindViewHolder glass failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            log(5, TAG, "PagePreviewAdapter glass hook unavailable", e);
        }
        try {
            Class<?> preview = Class.forName(
                    "com.android.launcher.pagepreview.PagePreviewItemView", false, cl);
            for (Method m : preview.getDeclaredMethods()) {
                if (!m.getName().equals("onDragExit") || m.isBridge() || m.isSynthetic()) continue;
                hookOnce(m, chain -> {
                    Object self = chain.getThisObject();
                    Object result = chain.proceed();
                    if (self instanceof View) GlassInstaller.uninstall((View) self);
                    if (enabled() && isPagePreviewChromeActive(self)) {
                        applyPagePreviewItemGlass(self);
                    }
                    return result;
                });
            }
            for (Method m : preview.getDeclaredMethods()) {
                if (!m.getName().equals("onLayout") || m.isBridge() || m.isSynthetic()) continue;
                hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    if (enabled() && isPagePreviewChromeActive(chain.getThisObject())) {
                        applyPagePreviewItemGlass(chain.getThisObject());
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            log(5, TAG, "PagePreviewItemView glass hooks unavailable", e);
        }
        try {
            Class<?> anim = Class.forName(
                    "com.android.launcher.pageindicators.PageIndicatorAnimHelper", false, cl);
            for (Method m : anim.getDeclaredMethods()) {
                String name = m.getName();
                if (!(name.equals("startPressDragging") || name.equals("cancelPressDragging")
                        || name.equals("startIndicatorBgAnim") || name.equals("reverseIndicatorBgAnim")
                        || name.equals("startPageNumChangeAnim") || name.equals("startIndicatorDotAnim"))
                        || m.isBridge() || m.isSynthetic()) continue;
                hookOnce(m, chain -> {
                    Object helper = chain.getThisObject();
                    Object indicator = field(helper, "mPageIndicator");
                    try {
                        if (name.equals("startIndicatorDotAnim")) {
                            // OEM forces mBgAlpha=0 then animates 255→0 while shrinking dots.
                            // Hold glass across that dip or blur flashes on the reappear frame.
                            pageIndicatorFrameActive = false;
                            pageIndicatorExitHold = true;
                            applyPageIndicatorFrameGlass(indicator);
                        } else if (name.equals("startPressDragging")
                                || name.equals("startIndicatorBgAnim")) {
                            pageIndicatorExitHold = false;
                            pageIndicatorFrameActive = true;
                            applyPageIndicatorFrameGlass(indicator);
                        }
                    } catch (Throwable e) {
                        log(5, TAG, "PageIndicatorAnimHelper." + name + " pre-glass failed", e);
                    }
                    Object result = chain.proceed();
                    try {
                        if (name.equals("cancelPressDragging") || name.equals("reverseIndicatorBgAnim")) {
                            pageIndicatorFrameActive = false;
                            if (isPagePreviewChromeActive(helper)) {
                                applyPageIndicatorFrameGlass(indicator);
                            } else {
                                beginPageIndicatorExitHold(indicator);
                            }
                        } else if (name.equals("startPageNumChangeAnim")
                                || name.equals("startIndicatorDotAnim")) {
                            pageIndicatorExitHold = true;
                            applyPageIndicatorFrameGlass(indicator);
                            schedulePageIndicatorExitRelease(indicator);
                        }
                    } catch (Throwable e) {
                        log(5, TAG, "PageIndicatorAnimHelper." + name + " glass failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            log(5, TAG, "PageIndicatorAnimHelper glass hooks unavailable", e);
        }
        try {
            Class<?> indicator = Class.forName(
                    "com.android.launcher.pageindicators.OplusPageIndicator", false, cl);
            for (Method m : indicator.getDeclaredMethods()) {
                String name = m.getName();
                if (m.isBridge() || m.isSynthetic()) continue;
                if (name.equals("drawBackgroundIfNeeded")) {
                    hookOnce(m, chain -> {
                        if (GlassInstaller.suppressPageIndicatorBackground()) {
                            return null;
                        }
                        if (enabled() && shouldOwnPageIndicatorFrame(chain.getThisObject())) {
                            applyPageIndicatorFrameGlass(chain.getThisObject());
                            Object blurBg = field(chain.getThisObject(), "mBlurBgView");
                            if (blurBg instanceof View && GlassInstaller.get((View) blurBg) != null) {
                                return null;
                            }
                        }
                        return chain.proceed();
                    });
                } else if (name.equals("initBlurBackground")) {
                    hookOnce(m, chain -> {
                        Object result = chain.proceed();
                        if (enabled() && shouldOwnPageIndicatorFrame(chain.getThisObject())) {
                            applyPageIndicatorFrameGlass(chain.getThisObject());
                        }
                        return result;
                    });
                } else if (name.equals("updateBlurBgIfNeed")) {
                    hookOnce(m, chain -> {
                        Object result = chain.proceed();
                        try {
                            Object self = chain.getThisObject();
                            Object blurBg = field(self, "mBlurBgView");
                            if (!(blurBg instanceof View)) return result;
                            View bg = (View) blurBg;
                            if (bg.getAlpha() <= 0.01f) {
                                // startIndicatorDotAnim zeros alpha then ramps back to 255.
                                // Never uninstall synchronously here while exit-hold is active.
                                if (pageIndicatorExitHold || pageIndicatorFrameActive
                                        || isPageIndicatorPageChangeAnimating(self)) {
                                    schedulePageIndicatorExitRelease(self);
                                } else if (GlassInstaller.get(bg) != null) {
                                    GlassInstaller.uninstall(bg);
                                }
                            } else if (shouldOwnPageIndicatorFrame(self)) {
                                applyPageIndicatorFrameGlass(self);
                            }
                        } catch (Throwable e) {
                            log(5, TAG, "updateBlurBgIfNeed glass cleanup failed", e);
                        }
                        return result;
                    });
                } else if (name.equals("setMarkersCount")) {
                    hookOnce(m, chain -> {
                        Object result = chain.proceed();
                        if (enabled() && shouldOwnPageIndicatorFrame(chain.getThisObject())) {
                            applyPageIndicatorFrameGlass(chain.getThisObject());
                        }
                        return result;
                    });
                }
            }
        } catch (Throwable e) {
            log(5, TAG, "OplusPageIndicator glass hooks unavailable", e);
        }
        try {
            Class<?> wrapper = Class.forName(
                    "com.android.launcher.togglebar.PressFeedbackPreviewWrapper", false, cl);
            for (Method m : wrapper.getDeclaredMethods()) {
                if (!m.getName().equals("onDraw") || m.getParameterCount() != 1
                        || m.isBridge() || m.isSynthetic()) continue;
                hookOnce(m, chain -> {
                    if (enabled()) {
                        Object view = field(chain.getThisObject(), "mView");
                        if (view instanceof View
                                && isClassOrSubclass(view,
                                "com.android.launcher.pagepreview.PagePreviewItemView")
                                && isPagePreviewChromeActive(view)) {
                            setField(chain.getThisObject(), "mIsNeedDrawPressColor", false);
                            applyPagePreviewItemGlass(view);
                        }
                    }
                    return chain.proceed();
                });
            }
        } catch (Throwable e) {
            log(5, TAG, "PressFeedbackPreviewWrapper.onDraw glass hook unavailable", e);
        }
    }

    private void applyToggleBarToolbarGlass(Object toolbar) {
        if (!enabled() || toolbar == null) return;
        applyPressFeedbackButtonGlass(field(toolbar, "applyBtn"));
        applyPressFeedbackButtonGlass(field(toolbar, "backToMainPage"));
        applyPressFeedbackButtonGlass(field(toolbar, "addCardBtn"));
        applyPressFeedbackButtonGlass(field(toolbar, "mFinishBtn"));
        applyPressFeedbackButtonGlass(field(toolbar, "mAddCardBtn"));
        applyPressFeedbackButtonGlass(field(toolbar, "dragCancelButton"));
        applyPressFeedbackButtonGlass(field(toolbar, "mDragCancelButton"));
        // Also walk the tree so renamed fields / DragCancelButton subclasses are covered.
        if (toolbar instanceof View) applyPressFeedbackButtonsUnder((View) toolbar);
    }

    private void applyPressFeedbackButtonsUnder(View root) {
        if (root == null) return;
        if (isClassOrSubclass(root, "com.android.launcher.views.PressFeedbackButton")) {
            applyPressFeedbackButtonGlass(root);
            return;
        }
        if (!(root instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup group = (android.view.ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            applyPressFeedbackButtonsUnder(group.getChildAt(i));
        }
    }

    private void applyPagePreviewItemGlass(Object item) {
        if (!enabled() || !(item instanceof View)) return;
        if (!isClassOrSubclass(item, "com.android.launcher.pagepreview.PagePreviewItemView")) return;
        if (!isPagePreviewChromeActive(item)) return;
        final View preview = (View) item;
        try {
            Object wrapper = field(preview, "mPressFeedbackPreviewWrapper");
            if (wrapper != null) {
                setField(wrapper, "mIsNeedDrawPressColor", false);
                invoke(wrapper, "setIsNeedDrawPressColor", new Class<?>[] { boolean.class }, false);
            }
            GlassDrawable existing = GlassInstaller.get(preview);
            boolean missing = existing == null || preview.getBackground() != existing;
            GlassInstaller.installBackground(preview, currentConfig());
            GlassDrawable live = GlassInstaller.get(preview);
            if (live == null) return;
            float radius = readPagePreviewRadius(preview);
            if (radius > 0f) live.setCornerRadii(radius, radius, radius, radius);
            if (!missing) return;
            Runnable refresh = () -> {
                GlassDrawable g = GlassInstaller.get(preview);
                if (g == null) return;
                float r = readPagePreviewRadius(preview);
                if (r > 0f) g.setCornerRadii(r, r, r, r);
                GlassInstaller.forceCapture(preview);
                preview.invalidate();
            };
            if (preview.getWidth() > 0 && preview.getHeight() > 0) refresh.run();
            else preview.post(refresh);
        } catch (Throwable e) {
            log(5, TAG, "applyPagePreviewItemGlass failed", e);
        }
    }

    private void hardRemovePagePreviewItemGlass(Object item) {
        if (!(item instanceof View)) return;
        if (!isClassOrSubclass(item, "com.android.launcher.pagepreview.PagePreviewItemView")) return;
        View preview = (View) item;
        try {
            Object wrapper = field(preview, "mPressFeedbackPreviewWrapper");
            if (wrapper != null) {
                setField(wrapper, "mIsNeedDrawPressColor", true);
                invoke(wrapper, "setIsNeedDrawPressColor", new Class<?>[] { boolean.class }, true);
            }
            GlassInstaller.uninstall(preview);
        } catch (Throwable e) {
            log(5, TAG, "removePagePreviewItemGlass failed", e);
        }
    }

    private void applyPagePreviewRootGlass(Object root) {
        if (!enabled() || !(root instanceof View)) return;
        applyPagePreviewItemsUnder((View) root);
    }

    private void removePagePreviewRootGlass(Object root) {
        if (!(root instanceof View)) return;
        hardRemovePagePreviewItemsUnder((View) root);
    }

    private void applyPagePreviewListGlass(Object list) {
        if (!enabled() || !(list instanceof View)) return;
        applyPagePreviewItemsUnder((View) list);
    }

    private void removePagePreviewListGlass(Object list) {
        if (!(list instanceof View)) return;
        hardRemovePagePreviewItemsUnder((View) list);
    }

    private void applyPagePreviewItemsUnder(View root) {
        if (root == null) return;
        if (isClassOrSubclass(root, "com.android.launcher.pagepreview.PagePreviewItemView")) {
            applyPagePreviewItemGlass(root);
            return;
        }
        if (!(root instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup group = (android.view.ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            applyPagePreviewItemsUnder(group.getChildAt(i));
        }
    }

    private void hardRemovePagePreviewItemsUnder(View root) {
        if (root == null) return;
        if (isClassOrSubclass(root, "com.android.launcher.pagepreview.PagePreviewItemView")) {
            hardRemovePagePreviewItemGlass(root);
            return;
        }
        if (!(root instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup group = (android.view.ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            hardRemovePagePreviewItemsUnder(group.getChildAt(i));
        }
    }

    private void syncPagePreviewFrameGlassForLauncher(Object launcher) {
        if (!enabled() || launcher == null) return;
        Object indicator = findPageIndicator(launcher);
        if (isPagePreviewChromeActive(launcher) || pageIndicatorFrameActive) {
            pageIndicatorExitHold = false;
            applyPagePreviewGlassForLauncher(launcher);
            applyPageIndicatorFrameGlass(indicator);
            return;
        }
        pageIndicatorFrameActive = false;
        // Thumbnails can drop immediately; indicator pill must keep glass through blank-page
        // shrink + fade that continues after NORMAL.
        Object root = findPagePreviewRoot(launcher);
        if (root instanceof View) hardRemovePagePreviewItemsUnder((View) root);
        beginPageIndicatorExitHold(indicator);
    }

    private void applyPagePreviewGlassForLauncher(Object launcher) {
        Object root = findPagePreviewRoot(launcher);
        if (root instanceof View) applyPagePreviewItemsUnder((View) root);
    }

    private Object findPagePreviewRoot(Object launcher) {
        Object manager = invokeNoArgs(launcher, "getPagePreviewManager");
        Object root = invokeNoArgs(manager, "getPagePreviewLayout");
        if (root == null) root = field(manager, "mPagePreviewLayout");
        return root;
    }

    private Object findPageIndicator(Object launcher) {
        Object workspace = invokeNoArgs(launcher, "getWorkspace");
        Object indicator = invokeNoArgs(workspace, "getPageIndicator");
        if (indicator == null) indicator = field(workspace, "mPageIndicator");
        return indicator;
    }

    private void beginPageIndicatorExitHold(Object indicator) {
        pageIndicatorExitHold = true;
        applyPageIndicatorFrameGlass(indicator);
        schedulePageIndicatorExitRelease(indicator);
    }

    private void schedulePageIndicatorExitRelease(Object indicator) {
        Object blurBg = field(indicator, "mBlurBgView");
        if (!(blurBg instanceof View)) return;
        View bg = (View) blurBg;
        if (pageIndicatorExitRelease != null && pageIndicatorExitReleaseTarget != null) {
            pageIndicatorExitReleaseTarget.removeCallbacks(pageIndicatorExitRelease);
        }
        pageIndicatorExitReleaseTarget = bg;
        final Object indRef = indicator;
        pageIndicatorExitRelease = () -> {
            try {
                if (pageIndicatorFrameActive || isPagePreviewChromeActive(indRef)) return;
                if (isPageIndicatorPageChangeAnimating(indRef)) {
                    schedulePageIndicatorExitRelease(indRef);
                    return;
                }
                Object liveBg = field(indRef, "mBlurBgView");
                View v = liveBg instanceof View ? (View) liveBg : bg;
                if (v.getAlpha() > 0.01f) {
                    pageIndicatorExitHold = true;
                    applyPageIndicatorFrameGlass(indRef);
                    schedulePageIndicatorExitRelease(indRef);
                    return;
                }
                pageIndicatorExitHold = false;
                if (GlassInstaller.get(v) != null) GlassInstaller.uninstall(v);
            } catch (Throwable e) {
                log(5, TAG, "pageIndicatorExitRelease failed", e);
            }
        };
        // startIndicatorDotAnim lasts 450ms and briefly zeros alpha before ramping up.
        bg.postDelayed(pageIndicatorExitRelease, 500);
    }

    private boolean isPageIndicatorPageChangeAnimating(Object indicatorOrHost) {
        Object indicator = indicatorOrHost;
        if (!isClassOrSubclass(indicator, "com.android.launcher.pageindicators.OplusPageIndicator")) {
            Object launcher = resolveLauncher(indicatorOrHost);
            if (launcher == null) launcher = findActiveLauncher();
            indicator = findPageIndicator(launcher);
        }
        Object helper = field(indicator, "mAnimHelper");
        Object animating = field(helper, "mPageChangeAnimating");
        return Boolean.TRUE.equals(animating);
    }

    private void applyPageIndicatorFrameGlass(Object indicator) {
        if (!enabled() || !(indicator instanceof View)) return;
        Object blurBg = field(indicator, "mBlurBgView");
        if (!(blurBg instanceof View)) return;
        final View bg = (View) blurBg;
        try {
            GlassDrawable existing = GlassInstaller.get(bg);
            boolean missing = existing == null || bg.getBackground() != existing;
            GlassInstaller.installBackground(bg, currentConfig());
            GlassDrawable live = GlassInstaller.get(bg);
            if (live == null) return;
            Object radiusObj = field(indicator, "mBackgroundRadius");
            float radius = radiusObj instanceof Number
                    ? ((Number) radiusObj).floatValue()
                    : 24f * bg.getResources().getDisplayMetrics().density;
            if (radius > 0f) live.setCornerRadii(radius, radius, radius, radius);
            if (!missing) return;
            Runnable refresh = () -> {
                GlassDrawable g = GlassInstaller.get(bg);
                if (g == null) return;
                Object rObj = field(indicator, "mBackgroundRadius");
                float r = rObj instanceof Number
                        ? ((Number) rObj).floatValue()
                        : 24f * bg.getResources().getDisplayMetrics().density;
                if (r > 0f) g.setCornerRadii(r, r, r, r);
                GlassInstaller.forceCapture(bg);
                bg.invalidate();
                ((View) indicator).invalidate();
            };
            if (bg.getWidth() > 0 && bg.getHeight() > 0) refresh.run();
            else bg.post(refresh);
        } catch (Throwable e) {
            log(5, TAG, "applyPageIndicatorFrameGlass failed", e);
        }
    }

    /** True when the expanded indicator pill should keep liquid glass (incl. exit fade). */
    private boolean shouldOwnPageIndicatorFrame(Object host) {
        if (!enabled()) return false;
        if (pageIndicatorFrameActive || pageIndicatorExitHold) return true;
        if (isPagePreviewChromeActive(host)) return true;
        Object indicator = host;
        if (!isClassOrSubclass(host, "com.android.launcher.pageindicators.OplusPageIndicator")) {
            Object launcher = resolveLauncher(host);
            if (launcher == null) launcher = findActiveLauncher();
            indicator = findPageIndicator(launcher);
        }
        Object blurBg = field(indicator, "mBlurBgView");
        return blurBg instanceof View && ((View) blurBg).getAlpha() > 0.01f
                && GlassInstaller.get((View) blurBg) != null;
    }

    /**
     * Thumbnail cards are shown in PAGE_PREVIEW / TOGGLE_BAR (widget tray), or while
     * press-dragging page dots.
     */
    private boolean isPagePreviewChromeActive(Object host) {
        if (pageIndicatorFrameActive) return true;
        Object launcher = resolveLauncher(host);
        if (launcher == null) launcher = findActiveLauncher();
        if (launcher == null) return false;
        return isInLauncherState(launcher, "PAGE_PREVIEW")
                || isInLauncherState(launcher, "TOGGLE_BAR");
    }

    private boolean isInLauncherState(Object launcher, String stateName) {
        if (launcher == null || stateName == null) return false;
        try {
            ClassLoader cl = launcher.getClass().getClassLoader();
            Class<?> stateClass = Class.forName("com.android.launcher3.LauncherState", false, cl);
            Object state = stateClass.getField(stateName).get(null);
            Method isInState = null;
            for (Method m : launcher.getClass().getMethods()) {
                if ("isInState".equals(m.getName()) && m.getParameterCount() == 1) {
                    isInState = m;
                    break;
                }
            }
            return isInState != null && state != null
                    && Boolean.TRUE.equals(isInState.invoke(launcher, state));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static float readPagePreviewRadius(View preview) {
        Object wrapper = field(preview, "mPressFeedbackPreviewWrapper");
        Object radius = invokeNoArgs(wrapper, "getRadius");
        if (radius instanceof Number && ((Number) radius).floatValue() > 0f) {
            return ((Number) radius).floatValue();
        }
        Object viaField = field(wrapper, "mRadius");
        if (viaField instanceof Number && ((Number) viaField).floatValue() > 0f) {
            return ((Number) viaField).floatValue();
        }
        // Fallback matches PreviewItemFrameRadius (8dp).
        return 8f * preview.getResources().getDisplayMetrics().density;
    }

    private void applyToggleBarItemGlass(Object itemRoot) {
        if (!enabled() || itemRoot == null) return;
        if (!isClassOrSubclass(itemRoot, "com.android.launcher.togglebar.views.PressFeedbackLinearLayout")) {
            return;
        }
        Object icon = field(itemRoot, "iconImageView");
        if (!(icon instanceof View) && itemRoot instanceof View) {
            icon = findChildBySimpleName((View) itemRoot, "PressFeedbackCircleImageView");
        }
        if (!(icon instanceof View)) return;
        final View circle = (View) icon;
        try {
            // Preserve the OEM glyph; glass sits behind as background with the same circular shape.
            GlassInstaller.installBackground(circle, currentConfig());
            Runnable refresh = () -> {
                GlassDrawable live = GlassInstaller.get(circle);
                if (live == null) return;
                int size = Math.min(circle.getWidth(), circle.getHeight());
                if (size <= 0) return;
                float radius = size / 2f;
                live.setCornerRadii(radius, radius, radius, radius);
                GlassInstaller.forceCapture(circle);
                circle.invalidate();
            };
            if (circle.getWidth() > 0 && circle.getHeight() > 0) refresh.run();
            else circle.post(refresh);
        } catch (Throwable e) {
            log(5, TAG, "applyToggleBarItemGlass failed", e);
        }
    }

    private static View findChildBySimpleName(View root, String simpleName) {
        if (root == null || simpleName == null) return null;
        if (root.getClass().getSimpleName().equals(simpleName)) return root;
        if (!(root instanceof android.view.ViewGroup)) return null;
        android.view.ViewGroup group = (android.view.ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            View found = findChildBySimpleName(group.getChildAt(i), simpleName);
            if (found != null) return found;
        }
        return null;
    }

    private void applyClearButtonGlass(Object panel) {
        if (!enabled() || panel == null) return;
        applyPressFeedbackButtonGlass(field(panel, "mClearAllBtn"));
    }

    /** Shared installer for PressFeedbackButton chrome (清除 / 完成 / 取消 / …). */
    private void applyPressFeedbackButtonGlass(Object buttonObj) {
        if (!enabled() || !(buttonObj instanceof View)) return;
        if (!isClassOrSubclass(buttonObj, "com.android.launcher.views.PressFeedbackButton")) return;
        View button = (View) buttonObj;
        try {
            setField(button, "mSrcDrawable", null);
            Object handler = field(button, "mPressFeedbackHandler");
            if (handler != null) {
                invoke(handler, "setDrawableColor", new Class<?>[] { int.class }, 0);
            }
            GlassInstaller.installBackground(button, currentConfig());
            GlassDrawable glass = GlassInstaller.get(button);
            if (glass != null) {
                float radius = readClearButtonRadius(button);
                if (radius > 0f) glass.setCornerRadii(radius, radius, radius, radius);
            }
            Runnable refresh = () -> {
                GlassDrawable live = GlassInstaller.get(button);
                if (live == null) return;
                float radius = readClearButtonRadius(button);
                if (radius <= 0f && button.getHeight() > 0) {
                    radius = button.getHeight() / 2f;
                }
                if (radius > 0f) live.setCornerRadii(radius, radius, radius, radius);
                GlassInstaller.forceCapture(button);
                button.invalidate();
            };
            if (button.getWidth() > 0 && button.getHeight() > 0) refresh.run();
            else button.post(refresh);
        } catch (Throwable e) {
            log(5, TAG, "applyPressFeedbackButtonGlass failed", e);
        }
    }

    /** Rewrites AnimInfo endValue so hand-follow / spring blur targets stay at 0. */
    private static void zeroAnimInfoEndValue(Object animInfo) {
        if (animInfo == null) return;
        setField(animInfo, "endValue", 0f);
    }

    private static float readClearButtonRadius(View button) {
        Object radius = field(button, "mDrawableRadius");
        if (radius instanceof Number) {
            float value = ((Number) radius).floatValue();
            if (value > 0f) return value;
        }
        Object viaGetter = invokeNoArgs(button, "getMDrawableRadius");
        if (viaGetter instanceof Number) {
            float value = ((Number) viaGetter).floatValue();
            if (value > 0f) return value;
        }
        return 0f;
    }

    private boolean shouldSuppressDepthBlur() {
        return shouldSuppressDepthBlur(null);
    }

    private boolean shouldSuppressDepthBlur(Object depthController) {
        if (!enabled()) return false;
        if (folderDragActive || folderOpenActive) return true;
        Object launcher = resolveLauncher(depthController != null
                ? field(depthController, "mLauncher") : null);
        if (launcher == null) launcher = findActiveLauncher();
        return shouldSuppressDepthBlurForLauncher(launcher);
    }

    private boolean shouldSuppressDepthBlurForLauncher(Object launcher) {
        return isInFrostedWallpaperBlurState(launcher) || hasOpenFolder(launcher);
    }

    /**
     * TOGGLE_BAR / PAGE_PREVIEW (icon edit / widget tray) and OVERVIEW* (Recents) share the
     * frosted wallpaper blur that should stay off while LiquidGlass is enabled.
     */
    private boolean isInFrostedWallpaperBlurState(Object launcher) {
        if (launcher == null) return false;
        try {
            ClassLoader cl = launcher.getClass().getClassLoader();
            Class<?> stateClass = Class.forName("com.android.launcher3.LauncherState", false, cl);
            Method isInState = null;
            for (Method m : launcher.getClass().getMethods()) {
                if ("isInState".equals(m.getName()) && m.getParameterCount() == 1) {
                    isInState = m;
                    break;
                }
            }
            if (isInState == null) return false;
            String[] fields = {
                    "TOGGLE_BAR", "PAGE_PREVIEW",
                    "OVERVIEW", "OVERVIEW_MODAL_TASK", "OVERVIEW_SPLIT_SELECT", "BACKGROUND_APP"
            };
            for (String name : fields) {
                Object state;
                try {
                    state = stateClass.getField(name).get(null);
                } catch (Throwable ignored) {
                    continue;
                }
                if (state != null && Boolean.TRUE.equals(isInState.invoke(launcher, state))) {
                    return true;
                }
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean hasOpenFolder(Object launcher) {
        if (launcher == null) return false;
        try {
            ClassLoader cl = launcher.getClass().getClassLoader();
            Class<?> afv = Class.forName("com.android.launcher3.AbstractFloatingView", false, cl);
            Method getOpenFolder = null;
            for (Method m : afv.getMethods()) {
                if ("getOpenFolder".equals(m.getName()) && m.getParameterCount() == 1) {
                    getOpenFolder = m;
                    break;
                }
            }
            if (getOpenFolder != null && getOpenFolder.invoke(null, launcher) != null) return true;
            Class<?> folder = Class.forName("com.android.launcher3.folder.Folder", false, cl);
            Method getOpen = null;
            for (Method m : folder.getMethods()) {
                if ("getOpen".equals(m.getName()) && m.getParameterCount() == 1) {
                    getOpen = m;
                    break;
                }
            }
            return getOpen != null && getOpen.invoke(null, launcher) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Object findActiveLauncher() {
        try {
            Class<?> launcherClass = Class.forName("com.android.launcher3.Launcher");
            try {
                Object tracker = launcherClass.getField("ACTIVITY_TRACKER").get(null);
                Object created = tracker.getClass().getMethod("getCreatedActivity").invoke(tracker);
                if (created != null) return created;
            } catch (Throwable ignored) { }
            try {
                return launcherClass.getMethod("getLauncher", android.content.Context.class)
                        .invoke(null, (Object) null);
            } catch (Throwable ignored) { }
        } catch (Throwable ignored) { }
        return null;
    }

    private volatile Object folderDragSource;

    private void beginFolderDrag(Object folderIcon) {
        // Folder-only flag. Widgets must never flip this on — refreshFolderDragGlass would
        // otherwise reinstall glass onto whatever DragView content is currently moving.
        if (!isFolderDragSource(folderIcon)) return;
        folderDragActive = true;
        folderDragSource = folderIcon;
        forceDepthBlur(folderIcon, 0f);
    }

    private void endFolderDrag(Object host) {
        if (!folderDragActive) return;
        folderDragActive = false;
        Object source = folderDragSource;
        folderDragSource = null;
        // Finger-up: drop overlay seeds immediately. Otherwise the returned FolderIcon keeps
        // compositing the desktop into its own LiquidGlass (self secondary-refraction).
        try {
            View dragContent = findFolderDragContentView(null);
            GlassInstaller.clearDragOverlays();
            if (dragContent != null && GlassInstaller.get(dragContent) != null) {
                GlassInstaller.uninstall(dragContent);
            }
            if (source != null) syncFolderIconDeferred(source, true);
        } catch (Throwable ignored) { }
        // Stay suppressed while still in TOGGLE_BAR / open folder; otherwise restore.
        Object launcher = resolveLauncher(host);
        if (shouldSuppressDepthBlurForLauncher(launcher) || folderOpenActive) {
            forceDepthBlur(launcher != null ? launcher : host, 0f);
            return;
        }
        try {
            float restore = 0f;
            Object stateManager = invokeNoArgs(launcher, "getStateManager");
            Object state = invokeNoArgs(stateManager, "getState");
            Object blur = invoke(state, "getBlur", new Class<?>[] { android.content.Context.class },
                    launcher instanceof android.content.Context ? launcher : null);
            if (blur instanceof Number) restore = ((Number) blur).floatValue();
            forceDepthBlur(launcher != null ? launcher : host, restore);
        } catch (Throwable e) {
            log(5, TAG, "Restore depth blur after folder drag failed", e);
        }
    }

    private void beginFolderOpen(Object folder) {
        folderOpenActive = true;
        forceDepthBlur(folder, 0f);
        if (DesktopBackdropHub.isLive()) {
            DesktopBackdropSampler.invalidateCache();
        }
    }

    private void endFolderOpen(Object host) {
        if (!folderOpenActive && !hasOpenFolder(resolveLauncher(host))) {
            return;
        }
        folderOpenActive = false;
        Object launcher = resolveLauncher(host);
        forceDepthBlur(launcher != null ? launcher : host, 0f);
        if (DesktopBackdropHub.isLive()) {
            DesktopBackdropSampler.invalidateCache();
        }
    }

    /**
     * Tell SystemUI whether Launcher-backed ashmem is meaningful.
     * <ul>
     *   <li>Home + Overview/Recents → ashmem (icons or Recents cards from Launcher)</li>
     *   <li>PAGE_PREVIEW / TOGGLE_BAR → {@code captureDisplay}</li>
     * </ul>
     */
    private void syncDesktopHomeValidForLauncher(Object launcher) {
        try {
            boolean overview = isLauncherOverviewState(launcher);
            LauncherAshmemMode.setOverviewActive(overview);
            boolean ashmemOk = !isInLauncherState(launcher, "PAGE_PREVIEW")
                    && !isInLauncherState(launcher, "TOGGLE_BAR");
            DesktopBackdropHub.setDesktopHomeValid(ashmemOk);
            if (DesktopBackdropHub.isLive()) {
                DesktopBackdropSampler.invalidateCache();
            }
        } catch (Throwable ignored) { }
    }

    private boolean isLauncherOverviewState(Object launcher) {
        if (launcher == null) return false;
        try {
            ClassLoader cl = launcher.getClass().getClassLoader();
            Class<?> stateClass = Class.forName("com.android.launcher3.LauncherState", false, cl);
            Method isInState = null;
            for (Method m : launcher.getClass().getMethods()) {
                if ("isInState".equals(m.getName()) && m.getParameterCount() == 1) {
                    isInState = m;
                    break;
                }
            }
            if (isInState == null) return false;
            String[] fields = {
                    "OVERVIEW", "OVERVIEW_MODAL_TASK", "OVERVIEW_SPLIT_SELECT", "BACKGROUND_APP"
            };
            for (String name : fields) {
                Object state;
                try {
                    state = stateClass.getField(name).get(null);
                } catch (Throwable ignored) {
                    continue;
                }
                if (state != null && Boolean.TRUE.equals(isInState.invoke(launcher, state))) {
                    return true;
                }
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Object resolveLauncher(Object host) {
        if (host == null) return null;
        if (isClassOrSubclass(host, "com.android.launcher3.Launcher")
                || isClassOrSubclass(host, "com.android.launcher.Launcher")) {
            return host;
        }
        Object launcher = field(host, "mLauncher");
        if (launcher != null) return launcher;
        return field(host, "mActivity");
    }

    private void forceDepthBlur(Object host, float value) {
        try {
            Object launcher = resolveLauncher(host);
            Object depth = invokeNoArgs(launcher, "getDepthController");
            if (depth == null) return;
            Method setBlurWithoutAnim = null;
            for (Method m : depth.getClass().getMethods()) {
                if (m.getName().equals("setBlurWithoutAnim") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == float.class) {
                    setBlurWithoutAnim = m;
                    break;
                }
            }
            if (setBlurWithoutAnim != null) {
                setBlurWithoutAnim.invoke(depth, value);
            }
            Method setIconBlurWithoutAnim = null;
            for (Method m : depth.getClass().getMethods()) {
                if (m.getName().equals("setIconBlurWithoutAnim") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == float.class) {
                    setIconBlurWithoutAnim = m;
                    break;
                }
            }
            if (setIconBlurWithoutAnim != null) {
                setIconBlurWithoutAnim.invoke(depth, 0f);
            }
        } catch (Throwable e) {
            log(5, TAG, "forceDepthBlur failed", e);
        }
    }

    private static Object invoke(Object object, String name, Class<?>[] types, Object... args) {
        if (object == null) return null;
        Class<?> c = object.getClass();
        while (c != null) {
            try {
                Method m = c.getDeclaredMethod(name, types);
                m.setAccessible(true);
                return m.invoke(object, args);
            } catch (Throwable ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    /** Drops any OplusBlurProperties already attached to the DragView. */
    private static void clearDragBlurProp(Object dragView) {
        Object blurProp = field(dragView, "mBlurProp");
        if (blurProp != null) {
            try {
                Method recycle = blurProp.getClass().getMethod("recycle");
                recycle.setAccessible(true);
                recycle.invoke(blurProp);
            } catch (Throwable ignored) { }
            setField(dragView, "mBlurProp", null);
        }
    }

    /** Neutralizes an OEM LayerBlurDrawable if it is still the content background. */
    private static void suppressLayerBlur(View content) {
        Drawable background = content.getBackground();
        if (background == null) return;
        if (!isClassOrSubclass(background, "com.android.launcher3.uioverrides.states.blurdrawable.LayerBlurDrawable")) {
            return;
        }
        try {
            Method setBlurAlpha = background.getClass().getMethod("setBlurAlpha", float.class);
            setBlurAlpha.setAccessible(true);
            setBlurAlpha.invoke(background, 0f);
        } catch (Throwable ignored) { }
        content.setBackground(null);
    }

    private void hookDragViewMove(ClassLoader cl) {
        final String className = "com.android.launcher3.dragndrop.DragView";
        try {
            Class<?> c = Class.forName(className, false, cl);
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("move") || m.isBridge() || m.isSynthetic()) continue;
                hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        Object content = invokeNoArgs(chain.getThisObject(), "getContentView");
                        if (!(content instanceof View)) return result;
                        View view = (View) content;
                        // Widget / card DragView: never refresh folder glass; always strip.
                        if (isWidgetDragContent(view) || !isFolderDragSource(folderDragSource)) {
                            stripWidgetDragViewGlass(chain.getThisObject());
                            if (folderDragActive && !isFolderDragSource(folderDragSource)) {
                                folderDragActive = false;
                                folderDragSource = null;
                            }
                            return result;
                        }
                        if (folderDragActive && isFolderDragSource(folderDragSource)) {
                            // Finger motion dirties geometry; only reinstall if OEM cleared glass.
                            if (GlassInstaller.get(view) == null) {
                                refreshFolderDragGlass(chain.getThisObject(), true);
                            } else {
                                view.invalidate();
                            }
                        }
                    } catch (Throwable e) {
                        log(5, TAG, "DragView.move glass refresh failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            log(5, TAG, "DragView.move hook unavailable", e);
        }
    }

    private void hookWorkspaceDragOver(ClassLoader cl) {
        after("com.android.launcher3.OplusWorkspace", cl, "onDragOver", this::syncDragOverlaySource);
        after("com.android.launcher3.Workspace", cl, "onDragOver", this::syncDragOverlaySource);
        for (String className : new String[] {
                "com.android.launcher3.OplusWorkspace",
                "com.android.launcher3.Workspace"
        }) {
            try {
                Class<?> c = Class.forName(className, false, cl);
                for (Method m : c.getDeclaredMethods()) {
                    if (m.isBridge() || m.isSynthetic()) continue;
                    if (!m.getName().equals("setCurrentDropLayout")) continue;
                    hookOnce(m, chain -> {
                        Object result = chain.proceed();
                        try {
                            if (folderDragActive && isFolderDragSource(folderDragSource)) {
                                syncDragOverlaySource(chain.getThisObject());
                            }
                        } catch (Throwable e) {
                            log(5, TAG, className + ".setCurrentDropLayout overlay sync failed", e);
                        }
                        return result;
                    });
                }
            } catch (Throwable e) {
                log(5, TAG, className + " setCurrentDropLayout hook unavailable", e);
            }
        }
        for (String className : new String[] {
                "com.android.launcher3.OplusWorkspace",
                "com.android.launcher3.Workspace"
        }) {
            try {
                Class<?> c = Class.forName(className, false, cl);
                for (Method m : c.getDeclaredMethods()) {
                    if (m.isBridge() || m.isSynthetic()) continue;
                    String name = m.getName();
                    if (!(name.equals("onScrollChanged")
                            || name.equals("onPageBeginMoving")
                            || name.equals("onPageEndTransition")
                            || name.equals("snapToPageForDrag"))) continue;
                    hookOnce(m, chain -> {
                        Object result = chain.proceed();
                        try {
                            if (folderDragActive && isFolderDragSource(folderDragSource)) {
                                refreshFolderDragGlass(null, true);
                            }
                        } catch (Throwable e) {
                            log(5, TAG, className + "." + name + " drag glass refresh failed", e);
                        }
                        return result;
                    });
                }
            } catch (Throwable e) {
                log(5, TAG, className + " page-scroll glass hook unavailable", e);
            }
        }
    }

    private void syncDragOverlaySource(Object workspace) {
        View desktopPage = desktopPageUnderDrag(workspace);
        Object hover = dragHoverView(workspace);
        boolean hoverIsFolder = hover != null
                && isClassOrSubclass(hover, "com.android.launcher3.folder.FolderIcon");

        // Overlay seed only while a real folder is being dragged — never for widget DragViews.
        if (folderDragActive && isFolderDragSource(folderDragSource)) {
            View content = findFolderDragContentView(null);
            if (content != null && GlassInstaller.get(content) != null) {
                GlassDrawable glass = GlassInstaller.get(content);
                if (glass != null) glass.setAlpha(255);
                View seed = workspace instanceof View ? (View) workspace : desktopPage;
                if (seed != null) {
                    GlassInstaller.setOverlaySource(content, seed);
                } else if (desktopPage != null) {
                    GlassInstaller.setOverlaySource(content, desktopPage);
                }
                GlassInstaller.forceCapture(content);
                content.invalidate();
            }
        }

        if (hoverIsFolder) {
            syncFolderIconDeferred(hover, true);
            keepHoverFolderGlassVisible(field(hover, "mBackground"));
        }
    }

    /**
     * Prefer OEM {@code getCurrentDropLayout()} (mNextPage || mCurrentPage); skip Hotseat.
     */
    private View desktopPageUnderDrag(Object workspace) {
        if (workspace == null) return null;
        try {
            Object drop = invokeNoArgs(workspace, "getCurrentDropLayout");
            if (drop instanceof View) return (View) drop;
        } catch (Throwable ignored) { }
        try {
            Object pageIndex = invokeNoArgs(workspace, "getNextPage");
            if (!(pageIndex instanceof Number) || ((Number) pageIndex).intValue() < 0) {
                pageIndex = invokeNoArgs(workspace, "getCurrentPage");
            }
            if (pageIndex instanceof Number) {
                Object page = invoke(workspace, "getPageAt",
                        new Class<?>[] { int.class },
                        ((Number) pageIndex).intValue());
                if (page instanceof View) return (View) page;
            }
        } catch (Throwable ignored) { }
        Object layout = field(workspace, "mDragTargetLayout");
        if (layout instanceof View) {
            String name = layout.getClass().getSimpleName();
            if (name == null || !name.contains("Hotseat")) return (View) layout;
        }
        try {
            Object pageIndex = invokeNoArgs(workspace, "getCurrentPage");
            if (pageIndex instanceof Number) {
                Object page = invoke(workspace, "getPageAt",
                        new Class<?>[] { int.class },
                        ((Number) pageIndex).intValue());
                if (page instanceof View) return (View) page;
            }
        } catch (Throwable ignored) { }
        return workspace instanceof View ? (View) workspace : null;
    }

    /** Prefer mDragOverView; fall back to mDragTargetLayout + mTargetCell. */
    private Object dragHoverView(Object workspace) {
        Object hover = field(workspace, "mDragOverView");
        if (hover instanceof View) return hover;
        Object layout = field(workspace, "mDragTargetLayout");
        Object cell = field(workspace, "mTargetCell");
        if (!(layout instanceof View) || !(cell instanceof int[])) return null;
        int[] targetCell = (int[]) cell;
        if (targetCell.length < 2) return null;
        try {
            Object child = invoke(layout, "getChildAt",
                    new Class<?>[] { int.class, int.class },
                    targetCell[0], targetCell[1]);
            return child instanceof View ? child : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Create-folder / folder-accept: OEM hides {@code mBgView} and paints via CellLayout
     * {@code drawBackground}. Install glass for that canvas path — never force mBgView
     * VISIBLE while delegated or while OEM hid it (that shows folder plate + canvas glass).
     * Never {@code layout(0,0)} an attached FolderIcon plate (that shifts glass to top-left).
     * Create-folder parks {@code mBgView} on CellLayout for capture; detach before FolderIcon
     * takes ownership (see {@link #preparePreviewTransferToFolder}).
     */
    private void keepHoverFolderGlassVisible(Object previewBackground) {
        if (!enabled() || previewBackground == null) return;
        ensurePreviewGlassInstalled(previewBackground);
        Object folderHost = resolvePreviewFolderIcon(previewBackground);
        if (folderHost != null && isFolderOpen(folderHost)) return;
        if (isFolderPopupOemOnly(folderHost)) return;

        Object bgView = field(previewBackground, "mBgView");
        boolean delegated = isPreviewDrawingDelegated(previewBackground);
        if (delegated) {
            placeCreateFolderBgForCapture(previewBackground);
            suppressOemPreviewDrawable(previewBackground);
            // Folder accept: keep OEM-hidden plate hidden — canvas glass only.
            if (folderHost != null && bgView instanceof View) {
                ((View) bgView).setVisibility(View.INVISIBLE);
            }
            Object delegate = field(previewBackground, "mDrawingDelegate");
            if (delegate instanceof View) ((View) delegate).invalidate();
            Object invalidate = field(previewBackground, "mInvalidateDelegate");
            if (invalidate instanceof View) ((View) invalidate).invalidate();
            Object createHost = field(previewBackground, "mHostView");
            if (createHost instanceof View) ((View) createHost).invalidate();
            if (DesktopBackdropHub.isLive()) {
                DesktopBackdropSampler.invalidateCache();
            }
            return;
        }

        // animateToAccept hides mBgView before delegateDrawing runs — do not fight OEM.
        if (bgView instanceof View && ((View) bgView).getVisibility() != View.VISIBLE) {
            Object delegate = field(previewBackground, "mDrawingDelegate");
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
        Object delegated = invokeNoArgs(previewBackground, "drawingDelegated");
        if (delegated instanceof Boolean) return (Boolean) delegated;
        return field(previewBackground, "mDrawingDelegate") != null;
    }

    private static Object resolvePreviewFolderIcon(Object previewBackground) {
        Object host = field(previewBackground, "mInvalidateDelegate");
        if (isClassOrSubclass(host, "com.android.launcher3.folder.FolderIcon")) return host;
        Object createHost = field(previewBackground, "mHostView");
        if (isClassOrSubclass(createHost, "com.android.launcher3.folder.FolderIcon")) {
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
        if (!enabled() || previewBackground == null) return;
        Object folderHost = resolvePreviewFolderIcon(previewBackground);
        if (folderHost != null && isFolderOpen(folderHost)) return;
        if (isFolderPopupOemOnly(folderHost)) return;

        Object bgView = field(previewBackground, "mBgView");
        if (!(bgView instanceof ImageView)) {
            try {
                Object ctx = field(previewBackground, "mContext");
                Object attachHost = folderHost != null ? folderHost
                        : field(previewBackground, "mInvalidateDelegate");
                if (ctx instanceof android.content.Context && attachHost instanceof View) {
                    invoke(previewBackground, "initBgViewIfNeed",
                            new Class<?>[]{android.content.Context.class, View.class},
                            ctx, attachHost);
                }
            } catch (Throwable ignored) { }
            bgView = field(previewBackground, "mBgView");
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
        GlassInstaller.installImage(image, currentConfig());
        // b73ba87: keep installImage/detectRadii corners. Do NOT write PreviewBackground.mRadius
        // (or mRadius*mScale) — that looks enlarged vs the desktop default and sticks after cancel.
        if (image.getDrawable() != null) image.setImageDrawable(null);
        suppressOemPreviewDrawable(previewBackground);
    }

    private ImageView createDetachedPreviewBgView(Object previewBackground) {
        try {
            Object ctx = field(previewBackground, "mContext");
            if (!(ctx instanceof android.content.Context)) return null;
            float radius = 0f;
            Object r = field(previewBackground, "mRadius");
            if (r instanceof Number) radius = ((Number) r).floatValue();
            Class<?> cls = Class.forName("com.android.launcher3.folder.FolderRoundImageView",
                    false, previewBackground.getClass().getClassLoader());
            Object created = cls.getConstructor(float.class, android.content.Context.class)
                    .newInstance(radius, ctx);
            if (created instanceof ImageView) {
                setField(previewBackground, "mBgView", created);
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
        Object bgView = field(preview, "mBgView");
        Object delegate = field(preview, "mDrawingDelegate");
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
        Object bgView = field(preview, "mBgView");
        Object delegate = field(preview, "mDrawingDelegate");
        if (!(bgView instanceof ImageView) || !(delegate instanceof ViewGroup)) return;
        ImageView image = (ImageView) bgView;
        ViewGroup cellLayout = (ViewGroup) delegate;
        if (image.getParent() != cellLayout) return;

        Object rectObj = invokeNoArgs(preview, "getBackgroundRect");
        if (!(rectObj instanceof android.graphics.Rect)) return;
        android.graphics.Rect r = (android.graphics.Rect) rectObj;
        if (r.width() <= 0 || r.height() <= 0) return;

        int cellX = 0;
        int cellY = 0;
        Object cx = field(preview, "mDelegateCellX");
        Object cy = field(preview, "mDelegateCellY");
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
        Object rv = field(preview, "mRadius");
        if (rv instanceof Number) radius = ((Number) rv).floatValue();
        // Never multiply mScale — accept enlarge would bake oversized corners into glass.
        if (radius > 0f) glass.setCornerRadii(radius, radius, radius, radius);
    }

    /** Read-only: remember detectRadii plate corners before accept grow. */
    private void snapshotRestingPlateRadii(Object preview) {
        if (preview == null) return;
        try {
            Object bg = field(preview, "mBgView");
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
        Object bg = field(preview, "mBgView");
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
        Object current = field(preview, "mBgDrawable");
        if (current != TRANSPARENT_PREVIEW_BG && current instanceof Drawable) {
            SAVED_PREVIEW_OEM_BG.putIfAbsent(preview, (Drawable) current);
            setField(preview, "mBgDrawable", TRANSPARENT_PREVIEW_BG);
        }
        Object bgView = field(preview, "mBgView");
        if (bgView instanceof ImageView) {
            ImageView image = (ImageView) bgView;
            Drawable imageDrawable = image.getDrawable();
            if (imageDrawable != null && !(imageDrawable instanceof GlassDrawable)
                    && imageDrawable != TRANSPARENT_PREVIEW_BG) {
                image.setImageDrawable(null);
            }
        }
    }

    private static void restoreOemPreviewDrawable(Object preview) {
        if (preview == null) return;
        Drawable saved = SAVED_PREVIEW_OEM_BG.remove(preview);
        if (saved != null) setField(preview, "mBgDrawable", saved);
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
        Object bgView = field(preview, "mBgView");
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
        Object bgView = field(preview, "mBgView");
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
        Object bgView = field(preview, "mBgView");
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
        Object delegate = field(preview, "mDrawingDelegate");
        if (delegate instanceof View) ((View) delegate).invalidate();
        Object invalidate = field(preview, "mInvalidateDelegate");
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
            invokeNoArgs(preview, "clearDrawingDelegate");
        } catch (Throwable ignored) {
            try {
                invoke(preview, "clearDrawingDelegate", new Class<?>[]{}, new Object[]{});
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
            invokeNoArgs(preview, "clearDrawingDelegate");
        } catch (Throwable ignored) { }
        restoreOemPreviewDrawable(preview);
        Object bgView = field(preview, "mBgView");
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
        Object bgView = field(preview, "mBgView");

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
                invoke(preview, "setBackground", new Class<?>[]{igroup}, folderHost);
            } catch (Throwable ignored) {
                try {
                    invokeSetBackground(preview, folderHost);
                } catch (Throwable ignored2) { }
            }
            setFolderBackgroundVisibility(preview, true);
            syncFolderPreview(folderHost, preview);
            restoreRestingPlateRadii(preview);
            icon.requestLayout();
            icon.post(() -> {
                setFolderBackgroundVisibility(preview, true);
                syncFolderPreview(folderHost, preview);
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
        Object bgView = field(preview, "mBgView");
        if (!(bgView instanceof ImageView)) return false;
        ImageView image = (ImageView) bgView;
        GlassDrawable glass = GlassInstaller.get(image);
        if (glass == null) return false;
        Object rectObj = invokeNoArgs(preview, "getBackgroundRect");
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
        Object scaleObj = field(preview, "mScale");
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

    private void hookCreateFolderPreviewGlass(ClassLoader cl) {
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
                    hookOnce(m, chain -> {
                        // Read previous before OEM clears it — cancel path passes null without
                        // always calling clearDrawingDelegate (see OplusWorkspace.onDragEnd).
                        Object self = chain.getThisObject();
                        Object before = field(self, "mFolderPreviewBackGround");
                        if (before == null) before = field(self, "previewBackground");
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
                            log(5, TAG, className + ".setFolderBG glass failed", e);
                        }
                        return result;
                    });
                }
            } catch (Throwable e) {
                log(5, TAG, "Class unavailable: " + className, e);
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
                hookOnce(m, chain -> {
                    Object preview = chain.getArg(0);
                    try {
                        preparePreviewTransferToFolder(preview);
                    } catch (Throwable e) {
                        log(5, TAG, "FolderIcon.setPreviewBackground prepare failed", e);
                    }
                    Object result = chain.proceed();
                    try {
                        Object host = chain.getThisObject();
                        if (preview != null && host instanceof View) {
                            activeCreateFolderPreview = null;
                            invokeSetBackground(preview, host);
                            setFolderBackgroundVisibility(preview, true);
                            syncFolderPreview(host, preview);
                            ((View) host).requestLayout();
                            ((View) host).invalidate();
                        }
                    } catch (Throwable e) {
                        log(5, TAG, "FolderIcon.setPreviewBackground glass failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            log(5, TAG, "FolderIcon.setPreviewBackground hook unavailable", e);
        }
    }

    /** Shared create-folder PreviewBackground currently shown (for cancel cleanup). */
    private volatile Object activeCreateFolderPreview;

    /**
     * Keeps the dragged-folder LiquidGlass backdrop in sync while the desktop pages scroll
     * under a mostly stationary DragView, and reinstalls glass if OEM restored LayerBlur.
     */
    private void refreshFolderDragGlass(Object dragViewHint, boolean reassert) {
        if (!enabled() || !folderDragActive || !isFolderDragSource(folderDragSource)) return;
        View content = findFolderDragContentView(dragViewHint);
        if (content == null || isWidgetDragContent(content)) return;
        if (reassert || GlassInstaller.get(content) == null) {
            suppressLayerBlur(content);
            GlassInstaller.installBackground(content, currentConfig());
            GlassDrawable glass = GlassInstaller.get(content);
            if (glass != null) glass.setAlpha(255);
        }
        Object workspace = invokeNoArgs(findActiveLauncher(), "getWorkspace");
        if (workspace != null) {
            syncDragOverlaySource(workspace);
        } else if (GlassInstaller.get(content) != null) {
            GlassInstaller.forceCapture(content);
            content.invalidate();
        }
    }

    private View findFolderDragContentView(Object dragViewHint) {
        Object dragView = dragViewHint;
        if (dragView == null) {
            Object launcher = findActiveLauncher();
            Object dragController = invokeNoArgs(launcher, "getDragController");
            Object dragObject = field(dragController, "mDragObject");
            dragView = field(dragObject, "dragView");
        }
        Object content = invokeNoArgs(dragView, "getContentView");
        if (!(content instanceof View)) return null;
        View view = (View) content;
        // Never treat widget/card DragView content as the folder drag glass host.
        if (isWidgetDragContent(view)) return null;
        return view;
    }

    private void hookFolderRefreshEvents(ClassLoader cl) {
        final String className = "com.android.launcher3.folder.FolderIcon";
        after(className, cl, "updateBackground", o -> syncFolderIconDeferred(o, false));
        after(className, cl, "onDrop", o -> syncFolderIconDeferred(o, false));
        // After resize finishes, ColorOS rebuilds background — reassert glass (do not sync on
        // every updateItemIconLayout frame). Corner radii stay on the b73ba87 installImage path.
        after("com.android.launcher3.folder.FlexibleFolderIcon", cl, "updateItemIconPreview",
                this::restoreFolderGlassAfterResize);
    }

    /**
     * Unlock briefly zeros DragLayer alpha and resets wallpaper depth. Soft-refresh all glass
     * hosts through the settle window. Cos16 FolderIcon unlock scale uses invalidate pumps
     * (page-flip style) plus live matrix sampling — not off-cycle forceCapture.
     */
    private void hookUnlockGlassRefresh(ClassLoader cl) {
        after("com.android.launcher.Launcher", cl, "onResume", o -> scheduleUnlockGlassRefresh());
        after("com.android.launcher3.Launcher", cl, "onResume", o -> scheduleUnlockGlassRefresh());
        hookUnlockFolderScaleTicks(cl);
        try {
            Class<?> utils = Class.forName(
                    "com.android.keyguardservice.KeyGuardDismissedUtils", false, cl);
            for (Method m : utils.getDeclaredMethods()) {
                if (m.isBridge() || m.isSynthetic()) continue;
                if (!m.getName().equals("setUnlockAnimRunning") || m.getParameterCount() != 1) {
                    continue;
                }
                hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        if (Boolean.TRUE.equals(chain.getArg(0))) {
                            GlassInstaller.startUnlockFolderInvalidatePumps();
                        }
                        scheduleUnlockGlassRefresh();
                    } catch (Throwable e) {
                        log(5, TAG, "setUnlockAnimRunning glass refresh failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            log(5, TAG, "KeyGuardDismissedUtils unlock refresh hook unavailable", e);
        }
        for (String className : new String[] {
                "com.android.launcher.Launcher",
                "com.android.launcher3.Launcher",
                "com.android.launcher3.OplusWorkspace",
                "com.android.launcher3.Workspace"
        }) {
            try {
                Class<?> c = Class.forName(className, false, cl);
                for (Method m : c.getDeclaredMethods()) {
                    if (m.isBridge() || m.isSynthetic()) continue;
                    if (m.getName().equals("onWindowFocusChanged") && m.getParameterCount() == 1) {
                        hookOnce(m, chain -> {
                            Object result = chain.proceed();
                            try {
                                if (Boolean.TRUE.equals(chain.getArg(0))) scheduleUnlockGlassRefresh();
                            } catch (Throwable e) {
                                log(5, TAG, className + ".onWindowFocusChanged refresh failed", e);
                            }
                            return result;
                        });
                    } else if (m.getName().equals("onScreenLockStateChange")
                            && m.getParameterCount() == 1) {
                        hookOnce(m, chain -> {
                            Object result = chain.proceed();
                            try {
                                Object state = chain.getArg(0);
                                Object lock = invokeNoArgs(state, "getLockState");
                                if (lock instanceof Number && ((Number) lock).intValue() == 1) {
                                    GlassInstaller.startUnlockFolderInvalidatePumps();
                                    scheduleUnlockGlassRefresh();
                                }
                            } catch (Throwable e) {
                                log(5, TAG, className + ".onScreenLockStateChange refresh failed", e);
                            }
                            return result;
                        });
                    }
                }
            } catch (Throwable e) {
                log(5, TAG, className + " unlock refresh hook unavailable", e);
            }
        }
    }

    /** Each unlock scale tick → dirty folder glass like a Workspace scroll frame. */
    private void hookUnlockFolderScaleTicks(ClassLoader cl) {
        try {
            for (Method m : View.class.getDeclaredMethods()) {
                if (m.isBridge() || m.isSynthetic()) continue;
                if (m.getParameterCount() != 1) continue;
                String name = m.getName();
                if (!name.equals("setScaleX") && !name.equals("setScaleY")) continue;
                Class<?> type = m.getParameterTypes()[0];
                if (type != float.class && type != Float.TYPE) continue;
                hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        Object self = chain.getThisObject();
                        if (!(self instanceof View)) return result;
                        View view = (View) self;
                        if (!isUnlockFolderIconHost(view)) return result;
                        GlassInstaller.onFolderUnlockScaleTick(view);
                    } catch (Throwable e) {
                        log(5, TAG, "View." + name + " unlock glass tick failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            log(5, TAG, "View unlock scale tick hook unavailable", e);
        }
    }

    private static boolean isUnlockFolderIconHost(View view) {
        for (Class<?> c = view.getClass(); c != null; c = c.getSuperclass()) {
            String name = c.getName();
            if (name.endsWith(".FolderIcon") || name.endsWith("FolderIcon")) return true;
        }
        return false;
    }

    private void scheduleUnlockGlassRefresh() {
        if (!enabled()) return;
        final int token = ++unlockGlassRefreshToken;
        GlassInstaller.refreshAll();
        GlassInstaller.startUnlockFolderInvalidatePumps();
        for (long delay : new long[] { 48L, 120L, 280L, 500L, 900L }) {
            mainHandler.postDelayed(() -> {
                if (token != unlockGlassRefreshToken || !enabled()) return;
                GlassInstaller.refreshAll();
                GlassInstaller.startUnlockFolderInvalidatePumps();
            }, delay);
        }
    }

    /**
     * Recents / app transitions zoom the system wallpaper via sendWallpaperCommand (1.0↔1.2).
     * That surface is not in the View hierarchy; track the Bundle so BackdropCapture can mirror it.
     */
    private void hookWallpaperScaleTracking(ClassLoader cl) {
        try {
            Class<?> depth = Class.forName(
                    "com.android.launcher3.uioverrides.states.OplusDepthController", false, cl);
            for (Method m : depth.getDeclaredMethods()) {
                if (!m.getName().equals("startWallpaperAnimation") || m.isBridge() || m.isSynthetic()) {
                    continue;
                }
                Class<?>[] types = m.getParameterTypes();
                if (types.length != 3 || types[2] != Bundle.class) continue;
                hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        Object extras = chain.getArg(2);
                        if (extras instanceof Bundle) {
                            WallpaperScaleTracker.onWallpaperCommand((Bundle) extras);
                            if (enabled()) GlassInstaller.refreshAll();
                        }
                    } catch (Throwable e) {
                        log(5, TAG, "Wallpaper scale track failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            log(5, TAG, "Wallpaper scale tracking hook unavailable", e);
        }
    }

    /** Restores mBgView visibility/layout contract after leaving the resize-frame path. */
    private void restoreFolderGlassAfterResize(Object folderIcon) {
        if (!(folderIcon instanceof View)) {
            syncFolderIconDeferred(folderIcon, true);
            return;
        }
        View icon = (View) folderIcon;
        Object preview = field(folderIcon, "mBackground");
        Object bgView = field(preview, "mBgView");
        // Ask ColorOS to relayout the folder icon children so mBgView leaves the temporary
        // ConvertBgParams layout applied during the resize-frame draw path.
        icon.requestLayout();
        icon.post(() -> {
            setFolderBackgroundVisibility(preview, true);
            syncFolderPreview(folderIcon, preview);
            if (bgView instanceof View) ((View) bgView).invalidate();
            icon.invalidate();
        });
    }

    private void hookFolderVisibility(ClassLoader cl) {
        // FlexibleFolderIcon.setIconVisible calls super then setImageDrawable(mBgDrawable).
        // Hook the subclass method so glass is reasserted after that OEM overwrite.
        for (String className : new String[] {
                "com.android.launcher3.folder.FlexibleFolderIcon",
                "com.android.launcher3.folder.FolderIcon"
        }) {
            try {
                Class<?> c = Class.forName(className, false, cl);
                Method method = c.getDeclaredMethod("setIconVisible", boolean.class);
                hookOnce(method, chain -> {
                    Object result = chain.proceed();
                    try {
                        Object icon = chain.getThisObject();
                        Object preview = field(icon, "mBackground");
                        boolean visible = Boolean.TRUE.equals(chain.getArg(0)) && !isFolderOpen(icon);
                        setFolderBackgroundVisibility(preview, visible);
                        if (visible) syncFolderPreviewDeferred(icon, preview, false);
                    } catch (Throwable e) {
                        log(5, TAG, className + ".setIconVisible apply failed", e);
                    }
                    return result;
                });
            } catch (Throwable e) {
                log(5, TAG, className + ".setIconVisible hook unavailable", e);
            }
        }
        try {
            Class<?> c = Class.forName("com.android.launcher3.folder.FlexibleFolderIcon", false, cl);
            Method setVisibility = c.getDeclaredMethod("setVisibility", int.class);
            hookOnce(setVisibility, chain -> {
                Object result = chain.proceed();
                try {
                    Object icon = chain.getThisObject();
                    if (Integer.valueOf(View.VISIBLE).equals(chain.getArg(0)) && !isFolderOpen(icon)) {
                        syncFolderPreviewDeferred(icon, field(icon, "mBackground"), false);
                    }
                } catch (Throwable e) {
                    log(5, TAG, "FlexibleFolderIcon.setVisibility apply failed", e);
                }
                return result;
            });
        } catch (Throwable e) {
            log(5, TAG, "FlexibleFolderIcon.setVisibility hook unavailable", e);
        }
    }

    private void syncFolderIconDeferred(Object folderIcon, boolean forceVisible) {
        Object preview = field(folderIcon, "mBackground");
        if (forceVisible) setFolderBackgroundVisibility(preview, true);
        syncFolderPreviewDeferred(folderIcon, preview, forceVisible);
    }

    private void syncFolderPreviewDeferred(Object folderIcon, Object previewBackground, boolean forceVisible) {
        syncFolderPreview(folderIcon, previewBackground);
        if (folderIcon instanceof View) {
            ((View) folderIcon).post(() -> {
                if (forceVisible) setFolderBackgroundVisibility(previewBackground, true);
                syncFolderPreview(folderIcon, previewBackground);
            });
        }
    }

    private void syncFolderPreview(Object folderIcon, Object previewBackground) {
        if (isFolderPopupOemOnly(folderIcon)) return;
        Object bgView = field(previewBackground, "mBgView");
        if (!(bgView instanceof ImageView)) return;
        ImageView image = (ImageView) bgView;
        Object original = field(previewBackground, "mBgDrawable");
        GlassDrawable installed = GlassInstaller.get(image);
        boolean open = isFolderOpen(folderIcon);
        if (enabled() && !open) {
            // ColorOS calls setImageDrawable(mBgDrawable) again while binding/layouting a folder.
            // Reassert the image-layer contract even when our background drawable is still present.
            GlassInstaller.installImage(image, currentConfig());
        } else if (installed != null) {
            GlassInstaller.restoreImage(image, original instanceof Drawable ? (Drawable) original : null);
        }
        if (open) image.setVisibility(View.INVISIBLE);
    }

    private static void setFolderBackgroundVisibility(Object previewBackground, boolean visible) {
        Object bgView = field(previewBackground, "mBgView");
        if (bgView instanceof View) ((View) bgView).setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
    }

    private static boolean isFolderOpen(Object folderIcon) {
        Object folder = field(folderIcon, "mFolder");
        Object result = invokeNoArgs(folder, "isOpen");
        if (result instanceof Boolean) return (Boolean) result;
        Object value = field(folder, "mIsOpen");
        return value instanceof Boolean && (Boolean) value;
    }

    private interface ObjectAction { void run(Object object) throws Throwable; }

    private void after(String className, ClassLoader cl, String methodName, ObjectAction action) {
        try {
            Class<?> c = Class.forName(className, false, cl);
            boolean found = false;
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(methodName) || m.isBridge() || m.isSynthetic()) continue;
                found = true;
                hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try { action.run(chain.getThisObject()); }
                    catch (Throwable e) { log(5, TAG, className + "." + methodName + " apply failed", e); }
                    return result;
                });
            }
            if (!found) log(5, TAG, "Method not found: " + className + "." + methodName);
        } catch (Throwable e) { log(5, TAG, "Class unavailable: " + className, e); }
    }

    private void hookOnce(Executable e, io.github.libxposed.api.XposedInterface.Hooker callback) {
        synchronized (hooked) {
            if (!hooked.add(e)) return;
            e.setAccessible(true);
            hook(e).intercept(callback);
            log(4, TAG, "Hooked: " + e.getDeclaringClass().getName() + "#" + e.getName());
        }
    }

    private GlassConfig currentConfig() {
        try { return remotePreferences == null ? new GlassConfig() : GlassConfig.read(remotePreferences); }
        catch (Throwable e) {
            log(5, TAG, "Remote preferences refresh failed", e);
            return new GlassConfig();
        }
    }

    private boolean enabled() { return currentConfig().enabled; }

    /** Master enable + 「修改菜单栏样式」 for desktop long-press / Overview TaskMenu / float menus. */
    private boolean menuStyleEnabled() {
        GlassConfig config = currentConfig();
        return config.enabled && config.modifyMenuStyle;
    }

    private static boolean isClassOrSubclass(Object object, String className) {
        if (object == null) return false;
        for (Class<?> c = object.getClass(); c != null; c = c.getSuperclass()) {
            if (c.getName().equals(className)) return true;
        }
        return false;
    }

    private static Object field(Object object, String name) {
        if (object == null) return null;
        Class<?> c = object.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(object);
            } catch (Throwable ignored) { c = c.getSuperclass(); }
        }
        return null;
    }

    private static boolean setField(Object object, String name, Object value) {
        if (object == null) return false;
        Class<?> c = object.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(object, value);
                return true;
            } catch (Throwable ignored) { c = c.getSuperclass(); }
        }
        return false;
    }

    private static Object invokeNoArgs(Object object, String name) {
        if (object == null) return null;
        Class<?> c = object.getClass();
        while (c != null) {
            try { Method m = c.getDeclaredMethod(name); m.setAccessible(true); return m.invoke(object); }
            catch (Throwable ignored) { c = c.getSuperclass(); }
        }
        return null;
    }

}
