package net.z841973620.colorosliquidglass.glass;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;
import net.z841973620.colorosliquidglass.hook.ColorOsVersion;
import java.util.List;

/** 桌面内容合成入口：安排图标、文件夹、拖拽和弹窗的绘制顺序，统一释放缓存。 */
final class DesktopIconOverlay {


    private DesktopIconOverlay() {}

    /**
     * Software backdrop path for moving-folder glass ({@code *DragView}) and long-press
     * options-menu plates ({@code colg_desktop_popup}). Both use
     * {@link #paintDesktopBehindHost}.
     * <p>
     * Page-indicator dots live in the horizontal strip between Workspace and Hotseat.
     * A folder sitting entirely on that strip intersects neither icon container — still
     * paint the dots (do not early-return when containers are empty).
     * @return true if at least one desktop item was painted
     */
    static boolean paintIntoTargetLocal(View glassHost, Canvas canvas) {
        if (glassHost == null || canvas == null) return false;
        boolean drag = DragViewOverlay.findDragView(glassHost) != null;
        boolean popup = "colg_desktop_popup".equals(glassHost.getTag());
        if (!drag && !popup) return false;
        return paintDesktopBehindHost(glassHost, canvas);
    }

    /**
     * Desktop icons/folders under glass in target-local space (moving folder / options menu).
     * @return true if at least one desktop item was painted into the sample
     */
    static boolean paintDesktopBehindHost(View glassHost, Canvas canvas) {
        if (glassHost == null || canvas == null) return false;
        View seed = BackdropCapture.overlaySourceOf(glassHost);
        Matrix targetToGlobal = new Matrix();
        glassHost.transformMatrixToGlobal(targetToGlobal);
        Matrix globalToTarget = new Matrix();
        if (!targetToGlobal.invert(globalToTarget)) {
            // Singular while open-anim scale≈0 — fall back to window translate.
            globalToTarget.reset();
            int[] loc = new int[2];
            glassHost.getLocationInWindow(loc);
            globalToTarget.setTranslate(-loc[0], -loc[1]);
        }
        RectF region = DesktopRenderUtils.screenRect(glassHost);
        if (region == null) return false;
        // Never fall back to the popup itself — it is a DragLayer sibling of Workspace, so
        // iconContainersUnderRegion(popup) finds no desktop cells (wallpaper-only settle).
        View anchor = seed != null ? seed : resolveWorkspaceSeed();
        if (anchor == null) return false;
        boolean popup = "colg_desktop_popup".equals(glassHost.getTag());
        View root = anchor.getRootView() != null ? anchor.getRootView() : anchor;
        // Mid-release: DragView may still be canceling — skip origin ghosts and paint the
        // live DragView like the float-menu path so resample keeps publishing icons.
        java.util.HashSet<View> skipIcons = null;
        if (popup) {
            skipIcons = new java.util.HashSet<>();
            DragViewOverlay.collectDragSources(root, skipIcons);
            FolderPreviewOverlay.collectDragControllerSources(anchor, skipIcons);
        }
        // Drag glass only: accept/create plates are CellLayout-delegated. Never run this
        // while sampling options-menu glass — forceCapturePreviewPlate nests into live
        // folder plates and poisons FOLDER_SNAPS (folder menu → later app menu 花屏).
        if (!popup) {
            FolderPreviewOverlay.paintDelegatedFolderPreviews(anchor, canvas, globalToTarget, region);
        }
        View skipHost = DragViewOverlay.findDragView(glassHost) != null ? glassHost : null;
        int painted;
        // In-folder options menu: same cached menu composite as desktop options glass
        // (paintAppMenuComposite → 角标) + lightweight title. Never whole-Folder rasterize.
        View openFolder = popup ? OpenFolderOverlay.findOpenFolderView(anchor) : null;
        if (popup && openFolder != null && OpenFolderOverlay.isFolderCurrentlyOpen(openFolder)) {
            FolderPreviewOverlay.collectOpenFolderDesktopSkips(anchor, openFolder, skipIcons);
            painted = OpenFolderOverlay.paintOpenFolderIconsInto(openFolder, canvas, globalToTarget, region,
                    skipHost, skipIcons, true);
            if (OpenFolderOverlay.paintOpenFolderTitleInto(openFolder, canvas, globalToTarget)) {
                painted++;
            }
        } else {
            // Only modern Launcher popups: Cos14 must keep its proven software path.
            PopupFolderBatch batch = popup && !canvas.isHardwareAccelerated()
                    && ColorOsVersion.detect() == ColorOsVersion.Flavor.MODERN
                    ? new PopupFolderBatch(glassHost.getWidth()) : null;
            painted = paintDesktopInto(anchor, canvas, globalToTarget, region, skipHost,
                    skipIcons, null, popup, batch);
            // GPU failure keeps the existing capture caller's previous-frame/retry policy.
            if (batch != null && !batch.paintInto(canvas)) return false;
        }
        boolean underPopup = false;
        if (popup) {
            DragViewOverlay.paintDragViewsInto(root, canvas, globalToTarget, region);
            // Cos16 More-functions submenu only. Cos14 mounts several colg_desktop_popup
            // panels; refracting siblings forceCaptures them mid-sample → PopupScaleSnapshot
            // normalizes the shared OplusPopupContainerWithArrow scale and kills open anim.
            if (BackdropCapture.isMoreFunctionsSubMenuActive()) {
                underPopup = DesktopPopupOverlay.paintOtherDesktopPopupGlass(root, glassHost, canvas, globalToTarget,
                        region);
            }
            // DragView alone under the menu still counts as a valid icon frame.
            if (painted == 0 && FolderPreviewOverlay.hasLiveDragView(root)) return true;
        }
        return painted > 0 || underPopup;
    }

    /**
     * Virtual folder-glass composite for an arbitrary on-screen rect (SystemUI float menu).
     * Same wallpaper companion path as drag glass: FastBitmap icons, folder plates, widgets,
     * page dots — no DragView / no real FolderIcon host required.
     */
    static void paintIntoScreenRect(Rect screenRect, Canvas canvas, View seed) {
        if (screenRect == null || screenRect.isEmpty() || canvas == null) return;
        Matrix globalToTarget = new Matrix();
        globalToTarget.setTranslate(-screenRect.left, -screenRect.top);
        RectF region = new RectF(screenRect);
        View anchor = seed != null ? seed : resolveWorkspaceSeed();
        if (anchor == null) return;
        View root = anchor.getRootView() != null ? anchor.getRootView() : anchor;
        // While a DragView is live, never paint the origin cell (even if the DragView is
        // outside the float-menu crop). Otherwise cancel move-out invents the icon at the
        // home cell while the real glyph is elsewhere. paintDragViewsInto only draws the
        // DragView when it intersects the sample; drag end clears the skip.
        java.util.HashSet<View> skipIcons = new java.util.HashSet<>();
        DragViewOverlay.collectDragSources(root, skipIcons);
        FolderPreviewOverlay.collectDragControllerSources(anchor, skipIcons);
        FolderPreviewOverlay.collectCreateFolderHosts(anchor, skipIcons);
        View openFolder = OpenFolderOverlay.findOpenFolderView(anchor);
        FolderPreviewOverlay.collectOpenFolderDesktopSkips(anchor, openFolder, skipIcons);
        // While dragging into an open Folder, hide workspace icons under the folder content
        // (avoids overlap with the Folder page). Cleared as soon as the folder closes or the
        // drag ends — cancel move-out restores those icons.
        RectF folderDropCover = FolderPreviewOverlay.folderDropIconCover(openFolder, root);
        FolderPreviewOverlay.paintDelegatedFolderPreviews(anchor, canvas, globalToTarget, region);
        // HW-snap apps (cached) so ColorOS onDraw/ViewOverlay 角标 appear under float glass —
        // FastBitmap alone omits badges. Same path as options-menu backdrop sampling.
        paintDesktopInto(anchor, canvas, globalToTarget, region, null, skipIcons, folderDropCover,
                true);
        OpenFolderOverlay.paintOpenFolders(anchor, canvas, globalToTarget, region);
        DragViewOverlay.paintDragViewsInto(root, canvas, globalToTarget, region);
    }

    /**
     * @param skipHost when non-null (drag glass), skip the dragged folder itself
     * @param skipIcons DragView origins / create-folder drop-target / open FolderIcon only
     * @param openFolderCover while dragging into an open Folder, skip icons under folder content
     * @param menuGlass when true, HW-snap apps so onDraw/ViewOverlay 角标 are included;
     *                  Cos14 folders use soft/warm sticky snaps; Cos16 folders keep live-plate HW
     * @return number of desktop items painted
     */
    private static int paintDesktopInto(View seed, Canvas canvas, Matrix globalToTarget,
            RectF region, View skipHost, java.util.HashSet<View> skipIcons,
            RectF openFolderCover, boolean menuGlass) {
        return paintDesktopInto(seed, canvas, globalToTarget, region, skipHost,
                skipIcons, openFolderCover, menuGlass, null);
    }

    private static int paintDesktopInto(View seed, Canvas canvas, Matrix globalToTarget,
            RectF region, View skipHost, java.util.HashSet<View> skipIcons,
            RectF openFolderCover, boolean menuGlass, PopupFolderBatch batch) {
        int painted = 0;
        List<ViewGroup> containers = DesktopRenderUtils.iconContainersUnderRegion(seed, region);
        for (ViewGroup icons : containers) {
            painted += paintIconContainerInto(icons, canvas, globalToTarget, region, skipHost,
                    skipIcons, openFolderCover, menuGlass, batch);
        }

        View workspace = DesktopRenderUtils.findWorkspaceAncestor(seed);
        View pageIndicator = DesktopRenderUtils.findPageIndicator(seed, workspace);
        if (pageIndicator != null
                && pageIndicator.getVisibility() == View.VISIBLE
                && pageIndicator.getAlpha() > 0.01f
                && pageIndicator.getWidth() > 0 && pageIndicator.getHeight() > 0
                && DesktopRenderUtils.intersectsRegion(region, pageIndicator)) {
            try {
                if (batch == null) DesktopChromeOverlay.paintDesktopChrome(pageIndicator, canvas, globalToTarget);
                else batch.addSoftware(() -> {
                    // The same OEM-draw boundary as the immediate path's surrounding catch.
                    try { DesktopChromeOverlay.paintDesktopChrome(pageIndicator, canvas, globalToTarget); }
                    catch (Throwable ignored) { }
                });
                painted++;
            } catch (Throwable ignored) { }
        }
        return painted;
    }

    static int paintIconContainerInto(ViewGroup icons, Canvas canvas,
            Matrix globalToTarget, RectF region, View skipHost,
            java.util.HashSet<View> skipIcons, RectF openFolderCover, boolean menuGlass) {
        return paintIconContainerInto(icons, canvas, globalToTarget, region, skipHost,
                skipIcons, openFolderCover, menuGlass, null);
    }

    static int paintIconContainerInto(ViewGroup icons, Canvas canvas,
            Matrix globalToTarget, RectF region, View skipHost,
            java.util.HashSet<View> skipIcons, RectF openFolderCover, boolean menuGlass,
            PopupFolderBatch batch) {
        if (icons == null) return 0;
        int painted = 0;
        for (int i = 0; i < icons.getChildCount(); i++) {
            View child = icons.getChildAt(i);
            if (child == null || child.getVisibility() != View.VISIBLE) continue;
            // Do not skip low-alpha icons: ColorOS dims the workspace during create-folder /
            // folder accept; those icons must still appear in float-menu glass.
            if (child.getWidth() <= 0 || child.getHeight() <= 0) continue;
            if (!DesktopRenderUtils.intersectsRegion(region, child)) continue;
            View host = DesktopRenderUtils.resolveDesktopItem(child);
            if (host == null) continue;
            if (skipHost != null && DragViewOverlay.isDragSourceOrSelf(skipHost, host)) continue;
            if (skipIcons != null && (skipIcons.contains(host) || skipIcons.contains(child)
                    || isListedDragSource(host, child, skipIcons))) {
                continue;
            }
            // Drop-into-open-folder: omit workspace icons under the folder page (overlap).
            // Cover is null once the folder closes or the drag ends → icons restore.
            if (openFolderCover != null
                    && (FolderPreviewOverlay.iconCoveredByFolderDrop(openFolderCover, child)
                    || FolderPreviewOverlay.iconCoveredByFolderDrop(openFolderCover, host))) {
                continue;
            }
            try {
                if (DesktopRenderUtils.isFolderIcon(host) || DesktopRenderUtils.isFolderIcon(child)) {
                    FolderCompositeRenderer.paintFolderIntoSample(DesktopRenderUtils.isFolderIcon(host) ? host : child,
                            canvas, globalToTarget, menuGlass, batch);
                    painted++;
                } else {
                    if (batch == null) {
                        paintNonFolderItem(child, host, canvas, globalToTarget, menuGlass);
                    } else {
                        batch.addSoftware(() -> paintNonFolderItem(child, host, canvas, globalToTarget, menuGlass));
                    }
                    painted++;
                }
            } catch (Throwable ignored) {
            }
        }
        return painted;
    }

    private static void paintNonFolderItem(View child, View host, Canvas canvas,
            Matrix globalToTarget, boolean menuGlass) {
        // OEM item draw methods are an external boundary, as in the unbatched loop.
        try {
            if (DesktopRenderUtils.isWidgetOrCard(host) || DesktopRenderUtils.isWidgetOrCard(child)) {
                DesktopItemRenderer.paintWidget(DesktopRenderUtils.isWidgetOrCard(host) ? host : child, canvas, globalToTarget);
            } else if (!menuGlass || !DesktopItemRenderer.paintAppMenuComposite(child, host, canvas, globalToTarget)) {
                DesktopItemRenderer.paintAppIcon(host, canvas, globalToTarget);
                DesktopItemRenderer.paintAppLabel(host, canvas, globalToTarget);
            }
        } catch (Throwable ignored) { }
    }

    private static boolean isListedDragSource(View host, View child,
            java.util.HashSet<View> dragSources) {
        for (View src : dragSources) {
            if (src == null || FolderPreviewOverlay.isBroadContainer(src)) continue;
            if (host == src || child == src) return true;
            // Limited descendant match for wrap containers — never against CellLayout/Workspace.
            if (DragViewOverlay.isDescendant(host, src) || DragViewOverlay.isDescendant(child, src)) return true;
        }
        return false;
    }

    static View resolveWorkspaceSeed() {
        try {
            Class<?> launcherClass = Class.forName("com.android.launcher3.Launcher");
            Object tracker = launcherClass.getField("ACTIVITY_TRACKER").get(null);
            Object launcher = tracker.getClass().getMethod("getCreatedActivity").invoke(tracker);
            Object ws = DesktopRenderUtils.invokeNoArgs(launcher, "getWorkspace");
            if (ws instanceof View) return (View) ws;
            if (launcher instanceof android.app.Activity) {
                return ((android.app.Activity) launcher).getWindow().getDecorView();
            }
        } catch (Throwable ignored) { }
        return null;
    }

    static void clearFolderSnaps() {
        for (FolderCompositeRenderer.FolderSnap snap : FolderCompositeRenderer.FOLDER_SNAPS.values()) {
            if (snap != null && snap.bitmap != null && !snap.bitmap.isRecycled()) {
                snap.bitmap.recycle();
            }
        }
        FolderCompositeRenderer.FOLDER_SNAPS.clear();
        for (DesktopItemRenderer.WidgetSnap snap : DesktopItemRenderer.WIDGET_SNAPS.values()) {
            if (snap != null && snap.bitmap != null && !snap.bitmap.isRecycled()) {
                snap.bitmap.recycle();
            }
        }
        DesktopItemRenderer.WIDGET_SNAPS.clear();
        clearAppSnaps();
    }

    /** Drop cached app HW snaps (icon + 角标) so the next menu sample is live. */
    static void clearAppSnaps() {
        for (DesktopItemRenderer.AppSnap snap : DesktopItemRenderer.APP_SNAPS.values()) {
            if (snap != null && snap.bitmap != null && !snap.bitmap.isRecycled()) {
                snap.bitmap.recycle();
            }
        }
        DesktopItemRenderer.APP_SNAPS.clear();
    }
    static boolean hasDesktopPopupGlass(View seed) {
        return DesktopPopupOverlay.hasDesktopPopupGlass(seed);
    }

    static void paintDesktopPopupGlassIntoScreenRect(Rect screenRect, Canvas canvas) {
        DesktopPopupOverlay.paintDesktopPopupGlassIntoScreenRect(screenRect, canvas);
    }

    static void paintDesktopPopupGlassIntoScreenRect(Rect screenRect, Canvas canvas, View seedRoot) {
        DesktopPopupOverlay.paintDesktopPopupGlassIntoScreenRect(screenRect, canvas, seedRoot);
    }

    static boolean isOrContainsDesktopPopupGlass(View view) {
        return DesktopPopupOverlay.isOrContainsDesktopPopupGlass(view);
    }

    static boolean hasVisibleOpenFolder(View seed) {
        return OpenFolderOverlay.hasVisibleOpenFolder(seed);
    }

    static boolean hasDelegatedFolderPreview(View seed) {
        return FolderPreviewOverlay.hasDelegatedFolderPreview(seed);
    }

    static void paintDragViewsIntoScreenRect(Rect screenRect, Canvas canvas, View root) {
        DragViewOverlay.paintDragViewsIntoScreenRect(screenRect, canvas, root);
    }

}
