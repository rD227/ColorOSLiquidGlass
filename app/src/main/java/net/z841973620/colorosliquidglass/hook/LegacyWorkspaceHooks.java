package net.z841973620.colorosliquidglass.hook;

import android.view.View;
import android.view.ViewGroup;
import net.z841973620.colorosliquidglass.glass.GlassDrawable;
import net.z841973620.colorosliquidglass.glass.GlassInstaller;
import java.lang.reflect.Method;

/** ColorOS 13/14 桌面编辑、页面预览、分页指示器和工具栏。 */
final class LegacyWorkspaceHooks {
    private final HookContext hooks;
    private final LegacyLauncherHooks launcherHooks;

    LegacyWorkspaceHooks(HookContext hooks, LegacyLauncherHooks launcherHooks) {
        this.hooks = hooks;
        this.launcherHooks = launcherHooks;
    }


    void hookToggleBarChrome(ClassLoader cl) {
        hooks.after(cl, "com.android.launcher.togglebar.views.ToggleStateToolbar", "onFinishInflate",
                this::applyToggleBarToolbarGlass);
        hooks.after(cl, "com.android.launcher.togglebar.views.ToggleStateToolbar", "onAttachedToWindow",
                this::applyToggleBarToolbarGlass);
        hooks.after(cl, "com.android.launcher.togglebar.views.PressFeedbackLinearLayout", "onFinishInflate",
                this::applyToggleBarItemGlass);
        hooks.after(cl, "com.android.launcher.togglebar.views.PressFeedbackLinearLayout", "onAttachedToWindow",
                this::applyToggleBarItemGlass);
        try {
            Class<?> adapter = Class.forName(
                    "com.android.launcher.togglebar.adapter.ToggleBarMainUIAdapter", false, cl);
            for (Method m : adapter.getDeclaredMethods()) {
                if (!m.getName().equals("onBindViewHolder") || m.isBridge() || m.isSynthetic()) continue;
                hooks.hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        if (hooks.enabled()) applyToggleBarItemGlass(HookReflection.field(chain.getArg(0), "itemView"));
                    } catch (Throwable e) {
                        hooks.log(5, "Os14 ToggleBarMainUIAdapter glass failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            hooks.log(5, "Os14 ToggleBarMainUIAdapter unavailable", e);
        }
        try {
            Class<?> handler = Class.forName(
                    "com.android.launcher.togglebar.animation.PressFeedbackHandler", false, cl);
            for (Method m : handler.getDeclaredMethods()) {
                String name = m.getName();
                if (!(name.equals("drawCircleColor") || name.equals("drawPressedColor"))
                        || m.isBridge() || m.isSynthetic()) continue;
                hooks.hookOnce(m, chain -> {
                    if (hooks.enabled()) {
                        Object view = HookReflection.field(chain.getThisObject(), "mView");
                        if (view instanceof View && GlassInstaller.get((View) view) != null) {
                            return null;
                        }
                    }
                    return chain.proceed();
                });
            }
        } catch (Throwable e) {
            hooks.log(5, "Os14 PressFeedbackHandler fill skip unavailable", e);
        }
    }

    /**
     * ColorOS 14 「布局」/「翻页」二级预览卡：OEM 用 {@code PressFeedbackPreviewWrapper}
     * 画实心磨砂底。装玻璃并关掉 fill，保留选中描边。
     */
    void hookToggleBarSecondLevelPreviewGlass(ClassLoader cl) {
        hooks.after(cl, "com.android.launcher.togglebar.views.ToggleBarLayoutItemPreview", "onLayout",
                this::applyToggleBarPreviewItemGlass);
        hooks.after(cl, "com.android.launcher.togglebar.views.ToggleBarLayoutItemPreview", "onAttachedToWindow",
                this::applyToggleBarPreviewItemGlass);
        hooks.after(cl, "com.android.launcher.togglebar.views.ToggleBarEffectItemContainer", "onLayout",
                this::applyToggleBarPreviewItemGlass);
        hooks.after(cl, "com.android.launcher.togglebar.views.ToggleBarEffectItemContainer", "onAttachedToWindow",
                this::applyToggleBarPreviewItemGlass);
        hooks.after(cl, "com.android.launcher.togglebar.views.TwoPanelPagepreviewItemContainer", "onLayout",
                this::applyToggleBarPreviewItemGlass);
        hooks.after(cl, "com.android.launcher.togglebar.views.TwoPanelPagepreviewItemContainer", "onAttachedToWindow",
                this::applyToggleBarPreviewItemGlass);

        try {
            Class<?> layoutAdapter = Class.forName(
                    "com.android.launcher.togglebar.adapter.ToggleBarLayoutAdapter", false, cl);
            for (Method m : layoutAdapter.getDeclaredMethods()) {
                if (!m.getName().equals("onBindViewHolder") || m.isBridge() || m.isSynthetic()) continue;
                hooks.hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        if (hooks.enabled()) applyToggleBarPreviewItemGlass(HookReflection.field(chain.getArg(0), "itemView"));
                    } catch (Throwable e) {
                        hooks.log(5, "Os14 ToggleBarLayoutAdapter glass failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            hooks.log(5, "Os14 ToggleBarLayoutAdapter unavailable", e);
        }
        try {
            Class<?> effectAdapter = Class.forName(
                    "com.android.launcher.togglebar.adapter.ToggleBarEffectAdapter", false, cl);
            for (Method m : effectAdapter.getDeclaredMethods()) {
                if (!m.getName().equals("onBindViewHolder") || m.isBridge() || m.isSynthetic()) continue;
                hooks.hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        if (hooks.enabled()) applyToggleBarPreviewItemGlass(HookReflection.field(chain.getArg(0), "itemView"));
                    } catch (Throwable e) {
                        hooks.log(5, "Os14 ToggleBarEffectAdapter glass failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            hooks.log(5, "Os14 ToggleBarEffectAdapter unavailable", e);
        }

        try {
            Class<?> wrapper = Class.forName(
                    "com.android.launcher.togglebar.PressFeedbackPreviewWrapper", false, cl);
            for (Method m : wrapper.getDeclaredMethods()) {
                if (!m.getName().equals("onDraw") || m.getParameterCount() != 1
                        || m.isBridge() || m.isSynthetic()) continue;
                hooks.hookOnce(m, chain -> {
                    if (hooks.enabled()) {
                        Object view = HookReflection.field(chain.getThisObject(), "mView");
                        if (view instanceof View && isToggleBarSecondLevelPreviewHost(view)) {
                            HookReflection.setField(chain.getThisObject(), "mIsNeedDrawPressColor", false);
                            HookReflection.invoke(chain.getThisObject(), "setIsNeedDrawPressColor",
                                    new Class<?>[] { boolean.class }, false);
                            applyToggleBarPreviewItemGlass(view);
                        } else if (view instanceof View
                                && HookReflection.isClassOrSubclass(view,
                                "com.android.launcher.pagepreview.PagePreviewItemView")
                                && isPagePreviewChromeActive(view)) {
                            HookReflection.setField(chain.getThisObject(), "mIsNeedDrawPressColor", false);
                            HookReflection.invoke(chain.getThisObject(), "setIsNeedDrawPressColor",
                                    new Class<?>[] { boolean.class }, false);
                            applyPagePreviewItemGlass(view);
                        }
                    }
                    return chain.proceed();
                });
            }
        } catch (Throwable e) {
            hooks.log(5, "Os14 PressFeedbackPreviewWrapper second-level glass unavailable", e);
        }
    }

    /**
     * ColorOS 14 二级「应用」按钮挂在 {@code toggle_bar_root} 内容里，不在 Toolbar 字段。
     * 用 initLayoutAnimation / setDrawableColor / onAttached 多路径兜底。
     */
    void hookToggleBarApplyButtonGlass(ClassLoader cl) {
        hooks.after(cl, "com.android.launcher.togglebar.controller.ToggleBarLayoutUIController", "createView",
                this::applySecondLevelApplyButtonGlass);
        hooks.after(cl, "com.android.launcher.togglebar.controller.ToggleBarEffectUIController", "createView",
                this::applySecondLevelApplyButtonGlass);
        hooks.after(cl, "com.android.launcher.togglebar.controller.ToggleBarLayoutUIController", "resume",
                this::applySecondLevelApplyButtonGlass);
        hooks.after(cl, "com.android.launcher.togglebar.controller.ToggleBarEffectUIController", "resume",
                this::applySecondLevelApplyButtonGlass);

        try {
            Class<?> rv = Class.forName(
                    "com.android.launcher.togglebar.views.ToggleBarRecyclerView", false, cl);
            for (Method m : rv.getDeclaredMethods()) {
                if (!m.getName().equals("initLayoutAnimation") || m.isBridge() || m.isSynthetic()) {
                    continue;
                }
                hooks.hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        if (hooks.enabled() && chain.getArgs().size() >= 1) {
                            Object btn = chain.getArg(0);
                            if (isApplyChangeButton(btn) || isPressFeedbackButton(btn)) {
                                // Layout/effect pass apply_change; page-preview may pass btn container.
                                if (btn instanceof ViewGroup) {
                                    applyPressFeedbackButtonsUnder((View) btn);
                                } else {
                                    applyPressFeedbackGlass(btn);
                                }
                            }
                        }
                    } catch (Throwable e) {
                        hooks.log(5, "Os14 ToggleBarRecyclerView.initLayoutAnimation glass failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            hooks.log(5, "Os14 ToggleBarRecyclerView.initLayoutAnimation unavailable", e);
        }

        try {
            Class<?> btn = Class.forName("com.android.launcher.views.PressFeedbackButton", false, cl);
            for (Method m : btn.getDeclaredMethods()) {
                if (m.isBridge() || m.isSynthetic()) continue;
                String name = m.getName();
                if (!(name.equals("setDrawableColor") || name.equals("onAttachedToWindow")
                        || name.equals("onLayout") || name.equals("setEnabled"))) continue;
                hooks.hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        Object self = chain.getThisObject();
                        if (hooks.enabled() && isApplyChangeButton(self)) {
                            applyPressFeedbackGlass(self);
                        }
                    } catch (Throwable e) {
                        hooks.log(5, "Os14 PressFeedbackButton apply glass failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            hooks.log(5, "Os14 PressFeedbackButton apply hooks unavailable", e);
        }
    }

    private void applySecondLevelApplyButtonGlass(Object ctrl) {
        if (!hooks.enabled() || ctrl == null) return;
        try {
            View apply = findViewByIdSafe(ctrl, "apply_change");
            if (apply == null && ctrl instanceof View) {
                apply = findApplyChangeUnder((View) ctrl);
            }
            if (apply == null) {
                Object root = HookReflection.field(ctrl, "mRootContainerView");
                if (root instanceof View) apply = findApplyChangeUnder((View) root);
            }
            if (apply == null) {
                Object content = HookReflection.invokeNoArgs(ctrl, "getContentView");
                if (content instanceof View) apply = findApplyChangeUnder((View) content);
            }
            if (apply != null) applyPressFeedbackGlass(apply);
        } catch (Throwable e) {
            hooks.log(5, "Os14 applySecondLevelApplyButtonGlass failed", e);
        }
    }

    private static View findApplyChangeUnder(View root) {
        if (root == null) return null;
        if (isApplyChangeButton(root)) return root;
        if (!(root instanceof ViewGroup)) return null;
        ViewGroup g = (ViewGroup) root;
        for (int i = 0; i < g.getChildCount(); i++) {
            View found = findApplyChangeUnder(g.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    private static boolean isApplyChangeButton(Object view) {
        if (!(view instanceof View)) return false;
        if (!isPressFeedbackButton(view)) return false;
        try {
            int id = ((View) view).getId();
            if (id == View.NO_ID) return false;
            String name = ((View) view).getResources().getResourceEntryName(id);
            return "apply_change".equals(name);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isPressFeedbackButton(Object view) {
        return HookReflection.isClassOrSubclass(view, "com.android.launcher.views.PressFeedbackButton")
                || (view != null && view.getClass().getSimpleName().contains("PressFeedback"));
    }

    /**
     * 选中桌面图标后底部页预览缩略图（{@code PagePreviewItemView}）。
     * ColorOS 16 的同名逻辑在 {@code ModuleMain}；此处仅服务 Cos14。
     */
    void hookPagePreviewItemGlass(ClassLoader cl) {
        hooks.after(cl, "com.android.launcher.pagepreview.PagePreviewRoot", "onStateEnabled",
                this::applyPagePreviewRootGlass);
        hooks.after(cl, "com.android.launcher.pagepreview.PagePreviewRoot", "onStateTransitionEnd",
                this::applyPagePreviewRootGlass);
        hooks.after(cl, "com.android.launcher.pagepreview.PagePreviewRoot", "onStateDisabled",
                this::removePagePreviewRootGlass);
        hooks.after(cl, "com.android.launcher.pagepreview.PagePreviewRoot", "onStateDisableTransitionEnd",
                this::removePagePreviewRootGlass);
        hooks.after(cl, "com.android.launcher.pagepreview.PagePreviewListContainer", "onPagePreviewStateEnable",
                this::applyPagePreviewListGlass);
        hooks.after(cl, "com.android.launcher.pagepreview.PagePreviewListContainer", "onPagePreviewStateDisable",
                this::removePagePreviewListGlass);

        try {
            Class<?> adapter = Class.forName(
                    "com.android.launcher.pagepreview.PagePreviewAdapter", false, cl);
            for (Method m : adapter.getDeclaredMethods()) {
                if (!m.getName().equals("onBindViewHolder") || m.isBridge() || m.isSynthetic()) continue;
                hooks.hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        if (!hooks.enabled() || !isPagePreviewChromeActive(chain.getThisObject())) {
                            return result;
                        }
                        Object holder = chain.getArg(0);
                        applyPagePreviewItemGlass(HookReflection.field(holder, "itemView"));
                        applyPagePreviewItemGlass(HookReflection.field(holder, "mTwoPanelView1"));
                        applyPagePreviewItemGlass(HookReflection.field(holder, "mTwoPanelView2"));
                        applyPagePreviewItemGlass(HookReflection.invokeNoArgs(holder, "getMTwoPanelView1"));
                        applyPagePreviewItemGlass(HookReflection.invokeNoArgs(holder, "getMTwoPanelView2"));
                    } catch (Throwable e) {
                        hooks.log(5, "Os14 PagePreviewAdapter.onBindViewHolder glass failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            hooks.log(5, "Os14 PagePreviewAdapter glass unavailable", e);
        }

        try {
            Class<?> preview = Class.forName(
                    "com.android.launcher.pagepreview.PagePreviewItemView", false, cl);
            for (Method m : preview.getDeclaredMethods()) {
                if (m.isBridge() || m.isSynthetic()) continue;
                String name = m.getName();
                if (!(name.equals("onLayout") || name.equals("onAttachedToWindow")
                        || name.equals("onDragExit"))) continue;
                final String hooked = name;
                hooks.hookOnce(m, chain -> {
                    Object self = chain.getThisObject();
                    Object result = chain.proceed();
                    try {
                        if ("onDragExit".equals(hooked) && self instanceof View) {
                            GlassInstaller.uninstall((View) self);
                        }
                        if (hooks.enabled() && isPagePreviewChromeActive(self)) {
                            applyPagePreviewItemGlass(self);
                        }
                    } catch (Throwable e) {
                        hooks.log(5, "Os14 PagePreviewItemView." + hooked + " glass failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            hooks.log(5, "Os14 PagePreviewItemView glass unavailable", e);
        }

        // Page-preview action buttons (移除 / 建文件夹) also use PressFeedbackButton.
        hooks.after(cl, "com.android.launcher.pagepreview.PagePreviewButtonContainer", "onFinishInflate",
                container -> {
                    if (!hooks.enabled()) return;
                    applyPressFeedbackGlass(HookReflection.field(container, "mRemoveBtn"));
                    applyPressFeedbackGlass(HookReflection.field(container, "mFolderFormBtn"));
                    if (container instanceof View) applyPressFeedbackButtonsUnder((View) container);
                });
        hooks.after(cl, "com.android.launcher.pagepreview.PagePreviewButtonContainer",
                "onWallpaperBrightnessChanged",
                container -> {
                    if (!hooks.enabled()) return;
                    applyPressFeedbackGlass(HookReflection.field(container, "mRemoveBtn"));
                    applyPressFeedbackGlass(HookReflection.field(container, "mFolderFormBtn"));
                });
    }

    private boolean isPagePreviewChromeActive(Object host) {
        Object launcher = LegacyLauncherHooks.resolveLauncher(host);
        if (launcher == null) launcher = launcherHooks.findActiveLauncher();
        if (launcher == null) return false;
        return isInLauncherState(launcher, "PAGE_PREVIEW")
                || isInLauncherState(launcher, "TOGGLE_BAR");
    }

    private void applyPagePreviewItemGlass(Object item) {
        if (!hooks.enabled() || !(item instanceof View)) return;
        if (!HookReflection.isClassOrSubclass(item, "com.android.launcher.pagepreview.PagePreviewItemView")) return;
        if (!isPagePreviewChromeActive(item)) return;
        final View preview = (View) item;
        try {
            Object wrapper = HookReflection.field(preview, "mPressFeedbackPreviewWrapper");
            if (wrapper != null) {
                HookReflection.setField(wrapper, "mIsNeedDrawPressColor", false);
                HookReflection.invoke(wrapper, "setIsNeedDrawPressColor", new Class<?>[] { boolean.class }, false);
            }
            GlassDrawable existing = GlassInstaller.get(preview);
            boolean missing = existing == null || preview.getBackground() != existing;
            GlassInstaller.installBackground(preview, hooks.currentConfig());
            GlassDrawable live = GlassInstaller.get(preview);
            if (live == null) return;
            float radius = readToggleBarPreviewRadius(preview);
            if (radius > 0f) live.setCornerRadii(radius, radius, radius, radius);
            if (!missing) return;
            Runnable refresh = () -> {
                GlassDrawable g = GlassInstaller.get(preview);
                if (g == null) return;
                float r = readToggleBarPreviewRadius(preview);
                if (r > 0f) g.setCornerRadii(r, r, r, r);
                GlassInstaller.forceCapture(preview);
                preview.invalidate();
            };
            if (preview.getWidth() > 0 && preview.getHeight() > 0) refresh.run();
            else preview.post(refresh);
        } catch (Throwable e) {
            hooks.log(5, "Os14 applyPagePreviewItemGlass failed", e);
        }
    }

    private void hardRemovePagePreviewItemGlass(Object item) {
        if (!(item instanceof View)) return;
        if (!HookReflection.isClassOrSubclass(item, "com.android.launcher.pagepreview.PagePreviewItemView")) return;
        View preview = (View) item;
        try {
            Object wrapper = HookReflection.field(preview, "mPressFeedbackPreviewWrapper");
            if (wrapper != null) {
                HookReflection.setField(wrapper, "mIsNeedDrawPressColor", true);
                HookReflection.invoke(wrapper, "setIsNeedDrawPressColor", new Class<?>[] { boolean.class }, true);
            }
            GlassInstaller.uninstall(preview);
        } catch (Throwable e) {
            hooks.log(5, "Os14 removePagePreviewItemGlass failed", e);
        }
    }

    private void applyPagePreviewRootGlass(Object root) {
        if (!hooks.enabled() || !(root instanceof View)) return;
        applyPagePreviewItemsUnder((View) root);
    }

    private void removePagePreviewRootGlass(Object root) {
        if (!(root instanceof View)) return;
        hardRemovePagePreviewItemsUnder((View) root);
    }

    private void applyPagePreviewListGlass(Object list) {
        if (!hooks.enabled() || !(list instanceof View)) return;
        applyPagePreviewItemsUnder((View) list);
    }

    private void removePagePreviewListGlass(Object list) {
        if (!(list instanceof View)) return;
        hardRemovePagePreviewItemsUnder((View) list);
    }

    private void applyPagePreviewItemsUnder(View root) {
        if (root == null) return;
        if (HookReflection.isClassOrSubclass(root, "com.android.launcher.pagepreview.PagePreviewItemView")) {
            applyPagePreviewItemGlass(root);
            return;
        }
        if (!(root instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            applyPagePreviewItemsUnder(group.getChildAt(i));
        }
    }

    private void hardRemovePagePreviewItemsUnder(View root) {
        if (root == null) return;
        if (HookReflection.isClassOrSubclass(root, "com.android.launcher.pagepreview.PagePreviewItemView")) {
            hardRemovePagePreviewItemGlass(root);
            return;
        }
        if (!(root instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            hardRemovePagePreviewItemsUnder(group.getChildAt(i));
        }
    }

    private static boolean isToggleBarSecondLevelPreviewHost(Object view) {
        return HookReflection.isClassOrSubclass(view, "com.android.launcher.togglebar.views.ToggleBarLayoutItemPreview")
                || HookReflection.isClassOrSubclass(view, "com.android.launcher.togglebar.views.ToggleBarEffectItemContainer")
                || HookReflection.isClassOrSubclass(view, "com.android.launcher.togglebar.views.TwoPanelPagepreviewItemContainer");
    }

    private void applyToggleBarPreviewItemGlass(Object item) {
        if (!hooks.enabled() || !(item instanceof View)) return;
        if (!isToggleBarSecondLevelPreviewHost(item)) return;
        final View preview = (View) item;
        try {
            Object wrapper = HookReflection.field(preview, "mPressFeedbackPreviewWrapper");
            if (wrapper != null) {
                HookReflection.setField(wrapper, "mIsNeedDrawPressColor", false);
                HookReflection.invoke(wrapper, "setIsNeedDrawPressColor", new Class<?>[] { boolean.class }, false);
            }
            GlassDrawable existing = GlassInstaller.get(preview);
            boolean missing = existing == null || preview.getBackground() != existing;
            GlassInstaller.installBackground(preview, hooks.currentConfig());
            GlassDrawable live = GlassInstaller.get(preview);
            if (live == null) return;
            float radius = readToggleBarPreviewRadius(preview);
            if (radius > 0f) live.setCornerRadii(radius, radius, radius, radius);
            if (!missing) return;
            Runnable refresh = () -> {
                GlassDrawable g = GlassInstaller.get(preview);
                if (g == null) return;
                float r = readToggleBarPreviewRadius(preview);
                if (r > 0f) g.setCornerRadii(r, r, r, r);
                GlassInstaller.forceCapture(preview);
                preview.invalidate();
            };
            if (preview.getWidth() > 0 && preview.getHeight() > 0) refresh.run();
            else preview.post(refresh);
        } catch (Throwable e) {
            hooks.log(5, "Os14 applyToggleBarPreviewItemGlass failed", e);
        }
    }

    private static float readToggleBarPreviewRadius(View preview) {
        Object wrapper = HookReflection.field(preview, "mPressFeedbackPreviewWrapper");
        Object radius = HookReflection.invokeNoArgs(wrapper, "getMRadius");
        if (radius instanceof Number && ((Number) radius).floatValue() > 0f) {
            return ((Number) radius).floatValue();
        }
        Object viaField = HookReflection.field(wrapper, "mRadius");
        if (viaField instanceof Number && ((Number) viaField).floatValue() > 0f) {
            return ((Number) viaField).floatValue();
        }
        return 12f * preview.getResources().getDisplayMetrics().density;
    }

    private static View findViewByIdSafe(Object host, String idName) {
        if (host == null || idName == null) return null;
        try {
            Object found = HookReflection.invoke(host, "findViewById", new Class<?>[] { int.class },
                    resolveLauncherViewId(host, idName));
            return found instanceof View ? (View) found : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int resolveLauncherViewId(Object host, String idName) {
        try {
            ClassLoader cl = host.getClass().getClassLoader();
            Class<?> r = Class.forName("com.android.launcher.R$id", false, cl);
            Object v = r.getField(idName).get(null);
            if (v instanceof Number) return ((Number) v).intValue();
        } catch (Throwable ignored) { }
        return 0;
    }

    /**
     * 长按桌面图标下方的卡片预览底板（{@code card_page_bg} / pending_card_img_bg）。
     */
    void hookPendingCardPreviewGlass(ClassLoader cl) {
        hooks.after(cl, "com.android.launcher3.popup.pendingcard.PendingCardContainer", "onFinishInflate",
                this::applyPendingCardPreviewGlass);
        hooks.after(cl, "com.android.launcher3.popup.pendingcard.PendingCardContainer", "onAttachedToWindow",
                this::applyPendingCardPreviewGlass);
        hooks.after(cl, "com.android.launcher3.popup.pendingcard.PendingCardContainer", "onLayout",
                this::applyPendingCardPreviewGlass);
        try {
            Class<?> container = Class.forName(
                    "com.android.launcher3.popup.pendingcard.PendingCardContainer", false, cl);
            for (Method m : container.getDeclaredMethods()) {
                if (!m.getName().equals("initCardPager") || m.isBridge() || m.isSynthetic()) continue;
                hooks.hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        if (hooks.menuStyleEnabled()) applyPendingCardPreviewGlass(chain.getThisObject());
                    } catch (Throwable e) {
                        hooks.log(5, "Os14 PendingCardContainer.initCardPager glass failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            hooks.log(5, "Os14 PendingCardContainer.initCardPager unavailable", e);
        }
        // Reclaim if OEM rewrote the plate while the options popup is open.
        hooks.after(cl, "com.android.launcher3.popup.OplusPopupContainerWithArrow", "onLayout", popup -> {
            if (!hooks.menuStyleEnabled() || !(popup instanceof ViewGroup)) return;
            applyPendingCardPreviewGlassUnder((ViewGroup) popup);
        });
    }

    private void applyPendingCardPreviewGlassUnder(ViewGroup root) {
        if (root == null) return;
        if (HookReflection.isClassOrSubclass(root, "com.android.launcher3.popup.pendingcard.PendingCardContainer")) {
            applyPendingCardPreviewGlass(root);
            return;
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child instanceof ViewGroup) applyPendingCardPreviewGlassUnder((ViewGroup) child);
        }
    }

    private void applyPendingCardPreviewGlass(Object container) {
        if (!hooks.menuStyleEnabled() || container == null) return;
        if (!HookReflection.isClassOrSubclass(container, "com.android.launcher3.popup.pendingcard.PendingCardContainer")) {
            return;
        }
        try {
            Object bg = HookReflection.field(container, "mPendingCardRVFourSpanXBg");
            if (!(bg instanceof View) && container instanceof View) {
                bg = ((View) container).findViewById(
                        resolveLauncherViewId(container, "card_page_bg"));
            }
            if (!(bg instanceof View)) return;
            final View plate = (View) bg;
            GlassInstaller.installBackground(plate, hooks.currentConfig());
            GlassDrawable live = GlassInstaller.get(plate);
            if (live == null) return;
            float radius = readPendingCardRadius(plate, container);
            if (radius > 0f) live.setCornerRadii(radius, radius, radius, radius);
            Runnable refresh = () -> {
                if (!hooks.menuStyleEnabled() || !plate.isAttachedToWindow()) return;
                GlassDrawable g = GlassInstaller.get(plate);
                if (g == null) {
                    GlassInstaller.installBackground(plate, hooks.currentConfig());
                    g = GlassInstaller.get(plate);
                }
                if (g == null) return;
                float r = readPendingCardRadius(plate, container);
                if (r > 0f) g.setCornerRadii(r, r, r, r);
                GlassInstaller.forceCapture(plate);
                plate.invalidate();
            };
            if (plate.getWidth() > 0 && plate.getHeight() > 0) refresh.run();
            else plate.post(refresh);
            plate.postDelayed(refresh, 120L);
        } catch (Throwable e) {
            hooks.log(5, "Os14 applyPendingCardPreviewGlass failed", e);
        }
    }

    private static float readPendingCardRadius(View plate, Object container) {
        try {
            int px = plate.getResources().getDimensionPixelSize(
                    plate.getResources().getIdentifier(
                            "pending_card_radius", "dimen", "com.android.launcher"));
            if (px > 0) return px;
        } catch (Throwable ignored) { }
        Object via = HookReflection.field(container, "mRadius");
        if (via instanceof Number && ((Number) via).floatValue() > 0f) {
            return ((Number) via).floatValue();
        }
        return 24f * plate.getResources().getDisplayMetrics().density;
    }

    // ── Page indicator frosted pill (TOGGLE_BAR / press-drag) ───────────────
    // ColorOS 14 draws the pill via Paint in drawBackgroundIfNeeded — no mBlurBgView.

    void hookPageIndicatorGlass(ClassLoader cl) {
        try {
            Class<?> anim = Class.forName(
                    "com.android.launcher.pageindicators.PageIndicatorAnimHelper", false, cl);
            for (Method m : anim.getDeclaredMethods()) {
                String name = m.getName();
                if (!(name.equals("startPressDragging") || name.equals("cancelPressDragging")
                        || name.equals("startIndicatorBgAnim") || name.equals("reverseIndicatorBgAnim")
                        || name.equals("startPageNumChangeAnim") || name.equals("startIndicatorDotAnim")
                        || name.equals("setBgAlpha"))
                        || m.isBridge() || m.isSynthetic()) continue;
                final String hooked = name;
                hooks.hookOnce(m, chain -> {
                    Object helper = chain.getThisObject();
                    Object indicator = HookReflection.field(helper, "mPageIndicator");
                    try {
                        if (hooked.equals("startPressDragging")
                                || hooked.equals("startIndicatorBgAnim")
                                || (hooked.equals("setBgAlpha") && hooks.enabled()
                                && chain.getArgs().size() >= 1
                                && chain.getArg(0) instanceof Number
                                && ((Number) chain.getArg(0)).intValue() > 0)) {
                            applyPageIndicatorGlass(indicator);
                        }
                    } catch (Throwable e) {
                        hooks.log(5, "Os14 PageIndicatorAnimHelper." + hooked + " pre-glass failed", e);
                    }
                    Object result = chain.proceed();
                    try {
                        if (hooked.equals("cancelPressDragging")
                                || hooked.equals("reverseIndicatorBgAnim")) {
                            if (shouldShowPageIndicatorGlass(indicator)) {
                                applyPageIndicatorGlass(indicator);
                            } else {
                                removePageIndicatorGlass(indicator);
                            }
                        } else if (hooked.equals("startPageNumChangeAnim")
                                || hooked.equals("startIndicatorDotAnim")) {
                            applyPageIndicatorGlass(indicator);
                        } else if (hooked.equals("setBgAlpha") && hooks.enabled()
                                && chain.getArgs().size() >= 1
                                && chain.getArg(0) instanceof Number
                                && ((Number) chain.getArg(0)).intValue() <= 0
                                && !shouldShowPageIndicatorGlass(indicator)) {
                            removePageIndicatorGlass(indicator);
                        }
                    } catch (Throwable e) {
                        hooks.log(5, "Os14 PageIndicatorAnimHelper." + hooked + " glass failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            hooks.log(5, "Os14 PageIndicatorAnimHelper glass unavailable", e);
        }

        try {
            Class<?> indicator = Class.forName(
                    "com.android.launcher.pageindicators.OplusPageIndicator", false, cl);
            for (Method m : indicator.getDeclaredMethods()) {
                if (m.isBridge() || m.isSynthetic()) continue;
                String name = m.getName();
                if (name.equals("drawBackgroundIfNeeded") && m.getParameterCount() == 1) {
                    hooks.hookOnce(m, chain -> {
                        if (!hooks.enabled()) return chain.proceed();
                        Object self = chain.getThisObject();
                        try {
                            if (shouldShowPageIndicatorGlass(self)) {
                                applyPageIndicatorGlass(self);
                                // Skip OEM translucent Paint pill — glass background draws instead.
                                return null;
                            }
                            removePageIndicatorGlass(self);
                        } catch (Throwable e) {
                            hooks.log(5, "Os14 page indicator drawBackground glass failed", e);
                        }
                        return chain.proceed();
                    });
                } else if (name.equals("onDraw") && m.getParameterCount() == 1) {
                    hooks.hookOnce(m, chain -> {
                        Object result = chain.proceed();
                        try {
                            if (hooks.enabled() && shouldShowPageIndicatorGlass(chain.getThisObject())) {
                                applyPageIndicatorGlass(chain.getThisObject());
                            }
                        } catch (Throwable ignored) { }
                        return result;
                    });
                } else if (name.equals("setBgAlpha") && m.getParameterCount() == 1) {
                    hooks.hookOnce(m, chain -> {
                        Object result = chain.proceed();
                        try {
                            Object arg0 = chain.getArg(0);
                            if (hooks.enabled() && arg0 instanceof Number
                                    && ((Number) arg0).intValue() > 0) {
                                applyPageIndicatorGlass(chain.getThisObject());
                            }
                        } catch (Throwable ignored) { }
                        return result;
                    });
                }
            }
        } catch (Throwable e) {
            hooks.log(5, "Os14 OplusPageIndicator glass unavailable", e);
        }

        hooks.after(cl, "com.android.launcher.togglebar.views.ToggleStateToolbar", "onAttachedToWindow",
                toolbar -> applyPageIndicatorGlassFromLauncher(LegacyLauncherHooks.resolveLauncher(toolbar)));
        hooks.after(cl, "com.android.launcher3.Launcher", "onResume",
                this::applyPageIndicatorGlassFromLauncher);
        hooks.after(cl, "com.android.launcher.Launcher", "onResume",
                this::applyPageIndicatorGlassFromLauncher);
    }

    private void applyPageIndicatorGlassFromLauncher(Object launcher) {
        if (!hooks.enabled()) return;
        Object indicator = findPageIndicator(launcher);
        if (indicator != null && shouldShowPageIndicatorGlass(indicator)) {
            applyPageIndicatorGlass(indicator);
        }
    }

    private Object findPageIndicator(Object launcher) {
        Object ws = HookReflection.invokeNoArgs(launcher, "getWorkspace");
        Object indicator = HookReflection.invokeNoArgs(ws, "getPageIndicator");
        if (indicator == null) indicator = HookReflection.field(ws, "mPageIndicator");
        return indicator;
    }

    private boolean shouldShowPageIndicatorGlass(Object indicator) {
        if (!(indicator instanceof View)) return false;
        try {
            Object anim = HookReflection.field(indicator, "mAnimHelper");
            Object alpha = HookReflection.invokeNoArgs(anim, "getBackgroundAlpha");
            if (alpha instanceof Number && ((Number) alpha).intValue() > 0) return true;
        } catch (Throwable ignored) { }
        Object launcher = LegacyLauncherHooks.resolveLauncher(indicator);
        if (launcher == null) launcher = HookReflection.field(indicator, "mLauncher");
        return isInLauncherState(launcher, "TOGGLE_BAR")
                || isInLauncherState(launcher, "PAGE_PREVIEW");
    }

    private void applyPageIndicatorGlass(Object indicator) {
        if (!hooks.enabled() || !(indicator instanceof View)) return;
        View view = (View) indicator;
        try {
            GlassDrawable existing = GlassInstaller.get(view);
            if (existing == null || view.getBackground() != existing) {
                GlassInstaller.installBackground(view, hooks.currentConfig());
                existing = GlassInstaller.get(view);
            }
            if (existing == null) return;
            float radius = 24f * view.getResources().getDisplayMetrics().density;
            Object r = HookReflection.field(indicator, "mBackgroundRadius");
            if (r instanceof Number && ((Number) r).floatValue() > 0f) {
                radius = ((Number) r).floatValue();
            }
            existing.setCornerRadii(radius, radius, radius, radius);
            if (view.getWidth() > 0 && view.getHeight() > 0) {
                GlassInstaller.forceCapture(view);
            }
            view.invalidate();
        } catch (Throwable e) {
            hooks.log(5, "Os14 applyPageIndicatorGlass failed", e);
        }
    }

    private void removePageIndicatorGlass(Object indicator) {
        if (!(indicator instanceof View)) return;
        View view = (View) indicator;
        try {
            if (GlassInstaller.get(view) != null || view.getBackground() instanceof GlassDrawable) {
                GlassInstaller.uninstall(view);
                if (view.getBackground() instanceof GlassDrawable) view.setBackground(null);
                view.invalidate();
            }
        } catch (Throwable ignored) { }
    }

    private boolean isInLauncherState(Object launcher, String stateName) {
        if (launcher == null || stateName == null) return false;
        try {
            ClassLoader cl = launcher.getClass().getClassLoader();
            Object state = null;
            try {
                state = Class.forName("com.android.launcher3.LauncherState", false, cl)
                        .getField(stateName).get(null);
            } catch (NoSuchFieldException ignored) {
                // ColorOS 14 keeps TOGGLE_BAR / PAGE_PREVIEW on OplusBaseLauncherState.
                state = Class.forName("com.android.launcher3.states.OplusBaseLauncherState", false, cl)
                        .getField(stateName).get(null);
            }
            for (Method m : launcher.getClass().getMethods()) {
                if ("isInState".equals(m.getName()) && m.getParameterCount() == 1) {
                    return state != null && Boolean.TRUE.equals(m.invoke(launcher, state));
                }
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private void applyToggleBarToolbarGlass(Object toolbar) {
        if (!hooks.enabled() || toolbar == null) return;
        applyPressFeedbackGlass(HookReflection.field(toolbar, "mFinishBtn"));
        applyPressFeedbackGlass(HookReflection.field(toolbar, "mAddCardBtn"));
        applyPressFeedbackGlass(HookReflection.field(toolbar, "mDragCancelButton"));
        applyPressFeedbackGlass(HookReflection.field(toolbar, "applyBtn"));
        applyPressFeedbackGlass(HookReflection.field(toolbar, "finishBtn"));
        if (toolbar instanceof View) applyPressFeedbackButtonsUnder((View) toolbar);
    }

    private void applyToggleBarItemGlass(Object itemRoot) {
        if (!hooks.enabled() || !(itemRoot instanceof View)) return;
        if (!HookReflection.isClassOrSubclass(itemRoot, "com.android.launcher.togglebar.views.PressFeedbackLinearLayout")) {
            return;
        }
        Object icon = HookReflection.field(itemRoot, "iconImageView");
        if (!(icon instanceof View)) {
            icon = findChildBySimpleName((View) itemRoot, "PressFeedbackCircleImageView");
        }
        if (!(icon instanceof View)) return;
        View circle = (View) icon;
        GlassInstaller.installBackground(circle, hooks.currentConfig());
        circle.post(() -> {
            GlassDrawable live = GlassInstaller.get(circle);
            if (live == null) return;
            int size = Math.min(circle.getWidth(), circle.getHeight());
            if (size <= 0) return;
            float radius = size / 2f;
            live.setCornerRadii(radius, radius, radius, radius);
            GlassInstaller.forceCapture(circle);
            circle.invalidate();
        });
    }

    private void applyPressFeedbackGlass(Object buttonObj) {
        if (!hooks.enabled() || !(buttonObj instanceof View)) return;
        if (!HookReflection.isClassOrSubclass(buttonObj, "com.android.launcher.views.PressFeedbackButton")
                && !buttonObj.getClass().getSimpleName().contains("PressFeedback")) {
            return;
        }
        View button = (View) buttonObj;
        try {
            HookReflection.setField(button, "mSrcDrawable", null);
            Object handler = HookReflection.field(button, "mPressFeedbackHandler");
            if (handler != null) {
                HookReflection.invoke(handler, "setDrawableColor", new Class<?>[] { int.class }, 0);
            }
            GlassInstaller.installBackground(button, hooks.currentConfig());
            GlassDrawable glass = GlassInstaller.get(button);
            if (glass != null) {
                float radius = readPressFeedbackRadius(button);
                if (radius > 0f) glass.setCornerRadii(radius, radius, radius, radius);
            }
            Runnable refresh = () -> {
                if (!hooks.enabled()) return;
                HookReflection.setField(button, "mSrcDrawable", null);
                GlassDrawable live = GlassInstaller.get(button);
                if (live == null) {
                    GlassInstaller.installBackground(button, hooks.currentConfig());
                    live = GlassInstaller.get(button);
                }
                if (live == null) return;
                float radius = readPressFeedbackRadius(button);
                if (radius <= 0f && button.getHeight() > 0) radius = button.getHeight() / 2f;
                if (radius > 0f) live.setCornerRadii(radius, radius, radius, radius);
                GlassInstaller.forceCapture(button);
                button.invalidate();
            };
            if (button.getWidth() > 0 && button.getHeight() > 0) refresh.run();
            else button.post(refresh);
            button.postDelayed(refresh, 80L);
            button.postDelayed(refresh, 200L);
        } catch (Throwable e) {
            hooks.log(5, "Os14 applyPressFeedbackGlass failed", e);
        }
    }

    private static float readPressFeedbackRadius(View button) {
        Object radius = HookReflection.field(button, "mDrawableRadius");
        if (radius instanceof Number && ((Number) radius).floatValue() > 0f) {
            return ((Number) radius).floatValue();
        }
        Object via = HookReflection.invokeNoArgs(button, "getMDrawableRadius");
        if (via instanceof Number && ((Number) via).floatValue() > 0f) {
            return ((Number) via).floatValue();
        }
        return 0f;
    }

    private void applyPressFeedbackButtonsUnder(View root) {
        if (root == null) return;
        if (HookReflection.isClassOrSubclass(root, "com.android.launcher.views.PressFeedbackButton")) {
            applyPressFeedbackGlass(root);
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                applyPressFeedbackButtonsUnder(g.getChildAt(i));
            }
        }
    }

    private static View findChildBySimpleName(View root, String simpleName) {
        if (root == null || simpleName == null) return null;
        if (root.getClass().getSimpleName().equals(simpleName)) return root;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = findChildBySimpleName(g.getChildAt(i), simpleName);
                if (found != null) return found;
            }
        }
        return null;
    }
}
