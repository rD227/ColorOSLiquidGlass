package net.z841973620.colorosliquidglass.hook;

import android.view.View;
import android.view.ViewGroup;
import net.z841973620.colorosliquidglass.glass.GlassDrawable;
import net.z841973620.colorosliquidglass.glass.GlassInstaller;
import java.lang.reflect.Method;

/** ColorOS 13/14 最近任务快捷菜单、清除按钮与手势面板。 */
final class LegacyRecentsHooks {
    private final HookContext hooks;
    private final LegacyLauncherHooks launcherHooks;

    LegacyRecentsHooks(HookContext hooks, LegacyLauncherHooks launcherHooks) {
        this.hooks = hooks;
        this.launcherHooks = launcherHooks;
    }


    // ── Recents clear / toggle bar / IPC (shared glass installers) ──────────

    void hookRecentsClearButton(ClassLoader cl) {
        hooks.after(cl, "com.oplus.quickstep.views.OplusClearAllPanelView", "onFinishInflate",
                this::applyClearButtonGlass);
        hooks.after(cl, "com.oplus.quickstep.views.OplusClearAllPanelView", "onAttachedToWindow",
                this::applyClearButtonGlass);
        try {
            Class<?> btn = Class.forName("com.android.launcher.views.PressFeedbackButton", false, cl);
            for (Method m : btn.getDeclaredMethods()) {
                if (!m.getName().equals("setSrcDrawable") || m.isBridge() || m.isSynthetic()) continue;
                hooks.hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        View view = (View) chain.getThisObject();
                        if (hooks.enabled() && GlassInstaller.get(view) != null) {
                            HookReflection.setField(view, "mSrcDrawable", null);
                            view.invalidate();
                        }
                    } catch (Throwable ignored) { }
                    return result;
                });
            }
            for (Method m : btn.getDeclaredMethods()) {
                if (!m.getName().equals("onDraw") || m.getParameterCount() != 1
                        || m.isBridge() || m.isSynthetic()) continue;
                hooks.hookOnce(m, chain -> {
                    Object self = chain.getThisObject();
                    if (!(self instanceof View) || !hooks.enabled() || GlassInstaller.get((View) self) == null) {
                        return chain.proceed();
                    }
                    Object handler = HookReflection.field(self, "mPressFeedbackHandler");
                    Object savedAlpha = handler != null ? HookReflection.field(handler, "mCurrentColorAlpha") : null;
                    if (handler != null) HookReflection.setField(handler, "mCurrentColorAlpha", 0);
                    HookReflection.setField(self, "mSrcDrawable", null);
                    try {
                        return chain.proceed();
                    } finally {
                        if (handler != null && savedAlpha != null) {
                            HookReflection.setField(handler, "mCurrentColorAlpha", savedAlpha);
                        }
                    }
                });
            }
        } catch (Throwable e) {
            hooks.log(5, "Os14 PressFeedbackButton hooks unavailable", e);
        }
    }

    private void applyClearButtonGlass(Object panel) {
        if (!hooks.enabled() || panel == null) return;
        Object btn = HookReflection.field(panel, "mClearAllBtn");
        if (btn == null && panel instanceof ViewGroup) {
            btn = findPressFeedbackButton((View) panel);
        }
        if (!(btn instanceof View)) return;
        View button = (View) btn;
        try {
            HookReflection.setField(button, "mSrcDrawable", null);
            GlassInstaller.installBackground(button, hooks.currentConfig());
            GlassDrawable glass = GlassInstaller.get(button);
            if (glass != null) {
                float density = button.getResources().getDisplayMetrics().density;
                float r = 24f * density;
                glass.setCornerRadii(r, r, r, r);
            }
            button.invalidate();
        } catch (Throwable e) {
            hooks.log(5, "Os14 applyClearButtonGlass failed", e);
        }
    }

    private static View findPressFeedbackButton(View root) {
        if (root == null) return null;
        if (root.getClass().getName().equals("com.android.launcher.views.PressFeedbackButton")) {
            return root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = findPressFeedbackButton(g.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    // ── Overview 浮窗 / 分屏 / TaskMenu ──────────────────────────────────────

    private static final Object TAG_TASK_HEADER_GLASS = new Object();

    void hookRecentsTaskShortcuts(ClassLoader cl) {
        hooks.after(cl, "com.oplus.quickstep.views.OplusTaskHeaderView", "onFinishInflate",
                this::applyTaskHeaderShortcutGlass);
        try {
            Class<?> header = Class.forName(
                    "com.oplus.quickstep.views.OplusTaskHeaderView", false, cl);
            for (Method m : header.getDeclaredMethods()) {
                if (m.isBridge() || m.isSynthetic()) continue;
                String name = m.getName();
                if (name.equals("showWindowIcon")) {
                    hooks.hookOnce(m, chain -> {
                        Object result = chain.proceed();
                        try {
                            if (hooks.enabled() && chain.getArgs().size() >= 1) {
                                applyTaskHeaderShortcutButtonGlass(chain.getArg(0));
                            }
                        } catch (Throwable e) {
                            hooks.log(5, "Os14 showWindowIcon glass failed", e);
                        }
                        return result;
                    });
                } else if (name.equals("hideWindowIcon")) {
                    hooks.hookOnce(m, chain -> {
                        Object result = chain.proceed();
                        try {
                            if (chain.getArgs().size() >= 1) {
                                removeTaskHeaderShortcutButtonGlass(chain.getArg(0));
                            }
                        } catch (Throwable e) {
                            hooks.log(5, "Os14 hideWindowIcon glass remove failed", e);
                        }
                        return result;
                    });
                }
            }
        } catch (Throwable e) {
            hooks.log(5, "Os14 OplusTaskHeaderView show/hide glass unavailable", e);
        }
        hooks.after(cl, "com.oplus.quickstep.views.OplusTaskHeaderView", "onDetachedFromWindow", header -> {
            removeTaskHeaderShortcutButtonGlass(HookReflection.field(header, "miniWindowBtn"));
            removeTaskHeaderShortcutButtonGlass(HookReflection.field(header, "splitWindowBtn"));
        });

        hooks.after(cl, "com.android.quickstep.views.OplusTaskMenuViewImpl", "addMenuOptions",
                this::applyTaskMenuGlass);
        hooks.after(cl, "com.android.quickstep.views.OplusTaskMenuViewImpl", "animateOpen",
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
                hooks.hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        if (hooks.enabled()) applyTaskMenuGlass(chain.getThisObject());
                    } catch (Throwable e) {
                        hooks.log(5, "Os14 TaskMenu." + name + " glass failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            hooks.log(5, "Os14 OplusTaskMenuViewImpl glass unavailable", e);
        }

        hookOs14RapidReactionGlass(cl);
    }

    private void applyTaskHeaderShortcutGlass(Object header) {
        if (!hooks.enabled() || header == null) return;
        applyTaskHeaderShortcutButtonGlass(HookReflection.field(header, "miniWindowBtn"));
        applyTaskHeaderShortcutButtonGlass(HookReflection.field(header, "splitWindowBtn"));
    }

    private void applyTaskHeaderShortcutButtonGlass(Object buttonObj) {
        if (!hooks.enabled() || !(buttonObj instanceof View)) return;
        final View button = (View) buttonObj;
        if (button.getVisibility() != View.VISIBLE) {
            removeTaskHeaderShortcutButtonGlass(button);
            return;
        }
        try {
            // Drop any stale glass first so reopen never stacks backgrounds.
            if (button.getTag() != TAG_TASK_HEADER_GLASS && GlassInstaller.get(button) != null) {
                GlassInstaller.uninstall(button);
            }
            GlassDrawable existing = GlassInstaller.get(button);
            if (existing != null && button.getTag() == TAG_TASK_HEADER_GLASS
                    && button.getBackground() == existing) {
                refreshTaskHeaderButtonGlass(button, existing);
                return;
            }
            if (existing != null && button.getBackground() != existing) {
                GlassInstaller.uninstall(button);
            }
            GlassInstaller.installBackground(button, hooks.currentConfig());
            button.setTag(TAG_TASK_HEADER_GLASS);
            GlassDrawable live = GlassInstaller.get(button);
            if (live != null) refreshTaskHeaderButtonGlass(button, live);
            else {
                button.post(() -> {
                    if (button.getVisibility() != View.VISIBLE) {
                        removeTaskHeaderShortcutButtonGlass(button);
                        return;
                    }
                    GlassInstaller.installBackground(button, hooks.currentConfig());
                    button.setTag(TAG_TASK_HEADER_GLASS);
                    GlassDrawable g = GlassInstaller.get(button);
                    if (g != null) refreshTaskHeaderButtonGlass(button, g);
                });
            }
        } catch (Throwable e) {
            hooks.log(5, "Os14 applyTaskHeaderShortcutButtonGlass failed", e);
        }
    }

    private void removeTaskHeaderShortcutButtonGlass(Object buttonObj) {
        if (!(buttonObj instanceof View)) return;
        View button = (View) buttonObj;
        try {
            if (GlassInstaller.get(button) != null
                    || button.getBackground() instanceof GlassDrawable
                    || button.getTag() == TAG_TASK_HEADER_GLASS) {
                GlassInstaller.uninstall(button);
                if (button.getBackground() instanceof GlassDrawable) {
                    button.setBackground(null);
                }
                button.setTag(null);
                button.invalidate();
            }
        } catch (Throwable e) {
            hooks.log(5, "Os14 removeTaskHeaderShortcutButtonGlass failed", e);
        }
    }

    private void refreshTaskHeaderButtonGlass(View button, GlassDrawable live) {
        float radius = Math.min(button.getWidth(), button.getHeight()) / 2f;
        if (radius <= 0f) {
            radius = 14f * button.getResources().getDisplayMetrics().density;
        }
        live.setCornerRadii(radius, radius, radius, radius);
        if (button.getWidth() > 0 && button.getHeight() > 0) {
            GlassInstaller.forceCapture(button);
            button.invalidate();
        }
    }

    private void applyTaskMenuGlass(Object menu) {
        if (!hooks.menuStyleEnabled() || !(menu instanceof View)) return;
        try {
            Object listObj = HookReflection.field(menu, "mListView");
            View listView = listObj instanceof View ? (View) listObj : null;
            clearTaskMenuOpaqueChrome(menu, listView);
            View glassHost = listView != null ? listView : (View) menu;
            if (listView != null && ((View) menu).getBackground() instanceof GlassDrawable) {
                GlassInstaller.uninstall((View) menu);
            }
            if (menu != glassHost) ((View) menu).setBackground(null);
            GlassInstaller.installBackground(glassHost, hooks.currentConfig());
            Object taskView = HookReflection.field(menu, "mTaskView");
            if (!(taskView instanceof View)) taskView = HookReflection.invokeNoArgs(menu, "getTaskView");
            if (taskView instanceof View) {
                GlassInstaller.setOverlaySource(glassHost, (View) taskView);
                net.z841973620.colorosliquidglass.glass.TaskContentOverlay.prebakeProtect((View) taskView);
            }
            final View host = glassHost;
            final View taskOverlay = taskView instanceof View ? (View) taskView : null;
            Runnable refresh = () -> {
                clearTaskMenuOpaqueChrome(menu, listView);
                if (!(host.getBackground() instanceof GlassDrawable)) {
                    GlassInstaller.installBackground(host, hooks.currentConfig());
                }
                GlassDrawable live = GlassInstaller.get(host);
                if (live == null) return;
                float radius = 16f * host.getResources().getDisplayMetrics().density;
                live.setCornerRadii(radius, radius, radius, radius);
                if (host.getWidth() <= 0 || host.getHeight() <= 0) return;
                if (taskOverlay != null) GlassInstaller.setOverlaySource(host, taskOverlay);
                GlassInstaller.forceCapture(host);
                host.invalidate();
            };
            if (host.getWidth() > 0 && host.getHeight() > 0) {
                refresh.run();
                host.post(refresh);
            } else {
                host.post(refresh);
            }
        } catch (Throwable e) {
            hooks.log(5, "Os14 applyTaskMenuGlass failed", e);
        }
    }

    private void clearTaskMenuOpaqueChrome(Object menu, View listView) {
        if (listView != null) {
            if (!(listView.getBackground() instanceof GlassDrawable)) listView.setBackground(null);
            Object parent = listView.getParent();
            if (parent instanceof View) {
                View roundFrame = (View) parent;
                if (!(roundFrame.getBackground() instanceof GlassDrawable)) {
                    roundFrame.setBackground(null);
                }
                try {
                    HookReflection.invoke(roundFrame, "setClipMode", new Class<?>[] { int.class }, 0);
                } catch (Throwable ignored) { }
            }
        }
        if (menu instanceof View) {
            View menuView = (View) menu;
            if (!(menuView.getBackground() instanceof GlassDrawable)) menuView.setBackground(null);
        }
    }

    private void hookOs14RapidReactionGlass(ClassLoader cl) {
        // ColorOS 14: TriggerPanelView + RectangleBackgroundView.
        // Swipe-up grows the capsule via RectangleBackgroundView.scaleFraction (onDraw);
        // reset() sets INVISIBLE. Glass hosts are tagged children: resize only relayouts,
        // close fully removes — never leave stale hosts for the next enter.
        try {
            Class<?> panel = Class.forName(
                    "com.oplus.quickstep.rapidreaction.widget.TriggerPanelView", false, cl);
            for (Method m : panel.getDeclaredMethods()) {
                if (m.isBridge() || m.isSynthetic()) continue;
                String name = m.getName();
                if (name.equals("onLayout") || name.equals("updateProgress")
                        || name.equals("initAnimation") || name.equals("setPanelType")) {
                    hooks.hookOnce(m, chain -> {
                        Object result = chain.proceed();
                        try {
                            syncOs14RapidReactionGlass(chain.getThisObject());
                        } catch (Throwable e) {
                            hooks.log(5, "Os14 TriggerPanelView." + name + " glass failed", e);
                        }
                        return result;
                    });
                } else if (name.equals("reset") || name.equals("resetIfNeed")) {
                    hooks.hookOnce(m, chain -> {
                        Object self = chain.getThisObject();
                        Object result = chain.proceed();
                        try {
                            removeOs14RapidReactionGlass(self);
                        } catch (Throwable e) {
                            hooks.log(5, "Os14 TriggerPanelView." + name + " remove glass failed", e);
                        }
                        return result;
                    });
                } else if (name.equals("onDetachedFromWindow")) {
                    hooks.hookOnce(m, chain -> {
                        try {
                            removeOs14RapidReactionGlass(chain.getThisObject());
                        } catch (Throwable ignored) { }
                        return chain.proceed();
                    });
                }
            }
        } catch (Throwable e) {
            hooks.log(5, "Os14 TriggerPanelView glass unavailable", e);
        }
        try {
            Class<?> bg = Class.forName(
                    "com.oplus.quickstep.rapidreaction.widget.RectangleBackgroundView", false, cl);
            for (Method m : bg.getDeclaredMethods()) {
                if (m.isBridge() || m.isSynthetic()) continue;
                String name = m.getName();
                if (name.equals("onDraw") && m.getParameterCount() == 1) {
                    hooks.hookOnce(m, chain -> {
                        try {
                            if (hooks.enabled()) clearOs14RapidOpaqueFills(chain.getThisObject());
                        } catch (Throwable ignored) { }
                        Object result = chain.proceed();
                        try {
                            // Rects are scaled in onDraw (scaleFraction) — resize glass after.
                            if (hooks.enabled()) {
                                Object parent = chain.getThisObject() instanceof View
                                        ? ((View) chain.getThisObject()).getParent() : null;
                                if (HookReflection.isClassOrSubclass(parent,
                                        "com.oplus.quickstep.rapidreaction.widget.TriggerPanelView")) {
                                    syncOs14RapidReactionGlass(parent);
                                }
                            }
                        } catch (Throwable ignored) { }
                        return result;
                    });
                } else if (name.equals("setScaleX") || name.equals("setScaleY")
                        || name.equals("reset")) {
                    hooks.hookOnce(m, chain -> {
                        Object result = chain.proceed();
                        try {
                            if (hooks.enabled()) {
                                Object parent = chain.getThisObject() instanceof View
                                        ? ((View) chain.getThisObject()).getParent() : null;
                                if (HookReflection.isClassOrSubclass(parent,
                                        "com.oplus.quickstep.rapidreaction.widget.TriggerPanelView")) {
                                    syncOs14RapidReactionGlass(parent);
                                }
                            }
                        } catch (Throwable ignored) { }
                        return result;
                    });
                }
            }
        } catch (Throwable e) {
            hooks.log(5, "Os14 RectangleBackgroundView glass unavailable", e);
        }
    }

    private static final Object TAG_RAPID_FLOAT_LEFT = new Object();
    private static final Object TAG_RAPID_FLOAT_RIGHT = new Object();

    private void syncOs14RapidReactionGlass(Object panel) {
        if (!(panel instanceof ViewGroup)) return;
        ViewGroup host = (ViewGroup) panel;
        // reset() sets INVISIBLE (4) and alreadyReset=true. Do not use alpha here —
        // enter animation starts VISIBLE at alpha 0 while the capsule is growing.
        if (!hooks.enabled() || host.getVisibility() != View.VISIBLE) {
            removeOs14RapidReactionGlass(panel);
            return;
        }
        Object alreadyReset = HookReflection.field(panel, "alreadyReset");
        if (Boolean.TRUE.equals(alreadyReset)) {
            removeOs14RapidReactionGlass(panel);
            return;
        }
        applyOs14RapidReactionGlass(panel);
    }

    private void applyOs14RapidReactionGlass(Object panel) {
        if (!hooks.enabled() || !(panel instanceof ViewGroup)) return;
        ViewGroup host = (ViewGroup) panel;
        try {
            Object bg = HookReflection.field(panel, "hintBackgroundView");
            if (!(bg instanceof View)) return;
            clearOs14RapidOpaqueFills(bg);
            float radius = 0f;
            Object r = HookReflection.field(bg, "bgRectangleCornerRadius");
            if (r instanceof Number) radius = ((Number) r).floatValue();
            if (radius <= 0f) {
                radius = 20f * ((View) bg).getResources().getDisplayMetrics().density;
            }
            // One tagged host per rect. Swipe-up resize only relayouts — never addView again.
            installOs14RapidGlassForRect(host, (View) bg, HookReflection.field(bg, "basicAndLeftRect"),
                    TAG_RAPID_FLOAT_LEFT, radius);
            Object panelType = HookReflection.field(panel, "panelType");
            boolean dual = panelType instanceof Number && ((Number) panelType).intValue() != 1;
            if (dual) {
                installOs14RapidGlassForRect(host, (View) bg, HookReflection.field(bg, "rightRect"),
                        TAG_RAPID_FLOAT_RIGHT, radius);
            } else {
                removeOs14RapidGlassHost(host, TAG_RAPID_FLOAT_RIGHT);
            }
        } catch (Throwable e) {
            hooks.log(5, "Os14 applyOs14RapidReactionGlass failed", e);
        }
    }

    private void removeOs14RapidReactionGlass(Object panel) {
        if (!(panel instanceof ViewGroup)) return;
        ViewGroup host = (ViewGroup) panel;
        try {
            removeOs14RapidGlassHost(host, TAG_RAPID_FLOAT_LEFT);
            removeOs14RapidGlassHost(host, TAG_RAPID_FLOAT_RIGHT);
            // Sweep any leftover glass children from earlier buggy installs.
            for (int i = host.getChildCount() - 1; i >= 0; i--) {
                View child = host.getChildAt(i);
                if (child == null) continue;
                Object tag = child.getTag();
                boolean ours = tag == TAG_RAPID_FLOAT_LEFT || tag == TAG_RAPID_FLOAT_RIGHT;
                // Only strip tagged hosts or plain View overlays that still carry GlassDrawable.
                if (ours || (child.getClass() == View.class
                        && (GlassInstaller.get(child) != null
                        || child.getBackground() instanceof GlassDrawable))) {
                    GlassInstaller.uninstall(child);
                    try {
                        host.removeView(child);
                    } catch (Throwable ignored) { }
                }
            }
        } catch (Throwable e) {
            hooks.log(5, "Os14 removeOs14RapidReactionGlass failed", e);
        }
    }

    private void installOs14RapidGlassForRect(ViewGroup panel, View bg, Object rectObj,
            Object tag, float radius) {
        if (!(rectObj instanceof android.graphics.RectF)) return;
        android.graphics.RectF rect = (android.graphics.RectF) rectObj;
        if (rect.width() < 2f || rect.height() < 2f) return;
        android.graphics.RectF local = new android.graphics.RectF(rect);
        local.offset(bg.getLeft(), bg.getTop());
        View glassHost = findOs14RapidGlassHost(panel, tag);
        if (glassHost == null) {
            glassHost = new View(panel.getContext());
            glassHost.setTag(tag);
            glassHost.setClickable(false);
            glassHost.setFocusable(false);
            glassHost.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            try {
                int index = panel.indexOfChild(bg);
                if (index >= 0) panel.addView(glassHost, index + 1);
                else panel.addView(glassHost, 0);
            } catch (Throwable e) {
                hooks.log(5, "Os14 rapid glass host attach failed", e);
                return;
            }
        }
        int left = Math.round(local.left);
        int top = Math.round(local.top);
        int right = Math.round(local.right);
        int bottom = Math.round(local.bottom);
        int prevW = glassHost.getWidth();
        int prevH = glassHost.getHeight();
        glassHost.measure(
                View.MeasureSpec.makeMeasureSpec(Math.max(1, right - left), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(Math.max(1, bottom - top), View.MeasureSpec.EXACTLY));
        glassHost.layout(left, top, right, bottom);
        glassHost.setVisibility(View.VISIBLE);
        GlassInstaller.setOverlaySource(glassHost, null);
        GlassDrawable existing = GlassInstaller.get(glassHost);
        boolean firstInstall = existing == null || glassHost.getBackground() != existing;
        if (firstInstall) {
            GlassInstaller.installBackground(glassHost, hooks.currentConfig());
            existing = GlassInstaller.get(glassHost);
        }
        if (existing != null && radius > 0f) {
            existing.setCornerRadii(radius, radius, radius, radius);
        }
        boolean sizeChanged = glassHost.getWidth() != prevW || glassHost.getHeight() != prevH;
        if (firstInstall || sizeChanged) {
            GlassInstaller.forceCapture(glassHost);
        }
        glassHost.invalidate();
    }

    private static View findOs14RapidGlassHost(ViewGroup panel, Object tag) {
        for (int i = 0; i < panel.getChildCount(); i++) {
            View child = panel.getChildAt(i);
            if (child != null && child.getTag() == tag) return child;
        }
        return null;
    }

    private void removeOs14RapidGlassHost(ViewGroup panel, Object tag) {
        View glassHost = findOs14RapidGlassHost(panel, tag);
        if (glassHost == null) return;
        try {
            GlassInstaller.uninstall(glassHost);
            panel.removeView(glassHost);
        } catch (Throwable ignored) { }
    }

    private static void clearOs14RapidOpaqueFills(Object bgView) {
        for (String paintName : new String[] {
                "basicAndLeftPaint", "rightPaint",
                "basicAndLeftStrokePaint", "rightStrokePaint"
        }) {
            Object paint = HookReflection.field(bgView, paintName);
            if (paint instanceof android.graphics.Paint) {
                ((android.graphics.Paint) paint).setColor(0);
            }
        }
        HookReflection.setField(bgView, "currentBasicAndLeftColor", 0);
        HookReflection.setField(bgView, "currentRightColor", 0);
        HookReflection.setField(bgView, "currentBasicAndLeftStrokeColor", 0);
        HookReflection.setField(bgView, "currentRightStrokeColor", 0);
    }
}
