package net.z841973620.colorosliquidglass.hook;

import android.animation.ObjectAnimator;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;
import net.z841973620.colorosliquidglass.glass.GlassDrawable;
import net.z841973620.colorosliquidglass.glass.GlassInstaller;
import net.z841973620.colorosliquidglass.glass.PopupCaptureTrace;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import static net.z841973620.colorosliquidglass.hook.HookContext.TAG;

/** 桌面长按菜单与“更多功能”子菜单：玻璃安装、过渡动画和关闭清理。 */
final class LauncherPopupHooks {
    private final HookContext hooks;
    private final LauncherHooks launcherHooks;

    LauncherPopupHooks(HookContext hooks, LauncherHooks launcherHooks) {
        this.hooks = hooks;
        this.launcherHooks = launcherHooks;
    }

    /**
     * FolderIcons currently showing the options menu — OEM long-press visuals only (no plate glass,
     * no menu glass). Prevents corner-radius corruption after cancel.
     */
    private final Set<Object> folderPopupOemOnly =
            Collections.newSetFromMap(new WeakHashMap<>());

    void hookFolderPopupBlur(ClassLoader cl) {
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
                    hooks.hookOnce(m, chain -> {
                        // Strip folder plate glass before OEM open — keeps resting corners intact.
                        // Menu glass still mounts on mAllPopupShortcutContainer below.
                        if (m.getParameterCount() >= 1) {
                            beginFolderPopupOemOnly(chain.getArg(0));
                        }
                        // Blur is always stripped while the module is on; glass is optional.
                        if (hooks.enabled()) keepPopupBlurTransparent(chain.getThisObject());
                        Object result = chain.proceed();
                        if (hooks.enabled()) keepPopupBlurTransparent(chain.getThisObject());
                        Object popup = chain.getThisObject();
                        Object longPressed = HookReflection.field(popup, "mLongPressedView");
                        beginFolderPopupOemOnly(longPressed);
                        if (hooks.menuStyleEnabled()) {
                            applyDesktopPopupGlass(popup);
                        }
                        return result;
                    });
                }
            }
            // Static showForIcon is the shared entry for BubbleTextView / widget / folder menus.
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("showForIcon") || m.isBridge() || m.isSynthetic()) continue;
                hooks.hookOnce(m, chain -> {
                    Object icon = chain.getArg(0);
                    beginFolderPopupOemOnly(icon);
                    Object result = chain.proceed();
                    try {
                        if (result != null && hooks.enabled()) {
                            keepPopupBlurTransparent(result);
                            if (hooks.menuStyleEnabled()) {
                                applyDesktopPopupGlass(result);
                            }
                        }
                    } catch (Throwable e) {
                        hooks.log(5, TAG, "showForIcon popup glass failed", e);
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
                hooks.hookOnce(m, chain -> {
                    Object popup = chain.getThisObject();
                    boolean closing = name.equals("animateClose")
                            || (m.getParameterCount() >= 1 && Boolean.FALSE.equals(chain.getArg(0)));
                    Object result = chain.proceed();
                    try {
                        if (hooks.menuStyleEnabled() && closing) {
                            retainDesktopPopupGlassThroughClose(popup);
                        }
                    } catch (Throwable e) {
                        hooks.log(5, TAG, className + "." + name + " close glass retain failed", e);
                    }
                    return result;
                });
            }
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("closeComplete") || m.isBridge() || m.isSynthetic()) continue;
                hooks.hookOnce(m, chain -> {
                    Object popup = chain.getThisObject();
                    Object longPressed = HookReflection.field(popup, "mLongPressedView");
                    Object result = chain.proceed();
                    try {
                        finishDesktopPopupGlass(popup);
                    } catch (Throwable e) {
                        hooks.log(5, TAG, "closeComplete popup glass cleanup failed", e);
                    }
                    try {
                        endFolderPopupOemOnly(longPressed);
                    } catch (Throwable e) {
                        hooks.log(5, TAG, "closeComplete folder OEM restore failed", e);
                    }
                    return result;
                });
            }
            // Capture desktop under the menu once open anim finishes (scale/alpha = 1).
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("openAnimationEnd") || m.isBridge() || m.isSynthetic()) {
                    continue;
                }
                hooks.hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        if (hooks.menuStyleEnabled()) {
                            Object popup = chain.getThisObject();
                            captureDesktopPopupGlassAfterOpen(popup);
                        }
                    } catch (Throwable e) {
                        hooks.log(5, TAG, "openAnimationEnd popup glass capture failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            hooks.log(5, TAG, "Popup blur hook unavailable", e);
        }
        // Belt-and-suspenders: open anim would fade PopupBlurView to 1 even if we zeroed alpha.
        try {
            Class<?> blur = Class.forName("com.android.launcher3.popup.PopupBlurView", false, cl);
            for (Method m : blur.getDeclaredMethods()) {
                if (!m.getName().equals("createBlurAnim") || m.getParameterCount() != 1
                        || m.isBridge() || m.isSynthetic()) continue;
                hooks.hookOnce(m, chain -> {
                    // Always kill OEM fullscreen blur when the module is enabled.
                    if (hooks.enabled() && Boolean.TRUE.equals(chain.getArg(0))) {
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
            hooks.log(5, TAG, "PopupBlurView.createBlurAnim hook unavailable", e);
        }
    }

    /** Hides the fullscreen PopupBlurView for any long-press options menu (icon / widget / folder). */
    void keepPopupBlurTransparent(Object popup) {
        if (!hooks.enabled() || popup == null) return;
        // Keep the PopupBlurView object alive so popup close/drag-resize bookkeeping still runs.
        // Only skip the open alpha animation that makes the full-screen blur visible.
        HookReflection.setField(popup, "mAddPopupBlurView", false);
        Object blur = HookReflection.field(popup, "mPopBlurView");
        if (blur instanceof View) ((View) blur).setAlpha(0f);
    }

    boolean isFolderPopupOemOnly(Object folderIcon) {
        return folderIcon != null && folderPopupOemOnly.contains(folderIcon);
    }

    /**
     * Folder options-menu open: strip plate glass so ColorOS long-press corner anim stays OEM.
     * Menu chrome still receives LiquidGlass via {@link #applyDesktopPopupGlass}.
     */
    private void beginFolderPopupOemOnly(Object icon) {
        if (!LauncherHooks.isFolderDragSource(icon)) return;
        if (!folderPopupOemOnly.add(icon)) return;
        try {
            Object preview = HookReflection.field(icon, "mBackground");
            Object bgView = HookReflection.field(preview, "mBgView");
            FolderPreviewHooks.restoreOemPreviewDrawable(preview);
            Drawable oem = HookReflection.field(preview, "mBgDrawable") instanceof Drawable
                    ? (Drawable) HookReflection.field(preview, "mBgDrawable") : null;
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
            hooks.log(5, TAG, "beginFolderPopupOemOnly failed", e);
        }
    }

    /** Folder options-menu close: drop OEM-only flag and reinstall plate glass via normal sync. */
    private void endFolderPopupOemOnly(Object icon) {
        if (!LauncherHooks.isFolderDragSource(icon)) return;
        folderPopupOemOnly.remove(icon);
        try {
            Object preview = HookReflection.field(icon, "mBackground");
            Object bgView = HookReflection.field(preview, "mBgView");
            if (bgView instanceof ImageView && GlassInstaller.get((ImageView) bgView) != null) {
                GlassInstaller.uninstall((ImageView) bgView);
            }
            LauncherHooks.setFolderBackgroundVisibility(preview, true);
            launcherHooks.syncFolderPreview(icon, preview);
            if (icon instanceof View v) {
                v.invalidate();
                v.post(() -> {
                    if (isFolderPopupOemOnly(icon)) return;
                    launcherHooks.syncFolderIconDeferred(icon, true);
                });
            }
        } catch (Throwable e) {
            hooks.log(5, TAG, "endFolderPopupOemOnly failed", e);
        }
    }

    private static final String TAG_DESKTOP_POPUP = "colg_desktop_popup";

    /**
     * LiquidGlass on desktop long-press option panels ({@code OplusPopupContainerWithArrow}).
     * Glass replaces the panel background (not a covering overlay). Backdrop samples desktop
     * icons via {@link DesktopIconOverlay} so settle-frame chrome cannot erase apps/folders.
     */
    private void applyDesktopPopupGlass(Object popup) {
        if (!hooks.menuStyleEnabled() || !(popup instanceof ViewGroup)) return;
        try {
            applyDesktopPopupGlassNow(popup);
            // One follow-up after OEM may restore white fills mid-open animation.
            if (popup instanceof View root) {
                root.post(() -> {
                    if (hooks.menuStyleEnabled() && root.isAttachedToWindow()) {
                        reassertDesktopPopupGlass(popup);
                    }
                });
            }
        } catch (Throwable e) {
            hooks.log(5, TAG, "applyDesktopPopupGlass failed", e);
        }
    }

    /** Light pass: strip restored row whites; reinstall only if OEM replaced glass. */
    void reassertDesktopPopupGlass(Object popup) {
        if (!hooks.menuStyleEnabled() || !(popup instanceof ViewGroup)) return;
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
        if (!hooks.menuStyleEnabled() || !(popup instanceof ViewGroup)) return;
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
        if (!hooks.menuStyleEnabled() || !(popup instanceof ViewGroup root)) return;
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
        Object all = HookReflection.field(popup, "mAllPopupShortcutContainer");
        if (all instanceof View && ((View) all).getVisibility() == View.VISIBLE) {
            hosts.add((View) all);
            return hosts;
        }
        addPopupPanelHost(hosts, HookReflection.field(popup, "mAppShortcutContainer"), root);
        addPopupPanelHost(hosts, HookReflection.field(popup, "mSystemShortcutContainer"), root);
        addPopupPanelHost(hosts, HookReflection.field(popup, "mDeepShortcutContainer"), root);
        addPopupPanelHost(hosts, HookReflection.field(popup, "mNotificationContainer"), root);
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
        if (host == null || !hooks.menuStyleEnabled()) return;
        if (host.getAlpha() < 1f) host.setAlpha(1f);
        clearDeepShortcutRowFills(host);
        Runnable install = () -> {
            long trace = PopupCaptureTrace.installStart();
            try {
                if (!hooks.menuStyleEnabled() || !host.isAttachedToWindow()) return;
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
                GlassInstaller.installBackground(host, hooks.currentConfig());
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
        if (!hooks.menuStyleEnabled() || !(popup instanceof ViewGroup)) return;
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
            hooks.log(5, TAG, "captureDesktopPopupGlassAfterOpen failed", e);
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
            if (HookReflection.isClassOrSubclass(current, "com.android.launcher3.Workspace")
                    || HookReflection.isClassOrSubclass(current, "com.android.launcher3.OplusWorkspace")) {
                return current;
            }
            Object parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        try {
            Object launcher = null;
            for (View current = from; current != null; ) {
                Object ctx = unwrapContext(current.getContext());
                if (ctx != null && HookReflection.isClassOrSubclass(ctx, "com.android.launcher3.Launcher")) {
                    launcher = ctx;
                    break;
                }
                Object parent = current.getParent();
                current = parent instanceof View ? (View) parent : null;
            }
            Object ws = HookReflection.invokeNoArgs(launcher, "getWorkspace");
            if (ws instanceof View) return (View) ws;
        } catch (Throwable ignored) { }
        try {
            Class<?> launcherClass = Class.forName("com.android.launcher3.Launcher");
            Object tracker = launcherClass.getField("ACTIVITY_TRACKER").get(null);
            Object launcher = tracker.getClass().getMethod("getCreatedActivity").invoke(tracker);
            Object ws = HookReflection.invokeNoArgs(launcher, "getWorkspace");
            return ws instanceof View ? (View) ws : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object unwrapContext(Object ctx) {
        Object cur = ctx;
        for (int i = 0; i < 6 && cur != null; i++) {
            if (HookReflection.isClassOrSubclass(cur, "com.android.launcher3.Launcher")) return cur;
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
        Object round = HookReflection.field(host, "mRadius");
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
    void hookMoreFunctionsSubMenuGlass(ClassLoader cl) {
        try {
            Class<?> more = Class.forName(
                    "com.android.launcher3.popup.MoreFunctionsPopupListWindow", false, cl);
            for (Method m : more.getDeclaredMethods()) {
                if (m.isBridge() || m.isSynthetic()) continue;
                String name = m.getName();
                if (name.equals("onMainMenuListViewClick")) {
                    hooks.hookOnce(m, chain -> {
                        Object window = chain.getThisObject();
                        Object result = chain.proceed();
                        try {
                            if (hooks.menuStyleEnabled()) {
                                beginMoreFunctionsSubMenuTransition(window);
                            }
                        } catch (Throwable e) {
                            hooks.log(5, TAG, "MoreFunctions.onMainMenuListViewClick glass failed", e);
                        }
                        return result;
                    });
                } else if (name.equals("dismissImmediately") || name.equals("dismiss")
                        || name.equals("superDismiss")) {
                    hooks.hookOnce(m, chain -> {
                        Object window = chain.getThisObject();
                        Object result = chain.proceed();
                        try {
                            finishMoreFunctionsSubMenuGlass(window);
                        } catch (Throwable e) {
                            hooks.log(5, TAG, "MoreFunctions." + name + " glass cleanup failed", e);
                        }
                        return result;
                    });
                }
            }
        } catch (Throwable e) {
            hooks.log(5, TAG, "MoreFunctionsPopupListWindow glass hook unavailable", e);
        }
        // Constructor ends here after setBackgroundColor(white) — replace before first draw.
        hooks.after("com.android.launcher3.popup.OplusPopupContainerWithArrow", cl, "setSubPopWindow",
                popup -> {
                    if (!hooks.menuStyleEnabled()) return;
                    try {
                        Object window = HookReflection.field(popup, "mSubPopWindow");
                        View host = resolveMoreFunctionsSubMenuHost(window);
                        if (host != null) installSubMenuGlassOnce(host);
                    } catch (Throwable e) {
                        hooks.log(5, TAG, "setSubPopWindow early submenu glass failed", e);
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
                hooks.hookOnce(m, chain -> {
                    try {
                        if (hooks.menuStyleEnabled()) {
                            GlassInstaller.setMoreFunctionsSubMenuActive(true);
                            Object sub = HookReflection.field(chain.getThisObject(), "f15455g");
                            if (sub instanceof View) {
                                installSubMenuGlassOnce((View) sub);
                            }
                        }
                    } catch (Throwable e) {
                        hooks.log(5, TAG, controllerName + ".j pre-glass failed", e);
                    }
                    Object result = chain.proceed();
                    try {
                        // j() just seeded f15656b — pin glass bounds to the reveal rect.
                        Object sub = HookReflection.field(chain.getThisObject(), "f15455g");
                        if (sub instanceof View) syncCouiRoundFrameRevealBounds((View) sub);
                    } catch (Throwable ignored) { }
                    return result;
                });
            }
        } catch (Throwable e) {
            hooks.log(5, TAG, controllerName + " open-start glass hook unavailable", e);
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
        if (!hooks.menuStyleEnabled() || host == null) return;
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
            GlassInstaller.installBackground(host, hooks.currentConfig());
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
            hooks.log(5, TAG, "installSubMenuGlassOnce failed", e);
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
        Object frame = HookReflection.field(window, "mSubMenuRoundFrameLayout");
        if (frame instanceof View) return (View) frame;
        Object list = HookReflection.field(window, "mSubMenuListView");
        if (list == null) list = HookReflection.invokeNoArgs(window, "getSubMenuListView");
        if (list instanceof View) {
            ViewParent parent = ((View) list).getParent();
            if (parent instanceof View) return (View) parent;
        }
        return null;
    }
}
