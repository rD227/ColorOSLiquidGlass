package net.z841973620.colorosliquidglass.hook;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import net.z841973620.colorosliquidglass.glass.BehindDisplayCapture;
import net.z841973620.colorosliquidglass.glass.GlassDrawable;
import net.z841973620.colorosliquidglass.glass.GlassInstaller;
import net.z841973620.colorosliquidglass.ipc.DesktopBackdropClient;
import java.lang.reflect.Method;
import static net.z841973620.colorosliquidglass.hook.HookContext.TAG;

/** SystemUI 悬浮应用菜单：安装玻璃、选择采样来源和释放任务。 */
final class FloatMenuHooks {
    private final HookContext hooks;

    FloatMenuHooks(HookContext hooks) {
        this.hooks = hooks;
    }

    /** Delayed float-menu glass applies — cancelled on dismiss so they cannot reopen ashmem. */
    private final java.util.ArrayList<Runnable> pendingFloatMenuGlass = new java.util.ArrayList<>();

    /**
     * Live float app-options menus run in SystemUI (WM Shell), not Launcher:
     * {@code FlexibleMenuManager} → {@code COUIIsolatedPopupListWindow}
     * ({@code RoundFrameLayout mMainMenuWrapper}).
     * Split-screen menus are intentionally skipped for now.
     */
    void hookSystemUiFloatMenus(ClassLoader cl) {
        hooks.after("com.oplus.flexibletask.menu.FlexibleMenuManager", cl, "showPopupWindowMenu",
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
                hooks.hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        if (hooks.menuStyleEnabled()) applyFlexibleMenuGlass(chain.getThisObject());
                    } catch (Throwable e) {
                        hooks.log(5, TAG, "FlexibleMenuManager." + name + " glass failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            hooks.log(5, TAG, "FlexibleMenuManager lambda glass hooks unavailable", e);
        }
        // Backup: prepareShowMainMenu runs right before WM.addView for float menus.
        hooks.after("com.coui.appcompat.poplist.COUIIsolatedPopupListWindow", cl, "prepareShowMainMenu",
                this::scheduleCouiPopupListGlass);
        // Re-bind DESKTOP/APP when the float bounds update while the menu is open.
        hooks.after("com.oplus.flexibletask.menu.FlexibleMenuManager", cl, "updateInfo", manager -> {
            try {
                if (!hooks.menuStyleEnabled() || !DesktopBackdropClient.isReady()) return;
                Object popup = HookReflection.field(manager, "mPopupWindow");
                View wrapper = HookReflection.asView(HookReflection.field(popup, "mMainMenuWrapper"));
                if (wrapper == null || !wrapper.isAttachedToWindow()) return;
                if (!BehindDisplayCapture.isSysUiMenuGlass(wrapper)) return;
                bindFloatMenuCapture(wrapper, manager);
                GlassInstaller.forceCapture(wrapper);
            } catch (Throwable ignored) { }
        });
        // ColorOS 16 dismiss API is dismissPopupWindowMenu (not dismiss/hide).
        hooks.after("com.oplus.flexibletask.menu.FlexibleMenuManager", cl, "dismissPopupWindowMenu",
                this::stopFloatMenuAshmem);
        hooks.after("com.oplus.flexibletask.menu.FlexibleMenuManager", cl, "dismiss",
                this::stopFloatMenuAshmem);
        hooks.after("com.oplus.flexibletask.menu.FlexibleMenuManager", cl, "hidePopupWindow",
                this::stopFloatMenuAshmem);
        hooks.after("com.oplus.flexibletask.menu.FlexibleMenuManager", cl, "hide",
                this::stopFloatMenuAshmem);
        hooks.after("com.coui.appcompat.poplist.COUIIsolatedPopupListWindow", cl, "dismiss",
                this::stopFloatMenuAshmem);
        hooks.after("com.coui.appcompat.poplist.COUIIsolatedPopupListWindow", cl, "dismissAllowingStateLoss",
                this::stopFloatMenuAshmem);
        hooks.after("android.widget.PopupWindow", cl, "dismiss", popup -> {
            try {
                if (popup != null && popup.getClass().getName().contains("COUIIsolatedPopupListWindow")) {
                    stopFloatMenuAshmem(popup);
                }
            } catch (Throwable ignored) { }
        });
    }

    private void cancelPendingFloatMenuGlass() {
        synchronized (pendingFloatMenuGlass) {
            for (Runnable r : pendingFloatMenuGlass) {
                hooks.mainHandler.removeCallbacks(r);
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
            hooks.log(5, TAG, "stopFloatMenuAshmem failed", t);
        }
    }

    private void scheduleFlexibleMenuGlass(Object manager) {
        if (!hooks.menuStyleEnabled() || manager == null) return;
        cancelPendingFloatMenuGlass();
        final int epoch = DesktopBackdropClient.sessionEpoch();
        Runnable apply = () -> {
            try {
                if (DesktopBackdropClient.sessionEpoch() != epoch) return;
                applyFlexibleMenuGlass(manager);
            } catch (Throwable e) {
                hooks.log(5, TAG, "applyFlexibleMenuGlass failed", e);
            }
        };
        trackFloatMenuGlass(apply);
        hooks.mainHandler.post(apply);
        hooks.mainHandler.postDelayed(apply, 80L);
        hooks.mainHandler.postDelayed(apply, 220L);
        hooks.mainHandler.postDelayed(apply, 480L);
    }

    private void applyFlexibleMenuGlass(Object manager) {
        if (!hooks.menuStyleEnabled() || manager == null) return;
        Object popup = HookReflection.field(manager, "mPopupWindow");
        if (popup == null) popup = HookReflection.invokeNoArgs(manager, "getPopupWindow");
        applyCouiPopupListGlass(popup, manager);
    }

    private void scheduleCouiPopupListGlass(Object popup) {
        if (!hooks.menuStyleEnabled() || popup == null) return;
        cancelPendingFloatMenuGlass();
        final int epoch = DesktopBackdropClient.sessionEpoch();
        Runnable apply = () -> {
            try {
                if (DesktopBackdropClient.sessionEpoch() != epoch) return;
                applyCouiPopupListGlass(popup, null);
            } catch (Throwable e) {
                hooks.log(5, TAG, "applyCouiPopupListGlass failed", e);
            }
        };
        trackFloatMenuGlass(apply);
        hooks.mainHandler.post(apply);
        hooks.mainHandler.postDelayed(apply, 80L);
        hooks.mainHandler.postDelayed(apply, 220L);
    }

    private void applyCouiPopupListGlass(Object popup, Object flexibleManager) {
        if (!hooks.menuStyleEnabled() || popup == null) return;
        try {
            HookReflection.invoke(popup, "setUseBackgroundBlur", new Class<?>[] { boolean.class }, false);
        } catch (Throwable ignored) { }
        View resolvedWrapper = HookReflection.asView(HookReflection.field(popup, "mMainMenuWrapper"));
        View resolvedList = HookReflection.asView(HookReflection.field(popup, "mMainListView"));
        if (resolvedList == null) resolvedList = HookReflection.asView(HookReflection.invokeNoArgs(popup, "getMainMenuListView"));
        if (resolvedWrapper == null && resolvedList != null) {
            Object parent = resolvedList.getParent();
            if (parent instanceof View) resolvedWrapper = (View) parent;
        }
        if (resolvedWrapper == null) {
            View content = HookReflection.asView(HookReflection.invokeNoArgs(popup, "getContentView"));
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
        GlassInstaller.installBackground(glassHost, hooks.currentConfig());
        final int epoch = DesktopBackdropClient.sessionEpoch();
        Runnable refresh = () -> {
            if (DesktopBackdropClient.sessionEpoch() != epoch) return;
            if (!glassHost.isAttachedToWindow()) return;
            try {
                HookReflection.invoke(popup, "setUseBackgroundBlur", new Class<?>[] { boolean.class }, false);
            } catch (Throwable ignored) { }
            clearCouiPopupOpaqueChrome(wrapper, listView);
            if (!(glassHost.getBackground() instanceof GlassDrawable)) {
                GlassInstaller.installBackground(glassHost, hooks.currentConfig());
            }
            GlassDrawable live = GlassInstaller.get(glassHost);
            if (live == null) return;
            float radius = readCouiPopupRadius(glassHost);
            if (radius > 0f) live.setCornerRadii(radius, radius, radius, radius);
            bindFloatMenuCapture(glassHost, flexibleManager);
            if (glassHost.getWidth() <= 0 || glassHost.getHeight() <= 0) return;
            GlassInstaller.forceCapture(glassHost);
            glassHost.invalidate();
            hooks.log(4, TAG, "Flexible/COUI popup glass ready size="
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
            Object boundsObj = HookReflection.field(flexibleManager, "mCurrentBounds");
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
                    hooks.log(4, TAG, "float menu capture APP (no max menu region)");
                    return;
                }
                BehindDisplayCapture.bindDesktop(glassHost, floatBounds, maxMenu, true);
                hooks.log(4, TAG, "float menu capture DESKTOP region=" + maxMenu.toShortString());
            } else {
                BehindDisplayCapture.bindApp(glassHost, floatBounds);
                hooks.log(4, TAG, "float menu capture APP above=" + above
                        + " launcherUnder=" + launcherUnder
                        + " homeDesktop=" + homeDesktop);
            }
        } catch (Throwable t) {
            hooks.log(5, TAG, "bindFloatMenuCapture failed", t);
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
            Object info = HookReflection.field(flexibleManager, "mTaskInfo");
            if (info == null) return false;
            Object top = HookReflection.field(info, "topActivity");
            if (!(top instanceof android.content.ComponentName)) {
                top = HookReflection.field(info, "realActivity");
            }
            if (!(top instanceof android.content.ComponentName)) {
                top = HookReflection.field(info, "baseActivity");
            }
            if (top instanceof android.content.ComponentName) {
                return isLauncherPackage(((android.content.ComponentName) top).getPackageName());
            }
            Object intent = HookReflection.field(info, "baseIntent");
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
        Object wObj = HookReflection.field(flexibleManager, "mMenuWidth");
        Object hObj = HookReflection.field(flexibleManager, "mMenuHeight");
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
        Object menuHObj = HookReflection.field(flexibleManager, "mMenuHeight");
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
                HookReflection.invoke(wrapper, "setClipMode", new Class<?>[] { int.class }, 0);
            } catch (Throwable ignored) { }
            if (!(wrapper.getBackground() instanceof GlassDrawable)) {
                wrapper.setBackground(null);
            }
        }
    }

    private static void disableCouiBackgroundBlur(View wrapper) {
        if (wrapper == null) return;
        try {
            Object builder = HookReflection.field(wrapper, "mBackgroundBlurBuilder");
            if (builder != null) {
                HookReflection.setField(builder, "mUseBackgroundBlur", false);
                try { HookReflection.invokeNoArgs(builder, "release"); } catch (Throwable ignored) { }
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
}
