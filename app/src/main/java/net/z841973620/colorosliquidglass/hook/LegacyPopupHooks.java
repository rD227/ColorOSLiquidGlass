package net.z841973620.colorosliquidglass.hook;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import net.z841973620.colorosliquidglass.glass.GlassDrawable;
import net.z841973620.colorosliquidglass.glass.GlassInstaller;
import java.lang.reflect.Method;
import java.util.HashSet;

/** ColorOS 13/14 桌面长按菜单的安装、过渡和清理。 */
final class LegacyPopupHooks {
    private final HookContext hooks;
    private final LegacyLauncherHooks launcherHooks;

    LegacyPopupHooks(HookContext hooks, LegacyLauncherHooks launcherHooks) {
        this.hooks = hooks;
        this.launcherHooks = launcherHooks;
    }


    // ── Popup OEM blur suppress + ColorOS 14 desktop menu glass ─────────────

    void hookFolderPopupBlur(ClassLoader cl) {
        final String className = "com.android.launcher3.popup.OplusPopupContainerWithArrow";
        try {
            Class<?> c = Class.forName(className, false, cl);
            // Instance open paths. Never use method result as popup — populateAndShow
            // returns boolean on ColorOS 14 (unlike a View return).
            for (Method m : c.getDeclaredMethods()) {
                String name = m.getName();
                if (m.isBridge() || m.isSynthetic()) continue;
                if ((name.equals("reorderAndShow")
                        || name.equals("onCreateOpenAnimation")
                        || name.equals("populateAndShow")
                        || name.equals("showEditPopupContainer"))
                        && m.getParameterCount() <= 6) {
                    hooks.hookOnce(m, chain -> {
                        Object popup = chain.getThisObject();
                        if (hooks.enabled()) keepPopupBlurTransparent(popup);
                        Object result = chain.proceed();
                        if (hooks.enabled()) keepPopupBlurTransparent(popup);
                        if (hooks.menuStyleEnabled()) applyDesktopPopupGlass(popup);
                        return result;
                    });
                }
            }
            // Static showForIcon — glass the returned popup View.
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("showForIcon") || m.isBridge() || m.isSynthetic()) continue;
                hooks.hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        if (result != null && hooks.enabled()) {
                            keepPopupBlurTransparent(result);
                            if (hooks.menuStyleEnabled()) applyDesktopPopupGlass(result);
                        }
                    } catch (Throwable e) {
                        hooks.log(5, "Os14 showForIcon popup glass failed", e);
                    }
                    return result;
                });
            }
            // Close: reclaim glass if OEM rewrote white fills, then clean up.
            for (Method m : c.getDeclaredMethods()) {
                if (m.isBridge() || m.isSynthetic()) continue;
                String name = m.getName();
                if (!(name.equals("getOpenCloseAnimatorWithoutPendingCard")
                        || name.equals("getOpenCloseAnimator")
                        || name.equals("animateClose"))) continue;
                if (name.startsWith("getOpenClose") && m.getParameterCount() < 1) continue;
                hooks.hookOnce(m, chain -> {
                    Object popup = chain.getThisObject();
                    boolean opening = name.startsWith("getOpenClose")
                            && m.getParameterCount() >= 1
                            && Boolean.TRUE.equals(chain.getArg(0));
                    boolean closing = name.equals("animateClose")
                            || (name.startsWith("getOpenClose")
                            && m.getParameterCount() >= 1
                            && Boolean.FALSE.equals(chain.getArg(0)));
                    Object result = chain.proceed();
                    try {
                        if (opening && result instanceof Animator && hooks.menuStyleEnabled()) {
                            final Object host = popup;
                            markDesktopPopupOpenAnimating(host, true);
                            ((Animator) result).addListener(new AnimatorListenerAdapter() {
                                private boolean canceled;

                                @Override
                                public void onAnimationCancel(Animator animation) {
                                    // Pre-drag end / animator recreate may cancel; keep pumping
                                    // so mid-release does not freeze the half-open backdrop.
                                    canceled = true;
                                    markDesktopPopupOpenAnimating(host, true);
                                }

                                @Override
                                public void onAnimationEnd(Animator animation) {
                                    if (canceled) {
                                        canceled = false;
                                        markDesktopPopupOpenAnimating(host, true);
                                        return;
                                    }
                                    markDesktopPopupOpenAnimating(host, false);
                                    captureDesktopPopupGlassAfterOpen(host);
                                    if (host instanceof View) {
                                        ((View) host).post(
                                                () -> captureDesktopPopupGlassAfterOpen(host));
                                    }
                                }
                            });
                        }
                        if (hooks.menuStyleEnabled() && closing) {
                            retainDesktopPopupGlassThroughClose(popup);
                        }
                    } catch (Throwable e) {
                        hooks.log(5, "Os14 " + className + "." + name + " popup glass failed", e);
                    }
                    return result;
                });
            }
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("closeComplete") || m.isBridge() || m.isSynthetic()) {
                    continue;
                }
                hooks.hookOnce(m, chain -> {
                    Object popup = chain.getThisObject();
                    Object result = chain.proceed();
                    try {
                        finishDesktopPopupGlass(popup);
                    } catch (Throwable e) {
                        hooks.log(5, "Os14 closeComplete popup glass cleanup failed", e);
                    }
                    return result;
                });
            }
            // OEM may restore opaque fills after measure/layout.
            hooks.after(cl, className, "onLayout", popup -> {
                if (hooks.enabled()) keepPopupBlurTransparent(popup);
                if (hooks.menuStyleEnabled()) reassertDesktopPopupGlass(popup);
            });
            hooks.after(cl, "com.android.launcher3.popup.PopupContainerWithArrow", "onLayout", popup -> {
                if (hooks.enabled()) keepPopupBlurTransparent(popup);
                if (hooks.menuStyleEnabled()) reassertDesktopPopupGlass(popup);
            });
        } catch (Throwable e) {
            hooks.log(5, "Os14 popup blur hook unavailable", e);
        }
        // animateOpen lives on ArrowPopup — filter to Oplus popup instances.
        try {
            Class<?> arrow = Class.forName(
                    "com.android.launcher3.popup.ArrowPopup", false, cl);
            for (Method m : arrow.getDeclaredMethods()) {
                if (!m.getName().equals("animateOpen") || m.isBridge() || m.isSynthetic()) {
                    continue;
                }
                hooks.hookOnce(m, chain -> {
                    Object self = chain.getThisObject();
                    Object result = chain.proceed();
                    try {
                        if (HookReflection.isClassOrSubclass(self,
                                "com.android.launcher3.popup.OplusPopupContainerWithArrow")) {
                            if (hooks.enabled()) keepPopupBlurTransparent(self);
                            if (hooks.menuStyleEnabled()) applyDesktopPopupGlass(self);
                        }
                    } catch (Throwable e) {
                        hooks.log(5, "Os14 ArrowPopup.animateOpen glass failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            hooks.log(5, "Os14 ArrowPopup.animateOpen hook unavailable", e);
        }
        try {
            Class<?> blur = Class.forName("com.android.launcher3.popup.PopupBlurView", false, cl);
            for (Method m : blur.getDeclaredMethods()) {
                if (!m.getName().equals("createBlurAnim") || m.getParameterCount() != 1
                        || m.isBridge() || m.isSynthetic()) continue;
                hooks.hookOnce(m, chain -> {
                    if (hooks.enabled() && Boolean.TRUE.equals(chain.getArg(0))) {
                        Object self = chain.getThisObject();
                        if (self instanceof View) {
                            ((View) self).setAlpha(0f);
                            ((View) self).setVisibility(View.INVISIBLE);
                        }
                        Object anim = chain.proceed();
                        if (anim instanceof android.animation.ObjectAnimator) {
                            android.animation.ObjectAnimator oa =
                                    (android.animation.ObjectAnimator) anim;
                            oa.setFloatValues(0f, 0f);
                            oa.setDuration(0L);
                        }
                        return anim;
                    }
                    return chain.proceed();
                });
            }
        } catch (Throwable e) {
            hooks.log(5, "Os14 PopupBlurView.createBlurAnim unavailable", e);
        }
        // Offline wallpaper blur path used by ColorOS 14 popups.
        try {
            Class<?> helper = Class.forName(
                    "com.android.launcher3.popup.PopupBlurHelper", false, cl);
            for (Method m : helper.getDeclaredMethods()) {
                String name = m.getName();
                if (m.isBridge() || m.isSynthetic()) continue;
                if (!(name.equals("loadPopupBlurBg") || name.startsWith("blurBitmap")
                        || name.equals("loadBlurDragLayer"))) continue;
                hooks.hookOnce(m, chain -> {
                    if (hooks.enabled()) return null;
                    return chain.proceed();
                });
            }
        } catch (Throwable e) {
            hooks.log(5, "Os14 PopupBlurHelper suppress unavailable", e);
        }
    }

    private void keepPopupBlurTransparent(Object popup) {
        if (!hooks.enabled() || popup == null) return;
        HookReflection.setField(popup, "mAddPopupBlurView", false);
        Object blur = HookReflection.field(popup, "mPopBlurView");
        if (blur instanceof View) {
            ((View) blur).setAlpha(0f);
            ((View) blur).setVisibility(View.INVISIBLE);
        }
    }

    private static final String TAG_DESKTOP_POPUP = "colg_desktop_popup";

    /**
     * LiquidGlass on ColorOS 14 desktop long-press option panels.
     * ColorOS 14 has no {@code mAllPopupShortcutContainer}; glass mounts on
     * {@code mAppShortcutContainer} / {@code mSystemShortcutContainer} (and scanned panels).
     */
    private void applyDesktopPopupGlass(Object popup) {
        if (!hooks.menuStyleEnabled() || !(popup instanceof ViewGroup)) return;
        try {
            markDesktopPopupOpenAnimating(popup, true);
            applyDesktopPopupGlassNow(popup);
            if (popup instanceof View) {
                View root = (View) popup;
                root.post(() -> {
                    if (hooks.menuStyleEnabled() && root.isAttachedToWindow()) {
                        reassertDesktopPopupGlass(popup);
                    }
                });
                // Open scale anim ~400ms — reassert after settle in case OEM rewrote fills.
                root.postDelayed(() -> {
                    if (hooks.menuStyleEnabled() && root.isAttachedToWindow()) {
                        captureDesktopPopupGlassAfterOpen(popup);
                    }
                }, 420L);
            }
        } catch (Throwable e) {
            hooks.log(5, "Os14 applyDesktopPopupGlass failed", e);
        }
    }

    /** Light pass: strip restored row whites; reinstall only if OEM replaced glass. */
    private void reassertDesktopPopupGlass(Object popup) {
        if (!hooks.menuStyleEnabled() || !(popup instanceof ViewGroup)) return;
        keepPopupBlurTransparent(popup);
        clearDesktopPopupDividers((ViewGroup) popup);
        for (View host : collectDesktopPopupPanelHosts((ViewGroup) popup)) {
            clearDeepShortcutRowFills(host);
            if (!(host.getBackground() instanceof GlassDrawable)) {
                installDesktopPopupPanelGlass(host);
            } else {
                // Do not forceCapture on every onLayout — wallpaper-only samples can erase
                // a good open-animation frame once the menu is fully opaque.
                host.invalidate();
            }
        }
    }

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
                host.invalidate();
            } else {
                installDesktopPopupPanelGlass(host);
            }
        }
    }

    private void finishDesktopPopupGlass(Object popup) {
        if (!(popup instanceof ViewGroup)) return;
        ViewGroup root = (ViewGroup) popup;
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

    /**
     * After open anim ends: restore chrome only. Do <b>not</b> {@code forceCapture} —
     * open-anim frames already have icons; settle forceCapture races OEM
     * {@code inflateOppositeView} then 花屏 (中途松手 → 完全打开后花屏).
     */
    private void captureDesktopPopupGlassAfterOpen(Object popup) {
        if (!hooks.menuStyleEnabled() || !(popup instanceof ViewGroup)) return;
        try {
            keepPopupBlurTransparent(popup);
            clearDesktopPopupDividers((ViewGroup) popup);
            for (View host : collectDesktopPopupPanelHosts((ViewGroup) popup)) {
                clearDeepShortcutRowFills(host);
                if (!(host.getBackground() instanceof GlassDrawable)
                        && GlassInstaller.get(host) == null) {
                    installDesktopPopupPanelGlass(host);
                } else {
                    host.invalidate();
                }
            }
        } catch (Throwable e) {
            hooks.log(5, "Os14 captureDesktopPopupGlassAfterOpen failed", e);
        }
    }

    private void markDesktopPopupOpenAnimating(Object popup, boolean animating) {
        if (!(popup instanceof ViewGroup)) return;
        ViewGroup root = (ViewGroup) popup;
        java.util.HashSet<View> hosts = new java.util.HashSet<>(collectDesktopPopupPanelHosts(root));
        collectTaggedDesktopPopupHosts(root, hosts);
        for (View host : hosts) {
            try {
                GlassInstaller.setDesktopPopupOpenAnimating(host, animating);
            } catch (Throwable ignored) { }
        }
    }

    private static void collectTaggedDesktopPopupHosts(View view, java.util.HashSet<View> out) {
        if (view == null || out == null) return;
        if (TAG_DESKTOP_POPUP.equals(view.getTag())) out.add(view);
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectTaggedDesktopPopupHosts(g.getChildAt(i), out);
            }
        }
    }

    private void applyDesktopPopupGlassNow(Object popup) {
        if (!hooks.menuStyleEnabled() || !(popup instanceof ViewGroup)) return;
        keepPopupBlurTransparent(popup);
        ViewGroup root = (ViewGroup) popup;
        clearDesktopPopupDividers(root);
        java.util.ArrayList<View> hosts = collectDesktopPopupPanelHosts(root);
        uninstallNestedPopupGlass(root, hosts);
        hooks.log(4, "Os14 desktop popup glass hosts=" + hosts.size()
                + " on " + root.getClass().getSimpleName());
        for (View host : hosts) {
            installDesktopPopupPanelGlass(host);
        }
    }

    private static java.util.ArrayList<View> collectDesktopPopupPanelHosts(ViewGroup root) {
        java.util.ArrayList<View> hosts = new java.util.ArrayList<>();
        Object popup = root;
        // ColorOS 16 all-container is absent on 14 — glass each shortcut panel separately.
        addPopupPanelHost(hosts, HookReflection.field(popup, "mAppShortcutContainer"), root);
        addPopupPanelHost(hosts, HookReflection.field(popup, "mSystemShortcutContainer"), root);
        addPopupPanelHost(hosts, HookReflection.field(popup, "mDeepShortcutContainer"), root);
        addPopupPanelHost(hosts, HookReflection.field(popup, "mNotificationContainer"), root);
        collectLikelyPopupPanels(root, root, hosts);
        // populateAndShow may leave mSystemShortcutContainer == this when only app shortcuts
        // exist; if no child panel was found, glass the root itself when it has an OEM plate.
        if (hosts.isEmpty() && isLikelyPopupShortcutPanel(root)) {
            hosts.add(root);
        }
        return hosts;
    }

    private static void collectLikelyPopupPanels(
            ViewGroup root, ViewGroup group, java.util.ArrayList<View> hosts) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child == null || child.getVisibility() != View.VISIBLE) continue;
            if (hosts.contains(child) || isUnderAnyHost(child, hosts)) {
                if (child instanceof ViewGroup && !hosts.contains(child)) {
                    collectLikelyPopupPanels(root, (ViewGroup) child, hosts);
                }
                continue;
            }
            if (isLikelyPopupShortcutPanel(child)) {
                hosts.add(child);
                continue;
            }
            if (child instanceof ViewGroup) {
                collectLikelyPopupPanels(root, (ViewGroup) child, hosts);
            }
        }
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
        if (!(candidate instanceof View)) return;
        View view = (View) candidate;
        // ColorOS 14 temporarily assigns mSystemShortcutContainer = this before inflate.
        if (view == popupRoot) return;
        if (view.getVisibility() != View.VISIBLE) return;
        if (!hosts.contains(view)) hosts.add(view);
    }

    private static boolean isLikelyPopupShortcutPanel(View view) {
        if (!(view instanceof android.widget.LinearLayout)) return false;
        String name = view.getClass().getName();
        if (name.contains("PendingCard") || name.contains("Arrow") || name.contains("Space")
                || name.contains("BubbleText") || name.contains("DeepShortcut")) {
            return false;
        }
        Drawable bg = view.getBackground();
        if (bg != null && !(bg instanceof GlassDrawable) && isOpaquePanelFill(bg)) return true;
        return GlassInstaller.get(view) != null || TAG_DESKTOP_POPUP.equals(view.getTag());
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
            if (!hooks.menuStyleEnabled() || !host.isAttachedToWindow()) return;
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
            if (host.getWidth() <= 0 || host.getHeight() <= 0) return;
            GlassInstaller.setDesktopPopupOpenAnimating(host, true);
            GlassInstaller.forceCapture(host);
            host.invalidate();
        };
        if (host.getWidth() > 0 && host.getHeight() > 0) {
            install.run();
            host.post(install);
        } else {
            host.post(install);
        }
    }

    /** ColorOS 14 dimen {@code shortcut_background_radius} = 12dp. */
    private static float resolvePopupCornerFallback(View host) {
        try {
            int id = host.getResources().getIdentifier(
                    "shortcut_background_radius", "dimen", host.getContext().getPackageName());
            if (id != 0) {
                float dim = host.getResources().getDimension(id);
                if (dim > 0f) return dim;
            }
        } catch (Throwable ignored) { }
        return 12f * host.getResources().getDisplayMetrics().density;
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
            Object launcher = LegacyLauncherHooks.resolveLauncher(from);
            if (launcher == null) {
                for (View current = from; current != null; ) {
                    Object ctx = unwrapContext(current.getContext());
                    if (ctx != null && HookReflection.isClassOrSubclass(ctx, "com.android.launcher3.Launcher")) {
                        launcher = ctx;
                        break;
                    }
                    Object parent = current.getParent();
                    current = parent instanceof View ? (View) parent : null;
                }
            }
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
                    && !(child instanceof ImageView)
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
            return Color.alpha(c) > 200;
        }
        return bg instanceof android.graphics.drawable.GradientDrawable
                || bg instanceof android.graphics.drawable.RippleDrawable
                || name.contains("Smooth") || name.contains("RoundRect")
                || name.contains("ShapeDrawable");
    }

    private static boolean isDeepShortcutRow(View view) {
        String name = view.getClass().getName();
        return name.contains("DeepShortcutView") || name.contains("DeepShortcut");
    }

    private static void suppressPopupPanelOemLayer(View host) {
        Drawable bg = host.getBackground();
        if (bg == null || bg instanceof GlassDrawable) return;
        // Always drop OEM plate so SmoothRect / LayerDrawable whites cannot sit under glass.
        host.setBackground(null);
    }

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
        return null;
    }
}
