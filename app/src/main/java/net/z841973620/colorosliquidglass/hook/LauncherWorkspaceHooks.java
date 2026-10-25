package net.z841973620.colorosliquidglass.hook;

import android.view.View;
import android.view.ViewGroup;
import net.z841973620.colorosliquidglass.glass.GlassDrawable;
import net.z841973620.colorosliquidglass.glass.GlassInstaller;
import java.lang.reflect.Method;
import static net.z841973620.colorosliquidglass.hook.HookContext.TAG;

/** 桌面编辑工具栏、页面缩略图、分页指示器和操作按钮。 */
final class LauncherWorkspaceHooks {
    private final HookContext hooks;
    private final LauncherHooks launcherHooks;

    LauncherWorkspaceHooks(HookContext hooks, LauncherHooks launcherHooks) {
        this.hooks = hooks;
        this.launcherHooks = launcherHooks;
    }

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

    /**
     * ToggleBar chrome: main menu circles (插件 / 壁纸与个性化 / …), top toolbar buttons
     * (完成 / 取消 / 添加卡片…), and bottom page-preview thumbnails that can be dragged.
     */
    void hookToggleBarChrome(ClassLoader cl) {
        hooks.after("com.android.launcher.togglebar.views.PressFeedbackLinearLayout", cl, "onFinishInflate",
                this::applyToggleBarItemGlass);
        hooks.after("com.android.launcher.togglebar.views.PressFeedbackLinearLayout", cl, "onAttachedToWindow",
                this::applyToggleBarItemGlass);
        hooks.after("com.android.launcher.togglebar.views.ToggleStateToolbar", cl, "onFinishInflate",
                this::applyToggleBarToolbarGlass);
        hooks.after("com.android.launcher.togglebar.views.ToggleStateToolbar", cl, "onAttachedToWindow",
                this::applyToggleBarToolbarGlass);
        hookPagePreviewFrameGlass(cl);
        try {
            Class<?> adapter = Class.forName(
                    "com.android.launcher.togglebar.adapter.ToggleBarMainUIAdapter", false, cl);
            for (Method m : adapter.getDeclaredMethods()) {
                if (!m.getName().equals("onBindViewHolder") || m.isBridge() || m.isSynthetic()) continue;
                hooks.hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        if (!hooks.enabled()) return result;
                        applyToggleBarItemGlass(HookReflection.field(chain.getArg(0), "itemView"));
                    } catch (Throwable e) {
                        hooks.log(5, TAG, "ToggleBarMainUIAdapter.onBindViewHolder glass failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            hooks.log(5, TAG, "ToggleBarMainUIAdapter glass hook unavailable", e);
        }
        // OEM fills are redrawn every frame; skip them only when glass owns that view's chrome.
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
                        if (view instanceof View && (GlassInstaller.get((View) view) != null
                                || (HookReflection.isClassOrSubclass(view,
                                "com.android.launcher.pagepreview.PagePreviewItemView")
                                && isPagePreviewChromeActive(view)))) {
                            return null;
                        }
                    }
                    return chain.proceed();
                });
            }
        } catch (Throwable e) {
            hooks.log(5, TAG, "PressFeedbackHandler glass fill skip unavailable", e);
        }
    }

    /**
     * Page-preview thumbnails (drawPressedColor frosted fill) + page-indicator expanded pill
     * (LayerBlur TYPE_PAGE_INDICATOR). Thumbnails install/remove with PAGE_PREVIEW / TOGGLE_BAR.
     * Indicator glass is held through the exit fade — including the blank-page dot shrink that
     * happens after the launcher is already back on NORMAL.
     */
    private void hookPagePreviewFrameGlass(ClassLoader cl) {
        hooks.after("com.android.launcher.pagepreview.PagePreviewRoot", cl, "onStateEnabled",
                this::applyPagePreviewRootGlass);
        hooks.after("com.android.launcher.pagepreview.PagePreviewRoot", cl, "onStateTransitionEnd",
                this::applyPagePreviewRootGlass);
        hooks.after("com.android.launcher.pagepreview.PagePreviewRoot", cl, "onStateDisabled",
                this::removePagePreviewRootGlass);
        hooks.after("com.android.launcher.pagepreview.PagePreviewRoot", cl, "onStateDisableTransitionEnd",
                this::removePagePreviewRootGlass);
        hooks.after("com.android.launcher.pagepreview.PagePreviewListContainer", cl, "onPagePreviewStateEnable",
                this::applyPagePreviewListGlass);
        hooks.after("com.android.launcher.pagepreview.PagePreviewListContainer", cl, "onPagePreviewStateDisable",
                this::removePagePreviewListGlass);
        try {
            Class<?> adapter = Class.forName(
                    "com.android.launcher.pagepreview.PagePreviewAdapter", false, cl);
            for (Method m : adapter.getDeclaredMethods()) {
                if (!m.getName().equals("onBindViewHolder") || m.isBridge() || m.isSynthetic()) continue;
                hooks.hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        if (!hooks.enabled() || !isPagePreviewChromeActive(chain.getThisObject())) return result;
                        Object holder = chain.getArg(0);
                        applyPagePreviewItemGlass(HookReflection.field(holder, "itemView"));
                        applyPagePreviewItemGlass(HookReflection.field(holder, "mTwoPanelView1"));
                        applyPagePreviewItemGlass(HookReflection.field(holder, "mTwoPanelView2"));
                        applyPagePreviewItemGlass(HookReflection.invokeNoArgs(holder, "getMTwoPanelView1"));
                        applyPagePreviewItemGlass(HookReflection.invokeNoArgs(holder, "getMTwoPanelView2"));
                    } catch (Throwable e) {
                        hooks.log(5, TAG, "PagePreviewAdapter.onBindViewHolder glass failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            hooks.log(5, TAG, "PagePreviewAdapter glass hook unavailable", e);
        }
        try {
            Class<?> preview = Class.forName(
                    "com.android.launcher.pagepreview.PagePreviewItemView", false, cl);
            for (Method m : preview.getDeclaredMethods()) {
                if (!m.getName().equals("onDragExit") || m.isBridge() || m.isSynthetic()) continue;
                hooks.hookOnce(m, chain -> {
                    Object self = chain.getThisObject();
                    Object result = chain.proceed();
                    if (self instanceof View) GlassInstaller.uninstall((View) self);
                    if (hooks.enabled() && isPagePreviewChromeActive(self)) {
                        applyPagePreviewItemGlass(self);
                    }
                    return result;
                });
            }
            for (Method m : preview.getDeclaredMethods()) {
                if (!m.getName().equals("onLayout") || m.isBridge() || m.isSynthetic()) continue;
                hooks.hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    if (hooks.enabled() && isPagePreviewChromeActive(chain.getThisObject())) {
                        applyPagePreviewItemGlass(chain.getThisObject());
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            hooks.log(5, TAG, "PagePreviewItemView glass hooks unavailable", e);
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
                hooks.hookOnce(m, chain -> {
                    Object helper = chain.getThisObject();
                    Object indicator = HookReflection.field(helper, "mPageIndicator");
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
                        hooks.log(5, TAG, "PageIndicatorAnimHelper." + name + " pre-glass failed", e);
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
                        hooks.log(5, TAG, "PageIndicatorAnimHelper." + name + " glass failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            hooks.log(5, TAG, "PageIndicatorAnimHelper glass hooks unavailable", e);
        }
        try {
            Class<?> indicator = Class.forName(
                    "com.android.launcher.pageindicators.OplusPageIndicator", false, cl);
            for (Method m : indicator.getDeclaredMethods()) {
                String name = m.getName();
                if (m.isBridge() || m.isSynthetic()) continue;
                if (name.equals("drawBackgroundIfNeeded")) {
                    hooks.hookOnce(m, chain -> {
                        if (GlassInstaller.suppressPageIndicatorBackground()) {
                            return null;
                        }
                        if (hooks.enabled() && shouldOwnPageIndicatorFrame(chain.getThisObject())) {
                            applyPageIndicatorFrameGlass(chain.getThisObject());
                            Object blurBg = HookReflection.field(chain.getThisObject(), "mBlurBgView");
                            if (blurBg instanceof View && GlassInstaller.get((View) blurBg) != null) {
                                return null;
                            }
                        }
                        return chain.proceed();
                    });
                } else if (name.equals("initBlurBackground")) {
                    hooks.hookOnce(m, chain -> {
                        Object result = chain.proceed();
                        if (hooks.enabled() && shouldOwnPageIndicatorFrame(chain.getThisObject())) {
                            applyPageIndicatorFrameGlass(chain.getThisObject());
                        }
                        return result;
                    });
                } else if (name.equals("updateBlurBgIfNeed")) {
                    hooks.hookOnce(m, chain -> {
                        Object result = chain.proceed();
                        try {
                            Object self = chain.getThisObject();
                            Object blurBg = HookReflection.field(self, "mBlurBgView");
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
                            hooks.log(5, TAG, "updateBlurBgIfNeed glass cleanup failed", e);
                        }
                        return result;
                    });
                } else if (name.equals("setMarkersCount")) {
                    hooks.hookOnce(m, chain -> {
                        Object result = chain.proceed();
                        if (hooks.enabled() && shouldOwnPageIndicatorFrame(chain.getThisObject())) {
                            applyPageIndicatorFrameGlass(chain.getThisObject());
                        }
                        return result;
                    });
                }
            }
        } catch (Throwable e) {
            hooks.log(5, TAG, "OplusPageIndicator glass hooks unavailable", e);
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
                        if (view instanceof View
                                && HookReflection.isClassOrSubclass(view,
                                "com.android.launcher.pagepreview.PagePreviewItemView")
                                && isPagePreviewChromeActive(view)) {
                            HookReflection.setField(chain.getThisObject(), "mIsNeedDrawPressColor", false);
                            applyPagePreviewItemGlass(view);
                        }
                    }
                    return chain.proceed();
                });
            }
        } catch (Throwable e) {
            hooks.log(5, TAG, "PressFeedbackPreviewWrapper.onDraw glass hook unavailable", e);
        }
    }

    private void applyToggleBarToolbarGlass(Object toolbar) {
        if (!hooks.enabled() || toolbar == null) return;
        applyPressFeedbackButtonGlass(HookReflection.field(toolbar, "applyBtn"));
        applyPressFeedbackButtonGlass(HookReflection.field(toolbar, "backToMainPage"));
        applyPressFeedbackButtonGlass(HookReflection.field(toolbar, "addCardBtn"));
        applyPressFeedbackButtonGlass(HookReflection.field(toolbar, "mFinishBtn"));
        applyPressFeedbackButtonGlass(HookReflection.field(toolbar, "mAddCardBtn"));
        applyPressFeedbackButtonGlass(HookReflection.field(toolbar, "dragCancelButton"));
        applyPressFeedbackButtonGlass(HookReflection.field(toolbar, "mDragCancelButton"));
        // Also walk the tree so renamed fields / DragCancelButton subclasses are covered.
        if (toolbar instanceof View) applyPressFeedbackButtonsUnder((View) toolbar);
    }

    private void applyPressFeedbackButtonsUnder(View root) {
        if (root == null) return;
        if (HookReflection.isClassOrSubclass(root, "com.android.launcher.views.PressFeedbackButton")) {
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
            hooks.log(5, TAG, "applyPagePreviewItemGlass failed", e);
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
            hooks.log(5, TAG, "removePagePreviewItemGlass failed", e);
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
        if (!(root instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup group = (android.view.ViewGroup) root;
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
        if (!(root instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup group = (android.view.ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            hardRemovePagePreviewItemsUnder(group.getChildAt(i));
        }
    }

    void syncPagePreviewFrameGlassForLauncher(Object launcher) {
        if (!hooks.enabled() || launcher == null) return;
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
        Object manager = HookReflection.invokeNoArgs(launcher, "getPagePreviewManager");
        Object root = HookReflection.invokeNoArgs(manager, "getPagePreviewLayout");
        if (root == null) root = HookReflection.field(manager, "mPagePreviewLayout");
        return root;
    }

    private Object findPageIndicator(Object launcher) {
        Object workspace = HookReflection.invokeNoArgs(launcher, "getWorkspace");
        Object indicator = HookReflection.invokeNoArgs(workspace, "getPageIndicator");
        if (indicator == null) indicator = HookReflection.field(workspace, "mPageIndicator");
        return indicator;
    }

    private void beginPageIndicatorExitHold(Object indicator) {
        pageIndicatorExitHold = true;
        applyPageIndicatorFrameGlass(indicator);
        schedulePageIndicatorExitRelease(indicator);
    }

    private void schedulePageIndicatorExitRelease(Object indicator) {
        Object blurBg = HookReflection.field(indicator, "mBlurBgView");
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
                Object liveBg = HookReflection.field(indRef, "mBlurBgView");
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
                hooks.log(5, TAG, "pageIndicatorExitRelease failed", e);
            }
        };
        // startIndicatorDotAnim lasts 450ms and briefly zeros alpha before ramping up.
        bg.postDelayed(pageIndicatorExitRelease, 500);
    }

    private boolean isPageIndicatorPageChangeAnimating(Object indicatorOrHost) {
        Object indicator = indicatorOrHost;
        if (!HookReflection.isClassOrSubclass(indicator, "com.android.launcher.pageindicators.OplusPageIndicator")) {
            Object launcher = LauncherHooks.resolveLauncher(indicatorOrHost);
            if (launcher == null) launcher = launcherHooks.findActiveLauncher();
            indicator = findPageIndicator(launcher);
        }
        Object helper = HookReflection.field(indicator, "mAnimHelper");
        Object animating = HookReflection.field(helper, "mPageChangeAnimating");
        return Boolean.TRUE.equals(animating);
    }

    private void applyPageIndicatorFrameGlass(Object indicator) {
        if (!hooks.enabled() || !(indicator instanceof View)) return;
        Object blurBg = HookReflection.field(indicator, "mBlurBgView");
        if (!(blurBg instanceof View)) return;
        final View bg = (View) blurBg;
        try {
            GlassDrawable existing = GlassInstaller.get(bg);
            boolean missing = existing == null || bg.getBackground() != existing;
            GlassInstaller.installBackground(bg, hooks.currentConfig());
            GlassDrawable live = GlassInstaller.get(bg);
            if (live == null) return;
            Object radiusObj = HookReflection.field(indicator, "mBackgroundRadius");
            float radius = radiusObj instanceof Number
                    ? ((Number) radiusObj).floatValue()
                    : 24f * bg.getResources().getDisplayMetrics().density;
            if (radius > 0f) live.setCornerRadii(radius, radius, radius, radius);
            if (!missing) return;
            Runnable refresh = () -> {
                GlassDrawable g = GlassInstaller.get(bg);
                if (g == null) return;
                Object rObj = HookReflection.field(indicator, "mBackgroundRadius");
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
            hooks.log(5, TAG, "applyPageIndicatorFrameGlass failed", e);
        }
    }

    /** True when the expanded indicator pill should keep liquid glass (incl. exit fade). */
    private boolean shouldOwnPageIndicatorFrame(Object host) {
        if (!hooks.enabled()) return false;
        if (pageIndicatorFrameActive || pageIndicatorExitHold) return true;
        if (isPagePreviewChromeActive(host)) return true;
        Object indicator = host;
        if (!HookReflection.isClassOrSubclass(host, "com.android.launcher.pageindicators.OplusPageIndicator")) {
            Object launcher = LauncherHooks.resolveLauncher(host);
            if (launcher == null) launcher = launcherHooks.findActiveLauncher();
            indicator = findPageIndicator(launcher);
        }
        Object blurBg = HookReflection.field(indicator, "mBlurBgView");
        return blurBg instanceof View && ((View) blurBg).getAlpha() > 0.01f
                && GlassInstaller.get((View) blurBg) != null;
    }

    /**
     * Thumbnail cards are shown in PAGE_PREVIEW / TOGGLE_BAR (widget tray), or while
     * press-dragging page dots.
     */
    private boolean isPagePreviewChromeActive(Object host) {
        if (pageIndicatorFrameActive) return true;
        Object launcher = LauncherHooks.resolveLauncher(host);
        if (launcher == null) launcher = launcherHooks.findActiveLauncher();
        if (launcher == null) return false;
        return isInLauncherState(launcher, "PAGE_PREVIEW")
                || isInLauncherState(launcher, "TOGGLE_BAR");
    }

    boolean isInLauncherState(Object launcher, String stateName) {
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
        Object wrapper = HookReflection.field(preview, "mPressFeedbackPreviewWrapper");
        Object radius = HookReflection.invokeNoArgs(wrapper, "getRadius");
        if (radius instanceof Number && ((Number) radius).floatValue() > 0f) {
            return ((Number) radius).floatValue();
        }
        Object viaField = HookReflection.field(wrapper, "mRadius");
        if (viaField instanceof Number && ((Number) viaField).floatValue() > 0f) {
            return ((Number) viaField).floatValue();
        }
        // Fallback matches PreviewItemFrameRadius (8dp).
        return 8f * preview.getResources().getDisplayMetrics().density;
    }

    private void applyToggleBarItemGlass(Object itemRoot) {
        if (!hooks.enabled() || itemRoot == null) return;
        if (!HookReflection.isClassOrSubclass(itemRoot, "com.android.launcher.togglebar.views.PressFeedbackLinearLayout")) {
            return;
        }
        Object icon = HookReflection.field(itemRoot, "iconImageView");
        if (!(icon instanceof View) && itemRoot instanceof View) {
            icon = findChildBySimpleName((View) itemRoot, "PressFeedbackCircleImageView");
        }
        if (!(icon instanceof View)) return;
        final View circle = (View) icon;
        try {
            // Preserve the OEM glyph; glass sits behind as background with the same circular shape.
            GlassInstaller.installBackground(circle, hooks.currentConfig());
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
            hooks.log(5, TAG, "applyToggleBarItemGlass failed", e);
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

    void applyClearButtonGlass(Object panel) {
        if (!hooks.enabled() || panel == null) return;
        applyPressFeedbackButtonGlass(HookReflection.field(panel, "mClearAllBtn"));
    }

    /** Shared installer for PressFeedbackButton chrome (清除 / 完成 / 取消 / …). */
    private void applyPressFeedbackButtonGlass(Object buttonObj) {
        if (!hooks.enabled() || !(buttonObj instanceof View)) return;
        if (!HookReflection.isClassOrSubclass(buttonObj, "com.android.launcher.views.PressFeedbackButton")) return;
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
            hooks.log(5, TAG, "applyPressFeedbackButtonGlass failed", e);
        }
    }

    /** Rewrites AnimInfo endValue so hand-follow / spring blur targets stay at 0. */
    static void zeroAnimInfoEndValue(Object animInfo) {
        if (animInfo == null) return;
        HookReflection.setField(animInfo, "endValue", 0f);
    }

    private static float readClearButtonRadius(View button) {
        Object radius = HookReflection.field(button, "mDrawableRadius");
        if (radius instanceof Number) {
            float value = ((Number) radius).floatValue();
            if (value > 0f) return value;
        }
        Object viaGetter = HookReflection.invokeNoArgs(button, "getMDrawableRadius");
        if (viaGetter instanceof Number) {
            float value = ((Number) viaGetter).floatValue();
            if (value > 0f) return value;
        }
        return 0f;
    }
}
