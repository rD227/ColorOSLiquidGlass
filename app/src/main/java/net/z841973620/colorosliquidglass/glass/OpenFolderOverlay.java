package net.z841973620.colorosliquidglass.glass;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** 已展开文件夹的查询，以及内容、图标与标题的叠加绘制。 */
final class OpenFolderOverlay {
    private OpenFolderOverlay() {}


    /**
     * Paint the open {@code Folder} floating page into the ashmem sample so float-menu glass
     * shows the opened folder (not only closed FolderIcon plates) while dropping an app in.
     */
    static void paintOpenFolders(View seed, Canvas canvas, Matrix globalToTarget,
            RectF region) {
        View folder = findOpenFolderView(seed);
        if (folder == null) return;
        if (folder.getVisibility() == View.GONE) return;
        float alpha = Math.max(folder.getAlpha(), 0f);
        if (alpha < 0.01f) alpha = 1f; // opening anim may briefly report 0
        int w = folder.getWidth();
        int h = folder.getHeight();
        if (w <= 0 || h <= 0) return;
        RectF screen = DesktopRenderUtils.screenRect(folder);
        if (screen == null || !RectF.intersects(region, screen)) {
            // Folder chrome may not yet have screen coords during spring-load — still try
            // when a FolderIcon under the region reports this folder open.
            if (!openFolderAnchoredInRegion(seed, folder, region)) return;
        }
        java.util.ArrayList<View> suppressed = new java.util.ArrayList<>();
        java.util.ArrayList<Float> alphas = new java.util.ArrayList<>();
        suppressBlurViewsForFolder(folder, suppressed, alphas);
        Bitmap rendered = null;
        try {
            // Always prefer the full Folder page (chrome + icons), not content-only.
            rendered = GlassHwRasterizer.render(w, h, folder::draw);
            if (rendered == null || rendered.isRecycled() || DesktopItemRenderer.isMostlyEmpty(rendered)) {
                if (rendered != null && !rendered.isRecycled()) rendered.recycle();
                rendered = DesktopItemRenderer.rasterizeViewSoftware(folder, w, h);
            }
            if (rendered == null || rendered.isRecycled() || DesktopItemRenderer.isMostlyEmpty(rendered)) {
                if (rendered != null && !rendered.isRecycled()) rendered.recycle();
                View content = folderContentView(folder);
                if (content != null && content.getWidth() > 0 && content.getHeight() > 0) {
                    int dw = content.getWidth();
                    int dh = content.getHeight();
                    rendered = GlassHwRasterizer.render(dw, dh, content::draw);
                    if (rendered != null && !rendered.isRecycled()) {
                        RectF dest = DesktopRenderUtils.mapItemRectToTarget(content,
                                new Rect(0, 0, dw, dh), globalToTarget);
                        if (dest != null && dest.width() >= 1f && dest.height() >= 1f
                                && RectF.intersects(dest, new RectF(0, 0,
                                region.width(), region.height()))) {
                            DesktopRenderUtils.BITMAP_PAINT.setAlpha(Math.round(255f * Math.min(1f, alpha)));
                            canvas.drawBitmap(rendered, null, dest, DesktopRenderUtils.BITMAP_PAINT);
                            DesktopRenderUtils.BITMAP_PAINT.setAlpha(255);
                        }
                        if (!rendered.isRecycled()) rendered.recycle();
                        return;
                    }
                }
                return;
            }
            RectF dest = DesktopRenderUtils.mapItemRectToTarget(folder, new Rect(0, 0, w, h), globalToTarget);
            if (dest == null || dest.width() < 1f || dest.height() < 1f) return;
            DesktopRenderUtils.BITMAP_PAINT.setAlpha(Math.round(255f * Math.min(1f, alpha)));
            canvas.drawBitmap(rendered, null, dest, DesktopRenderUtils.BITMAP_PAINT);
            DesktopRenderUtils.BITMAP_PAINT.setAlpha(255);
        } catch (Throwable ignored) {
        } finally {
            restoreSuppressedAlphas(suppressed, alphas);
            if (rendered != null && !rendered.isRecycled()) rendered.recycle();
        }
    }

    private static boolean openFolderAnchoredInRegion(View seed, View folder, RectF region) {
        if (folder == null || region == null) return false;
        List<ViewGroup> containers = DesktopRenderUtils.iconContainersUnderRegion(
                seed != null ? seed : folder, region);
        for (ViewGroup icons : containers) {
            for (int i = 0; i < icons.getChildCount(); i++) {
                View child = icons.getChildAt(i);
                if (child == null) continue;
                View host = DesktopRenderUtils.resolveDesktopItem(child);
                View icon = DesktopRenderUtils.isFolderIcon(host) ? host : (DesktopRenderUtils.isFolderIcon(child) ? child : null);
                if (icon == null || !DesktopRenderUtils.intersectsRegion(region, icon)) continue;
                Object linked = DesktopRenderUtils.fieldValue(icon, "mFolder");
                if (linked == folder) return true;
            }
        }
        return false;
    }

    private static void suppressBlurViewsForFolder(View view, java.util.ArrayList<View> out,
            java.util.ArrayList<Float> alphas) {
        if (view == null) return;
        String name = view.getClass().getSimpleName();
        if (name != null && (name.contains("Blur") || name.contains("blur"))) {
            if (view.getVisibility() == View.VISIBLE && view.getAlpha() > 0.01f) {
                out.add(view);
                alphas.add(view.getAlpha());
                view.setAlpha(0f);
            }
        }
        Object blurBg = DesktopRenderUtils.fieldValue(view, "mBlurBgView");
        if (blurBg instanceof View && blurBg != view) {
            suppressBlurViewsForFolder((View) blurBg, out, alphas);
        }
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                suppressBlurViewsForFolder(g.getChildAt(i), out, alphas);
            }
        }
    }

    private static void restoreSuppressedAlphas(java.util.ArrayList<View> views,
            java.util.ArrayList<Float> alphas) {
        for (int i = 0; i < views.size(); i++) {
            try {
                views.get(i).setAlpha(alphas.get(i));
            } catch (Throwable ignored) { }
        }
    }

    static View folderContentView(View folder) {
        Object content = DesktopRenderUtils.fieldValue(folder, "mContent");
        if (content instanceof View) return (View) content;
        content = DesktopRenderUtils.invokeNoArgs(folder, "getContent");
        if (content instanceof View) return (View) content;
        content = DesktopRenderUtils.fieldValue(folder, "mFolderContent");
        if (content instanceof View) return (View) content;
        return null;
    }

    static View findOpenFolderView(View seed) {
        try (PopupCaptureTrace.Stage ignoredTrace = PopupCaptureTrace.section("findOpenFolderView")) {
            try {
                Object launcher;
                try (PopupCaptureTrace.Stage stage = PopupCaptureTrace.section("folder_launcher")) {
                    launcher = resolveLauncherAny(seed);
                }
                PopupCaptureTrace.outcome("folder_launcher", launcher == null ? "missing" : "found");
                try (PopupCaptureTrace.Stage stage = PopupCaptureTrace.section("folder_oem")) {
                    if (launcher != null) {
                        ClassLoader cl = launcher.getClass().getClassLoader();
                        OpenFolderQuery.Result query = OpenFolderQuery.query(launcher);
                        if (query.getAvailable()) {
                            View fromAfv = query.getFolder();
                            // A successful null is the current authoritative answer, not an API
                            // failure. Do not scan every desktop page to rediscover that answer.
                            if (fromAfv == null) return null;
                            if (isFolderCurrentlyOpen(fromAfv)) return fromAfv;
                            PopupCaptureTrace.outcome("folder_query", "returned_not_open");
                        }
                        for (String cls : new String[] {
                                "com.android.launcher3.folder.Folder",
                                "com.android.launcher3.folder.OplusFolder"
                        }) {
                            View fromGet = invokeOpenFolder(launcher, cl, cls, "getOpen");
                            if (fromGet != null) return fromGet;
                        }
                    }
                }
                View root = seed != null
                        ? (seed.getRootView() != null ? seed.getRootView() : seed) : null;
                View found;
                try (PopupCaptureTrace.Stage stage = PopupCaptureTrace.section("folder_tree")) {
                    found = findOpenFolderInTree(root);
                }
                PopupCaptureTrace.outcome("folder_tree", found == null ? "not_found" : "found");
                if (found != null) return found;
                try (PopupCaptureTrace.Stage stage = PopupCaptureTrace.section("folder_icons")) {
                    found = findOpenFolderViaIcons(seed);
                }
                PopupCaptureTrace.outcome("folder_icons", found == null ? "not_found" : "found");
                return found;
            } catch (Throwable ignored) {
                PopupCaptureTrace.outcome("folder_search", "failed");
            }
            return null;
        }
    }

    private static View invokeOpenFolder(Object launcher, ClassLoader cl, String className,
            String methodName) {
        String failure = "lookup_failed";
        try {
            Class<?> cls = Class.forName(className, false, cl);
            boolean matched = false;
            for (java.lang.reflect.Method m : cls.getMethods()) {
                if (!methodName.equals(m.getName()) || m.getParameterCount() != 1) continue;
                matched = true;
                failure = "invoke_failed";
                Object folder = m.invoke(null, launcher);
                failure = "validation_failed";
                if (folder instanceof View && isFolderCurrentlyOpen((View) folder)) {
                    PopupCaptureTrace.outcome(className, "found");
                    return (View) folder;
                }
                PopupCaptureTrace.outcome(className, folder == null ? "returned_null"
                        : folder instanceof View ? "returned_not_open" : "returned_non_view");
                break;
            }
            if (!matched) PopupCaptureTrace.outcome(className, "method_missing");
        } catch (Throwable error) {
            PopupCaptureTrace.outcome(className, error instanceof ClassNotFoundException
                    ? "class_missing" : failure);
        }
        return null;
    }

    static boolean isFolderCurrentlyOpen(View folder) {
        if (folder == null || !isFolderFloatingView(folder)) return false;
        if (folder.getVisibility() == View.GONE) return false;
        if (folder.getWidth() <= 0 || folder.getHeight() <= 0) return false;
        Object open = DesktopRenderUtils.invokeNoArgs(folder, "isOpen");
        if (open instanceof Boolean) return (Boolean) open;
        return Boolean.TRUE.equals(DesktopRenderUtils.fieldValue(folder, "mIsOpen"));
    }

    private static View findOpenFolderViaIcons(View seed) {
        View workspace = DesktopRenderUtils.findWorkspaceAncestor(seed);
        if (workspace == null) {
            Object launcher = resolveLauncherAny(seed);
            Object ws = DesktopRenderUtils.invokeNoArgs(launcher, "getWorkspace");
            if (ws instanceof View) workspace = (View) ws;
        }
        if (workspace == null) return null;
        if (!(workspace instanceof ViewGroup)) return null;
        ViewGroup pages = (ViewGroup) workspace;
        for (int i = 0; i < pages.getChildCount(); i++) {
            ViewGroup icons = DesktopRenderUtils.shortcutsAndWidgetsOf(pages.getChildAt(i));
            if (icons == null) continue;
            for (int j = 0; j < icons.getChildCount(); j++) {
                View child = icons.getChildAt(j);
                if (child == null) continue;
                View host = DesktopRenderUtils.resolveDesktopItem(child);
                View icon = DesktopRenderUtils.isFolderIcon(host) ? host : (DesktopRenderUtils.isFolderIcon(child) ? child : null);
                if (icon == null) continue;
                Object folder = DesktopRenderUtils.fieldValue(icon, "mFolder");
                if (folder instanceof View && isFolderCurrentlyOpen((View) folder)) {
                    return (View) folder;
                }
            }
        }
        return null;
    }

    static Object resolveLauncherAny(View seed) {
        Object launcher = DesktopRenderUtils.findLauncher(seed, DesktopRenderUtils.findWorkspaceAncestor(seed));
        if (launcher != null) return launcher;
        try {
            Class<?> launcherClass = Class.forName("com.android.launcher3.Launcher");
            Object tracker = launcherClass.getField("ACTIVITY_TRACKER").get(null);
            return tracker.getClass().getMethod("getCreatedActivity").invoke(tracker);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** {@code Folder} / {@code OplusFolder}, not FolderIcon / FolderPagedView / FolderName. */
    private static boolean isFolderFloatingView(View view) {
        for (Class<?> c = view == null ? null : view.getClass(); c != null; c = c.getSuperclass()) {
            if ("Folder".equals(c.getSimpleName())) return true;
        }
        return false;
    }

    private static View findOpenFolderInTree(View root) {
        if (root == null) return null;
        if (isFolderCurrentlyOpen(root)) return root;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = findOpenFolderInTree(g.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    /** True when an open Folder floating page is visible (force ashmem icons rebuild). */
    static boolean hasVisibleOpenFolder(View seed) {
        View folder = findOpenFolderView(seed);
        return folder != null && folder.getVisibility() != View.GONE
                && folder.getWidth() > 0 && folder.getHeight() > 0;
    }

    /**
     * Paint apps inside an open {@code Folder} with the same {@code menuGlass} composite
     * path as Workspace options menus ({@link #paintAppMenuComposite} for 角标). Callers
     * must not clear {@code APP_SNAPS} every settle frame. Never rasterize the whole Folder.
     */
    static int paintOpenFolderIconsInto(View openFolder, Canvas canvas,
            Matrix globalToTarget, RectF region, View skipHost,
            java.util.HashSet<View> skipIcons, boolean menuGlass) {
        if (openFolder == null || region == null) return 0;
        ViewGroup icons = openFolderCurrentIconContainer(openFolder);
        if (icons != null) {
            return DesktopIconOverlay.paintIconContainerInto(icons, canvas, globalToTarget, region, skipHost,
                    skipIcons, null, menuGlass);
        }
        View content = folderContentView(openFolder);
        if (!(content instanceof ViewGroup)) return 0;
        // Fallback: only the current page when getCurrentCellLayout is unavailable.
        ViewGroup pages = (ViewGroup) content;
        int painted = 0;
        Object cur = DesktopRenderUtils.invokeNoArgs(content, "getCurrentPage");
        int pageIndex = cur instanceof Number ? ((Number) cur).intValue() : -1;
        if (pageIndex >= 0 && pageIndex < pages.getChildCount()) {
            ViewGroup pageIcons = DesktopRenderUtils.shortcutsAndWidgetsOf(pages.getChildAt(pageIndex));
            if (pageIcons != null) {
                return DesktopIconOverlay.paintIconContainerInto(pageIcons, canvas, globalToTarget, region,
                        skipHost, skipIcons, null, menuGlass);
            }
        }
        for (int i = 0; i < pages.getChildCount(); i++) {
            ViewGroup pageIcons = DesktopRenderUtils.shortcutsAndWidgetsOf(pages.getChildAt(i));
            if (pageIcons == null) continue;
            painted += DesktopIconOverlay.paintIconContainerInto(pageIcons, canvas, globalToTarget, region,
                    skipHost, skipIcons, null, menuGlass);
        }
        return painted;
    }

    /** Current-page {@code ShortcutAndWidgetContainer} inside an open Folder. */
    private static ViewGroup openFolderCurrentIconContainer(View openFolder) {
        View content = folderContentView(openFolder);
        if (content == null) return DesktopRenderUtils.shortcutsAndWidgetsOf(openFolder);
        Object cell = DesktopRenderUtils.invokeNoArgs(content, "getCurrentCellLayout");
        if (cell instanceof View) {
            ViewGroup icons = DesktopRenderUtils.shortcutsAndWidgetsOf((View) cell);
            if (icons != null) return icons;
        }
        Object cur = DesktopRenderUtils.invokeNoArgs(content, "getCurrentPage");
        if (cur instanceof Number) {
            try {
                Object page = content.getClass().getMethod("getPageAt", int.class)
                        .invoke(content, ((Number) cur).intValue());
                if (page instanceof View) {
                    ViewGroup icons = DesktopRenderUtils.shortcutsAndWidgetsOf((View) page);
                    if (icons != null) return icons;
                }
            } catch (Throwable ignored) { }
        }
        return DesktopRenderUtils.shortcutsAndWidgetsOf(content);
    }

    /**
     * Draw open-folder header title ({@code R.id.folder_name} / {@code mFolderName}) into the
     * menu sample with canvas text only — no View.draw, no setAlpha, no Folder-page rasterize.
     */
    static boolean paintOpenFolderTitleInto(View openFolder, Canvas canvas,
            Matrix globalToTarget) {
        TextView name = openFolderTitleView(openFolder);
        if (name == null || name.getVisibility() != View.VISIBLE) return false;
        if (name.getWidth() <= 0 || name.getHeight() <= 0) return false;
        CharSequence text = name.getText();
        boolean fromHint = false;
        if (TextUtils.isEmpty(text)) {
            text = name.getHint();
            fromHint = true;
        }
        if (TextUtils.isEmpty(text)) return false;
        Matrix itemToGlobal = new Matrix();
        name.transformMatrixToGlobal(itemToGlobal);
        Matrix itemToHost = new Matrix();
        itemToHost.setConcat(globalToTarget, itemToGlobal);
        int save = canvas.save();
        canvas.concat(itemToHost);
        try {
            DesktopRenderUtils.TITLE_PAINT.set(name.getPaint());
            int color = fromHint ? name.getCurrentHintTextColor() : name.getCurrentTextColor();
            if (color == 0) color = name.getCurrentTextColor();
            DesktopRenderUtils.TITLE_PAINT.setColor(color);
            DesktopRenderUtils.TITLE_PAINT.setAlpha(255);
            DesktopRenderUtils.TITLE_PAINT.setTextAlign(Paint.Align.CENTER);
            float x = name.getWidth() * 0.5f;
            float y = name.getBaseline();
            if (y <= 0f) {
                Paint.FontMetrics fm = DesktopRenderUtils.TITLE_PAINT.getFontMetrics();
                y = (name.getHeight() - fm.ascent - fm.descent) * 0.5f;
            }
            canvas.drawText(text.toString(), x, y, DesktopRenderUtils.TITLE_PAINT);
            return true;
        } catch (Throwable ignored) {
            return false;
        } finally {
            canvas.restoreToCount(save);
        }
    }

    private static TextView openFolderTitleView(View openFolder) {
        if (openFolder == null) return null;
        TextView fromField = FolderCompositeRenderer.folderNameView(openFolder);
        if (fromField != null) return fromField;
        try {
            String pkg = openFolder.getContext().getPackageName();
            int id = openFolder.getResources().getIdentifier("folder_name", "id", pkg);
            if (id == 0) {
                id = openFolder.getResources().getIdentifier(
                        "folder_name", "id", "com.android.launcher");
            }
            if (id != 0) {
                View found = openFolder.findViewById(id);
                if (found instanceof TextView) return (TextView) found;
            }
        } catch (Throwable ignored) { }
        return null;
    }
}
