package net.z841973620.colorosliquidglass.hook;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import net.z841973620.colorosliquidglass.glass.DesktopBackdropSampler;
import net.z841973620.colorosliquidglass.glass.GlassDrawable;
import net.z841973620.colorosliquidglass.glass.GlassInstaller;
import net.z841973620.colorosliquidglass.glass.WallpaperScaleTracker;
import net.z841973620.colorosliquidglass.ipc.DesktopBackdropHub;
import java.lang.reflect.Method;

/** ColorOS 13/14 桌面安装顺序、拖拽、文件夹开关和背景共享。 */
final class LegacyLauncherHooks {
    private final HookContext hooks;
    final LegacyFolderHooks folderHooks;
    final LegacyPopupHooks popupHooks;
    final LegacyRecentsHooks recentsHooks;
    final LegacyWorkspaceHooks workspaceHooks;

    LegacyLauncherHooks(HookContext hooks) {
        this.hooks = hooks;
        folderHooks = new LegacyFolderHooks(hooks, this);
        popupHooks = new LegacyPopupHooks(hooks, this);
        recentsHooks = new LegacyRecentsHooks(hooks, this);
        workspaceHooks = new LegacyWorkspaceHooks(hooks, this);
    }

    private volatile boolean folderOpenActive;
    volatile boolean folderDragActive;
    private Object folderDragSource;

    void install(ClassLoader cl) {
        hooks.log(4, "Os14HookBackend: hooking Launcher (ColorOS 13/14 APIs)");
        folderHooks.hookFolderPreviewBackground(cl);
        hookFolderVisibility(cl);
        hookFolderCloseMorphGlass(cl);
        hookFolderRefreshEvents(cl);
        popupHooks.hookFolderPopupBlur(cl);
        hookFolderDragPreview(cl);
        hookFolderDragDepthBlur(cl);
        recentsHooks.hookRecentsClearButton(cl);
        recentsHooks.hookRecentsTaskShortcuts(cl);
        workspaceHooks.hookToggleBarChrome(cl);
        workspaceHooks.hookToggleBarSecondLevelPreviewGlass(cl);
        workspaceHooks.hookToggleBarApplyButtonGlass(cl);
        workspaceHooks.hookPagePreviewItemGlass(cl);
        workspaceHooks.hookPendingCardPreviewGlass(cl);
        workspaceHooks.hookPageIndicatorGlass(cl);
        hookUnlockGlassRefresh(cl);
        hookWallpaperScaleTracking(cl);
        hookDesktopBackdropIpc(cl);
        hooks.after(cl, "com.android.launcher3.folder.FolderIcon", "onFolderClose", o -> {
            endFolderOpen(o);
            folderHooks.syncFolderIcon(o);
        });
    }

    // ── Depth blur (no setIconBlur on ColorOS 14) ───────────────────────────

    private void hookFolderDragDepthBlur(ClassLoader cl) {
        try {
            Class<?> depth = Class.forName(
                    "com.android.launcher3.uioverrides.states.OplusDepthController", false, cl);
            for (Method m : depth.getDeclaredMethods()) {
                String name = m.getName();
                if (!(name.equals("setBlur") || name.equals("setBlurWithoutAnim"))) continue;
                if (m.getParameterCount() < 1 || m.getParameterTypes()[0] != float.class) continue;
                if (m.isBridge() || m.isSynthetic()) continue;
                hooks.hookOnce(m, chain -> {
                    if (hooks.enabled()) {
                        Object[] args = chain.getArgs().toArray(new Object[0]);
                        if (args.length > 0 && args[0] instanceof Number) args[0] = 0f;
                        return chain.proceed(args);
                    }
                    return chain.proceed();
                });
            }
            for (Method m : depth.getDeclaredMethods()) {
                if (!m.getName().equals("getFolderBlur") || m.getParameterCount() != 0
                        || m.isBridge() || m.isSynthetic()) continue;
                hooks.hookOnce(m, chain -> {
                    if (!hooks.enabled()) return chain.proceed();
                    if (folderOpenActive || folderDragActive || hasOpenFolder(null)) return 0f;
                    return chain.proceed();
                });
            }
        } catch (Throwable e) {
            hooks.log(5, "Os14 DepthController blur hook unavailable", e);
        }

        // Depth blur writes are clamped above; static blur view follows setBlur(0).

        for (String stateClass : new String[] {
                "com.android.launcher3.states.ToggleBarState",
                "com.android.launcher3.states.PagePreviewState",
                "com.android.launcher3.uioverrides.states.OverviewState",
                "com.android.launcher3.uioverrides.states.BackgroundAppState",
                "com.android.launcher3.states.OplusSpringLoadedState"
        }) {
            try {
                Class<?> c = Class.forName(stateClass, false, cl);
                for (Method m : c.getDeclaredMethods()) {
                    if (m.isBridge() || m.isSynthetic()) continue;
                    String name = m.getName();
                    if ((name.equals("getWallpaperBlur") || name.equals("getBlurUnchecked")
                            || name.equals("getDepthUnchecked"))
                            && m.getParameterCount() <= 1) {
                        hooks.hookOnce(m, chain -> hooks.enabled() ? 0f : chain.proceed());
                    } else if ((name.equals("getLauncherRootViewBgAlpha")
                            || name.equals("getCellLayoutBgAlpha"))
                            && m.getParameterCount() <= 1) {
                        hooks.hookOnce(m, chain -> hooks.enabled() ? 0 : chain.proceed());
                    }
                }
            } catch (Throwable ignored) { }
        }

        try {
            Class<?> helper = Class.forName(
                    "com.android.quickstep.touch.SwipeToRecentAnimationHelper", false, cl);
            for (Method m : helper.getDeclaredMethods()) {
                if (m.isBridge() || m.isSynthetic()) continue;
                String name = m.getName();
                if (!(name.equals("createBlurAndDragLayerAlphaAnim")
                        || name.equals("doBackGroundAnim")
                        || name.equals("initState"))) continue;
                hooks.hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    if (!hooks.enabled()) return result;
                    Object self = chain.getThisObject();
                    HookReflection.setField(self, "wallpaperBlurEndValue", 0f);
                    forceDepthBlur(self, 0f);
                    return result;
                });
            }
        } catch (Throwable e) {
            hooks.log(5, "Os14 SwipeToRecent blur clamp unavailable", e);
        }

        // Folder open/close drives gaussian via FolderExtImplV2 — keep depth at 0.
        try {
            Class<?> ext = Class.forName(
                    "com.android.launcher3.folder.FolderExtImplV2", false, cl);
            for (Method m : ext.getDeclaredMethods()) {
                if (!m.getName().equals("fadeGaussianBlurState") || m.isBridge() || m.isSynthetic()) {
                    continue;
                }
                hooks.hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    if (hooks.enabled()) forceDepthBlur(chain.getThisObject(), 0f);
                    return result;
                });
            }
        } catch (Throwable e) {
            hooks.log(5, "Os14 fadeGaussianBlurState clamp unavailable", e);
        }
    }

    private void hookFolderVisibility(ClassLoader cl) {
        // Only hook OplusFolder.animateOpen — it calls super.animateOpen(); hooking both
        // Folder + OplusFolder would run beginFolderOpen twice mid-open.
        hooks.after(cl, "com.android.launcher3.folder.OplusFolder", "animateOpen", this::beginFolderOpen);
        hooks.after(cl, "com.android.launcher3.folder.Folder", "animateOpen", folder -> {
            // AOSP Folder used when OplusFolder is absent.
            if (!HookReflection.isClassOrSubclass(folder, "com.android.launcher3.folder.OplusFolder")) {
                beginFolderOpen(folder);
            }
        });
        // ColorOS 14 ends close with closeComplete(boolean), not onFolderCloseCompleted.
        hooks.after(cl, "com.android.launcher3.folder.Folder", "closeComplete", folder -> {
            endFolderOpen(folder);
            Object icon = HookReflection.field(folder, "mFolderIcon");
            if (icon != null) folderHooks.syncFolderIcon(icon);
        });
        // handleClose / animateClosed start the morph — keep depth at 0; glass applied via
        // OplusFolderAnimationManager hooks after OEM sets the morph drawable.
        hooks.after(cl, "com.android.launcher3.folder.Folder", "handleClose", folder -> {
            folderOpenActive = true;
            forceDepthBlur(folder, 0f);
        });
        hooks.after(cl, "com.android.launcher3.folder.Folder", "animateClosed", folder -> {
            folderOpenActive = true;
            forceDepthBlur(folder, 0f);
            applyFolderMorphGlass(folder);
        });
        hooks.after(cl, "com.android.launcher3.folder.Folder", "close", folder -> {
            endFolderOpen(folder);
            Object icon = HookReflection.field(folder, "mFolderIcon");
            if (icon != null) folderHooks.syncFolderIcon(icon);
        });
    }

    private void hookFolderRefreshEvents(ClassLoader cl) {
        hooks.after(cl, "com.android.launcher3.folder.FolderIcon", "onItemsChanged", folderHooks::syncFolderIcon);
        hooks.after(cl, "com.android.launcher3.folder.OplusFolderIcon", "onItemsChanged", folderHooks::syncFolderIcon);
        hooks.after(cl, "com.android.launcher3.folder.PreviewItemManager", "onItemsChanged", mgr -> {
            Object icon = HookReflection.field(mgr, "mIcon");
            if (icon != null) folderHooks.syncFolderIcon(icon);
        });
    }

    private void hookUnlockGlassRefresh(ClassLoader cl) {
        hooks.after(cl, "com.android.launcher3.Launcher", "onResume", ignored -> GlassInstaller.refreshAll());
        hooks.after(cl, "com.android.launcher.Launcher", "onResume", ignored -> GlassInstaller.refreshAll());
    }

    private void hookWallpaperScaleTracking(ClassLoader cl) {
        try {
            Class<?> wm = Class.forName("android.app.WallpaperManager", false, cl);
            for (Method m : wm.getDeclaredMethods()) {
                if (!m.getName().equals("sendWallpaperCommand") || m.isBridge() || m.isSynthetic()) {
                    continue;
                }
                hooks.hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        for (Object arg : chain.getArgs()) {
                            if (arg instanceof android.os.Bundle) {
                                WallpaperScaleTracker.onWallpaperCommand((android.os.Bundle) arg);
                                break;
                            }
                        }
                    } catch (Throwable ignored) { }
                    return result;
                });
            }
        } catch (Throwable e) {
            hooks.log(5, "Os14 wallpaper scale tracking unavailable", e);
        }
    }

    private void hookDesktopBackdropIpc(ClassLoader cl) {
        DesktopBackdropHub.start();
        hooks.after(cl, "com.android.launcher3.Launcher", "onResume", this::attachDesktopBackdropRoot);
        hooks.after(cl, "com.android.launcher.Launcher", "onResume", this::attachDesktopBackdropRoot);
        hooks.after(cl, "com.android.launcher3.Launcher", "onAttachedToWindow", this::attachDesktopBackdropRoot);
        hooks.after(cl, "com.android.launcher.Launcher", "onAttachedToWindow", this::attachDesktopBackdropRoot);
        hooks.after(cl, "com.android.launcher3.Workspace", "scrollTo",
                o -> DesktopBackdropSampler.invalidateAll());
        hooks.after(cl, "com.android.launcher3.OplusWorkspace", "scrollTo",
                o -> DesktopBackdropSampler.invalidateAll());
        hooks.after(cl, "com.android.launcher3.CellLayout", "onLayout",
                o -> DesktopBackdropSampler.invalidateCache());
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
            DesktopBackdropSampler.invalidateCache();
        } catch (Throwable e) {
            hooks.log(5, "Os14 attachDesktopBackdropRoot failed", e);
        }
    }

    // ── Folder close morph plate ────────────────────────────────────────────

    private void hookFolderCloseMorphGlass(ClassLoader cl) {
        // Do NOT clear morph background on inflate — updateAnimColorFilter NPEs on null
        // getBackground() when the folder later opens.
        try {
            Class<?> anim = Class.forName(
                    "com.android.launcher3.folder.OplusFolderAnimationManager", false, cl);
            for (Method m : anim.getDeclaredMethods()) {
                if (m.isBridge() || m.isSynthetic()) continue;
                String name = m.getName();
                if (!(name.equals("getFolderBackgroundAnimator")
                        || name.equals("updateAnimColorFilter")
                        || name.equals("createFolderBackgroundAnimatorSet"))) continue;
                final String hooked = name;
                hooks.hookOnce(m, chain -> {
                    // Ensure morph ImageView has a non-null background before OEM color-filter.
                    if ("updateAnimColorFilter".equals(hooked) && chain.getArgs().size() >= 1) {
                        Object arg0 = chain.getArg(0);
                        if (arg0 instanceof ImageView) {
                            ImageView iv = (ImageView) arg0;
                            if (iv.getBackground() == null) {
                                iv.setBackground(new ColorDrawable(0x66FFFFFF));
                            }
                        }
                    }
                    Object result = chain.proceed();
                    try {
                        if (!hooks.enabled()) return result;
                        Object self = chain.getThisObject();
                        Object folder = HookReflection.field(self, "mColorFolder");
                        if (folder == null) folder = HookReflection.field(self, "mFolder");
                        // Replace OEM translucent plate with glass; never leave background null.
                        applyFolderMorphGlass(folder);
                        forceDepthBlur(folder != null ? folder : self, 0f);
                    } catch (Throwable e) {
                        hooks.log(5, "Os14 folder morph glass failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            hooks.log(5, "Os14 OplusFolderAnimationManager glass unavailable", e);
        }
    }

    private void suppressFolderMorphBackground(Object folder) {
        // Safe variant: never leave ImageView background null (OEM updateAnimColorFilter NPEs).
        if (!hooks.enabled() || folder == null) return;
        applyFolderMorphGlass(folder);
    }

    private void applyFolderMorphGlass(Object folder) {
        if (!hooks.enabled() || folder == null) return;
        try {
            Object animBg = HookReflection.field(folder, "mContentAnimationBackground");
            if (!(animBg instanceof ImageView)) return;
            ImageView iv = (ImageView) animBg;
            iv.setImageDrawable(null);
            GlassInstaller.installBackground(iv, hooks.currentConfig());
            GlassDrawable glass = GlassInstaller.get(iv);
            if (glass != null) {
                float density = iv.getResources().getDisplayMetrics().density;
                float radius = 24f * density;
                Object preview = null;
                Object icon = HookReflection.field(folder, "mFolderIcon");
                if (icon != null) preview = HookReflection.field(icon, "mBackground");
                Object r = HookReflection.field(preview, "mRadius");
                if (r instanceof Number && ((Number) r).floatValue() > 0f) {
                    radius = ((Number) r).floatValue();
                }
                glass.setCornerRadii(radius, radius, radius, radius);
            }
            if (iv.getWidth() > 0 && iv.getHeight() > 0) {
                GlassInstaller.forceCapture(iv);
            }
            iv.invalidate();
        } catch (Throwable e) {
            hooks.log(5, "Os14 applyFolderMorphGlass failed", e);
        }
    }

    // ── Long-press folder DragView glass ────────────────────────────────────

    private void hookFolderDragPreview(ClassLoader cl) {
        // ColorOS 14 folder drag uses DragPreviewProvider.createDrawable() (not contentView):
        // resetPressAnimStateForLongClick restores white mBgDrawable, then view.draw() bakes it
        // into a FastBitmapDrawable. Glass on ImageView background sits under that white layer.
        try {
            Class<?> folderIcon = Class.forName(
                    "com.android.launcher3.folder.OplusFolderIcon", false, cl);
            for (Method m : folderIcon.getDeclaredMethods()) {
                if (!m.getName().equals("resetPressAnimStateForLongClick")
                        || m.isBridge() || m.isSynthetic()) continue;
                hooks.hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        if (hooks.enabled()) {
                            Object preview = HookReflection.field(chain.getThisObject(), "mBackground");
                            folderHooks.suppressOemDrawable(preview);
                        }
                    } catch (Throwable e) {
                        hooks.log(5, "Os14 resetPressAnimStateForLongClick suppress failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            hooks.log(5, "Os14 resetPressAnimStateForLongClick hook unavailable", e);
        }

        try {
            Class<?> provider = Class.forName(
                    "com.android.launcher3.graphics.DragPreviewProvider", false, cl);
            for (Method m : provider.getDeclaredMethods()) {
                if (m.isBridge() || m.isSynthetic()) continue;
                String name = m.getName();
                // createDrawable + drawDragView / lambda$createDrawable$0
                if (!(name.equals("createDrawable") || name.equals("drawDragView")
                        || name.contains("createDrawable"))) continue;
                hooks.hookOnce(m, chain -> {
                    try {
                        if (hooks.enabled()) {
                            Object view = HookReflection.field(chain.getThisObject(), "mView");
                            if (isFolderIcon(view)) {
                                beginFolderDrag(view);
                                folderHooks.suppressOemDrawable(HookReflection.field(view, "mBackground"));
                            }
                        }
                    } catch (Throwable ignored) { }
                    return chain.proceed();
                });
            }
        } catch (Throwable e) {
            hooks.log(5, "Os14 DragPreviewProvider suppress unavailable", e);
        }

        try {
            Class<?> workspace = Class.forName("com.android.launcher3.Workspace", false, cl);
            for (Method m : workspace.getDeclaredMethods()) {
                if (!m.getName().equals("beginDragShared") || m.isBridge() || m.isSynthetic()) {
                    continue;
                }
                if (m.getParameterCount() < 2) continue;
                hooks.hookOnce(m, chain -> {
                    try {
                        if (hooks.enabled() && chain.getArgs().size() >= 1
                                && isFolderIcon(chain.getArg(0))) {
                            beginFolderDrag(chain.getArg(0));
                            folderHooks.suppressOemDrawable(HookReflection.field(chain.getArg(0), "mBackground"));
                        }
                    } catch (Throwable ignored) { }
                    Object result = chain.proceed();
                    try {
                        if (!hooks.enabled()) return result;
                        Object source = chain.getArgs().size() >= 1 ? chain.getArg(0) : null;
                        if (!isFolderIcon(source)) {
                            if (folderDragActive) endFolderDrag(source);
                            return result;
                        }
                        beginFolderDrag(source);
                        folderHooks.suppressOemDrawable(HookReflection.field(source, "mBackground"));
                        Object dragView = result;
                        if (dragView == null) {
                            Object controller = HookReflection.invokeNoArgs(chain.getThisObject(), "getDragController");
                            dragView = HookReflection.invokeNoArgs(controller, "getDragObject");
                            dragView = HookReflection.field(dragView, "dragView");
                            if (dragView == null) dragView = HookReflection.invokeNoArgs(controller, "getDragView");
                        }
                        installFolderDragGlass(dragView, chain.getThisObject());
                    } catch (Throwable e) {
                        hooks.log(5, "Os14 beginDragShared folder glass failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            hooks.log(5, "Os14 beginDragShared hook unavailable", e);
        }

        for (String dragViewClass : new String[] {
                "com.android.launcher3.dragndrop.OplusDragView",
                "com.android.launcher3.dragndrop.DragView",
                "com.android.launcher3.dragndrop.LauncherDragView"
        }) {
            try {
                Class<?> c = Class.forName(dragViewClass, false, cl);
                for (Method m : c.getDeclaredMethods()) {
                    if (m.isBridge() || m.isSynthetic()) continue;
                    String name = m.getName();
                    if (name.equals("onDragStart")) {
                        hooks.hookOnce(m, chain -> {
                            Object result = chain.proceed();
                            try {
                                if (hooks.enabled() && folderDragActive) {
                                    installFolderDragGlass(chain.getThisObject(), null);
                                }
                            } catch (Throwable ignored) { }
                            return result;
                        });
                    } else if (name.equals("onDragEnd") || name.equals("remove")
                            || name.equals("detachContentView")) {
                        hooks.hookOnce(m, chain -> {
                            Object result = chain.proceed();
                            try {
                                if (folderDragActive) endFolderDrag(chain.getThisObject());
                            } catch (Throwable ignored) { }
                            return result;
                        });
                    } else if (name.equals("move") || name.equals("setTranslationX")
                            || name.equals("setTranslationY")) {
                        hooks.hookOnce(m, chain -> {
                            Object result = chain.proceed();
                            try {
                                if (hooks.enabled() && folderDragActive) {
                                    Object content = HookReflection.invokeNoArgs(chain.getThisObject(), "getContentView");
                                    if (content instanceof View && GlassInstaller.get((View) content) != null) {
                                        GlassInstaller.forceCapture((View) content);
                                    }
                                }
                            } catch (Throwable ignored) { }
                            return result;
                        });
                    }
                }
            } catch (Throwable ignored) { }
        }

        hooks.after(cl, "com.android.launcher3.dragndrop.DragController", "endDrag",
                o -> {
                    if (folderDragActive) endFolderDrag(o);
                    else GlassInstaller.prioritizeDesktopPopupOpenSampling();
                });
        hooks.after(cl, "com.android.launcher3.dragndrop.DragController", "cancelDrag",
                o -> {
                    if (folderDragActive) endFolderDrag(o);
                    else GlassInstaller.prioritizeDesktopPopupOpenSampling();
                });
        hooks.after(cl, "com.android.launcher3.dragndrop.OplusDragController", "endDrag",
                o -> {
                    if (folderDragActive) endFolderDrag(o);
                    else GlassInstaller.prioritizeDesktopPopupOpenSampling();
                });
        hooks.after(cl, "com.android.launcher3.dragndrop.OplusDragController", "cancelDrag",
                o -> {
                    if (folderDragActive) endFolderDrag(o);
                    else GlassInstaller.prioritizeDesktopPopupOpenSampling();
                });
    }

    private void installFolderDragGlass(Object dragView, Object workspace) {
        if (!hooks.enabled() || dragView == null) return;
        try {
            if (folderDragSource != null) {
                folderHooks.suppressOemDrawable(HookReflection.field(folderDragSource, "mBackground"));
            }
            Object content = HookReflection.invokeNoArgs(dragView, "getContentView");
            if (!(content instanceof View)) return;
            View view = (View) content;
            // ColorOS 14 createDrawable path: ImageView holds a bitmap that may still contain
            // the OEM white plate. Rebuild from the FolderIcon with white suppressed so glass
            // (View background) is visible through transparent plate pixels.
            if (view instanceof ImageView && folderDragSource instanceof View) {
                rebuildFolderDragBitmapWithoutOemPlate((ImageView) view, (View) folderDragSource);
            } else if (isFolderIcon(view)) {
                folderHooks.suppressOemDrawable(HookReflection.field(view, "mBackground"));
            }
            GlassInstaller.installBackground(view, hooks.currentConfig());
            GlassDrawable glass = GlassInstaller.get(view);
            if (glass != null) {
                glass.setAlpha(255);
                float radius = readFolderDragRadius(view);
                if (radius > 0f) glass.setCornerRadii(radius, radius, radius, radius);
            }
            View seed = workspace instanceof View ? (View) workspace : null;
            if (seed == null && folderDragSource instanceof View) {
                Object launcher = resolveLauncher(folderDragSource);
                Object ws = HookReflection.invokeNoArgs(launcher, "getWorkspace");
                if (ws instanceof View) seed = (View) ws;
            }
            if (seed != null) GlassInstaller.setOverlaySource(view, seed);
            GlassInstaller.forceCapture(view);
            view.invalidate();
            if (dragView instanceof View) ((View) dragView).invalidate();
        } catch (Throwable e) {
            hooks.log(5, "Os14 installFolderDragGlass failed", e);
        }
    }

    private void rebuildFolderDragBitmapWithoutOemPlate(ImageView image, View folderIcon) {
        try {
            Object preview = HookReflection.field(folderIcon, "mBackground");
            folderHooks.suppressOemDrawable(preview);
            Drawable current = image.getDrawable();
            int width = current != null ? Math.max(1, current.getIntrinsicWidth()) : 0;
            int height = current != null ? Math.max(1, current.getIntrinsicHeight()) : 0;
            if (width <= 1 || height <= 1) {
                Rect bounds = new Rect();
                try {
                    Method m = null;
                    for (Method cand : folderIcon.getClass().getMethods()) {
                        if ("getWorkspaceVisualDragBounds".equals(cand.getName())
                                && cand.getParameterCount() == 1) {
                            m = cand;
                            break;
                        }
                    }
                    if (m != null) {
                        m.invoke(folderIcon, bounds);
                        width = Math.max(1, bounds.width());
                        height = Math.max(1, bounds.height());
                    }
                } catch (Throwable ignored) { }
                if (width <= 1) width = Math.max(1, folderIcon.getWidth());
                if (height <= 1) height = Math.max(1, folderIcon.getHeight());
            }
            if (width <= 1 || height <= 1) return;
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                    width, height, android.graphics.Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            int save = canvas.save();
            try {
                // Match DragPreviewProvider centering of the visual drag bounds.
                Rect plate = new Rect();
                try {
                    Method m = null;
                    for (Method cand : folderIcon.getClass().getMethods()) {
                        if ("getWorkspaceVisualDragBounds".equals(cand.getName())
                                && cand.getParameterCount() == 1) {
                            m = cand;
                            break;
                        }
                    }
                    if (m != null) {
                        m.invoke(folderIcon, plate);
                        canvas.translate(-plate.left, -plate.top);
                    }
                } catch (Throwable ignored) { }
                folderHooks.suppressOemDrawable(preview);
                folderIcon.draw(canvas);
            } finally {
                canvas.restoreToCount(save);
            }
            image.setImageBitmap(bmp);
            image.setBackground(null);
        } catch (Throwable e) {
            hooks.log(5, "Os14 rebuildFolderDragBitmapWithoutOemPlate failed", e);
        }
    }

    private float readFolderDragRadius(View host) {
        try {
            Object preview = null;
            if (isFolderIcon(host)) preview = HookReflection.field(host, "mBackground");
            else if (folderDragSource != null) preview = HookReflection.field(folderDragSource, "mBackground");
            Object r = HookReflection.field(preview, "mRadius");
            if (r instanceof Number && ((Number) r).floatValue() > 0f) {
                return ((Number) r).floatValue();
            }
        } catch (Throwable ignored) { }
        return 16f * host.getResources().getDisplayMetrics().density;
    }

    private void beginFolderDrag(Object folderIcon) {
        folderDragActive = true;
        folderDragSource = folderIcon;
        try {
            folderHooks.suppressOemDrawable(HookReflection.field(folderIcon, "mBackground"));
        } catch (Throwable ignored) { }
        forceDepthBlur(folderIcon, 0f);
    }

    private void endFolderDrag(Object host) {
        if (!folderDragActive) return;
        folderDragActive = false;
        Object source = folderDragSource;
        folderDragSource = null;
        try {
            GlassInstaller.clearDragOverlays();
            if (source != null) folderHooks.syncFolderIcon(source);
            // Sibling folders skipped canvas glass only for the drag source; re-sync all.
            refreshWorkspaceFolderGlass(source != null ? source : host);
            // clearDragOverlays already re-arms menu open sampling; call again after folder
            // sync so open-anim capture wins over mid-release freeze.
            GlassInstaller.prioritizeDesktopPopupOpenSampling();
        } catch (Throwable ignored) { }
        forceDepthBlur(host != null ? host : source, 0f);
    }

    /** True when this preview belongs to the folder currently being dragged. */
    boolean isDragSourcePreview(Object preview) {
        if (preview == null || folderDragSource == null) return false;
        Object host = resolveFolderIcon(preview);
        return host != null && host == folderDragSource;
    }

    private void refreshWorkspaceFolderGlass(Object from) {
        try {
            Object launcher = resolveLauncher(from);
            Object workspace = HookReflection.invokeNoArgs(launcher, "getWorkspace");
            if (workspace instanceof ViewGroup) {
                walkSyncFolderIcons((ViewGroup) workspace);
            }
        } catch (Throwable ignored) { }
    }

    private void walkSyncFolderIcons(ViewGroup group) {
        if (group == null) return;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child == null) continue;
            if (isFolderIcon(child)) {
                folderHooks.syncFolderIcon(child);
                child.invalidate();
            } else if (child instanceof ViewGroup) {
                walkSyncFolderIcons((ViewGroup) child);
            }
        }
    }

    // ── Folder open/close depth helpers ─────────────────────────────────────

    private void beginFolderOpen(Object folder) {
        folderOpenActive = true;
        forceDepthBlur(folder, 0f);
        // Do not touch mContentAnimationBackground here — OEM updateAnimColorFilter requires
        // a non-null background; morph glass is applied from animation-manager hooks instead.
        if (DesktopBackdropHub.isLive()) DesktopBackdropSampler.invalidateCache();
    }

    private void endFolderOpen(Object host) {
        folderOpenActive = false;
        forceDepthBlur(host, 0f);
        if (DesktopBackdropHub.isLive()) DesktopBackdropSampler.invalidateCache();
    }

    private void forceDepthBlur(Object host, float value) {
        try {
            Object launcher = resolveLauncher(host);
            Object depth = HookReflection.invokeNoArgs(launcher, "getDepthController");
            if (depth == null) return;
            for (Method m : depth.getClass().getMethods()) {
                if (m.getName().equals("setBlurWithoutAnim") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == float.class) {
                    m.invoke(depth, value);
                    break;
                }
            }
            for (Method m : depth.getClass().getMethods()) {
                if (m.getName().equals("setBlur") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == float.class) {
                    m.invoke(depth, value);
                    break;
                }
            }
            Object staticBlur = HookReflection.field(depth, "mStaticBlurView");
            if (staticBlur instanceof View) ((View) staticBlur).setAlpha(0f);
        } catch (Throwable e) {
            hooks.log(5, "Os14 forceDepthBlur failed", e);
        }
    }

    private boolean hasOpenFolder(Object launcher) {
        try {
            if (launcher == null) launcher = findActiveLauncher();
            if (launcher == null) return folderOpenActive;
            Object open = HookReflection.invokeNoArgs(launcher, "getOpenFolder");
            if (open != null) return true;
            Object workspace = HookReflection.invokeNoArgs(launcher, "getWorkspace");
            Object folder = HookReflection.invokeNoArgs(workspace, "getOpenFolder");
            return folder != null;
        } catch (Throwable ignored) {
            return folderOpenActive;
        }
    }

    Object findActiveLauncher() {
        try {
            for (Object host : new Object[] { folderDragSource }) {
                Object launcher = resolveLauncher(host);
                if (launcher != null) return launcher;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    static Object resolveLauncher(Object host) {
        if (host == null) return null;
        if (HookReflection.isClassOrSubclass(host, "com.android.launcher3.Launcher")
                || HookReflection.isClassOrSubclass(host, "com.android.launcher.Launcher")) {
            return host;
        }
        Object launcher = HookReflection.field(host, "mLauncher");
        if (launcher != null) return launcher;
        Object activity = HookReflection.field(host, "mActivity");
        if (activity != null) return activity;
        Object folder = HookReflection.field(host, "mFolder");
        if (folder != null) return resolveLauncher(folder);
        Object icon = HookReflection.field(host, "mFolderIcon");
        if (icon != null) return resolveLauncher(icon);
        return HookReflection.field(host, "mColorFolder") != null
                ? resolveLauncher(HookReflection.field(host, "mColorFolder")) : null;
    }

    static boolean isFolderIcon(Object object) {
        return HookReflection.isClassOrSubclass(object, "com.android.launcher3.folder.FolderIcon");
    }

    static Object resolveFolderIcon(Object preview) {
        Object host = HookReflection.field(preview, "mInvalidateDelegate");
        if (isFolderIcon(host)) return host;
        return null;
    }

    static boolean isFolderOpen(Object folderIcon) {
        Object folder = HookReflection.field(folderIcon, "mFolder");
        Object result = HookReflection.invokeNoArgs(folder, "isOpen");
        if (result instanceof Boolean) return (Boolean) result;
        Object value = HookReflection.field(folder, "mIsOpen");
        return value instanceof Boolean && (Boolean) value;
    }
}
