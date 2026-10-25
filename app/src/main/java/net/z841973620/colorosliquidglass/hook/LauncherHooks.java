package net.z841973620.colorosliquidglass.hook;

import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import net.z841973620.colorosliquidglass.glass.DesktopBackdropSampler;
import net.z841973620.colorosliquidglass.glass.GlassDrawable;
import net.z841973620.colorosliquidglass.glass.GlassInstaller;
import net.z841973620.colorosliquidglass.glass.LauncherAshmemMode;
import net.z841973620.colorosliquidglass.glass.WallpaperScaleTracker;
import net.z841973620.colorosliquidglass.ipc.DesktopBackdropHub;
import java.lang.reflect.Method;
import static net.z841973620.colorosliquidglass.hook.HookContext.TAG;

/** 桌面 Hook 的安装顺序，以及文件夹拖拽、桌面状态、壁纸和解锁的协调。 */
final class LauncherHooks {
    private final HookContext hooks;
    final FolderPreviewHooks folderPreviewHooks;
    final LauncherPopupHooks popupHooks;
    final LauncherRecentsHooks recentsHooks;
    final LauncherWorkspaceHooks workspaceHooks;

    LauncherHooks(HookContext hooks) {
        this.hooks = hooks;
        folderPreviewHooks = new FolderPreviewHooks(hooks, this);
        popupHooks = new LauncherPopupHooks(hooks, this);
        recentsHooks = new LauncherRecentsHooks(hooks, this);
        workspaceHooks = new LauncherWorkspaceHooks(hooks, this);
    }

    /** True while a folder DragView is active; also used with TOGGLE_BAR blur suppression. */
    private volatile boolean folderDragActive;
    /** True from Folder.animateOpen until close; suppresses wallpaper depth blur. */
    private volatile boolean folderOpenActive;
    /** Bumps when a new unlock/resume refresh burst starts so older delayed posts are ignored. */
    private volatile int unlockGlassRefreshToken;

    void hookLauncher(ClassLoader cl) {
        // Closed folder icons own a dedicated FolderRoundImageView. Never style Folder/OplusFolder:
        // FolderIcon still dispatches this child while the open animation is running, so a glass
        // background on the container obscures the opened folder's icons.
        folderPreviewHooks.hookFolderPreviewBackground(cl);
        folderPreviewHooks.hookCreateFolderPreviewGlass(cl);
        hookFolderVisibility(cl);
        hookFolderRefreshEvents(cl);
        popupHooks.hookFolderPopupBlur(cl);
        popupHooks.hookMoreFunctionsSubMenuGlass(cl);
        hookFolderDragPreview(cl);
        hookFolderDragDepthBlur(cl);
        recentsHooks.hookRecentsClearButton(cl);
        recentsHooks.hookRecentsTaskShortcuts(cl);
        workspaceHooks.hookToggleBarChrome(cl);
        hookDragViewMove(cl);
        hookWorkspaceDragOver(cl);
        hookUnlockGlassRefresh(cl);
        hookWallpaperScaleTracking(cl);
        hookDesktopBackdropIpc(cl);
        hooks.after("com.android.launcher3.folder.FolderIcon", cl, "onFolderClose", o -> {
            endFolderOpen(o);
            syncFolderIconDeferred(o, true);
        });
    }

    /**
     * Export folder-glass-equivalent desktop tiles to SystemUI float menus via SharedMemory.
     */
    private void hookDesktopBackdropIpc(ClassLoader cl) {
        DesktopBackdropHub.start();
        hooks.after("com.android.launcher3.Launcher", cl, "onResume", launcher -> {
            attachDesktopBackdropRoot(launcher);
            DesktopBackdropSampler.invalidateCache();
        });
        hooks.after("com.android.launcher.Launcher", cl, "onResume", launcher -> {
            attachDesktopBackdropRoot(launcher);
            DesktopBackdropSampler.invalidateCache();
        });
        hooks.after("com.android.launcher3.Launcher", cl, "onAttachedToWindow", this::attachDesktopBackdropRoot);
        hooks.after("com.android.launcher.Launcher", cl, "onAttachedToWindow", this::attachDesktopBackdropRoot);
        hooks.after("com.android.launcher3.Workspace", cl, "scrollTo", o ->
                DesktopBackdropSampler.invalidateAll());
        hooks.after("com.android.launcher3.OplusWorkspace", cl, "scrollTo", o ->
                DesktopBackdropSampler.invalidateAll());
        hooks.after("com.android.launcher3.Workspace", cl, "onScrollChanged", o ->
                DesktopBackdropSampler.invalidateAll());
        hooks.after("com.android.launcher3.OplusWorkspace", cl, "onScrollChanged", o ->
                DesktopBackdropSampler.invalidateAll());
        hooks.after("com.android.launcher3.CellLayout", cl, "onLayout", o ->
                DesktopBackdropSampler.invalidateCache());
        hooks.after("com.android.launcher3.CellLayout", cl, "onViewAdded", o ->
                DesktopBackdropSampler.invalidateCache());
        hooks.after("com.android.launcher3.CellLayout", cl, "onViewRemoved", o ->
                DesktopBackdropSampler.invalidateCache());
        hooks.after("com.android.launcher3.hotseat.OplusHotseat", cl, "onLayout", o ->
                DesktopBackdropSampler.invalidateCache());
        hooks.after("com.android.launcher3.dragndrop.DragView", cl, "move", o -> {
            if (DesktopBackdropHub.isLive()) DesktopBackdropSampler.invalidateCache();
        });
        hooks.after("com.android.launcher3.dragndrop.OplusDragView", cl, "move", o -> {
            if (DesktopBackdropHub.isLive()) DesktopBackdropSampler.invalidateCache();
        });
        // ColorOS may reposition via translation/layout instead of move() — keep glass streaming.
        hooks.after("com.android.launcher3.dragndrop.DragView", cl, "setTranslationX", o -> {
            if (DesktopBackdropHub.isLive()) DesktopBackdropSampler.invalidateCache();
        });
        hooks.after("com.android.launcher3.dragndrop.DragView", cl, "setTranslationY", o -> {
            if (DesktopBackdropHub.isLive()) DesktopBackdropSampler.invalidateCache();
        });
        hooks.after("com.android.launcher3.dragndrop.OplusDragView", cl, "setTranslationX", o -> {
            if (DesktopBackdropHub.isLive()) DesktopBackdropSampler.invalidateCache();
        });
        hooks.after("com.android.launcher3.dragndrop.OplusDragView", cl, "setTranslationY", o -> {
            if (DesktopBackdropHub.isLive()) DesktopBackdropSampler.invalidateCache();
        });
        hooks.after("com.android.launcher3.popup.OplusPopupContainerWithArrow", cl, "onLayout", o -> {
            if (DesktopBackdropHub.isLive()) DesktopBackdropSampler.invalidateCache();
            if (hooks.enabled()) popupHooks.keepPopupBlurTransparent(o);
            if (hooks.menuStyleEnabled()) {
                popupHooks.reassertDesktopPopupGlass(o);
            }
        });
        hooks.after("com.android.launcher3.popup.PopupContainerWithArrow", cl, "onLayout", o -> {
            if (DesktopBackdropHub.isLive()) DesktopBackdropSampler.invalidateCache();
            if (hooks.enabled()) popupHooks.keepPopupBlurTransparent(o);
            if (hooks.menuStyleEnabled()) {
                popupHooks.reassertDesktopPopupGlass(o);
            }
        });
        hooks.after("com.android.launcher.layout.ItemResizeFrame", cl, "dispatchDraw", o -> {
            if (DesktopBackdropHub.isLive()) DesktopBackdropSampler.invalidateCache();
        });
        hooks.after("com.android.launcher.layout.ItemResizeFrame", cl, "onLayout", o -> {
            if (DesktopBackdropHub.isLive()) DesktopBackdropSampler.invalidateCache();
        });
    }

    private void attachDesktopBackdropRoot(Object launcher) {
        try {
            if (launcher instanceof android.app.Activity) {
                View decor = ((android.app.Activity) launcher).getWindow().getDecorView();
                DesktopBackdropSampler.setLauncherRoot(decor);
            } else {
                Object window = HookReflection.invokeNoArgs(launcher, "getWindow");
                Object decor = HookReflection.invokeNoArgs(window, "getDecorView");
                if (decor instanceof View) DesktopBackdropSampler.setLauncherRoot((View) decor);
            }
            syncDesktopHomeValidForLauncher(launcher);
        } catch (Throwable e) {
            hooks.log(5, TAG, "attachDesktopBackdropRoot failed", e);
        }
    }

    /**
     * Folder-only drag preview glass (ported from 408f380 onto the pre-widget-chrome tree).
     * Widget/card DragViews must never receive folder move glass via install/refresh paths.
     */
    static boolean isFolderDragSource(Object source) {
        return source != null && (
                HookReflection.isClassOrSubclass(source, "com.android.launcher3.folder.FolderIcon")
                        || HookReflection.isClassOrSubclass(source, "com.android.launcher3.folder.FlexibleFolderIcon")
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
            Object content = HookReflection.invokeNoArgs(dragView, "getContentView");
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
                hooks.hookOnce(m, chain -> {
                    Object source = chain.getArg(0);
                    Object dragView = chain.getArg(1);
                    // ColorOS copies LayerBlurDrawable onto the drag preview here. Skip that
                    // OEM blur layer entirely and let LiquidGlass own the dragged folder look.
                    if (hooks.enabled() && isFolderDragSource(source)) {
                        try {
                            Object content = HookReflection.invokeNoArgs(dragView, "getContentView");
                            // Never install folder-drag glass onto widget/card DragView content.
                            if (content instanceof View && isWidgetDragContent((View) content)) {
                                stripWidgetDragViewGlass(dragView);
                                return chain.proceed();
                            }
                            beginFolderDrag(source);
                            clearDragBlurProp(dragView);
                            if (content instanceof View) {
                                suppressLayerBlur((View) content);
                                GlassInstaller.installBackground((View) content, hooks.currentConfig());
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
                            hooks.log(5, TAG, className + ".handleFolderBackground apply failed", e);
                        }
                        return null;
                    }
                    Object result = chain.proceed();
                    // Non-folder: strip any folder-drag glass that leaked onto the preview.
                    if (hooks.enabled() && dragView != null) {
                        stripWidgetDragViewGlass(dragView);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            hooks.log(5, TAG, "Folder drag preview hook unavailable", e);
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
                    hooks.hookOnce(m, chain -> {
                        Object result = chain.proceed();
                        try {
                            if (!hooks.enabled()) return result;
                            Object dragView = chain.getThisObject();
                            Object content = HookReflection.invokeNoArgs(dragView, "getContentView");
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
                            hooks.log(5, TAG, dragViewClass + ".onDragStart strip widget glass failed", e);
                        }
                        return result;
                    });
                }
            }
        } catch (Throwable e) {
            hooks.log(5, TAG, "Widget drag-start strip hook unavailable", e);
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
                hooks.hookOnce(m, chain -> {
                    // Always clamp while enabled: gesture blur runs before OVERVIEW is settled.
                    if (hooks.enabled()) {
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
                hooks.hookOnce(m, chain -> {
                    if (!hooks.enabled()) return chain.proceed();
                    if (folderOpenActive || hasOpenFolder(findActiveLauncher())) return 0f;
                    return chain.proceed();
                });
            }
        } catch (Throwable e) {
            hooks.log(5, TAG, "DepthController blur hook unavailable", e);
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
                    hooks.hookOnce(m, chain -> {
                        if (hooks.enabled()) {
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
                    hooks.hookOnce(m, chain -> {
                        if (!hooks.enabled()) return chain.proceed();
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
                    hooks.hookOnce(m, chain -> {
                        if (hooks.enabled()) LauncherWorkspaceHooks.zeroAnimInfoEndValue(chain.getArg(0));
                        return chain.proceed();
                    });
                }
            }
        } catch (Throwable e) {
            hooks.log(5, TAG, "DepthAnimImpl wallpaper blur hook unavailable", e);
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
                hooks.hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    if (!hooks.enabled()) return result;
                    Object self = chain.getThisObject();
                    HookReflection.setField(self, "wallpaperBlurEndValue", 0f);
                    Object blurAnim = HookReflection.field(self, "mWallpaperBlurAnim");
                    HookReflection.invoke(blurAnim, "animateToFinalPosition",
                            new Class<?>[] { float.class }, 0f);
                    Object launcher = HookReflection.field(self, "mLauncher");
                    if (launcher != null) forceDepthBlur(launcher, 0f);
                    return result;
                });
            }
        } catch (Throwable e) {
            hooks.log(5, TAG, "SwipeToRecent blur clamp unavailable", e);
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
                    hooks.hookOnce(m, chain -> {
                        Object result = chain.proceed();
                        if (hooks.enabled()) return 0f;
                        return result;
                    });
                }
            } catch (Throwable e) {
                hooks.log(5, TAG, stateClass + ".getBlurUnchecked hook unavailable", e);
            }
        }

        hooks.after("com.android.launcher3.folder.FlexibleFolderIcon", cl, "onDragStart", o -> {
            if (hooks.enabled()) beginFolderDrag(o);
        });
        hooks.after("com.android.launcher3.folder.FlexibleFolderIcon", cl, "onDragEnd", o -> {
            endFolderDrag(o);
        });
        hooks.after("com.android.launcher3.dragndrop.DragController", cl, "endDrag", o -> {
            if (folderDragActive) endFolderDrag(HookReflection.field(o, "mActivity"));
        });
        hooks.after("com.android.launcher3.dragndrop.DragController", cl, "cancelDrag", o -> {
            if (folderDragActive) endFolderDrag(HookReflection.field(o, "mActivity"));
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
                    hooks.hookOnce(m, chain -> {
                        Object result = chain.proceed();
                        if (!hooks.enabled()) return result;
                        try {
                            Object self = chain.getThisObject();
                            Object open = HookReflection.invokeNoArgs(self, "isOpen");
                            boolean isOpen = open instanceof Boolean ? (Boolean) open
                                    : Boolean.TRUE.equals(HookReflection.field(self, "mIsOpen"));
                            if (hooked.equals("animateOpen") || hooked.equals("beginExternalDrag")
                                    || isOpen) {
                                beginFolderOpen(self);
                                forceDepthBlur(self, 0f);
                                if (DesktopBackdropHub.isLive()) {
                                    DesktopBackdropSampler.invalidateCache();
                                }
                            }
                        } catch (Throwable e) {
                            hooks.log(5, TAG, folderClass + "." + hooked + " open glass failed", e);
                        }
                        return result;
                    });
                }
            } catch (Throwable e) {
                hooks.log(5, TAG, folderClass + " open hooks unavailable", e);
            }
        }
        // Long-press edit / Recents enter states with wallpaper blur=1; clear it.
        hooks.after("com.android.launcher3.statemanager.StateManager", cl, "goToState", o -> {
            if (!hooks.enabled()) return;
            Object activity = HookReflection.field(o, "mActivity");
            if (activity == null) activity = HookReflection.field(o, "mContext");
            if (shouldSuppressDepthBlurForLauncher(activity)) forceDepthBlur(activity, 0f);
            workspaceHooks.syncPagePreviewFrameGlassForLauncher(activity);
            syncDesktopHomeValidForLauncher(activity);
        });
        hooks.after("com.android.launcher3.statemanager.StateManager", cl, "onStateTransitionEnd", o -> {
            if (!hooks.enabled()) return;
            Object activity = HookReflection.field(o, "mActivity");
            if (activity == null) activity = HookReflection.field(o, "mContext");
            if (shouldSuppressDepthBlurForLauncher(activity)) forceDepthBlur(activity, 0f);
            workspaceHooks.syncPagePreviewFrameGlassForLauncher(activity);
            syncDesktopHomeValidForLauncher(activity);
        });
    }

    private boolean shouldSuppressDepthBlur() {
        return shouldSuppressDepthBlur(null);
    }

    private boolean shouldSuppressDepthBlur(Object depthController) {
        if (!hooks.enabled()) return false;
        if (folderDragActive || folderOpenActive) return true;
        Object launcher = resolveLauncher(depthController != null
                ? HookReflection.field(depthController, "mLauncher") : null);
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

    Object findActiveLauncher() {
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
            Object stateManager = HookReflection.invokeNoArgs(launcher, "getStateManager");
            Object state = HookReflection.invokeNoArgs(stateManager, "getState");
            Object blur = HookReflection.invoke(state, "getBlur", new Class<?>[] { android.content.Context.class },
                    launcher instanceof android.content.Context ? launcher : null);
            if (blur instanceof Number) restore = ((Number) blur).floatValue();
            forceDepthBlur(launcher != null ? launcher : host, restore);
        } catch (Throwable e) {
            hooks.log(5, TAG, "Restore depth blur after folder drag failed", e);
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
            boolean ashmemOk = !workspaceHooks.isInLauncherState(launcher, "PAGE_PREVIEW")
                    && !workspaceHooks.isInLauncherState(launcher, "TOGGLE_BAR");
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

    static Object resolveLauncher(Object host) {
        if (host == null) return null;
        if (HookReflection.isClassOrSubclass(host, "com.android.launcher3.Launcher")
                || HookReflection.isClassOrSubclass(host, "com.android.launcher.Launcher")) {
            return host;
        }
        Object launcher = HookReflection.field(host, "mLauncher");
        if (launcher != null) return launcher;
        return HookReflection.field(host, "mActivity");
    }

    private void forceDepthBlur(Object host, float value) {
        try {
            Object launcher = resolveLauncher(host);
            Object depth = HookReflection.invokeNoArgs(launcher, "getDepthController");
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
            hooks.log(5, TAG, "forceDepthBlur failed", e);
        }
    }

    /** Drops any OplusBlurProperties already attached to the DragView. */
    private static void clearDragBlurProp(Object dragView) {
        Object blurProp = HookReflection.field(dragView, "mBlurProp");
        if (blurProp != null) {
            try {
                Method recycle = blurProp.getClass().getMethod("recycle");
                recycle.setAccessible(true);
                recycle.invoke(blurProp);
            } catch (Throwable ignored) { }
            HookReflection.setField(dragView, "mBlurProp", null);
        }
    }

    /** Neutralizes an OEM LayerBlurDrawable if it is still the content background. */
    private static void suppressLayerBlur(View content) {
        Drawable background = content.getBackground();
        if (background == null) return;
        if (!HookReflection.isClassOrSubclass(background, "com.android.launcher3.uioverrides.states.blurdrawable.LayerBlurDrawable")) {
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
                hooks.hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        Object content = HookReflection.invokeNoArgs(chain.getThisObject(), "getContentView");
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
                        hooks.log(5, TAG, "DragView.move glass refresh failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            hooks.log(5, TAG, "DragView.move hook unavailable", e);
        }
    }

    private void hookWorkspaceDragOver(ClassLoader cl) {
        hooks.after("com.android.launcher3.OplusWorkspace", cl, "onDragOver", this::syncDragOverlaySource);
        hooks.after("com.android.launcher3.Workspace", cl, "onDragOver", this::syncDragOverlaySource);
        for (String className : new String[] {
                "com.android.launcher3.OplusWorkspace",
                "com.android.launcher3.Workspace"
        }) {
            try {
                Class<?> c = Class.forName(className, false, cl);
                for (Method m : c.getDeclaredMethods()) {
                    if (m.isBridge() || m.isSynthetic()) continue;
                    if (!m.getName().equals("setCurrentDropLayout")) continue;
                    hooks.hookOnce(m, chain -> {
                        Object result = chain.proceed();
                        try {
                            if (folderDragActive && isFolderDragSource(folderDragSource)) {
                                syncDragOverlaySource(chain.getThisObject());
                            }
                        } catch (Throwable e) {
                            hooks.log(5, TAG, className + ".setCurrentDropLayout overlay sync failed", e);
                        }
                        return result;
                    });
                }
            } catch (Throwable e) {
                hooks.log(5, TAG, className + " setCurrentDropLayout hook unavailable", e);
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
                    hooks.hookOnce(m, chain -> {
                        Object result = chain.proceed();
                        try {
                            if (folderDragActive && isFolderDragSource(folderDragSource)) {
                                refreshFolderDragGlass(null, true);
                            }
                        } catch (Throwable e) {
                            hooks.log(5, TAG, className + "." + name + " drag glass refresh failed", e);
                        }
                        return result;
                    });
                }
            } catch (Throwable e) {
                hooks.log(5, TAG, className + " page-scroll glass hook unavailable", e);
            }
        }
    }

    private void syncDragOverlaySource(Object workspace) {
        View desktopPage = desktopPageUnderDrag(workspace);
        Object hover = dragHoverView(workspace);
        boolean hoverIsFolder = hover != null
                && HookReflection.isClassOrSubclass(hover, "com.android.launcher3.folder.FolderIcon");

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
            folderPreviewHooks.keepHoverFolderGlassVisible(HookReflection.field(hover, "mBackground"));
        }
    }

    /**
     * Prefer OEM {@code getCurrentDropLayout()} (mNextPage || mCurrentPage); skip Hotseat.
     */
    private View desktopPageUnderDrag(Object workspace) {
        if (workspace == null) return null;
        try {
            Object drop = HookReflection.invokeNoArgs(workspace, "getCurrentDropLayout");
            if (drop instanceof View) return (View) drop;
        } catch (Throwable ignored) { }
        try {
            Object pageIndex = HookReflection.invokeNoArgs(workspace, "getNextPage");
            if (!(pageIndex instanceof Number) || ((Number) pageIndex).intValue() < 0) {
                pageIndex = HookReflection.invokeNoArgs(workspace, "getCurrentPage");
            }
            if (pageIndex instanceof Number) {
                Object page = HookReflection.invoke(workspace, "getPageAt",
                        new Class<?>[] { int.class },
                        ((Number) pageIndex).intValue());
                if (page instanceof View) return (View) page;
            }
        } catch (Throwable ignored) { }
        Object layout = HookReflection.field(workspace, "mDragTargetLayout");
        if (layout instanceof View) {
            String name = layout.getClass().getSimpleName();
            if (name == null || !name.contains("Hotseat")) return (View) layout;
        }
        try {
            Object pageIndex = HookReflection.invokeNoArgs(workspace, "getCurrentPage");
            if (pageIndex instanceof Number) {
                Object page = HookReflection.invoke(workspace, "getPageAt",
                        new Class<?>[] { int.class },
                        ((Number) pageIndex).intValue());
                if (page instanceof View) return (View) page;
            }
        } catch (Throwable ignored) { }
        return workspace instanceof View ? (View) workspace : null;
    }

    /** Prefer mDragOverView; fall back to mDragTargetLayout + mTargetCell. */
    private Object dragHoverView(Object workspace) {
        Object hover = HookReflection.field(workspace, "mDragOverView");
        if (hover instanceof View) return hover;
        Object layout = HookReflection.field(workspace, "mDragTargetLayout");
        Object cell = HookReflection.field(workspace, "mTargetCell");
        if (!(layout instanceof View) || !(cell instanceof int[])) return null;
        int[] targetCell = (int[]) cell;
        if (targetCell.length < 2) return null;
        try {
            Object child = HookReflection.invoke(layout, "getChildAt",
                    new Class<?>[] { int.class, int.class },
                    targetCell[0], targetCell[1]);
            return child instanceof View ? child : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Keeps the dragged-folder LiquidGlass backdrop in sync while the desktop pages scroll
     * under a mostly stationary DragView, and reinstalls glass if OEM restored LayerBlur.
     */
    private void refreshFolderDragGlass(Object dragViewHint, boolean reassert) {
        if (!hooks.enabled() || !folderDragActive || !isFolderDragSource(folderDragSource)) return;
        View content = findFolderDragContentView(dragViewHint);
        if (content == null || isWidgetDragContent(content)) return;
        if (reassert || GlassInstaller.get(content) == null) {
            suppressLayerBlur(content);
            GlassInstaller.installBackground(content, hooks.currentConfig());
            GlassDrawable glass = GlassInstaller.get(content);
            if (glass != null) glass.setAlpha(255);
        }
        Object workspace = HookReflection.invokeNoArgs(findActiveLauncher(), "getWorkspace");
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
            Object dragController = HookReflection.invokeNoArgs(launcher, "getDragController");
            Object dragObject = HookReflection.field(dragController, "mDragObject");
            dragView = HookReflection.field(dragObject, "dragView");
        }
        Object content = HookReflection.invokeNoArgs(dragView, "getContentView");
        if (!(content instanceof View)) return null;
        View view = (View) content;
        // Never treat widget/card DragView content as the folder drag glass host.
        if (isWidgetDragContent(view)) return null;
        return view;
    }

    private void hookFolderRefreshEvents(ClassLoader cl) {
        final String className = "com.android.launcher3.folder.FolderIcon";
        hooks.after(className, cl, "updateBackground", o -> syncFolderIconDeferred(o, false));
        hooks.after(className, cl, "onDrop", o -> syncFolderIconDeferred(o, false));
        // After resize finishes, ColorOS rebuilds background — reassert glass (do not sync on
        // every updateItemIconLayout frame). Corner radii stay on the b73ba87 installImage path.
        hooks.after("com.android.launcher3.folder.FlexibleFolderIcon", cl, "updateItemIconPreview",
                this::restoreFolderGlassAfterResize);
    }

    /**
     * Unlock briefly zeros DragLayer alpha and resets wallpaper depth. Soft-refresh all glass
     * hosts through the settle window. Cos16 FolderIcon unlock scale uses invalidate pumps
     * (page-flip style) plus live matrix sampling — not off-cycle forceCapture.
     */
    private void hookUnlockGlassRefresh(ClassLoader cl) {
        hooks.after("com.android.launcher.Launcher", cl, "onResume", o -> scheduleUnlockGlassRefresh());
        hooks.after("com.android.launcher3.Launcher", cl, "onResume", o -> scheduleUnlockGlassRefresh());
        hookUnlockFolderScaleTicks(cl);
        try {
            Class<?> utils = Class.forName(
                    "com.android.keyguardservice.KeyGuardDismissedUtils", false, cl);
            for (Method m : utils.getDeclaredMethods()) {
                if (m.isBridge() || m.isSynthetic()) continue;
                if (!m.getName().equals("setUnlockAnimRunning") || m.getParameterCount() != 1) {
                    continue;
                }
                hooks.hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        if (Boolean.TRUE.equals(chain.getArg(0))) {
                            GlassInstaller.startUnlockFolderInvalidatePumps();
                        }
                        scheduleUnlockGlassRefresh();
                    } catch (Throwable e) {
                        hooks.log(5, TAG, "setUnlockAnimRunning glass refresh failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            hooks.log(5, TAG, "KeyGuardDismissedUtils unlock refresh hook unavailable", e);
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
                        hooks.hookOnce(m, chain -> {
                            Object result = chain.proceed();
                            try {
                                if (Boolean.TRUE.equals(chain.getArg(0))) scheduleUnlockGlassRefresh();
                            } catch (Throwable e) {
                                hooks.log(5, TAG, className + ".onWindowFocusChanged refresh failed", e);
                            }
                            return result;
                        });
                    } else if (m.getName().equals("onScreenLockStateChange")
                            && m.getParameterCount() == 1) {
                        hooks.hookOnce(m, chain -> {
                            Object result = chain.proceed();
                            try {
                                Object state = chain.getArg(0);
                                Object lock = HookReflection.invokeNoArgs(state, "getLockState");
                                if (lock instanceof Number && ((Number) lock).intValue() == 1) {
                                    GlassInstaller.startUnlockFolderInvalidatePumps();
                                    scheduleUnlockGlassRefresh();
                                }
                            } catch (Throwable e) {
                                hooks.log(5, TAG, className + ".onScreenLockStateChange refresh failed", e);
                            }
                            return result;
                        });
                    }
                }
            } catch (Throwable e) {
                hooks.log(5, TAG, className + " unlock refresh hook unavailable", e);
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
                hooks.hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        Object self = chain.getThisObject();
                        if (!(self instanceof View)) return result;
                        View view = (View) self;
                        if (!isUnlockFolderIconHost(view)) return result;
                        GlassInstaller.onFolderUnlockScaleTick(view);
                    } catch (Throwable e) {
                        hooks.log(5, TAG, "View." + name + " unlock glass tick failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            hooks.log(5, TAG, "View unlock scale tick hook unavailable", e);
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
        if (!hooks.enabled()) return;
        final int token = ++unlockGlassRefreshToken;
        GlassInstaller.refreshAll();
        GlassInstaller.startUnlockFolderInvalidatePumps();
        for (long delay : new long[] { 48L, 120L, 280L, 500L, 900L }) {
            hooks.mainHandler.postDelayed(() -> {
                if (token != unlockGlassRefreshToken || !hooks.enabled()) return;
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
                hooks.hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        Object extras = chain.getArg(2);
                        if (extras instanceof Bundle) {
                            WallpaperScaleTracker.onWallpaperCommand((Bundle) extras);
                            if (hooks.enabled()) GlassInstaller.refreshAll();
                        }
                    } catch (Throwable e) {
                        hooks.log(5, TAG, "Wallpaper scale track failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            hooks.log(5, TAG, "Wallpaper scale tracking hook unavailable", e);
        }
    }

    /** Restores mBgView visibility/layout contract after leaving the resize-frame path. */
    private void restoreFolderGlassAfterResize(Object folderIcon) {
        if (!(folderIcon instanceof View)) {
            syncFolderIconDeferred(folderIcon, true);
            return;
        }
        View icon = (View) folderIcon;
        Object preview = HookReflection.field(folderIcon, "mBackground");
        Object bgView = HookReflection.field(preview, "mBgView");
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
                hooks.hookOnce(method, chain -> {
                    Object result = chain.proceed();
                    try {
                        Object icon = chain.getThisObject();
                        Object preview = HookReflection.field(icon, "mBackground");
                        boolean visible = Boolean.TRUE.equals(chain.getArg(0)) && !isFolderOpen(icon);
                        setFolderBackgroundVisibility(preview, visible);
                        if (visible) syncFolderPreviewDeferred(icon, preview, false);
                    } catch (Throwable e) {
                        hooks.log(5, TAG, className + ".setIconVisible apply failed", e);
                    }
                    return result;
                });
            } catch (Throwable e) {
                hooks.log(5, TAG, className + ".setIconVisible hook unavailable", e);
            }
        }
        try {
            Class<?> c = Class.forName("com.android.launcher3.folder.FlexibleFolderIcon", false, cl);
            Method setVisibility = c.getDeclaredMethod("setVisibility", int.class);
            hooks.hookOnce(setVisibility, chain -> {
                Object result = chain.proceed();
                try {
                    Object icon = chain.getThisObject();
                    if (Integer.valueOf(View.VISIBLE).equals(chain.getArg(0)) && !isFolderOpen(icon)) {
                        syncFolderPreviewDeferred(icon, HookReflection.field(icon, "mBackground"), false);
                    }
                } catch (Throwable e) {
                    hooks.log(5, TAG, "FlexibleFolderIcon.setVisibility apply failed", e);
                }
                return result;
            });
        } catch (Throwable e) {
            hooks.log(5, TAG, "FlexibleFolderIcon.setVisibility hook unavailable", e);
        }
    }

    void syncFolderIconDeferred(Object folderIcon, boolean forceVisible) {
        Object preview = HookReflection.field(folderIcon, "mBackground");
        if (forceVisible) setFolderBackgroundVisibility(preview, true);
        syncFolderPreviewDeferred(folderIcon, preview, forceVisible);
    }

    void syncFolderPreviewDeferred(Object folderIcon, Object previewBackground, boolean forceVisible) {
        syncFolderPreview(folderIcon, previewBackground);
        if (folderIcon instanceof View) {
            ((View) folderIcon).post(() -> {
                if (forceVisible) setFolderBackgroundVisibility(previewBackground, true);
                syncFolderPreview(folderIcon, previewBackground);
            });
        }
    }

    void syncFolderPreview(Object folderIcon, Object previewBackground) {
        if (popupHooks.isFolderPopupOemOnly(folderIcon)) return;
        Object bgView = HookReflection.field(previewBackground, "mBgView");
        if (!(bgView instanceof ImageView)) return;
        ImageView image = (ImageView) bgView;
        Object original = HookReflection.field(previewBackground, "mBgDrawable");
        GlassDrawable installed = GlassInstaller.get(image);
        boolean open = isFolderOpen(folderIcon);
        if (hooks.enabled() && !open) {
            // ColorOS calls setImageDrawable(mBgDrawable) again while binding/layouting a folder.
            // Reassert the image-layer contract even when our background drawable is still present.
            GlassInstaller.installImage(image, hooks.currentConfig());
        } else if (installed != null) {
            GlassInstaller.restoreImage(image, original instanceof Drawable ? (Drawable) original : null);
        }
        if (open) image.setVisibility(View.INVISIBLE);
    }

    static void setFolderBackgroundVisibility(Object previewBackground, boolean visible) {
        Object bgView = HookReflection.field(previewBackground, "mBgView");
        if (bgView instanceof View) ((View) bgView).setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
    }

    static boolean isFolderOpen(Object folderIcon) {
        Object folder = HookReflection.field(folderIcon, "mFolder");
        Object result = HookReflection.invokeNoArgs(folder, "isOpen");
        if (result instanceof Boolean) return (Boolean) result;
        Object value = HookReflection.field(folder, "mIsOpen");
        return value instanceof Boolean && (Boolean) value;
    }
}
