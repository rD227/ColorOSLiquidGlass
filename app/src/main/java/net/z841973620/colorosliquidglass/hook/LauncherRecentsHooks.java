package net.z841973620.colorosliquidglass.hook;

import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import net.z841973620.colorosliquidglass.glass.GlassDrawable;
import net.z841973620.colorosliquidglass.glass.GlassInstaller;
import net.z841973620.colorosliquidglass.glass.TaskContentOverlay;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import static net.z841973620.colorosliquidglass.hook.HookContext.TAG;

/** 最近任务菜单、快捷操作和手势触发面板。 */
final class LauncherRecentsHooks {
    private final HookContext hooks;
    private final LauncherHooks launcherHooks;

    LauncherRecentsHooks(HookContext hooks, LauncherHooks launcherHooks) {
        this.hooks = hooks;
        this.launcherHooks = launcherHooks;
    }


    /**
     * Recents "清除" is a PressFeedbackButton that self-draws a solid/src fill. Replace that
     * chrome with LiquidGlass while keeping the label and press-scale feedback.
     */
    void hookRecentsClearButton(ClassLoader cl) {
        hooks.after("com.oplus.quickstep.views.OplusClearAllPanelView", cl, "onFinishInflate", o -> {
            launcherHooks.workspaceHooks.applyClearButtonGlass(o);
        });
        hooks.after("com.oplus.quickstep.views.OplusClearAllPanelView", cl, "onAttachedToWindow", o -> {
            launcherHooks.workspaceHooks.applyClearButtonGlass(o);
        });
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
                    } catch (Throwable e) {
                        hooks.log(5, TAG, "PressFeedbackButton.setSrcDrawable glass reassert failed", e);
                    }
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
                    // OEM fills an opaque-ish rounded rect before text; zero the fill so glass shows.
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
            hooks.log(5, TAG, "PressFeedbackButton clear-glass hooks unavailable", e);
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
    void hookRecentsTaskShortcuts(ClassLoader cl) {
        hookRecentsRapidReactionGlass(cl);
        hooks.after("com.android.quickstep.views.OplusTaskMenuViewImpl", cl, "addMenuOptions",
                this::applyTaskMenuGlass);
        hooks.after("com.android.quickstep.views.OplusTaskMenuViewImpl", cl, "animateOpen",
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
                hooks.hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        if (!hooks.menuStyleEnabled()) return result;
                        if (hooked.equals("animateOpenOrClosed")
                                && Boolean.TRUE.equals(chain.getArg(0))) {
                            return result;
                        }
                        applyTaskMenuGlass(chain.getThisObject());
                        if (hooked.equals("animateOpenOrClosed")) {
                            scheduleTaskMenuGlassSettle(chain.getThisObject());
                        }
                    } catch (Throwable e) {
                        hooks.log(5, TAG, "OplusTaskMenuViewImpl." + hooked + " glass failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            hooks.log(5, TAG, "OplusTaskMenuViewImpl glass hooks unavailable", e);
        }

        hooks.after("com.oplus.quickstep.views.OplusTaskHeaderView", cl, "onFinishInflate",
                this::applyTaskHeaderShortcutGlass);
        try {
            Class<?> header = Class.forName(
                    "com.oplus.quickstep.views.OplusTaskHeaderView", false, cl);
            for (Method m : header.getDeclaredMethods()) {
                if (!m.getName().equals("showWindowIcon") || m.isBridge() || m.isSynthetic()) {
                    continue;
                }
                hooks.hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        if (!hooks.enabled()) return result;
                        Object arg0 = chain.getArg(0);
                        if (arg0 instanceof View) {
                            applyTaskHeaderShortcutButtonGlass((View) arg0);
                        }
                        applyTaskHeaderShortcutGlass(chain.getThisObject());
                    } catch (Throwable e) {
                        hooks.log(5, TAG, "OplusTaskHeaderView.showWindowIcon glass failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            hooks.log(5, TAG, "OplusTaskHeaderView shortcut glass hooks unavailable", e);
        }
    }

    /**
     * From-app swipe RapidReaction capsules (分屏 / 浮窗). OEM paints opaque rounded rects
     * in {@code MultiRectangleBackgroundView}; replace those fills with LiquidGlass hosts
     * sized to each {@code RectPaint} and keep icon/title content above.
     */
    private void hookRecentsRapidReactionGlass(ClassLoader cl) {
        hooks.after("com.oplus.quickstep.rapidreaction.widget.MultiTriggerPanelView", cl,
                "updateViewVisibility", this::applyRapidReactionGlass);
        hooks.after("com.oplus.quickstep.rapidreaction.widget.MultiTriggerPanelView", cl,
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
                hooks.hookOnce(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        if (hooks.enabled()) applyRapidReactionGlass(chain.getThisObject());
                    } catch (Throwable e) {
                        hooks.log(5, TAG, "MultiTriggerPanelView." + hooked + " glass failed", e);
                    }
                    return result;
                });
            }
        } catch (Throwable e) {
            hooks.log(5, TAG, "MultiTriggerPanelView glass hooks unavailable", e);
        }
        try {
            Class<?> bg = Class.forName(
                    "com.oplus.quickstep.rapidreaction.widget.MultiRectangleBackgroundView",
                    false, cl);
            for (Method m : bg.getDeclaredMethods()) {
                if (!m.getName().equals("onDraw") || m.getParameterCount() != 1
                        || m.isBridge() || m.isSynthetic()) continue;
                hooks.hookOnce(m, chain -> {
                    try {
                        if (hooks.enabled()) clearRapidReactionOpaqueFills(chain.getThisObject());
                    } catch (Throwable ignored) { }
                    return chain.proceed();
                });
            }
        } catch (Throwable e) {
            hooks.log(5, TAG, "MultiRectangleBackgroundView.onDraw glass hook unavailable", e);
        }
    }

    private void applyRapidReactionGlass(Object panel) {
        if (!hooks.enabled() || !(panel instanceof ViewGroup)) return;
        final ViewGroup host = (ViewGroup) panel;
        if (host.getVisibility() != View.VISIBLE) return;
        try {
            Object bg = HookReflection.field(panel, "hintDoubleRectBackgroundView");
            if (!(bg instanceof View)) return;
            Object paints = HookReflection.field(bg, "mBgRectPaints");
            if (!(paints instanceof Iterable<?>)) return;
            float radius = readRapidReactionRadius((View) bg);
            clearRapidReactionOpaqueFills(bg);
            for (Object rectPaint : (Iterable<?>) paints) {
                if (rectPaint == null) continue;
                Object linked = HookReflection.invokeNoArgs(rectPaint, "getMLinkedEntranceType");
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
                GlassInstaller.installBackground(glassHost, hooks.currentConfig());
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
            hooks.log(5, TAG, "applyRapidReactionGlass failed", e);
        }
    }

    private static boolean isRapidSplitOrFloat(Object selectionOptions) {
        if (selectionOptions == null) return false;
        String name = selectionOptions.toString();
        return name.contains("SPLIT_WINDOW") || name.contains("FLOATING_WINDOW");
    }

    private static void clearRapidReactionOpaqueFills(Object bgView) {
        Object paints = HookReflection.field(bgView, "mBgRectPaints");
        if (!(paints instanceof Iterable<?>)) return;
        for (Object rectPaint : (Iterable<?>) paints) {
            if (rectPaint == null) continue;
            Object linked = HookReflection.invokeNoArgs(rectPaint, "getMLinkedEntranceType");
            if (!isRapidSplitOrFloat(linked)) continue;
            Object paint = HookReflection.invokeNoArgs(rectPaint, "getMPaint");
            if (paint instanceof android.graphics.Paint) {
                ((android.graphics.Paint) paint).setColor(0);
            }
        }
    }

    private static android.graphics.RectF rapidPaintDrawRect(Object rectPaint) {
        Object draw = HookReflection.field(rectPaint, "mDrawRect");
        if (draw instanceof android.graphics.RectF) {
            android.graphics.RectF r = (android.graphics.RectF) draw;
            if (r.width() > 1f && r.height() > 1f) return new android.graphics.RectF(r);
        }
        Object base = HookReflection.invokeNoArgs(rectPaint, "getMBaseRect");
        if (!(base instanceof android.graphics.RectF)) base = HookReflection.field(rectPaint, "mBaseRect");
        if (!(base instanceof android.graphics.RectF)) return null;
        android.graphics.RectF rect = new android.graphics.RectF((android.graphics.RectF) base);
        Object tx = HookReflection.invokeNoArgs(rectPaint, "getMTranslationX");
        Object ty = HookReflection.invokeNoArgs(rectPaint, "getMTranslationY");
        float ox = tx instanceof Number ? ((Number) tx).floatValue() : 0f;
        float oy = ty instanceof Number ? ((Number) ty).floatValue() : 0f;
        rect.offset(ox, oy);
        Object sx = HookReflection.invokeNoArgs(rectPaint, "getMScaleX");
        Object sy = HookReflection.invokeNoArgs(rectPaint, "getMScaleY");
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
            Object swipeRef = HookReflection.field(panelOrMenu, "swipeUpHandlerRef");
            Object swipe = swipeRef instanceof java.lang.ref.WeakReference
                    ? ((java.lang.ref.WeakReference<?>) swipeRef).get()
                    : null;
            Object recents = swipe != null ? HookReflection.field(swipe, "mRecentsView") : null;
            if (recents == null) recents = HookReflection.invokeNoArgs(swipe, "getRecentsView");
            if (recents == null) {
                Object launcher = launcherHooks.findActiveLauncher();
                recents = HookReflection.invokeNoArgs(launcher, "getOverviewPanel");
            }
            Object running = HookReflection.invokeNoArgs(recents, "getRunningTaskView");
            if (running instanceof View) return (View) running;
            Object current = HookReflection.invokeNoArgs(recents, "getCurrentPageTaskView");
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
        Object listObj = HookReflection.field(menu, "mListView");
        final View glassHost = listObj instanceof View ? (View) listObj : host;
        final View taskView = resolveTaskMenuTaskView(menu);
        Runnable settle = new Runnable() {
            int tries = 0;
            @Override public void run() {
                try {
                    if (!hooks.enabled() || !glassHost.isAttachedToWindow()) return;
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
                    hooks.log(5, TAG, "task menu glass settle failed", t);
                }
            }
        };
        host.postDelayed(settle, 400L);
    }

    /** LiquidGlass behind the Recents ··· popup that also lists 分屏 / 浮窗. */
    private void applyTaskMenuGlass(Object menu) {
        if (!hooks.menuStyleEnabled() || !(menu instanceof View)) return;
        final View host = (View) menu;
        try {
            Object listObj = HookReflection.field(menu, "mListView");
            final View listView = listObj instanceof View ? (View) listObj : null;
            clearTaskMenuOpaqueChrome(menu, listView);

            // Prefer ListView as glass host: it owns the rounded popup panel bounds.
            final View glassHost = listView != null ? listView : host;
            if (listView != null && host.getBackground() instanceof GlassDrawable) {
                // Avoid double glass if we previously installed on the LinearLayout wrapper.
                GlassInstaller.uninstall(host);
            }
            if (host != glassHost) host.setBackground(null);

            GlassInstaller.installBackground(glassHost, hooks.currentConfig());
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
                    GlassInstaller.installBackground(glassHost, hooks.currentConfig());
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
                hooks.log(4, TAG, "TaskMenu glass ready size="
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
            hooks.log(5, TAG, "applyTaskMenuGlass failed", e);
        }
    }

    private View resolveTaskMenuTaskView(Object menu) {
        Object taskView = HookReflection.field(menu, "mTaskView");
        if (taskView instanceof View) return (View) taskView;
        Object via = HookReflection.invokeNoArgs(menu, "getTaskView");
        if (via instanceof View) return (View) via;
        Object container = HookReflection.field(menu, "mTaskContainer");
        Object fromContainer = HookReflection.invokeNoArgs(container, "getTaskView");
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
                    HookReflection.invoke(roundFrame, "setClipMode", new Class<?>[] { int.class }, 0);
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

    private void applyTaskHeaderShortcutGlass(Object header) {
        if (!hooks.enabled() || header == null) return;
        applyTaskHeaderShortcutButtonGlass(HookReflection.field(header, "splitWindowBtn"));
        applyTaskHeaderShortcutButtonGlass(HookReflection.invokeNoArgs(header, "getSplitWindowBtn"));
        applyTaskHeaderShortcutButtonGlass(HookReflection.field(header, "miniWindowBtn"));
        applyTaskHeaderShortcutButtonGlass(HookReflection.invokeNoArgs(header, "getMiniWindowBtn"));
    }

    /**
     * Large-display header 分屏 / 浮窗 ImageButtons. Skip GONE hosts — phones keep them
     * invisible forever behind {@code hasLargeDisplayFeatures()}.
     */
    private void applyTaskHeaderShortcutButtonGlass(Object buttonObj) {
        if (!hooks.enabled() || !(buttonObj instanceof ImageView)) return;
        final View button = (View) buttonObj;
        if (button.getVisibility() != View.VISIBLE) return;
        try {
            GlassInstaller.installBackground(button, hooks.currentConfig());
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
                hooks.log(4, TAG, "TaskHeader shortcut glass ready "
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
            hooks.log(5, TAG, "applyTaskHeaderShortcutButtonGlass failed", e);
        }
    }
}
