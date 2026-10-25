package net.z841973620.colorosliquidglass.glass;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import net.z841973620.colorosliquidglass.hook.ColorOsVersion;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 创建/接纳文件夹时，CellLayout 委托绘制的预览图标和底板。 */
final class FolderPreviewOverlay {
    private FolderPreviewOverlay() {}


    /**
     * ColorOS draws create-folder and folder-accept plates via
     * {@code CellLayout → OplusPreviewBackground.drawBackground}, not as FolderIcon children.
     * Ashmem sampling must paint those plates explicitly.
     */
    static void paintDelegatedFolderPreviews(View seed, Canvas canvas,
            Matrix globalToTarget, RectF region) {
        View workspace = DesktopRenderUtils.findWorkspaceAncestor(seed);
        if (workspace != null) {
            paintOneDelegatedPreview(DesktopRenderUtils.fieldValue(workspace, "mGroupCreatePreviewBg"),
                    canvas, globalToTarget, region);
        }
        List<ViewGroup> containers = DesktopRenderUtils.iconContainersUnderRegion(seed, region);
        for (ViewGroup icons : containers) {
            for (int i = 0; i < icons.getChildCount(); i++) {
                View child = icons.getChildAt(i);
                if (child == null) continue;
                View host = DesktopRenderUtils.resolveDesktopItem(child);
                View folder = DesktopRenderUtils.isFolderIcon(host) ? host : (DesktopRenderUtils.isFolderIcon(child) ? child : null);
                if (folder == null) continue;
                paintOneDelegatedPreview(DesktopRenderUtils.fieldValue(folder, "mBackground"),
                        canvas, globalToTarget, region);
            }
        }
    }

    private static void paintOneDelegatedPreview(Object preview, Canvas canvas,
            Matrix globalToTarget, RectF region) {
        if (preview == null || canvas == null) return;
        if (!isPreviewDrawingDelegated(preview)) return;
        Object delegate = DesktopRenderUtils.fieldValue(preview, "mDrawingDelegate");
        if (!(delegate instanceof View)) return;
        View cellLayout = (View) delegate;
        Object rectObj = DesktopRenderUtils.invokeNoArgs(preview, "getBackgroundRect");
        if (!(rectObj instanceof Rect)) return;
        Rect bg = (Rect) rectObj;
        if (bg.width() <= 0 || bg.height() <= 0) return;

        int cellX = intField(preview, "mDelegateCellX");
        int cellY = intField(preview, "mDelegateCellY");
        int[] cellPoint = new int[2];
        if (!cellToPoint(cellLayout, cellX, cellY, cellPoint)) return;

        Object bgViewObj = DesktopRenderUtils.fieldValue(preview, "mBgView");
        View bgView = bgViewObj instanceof View ? (View) bgViewObj : null;
        GlassDrawable glass = bgView != null ? GlassInstaller.get(bgView) : null;
        if (glass == null) {
            // Cos14 has no mBgView — the capture host is the invisible ImageView child of
            // the FolderIcon. Fall back to it so accept plates stay visible under drag glass.
            View folderIcon = resolveFolderIconFromPreview(preview);
            View capture = folderIcon != null ? FolderCompositeRenderer.findFolderGlassCaptureHost(folderIcon) : null;
            if (capture != null) {
                bgView = capture;
                glass = GlassInstaller.get(capture);
            }
        }
        if (glass == null) return;

        // Create-folder: park mBgView on CellLayout at the plate so forceCapture samples the
        // same screen pixels CellLayout paints (detached/wrong park → offset + bright).
        boolean createFolder = !isPreviewBoundToFolderIcon(preview);
        if (createFolder) {
            parkCreateFolderCaptureHost(preview, cellLayout, bg, cellPoint);
            bgViewObj = DesktopRenderUtils.fieldValue(preview, "mBgView");
            bgView = bgViewObj instanceof View ? (View) bgViewObj : null;
            glass = bgView != null ? GlassInstaller.get(bgView) : null;
            if (glass == null) return;
        }

        // Dest must match CellLayout delegated plate (accept canvas). Mapping via FolderIcon
        // alone skews an accept-scaled plate toward bottom-right under drag glass.
        RectF dest = plateDestOnScreen(bgView, cellLayout, cellPoint, bg);
        if (dest == null || !RectF.intersects(region, dest)) return;
        globalToTarget.mapRect(dest);

        final int w = bg.width();
        final int h = bg.height();
        final View captureHost = bgView;
        final GlassDrawable plate = glass;
        // Backdrop is the folder's own resting plate sample — reuse it once warmed. Never
        // forceCapture every frame: the drag glass sample runs continuously, and each
        // forceCapture re-rasterizes the whole tree (visible as flicker on the accept plate).
        if (captureHost != null && !GlassInstaller.hasBackdropFrame(captureHost)
                && captureHost.getParent() != null
                && captureHost.getWidth() > 0 && captureHost.getHeight() > 0) {
            try {
                GlassInstaller.forceCapturePreviewPlate(captureHost);
            } catch (Throwable ignored) { }
        }
        if (captureHost == null || !GlassInstaller.hasBackdropFrame(captureHost)) {
            // Without a backdrop frame GlassDrawable paints bright translucent AGSL fallback.
            return;
        }
        Bitmap rendered = GlassHwRasterizer.render(w, h, hwCanvas -> {
            try {
                plate.setBounds(0, 0, w, h);
                float radius = 0f;
                Object rv = DesktopRenderUtils.fieldValue(preview, "mRadius");
                if (rv instanceof Number) radius = ((Number) rv).floatValue();
                Object scale = DesktopRenderUtils.fieldValue(preview, "mScale");
                if (scale instanceof Number) radius *= ((Number) scale).floatValue();
                if (radius > 0f) plate.setCornerRadii(radius, radius, radius, radius);
                plate.draw(hwCanvas);
            } catch (Throwable ignored) { }
        });
        if (rendered == null || rendered.isRecycled()) return;
        canvas.drawBitmap(rendered, null, dest, DesktopRenderUtils.BITMAP_PAINT);
        if (!rendered.isRecycled()) rendered.recycle();

        // Plate glass alone is empty of preview glyphs — paint shrunk icons into the plate.
        if (createFolder) {
            paintCreateFolderPreviewIcons(preview, cellLayout, dest, canvas);
        } else {
            View folderIcon = resolveFolderIconFromPreview(preview);
            if (folderIcon != null) {
                paintFolderPreviewIconsIntoPlate(folderIcon, dest, canvas);
            }
        }
    }

    /** BubbleTextViews currently hosting the shared create-folder PreviewBackground. */
    static void collectCreateFolderHosts(View seed, java.util.HashSet<View> out) {
        if (seed == null || out == null) return;
        View workspace = DesktopRenderUtils.findWorkspaceAncestor(seed);
        Object createBg = workspace != null
                ? DesktopRenderUtils.fieldValue(workspace, "mGroupCreatePreviewBg") : null;
        if (createBg == null || !isPreviewDrawingDelegated(createBg)) return;

        // Only leaf icon views — never CellLayout / Workspace (that wiped the whole page via
        // isListedDragSource descendant matching).
        addSkipIcon(out, DesktopRenderUtils.fieldValue(createBg, "mInvalidateDelegate"));

        Object delegate = DesktopRenderUtils.fieldValue(createBg, "mDrawingDelegate");
        if (delegate instanceof View) {
            addSkipIcon(out, findIconAtDelegateCell((View) delegate, createBg));
        }

        List<ViewGroup> containers = new ArrayList<>();
        if (workspace instanceof ViewGroup) {
            ViewGroup pages = (ViewGroup) workspace;
            for (int i = 0; i < pages.getChildCount(); i++) {
                ViewGroup icons = DesktopRenderUtils.shortcutsAndWidgetsOf(pages.getChildAt(i));
                if (icons != null) containers.add(icons);
            }
        }
        ViewGroup primary = DesktopRenderUtils.shortcutsAndWidgetsOf(workspace);
        if (primary != null && !containers.contains(primary)) containers.add(primary);
        for (ViewGroup icons : containers) {
            for (int i = 0; i < icons.getChildCount(); i++) {
                View child = icons.getChildAt(i);
                if (child == null) continue;
                View host = DesktopRenderUtils.resolveDesktopItem(child);
                View target = host != null ? host : child;
                if (DesktopRenderUtils.isFolderIcon(target) || isBroadContainer(target)) continue;
                if (viewHoldsPreviewBackground(target, createBg)
                        || viewHoldsPreviewBackground(child, createBg)) {
                    addSkipIcon(out, target);
                    addSkipIcon(out, child);
                }
            }
        }
    }

    /** Add only desktop icon leaves to the skip set (never page/layout containers). */
    static void addSkipIcon(java.util.HashSet<View> out, Object candidate) {
        if (out == null || !(candidate instanceof View)) return;
        View view = (View) candidate;
        if (isBroadContainer(view)) return;
        if (!(isDesktopIconLeaf(view) || DesktopRenderUtils.isFolderIcon(view))) {
            View resolved = DesktopRenderUtils.resolveDesktopItem(view);
            if (resolved != null && resolved != view && !isBroadContainer(resolved)
                    && (isDesktopIconLeaf(resolved) || DesktopRenderUtils.isFolderIcon(resolved))) {
                out.add(resolved);
            }
            return;
        }
        out.add(view);
        View resolved = DesktopRenderUtils.resolveDesktopItem(view);
        if (resolved != null && !isBroadContainer(resolved)) out.add(resolved);
    }

    static boolean isBroadContainer(View view) {
        if (view == null) return false;
        for (Class<?> c = view.getClass(); c != null; c = c.getSuperclass()) {
            String name = c.getSimpleName();
            if (name == null) continue;
            if (name.contains("CellLayout") || name.contains("Workspace")
                    || name.contains("DragLayer") || name.contains("ShortcutAndWidget")
                    || name.equals("Folder") || name.endsWith("FolderPagedView")
                    || name.contains("Hotseat") && !name.contains("Icon")) {
                return true;
            }
        }
        return view instanceof ViewGroup && DesktopItemRenderer.iconDrawableOf(view) == null && !DesktopRenderUtils.isFolderIcon(view)
                && !(view instanceof TextView);
    }

    private static boolean isDesktopIconLeaf(View view) {
        if (view == null || isBroadContainer(view)) return false;
        if (DesktopRenderUtils.isFolderIcon(view)) return true;
        if (DesktopItemRenderer.iconDrawableOf(view) != null) return true;
        String name = view.getClass().getSimpleName();
        return name != null && (name.contains("BubbleText") || name.contains("ShortcutContainer")
                || name.contains("AppIcon"));
    }

    private static boolean viewHoldsPreviewBackground(View view, Object preview) {
        if (view == null || preview == null) return false;
        for (String name : new String[] {
                "mFolderPreviewBackGround", "previewBackground", "mFolderBackground",
                "mPreviewBackground", "folderBackground"
        }) {
            if (DesktopRenderUtils.fieldValue(view, name) == preview) return true;
        }
        return false;
    }

    private static View findIconAtDelegateCell(View cellLayout, Object preview) {
        if (cellLayout == null || preview == null) return null;
        int cellX = intField(preview, "mDelegateCellX");
        int cellY = intField(preview, "mDelegateCellY");
        ViewGroup icons = DesktopRenderUtils.shortcutsAndWidgetsOf(cellLayout);
        if (icons == null) return null;
        for (int i = 0; i < icons.getChildCount(); i++) {
            View child = icons.getChildAt(i);
            if (child == null) continue;
            int[] xy = readCellXY(child);
            if (xy != null && xy[0] == cellX && xy[1] == cellY) return child;
            View host = DesktopRenderUtils.resolveDesktopItem(child);
            if (host != null && host != child) {
                int[] hxy = readCellXY(host);
                if (hxy != null && hxy[0] == cellX && hxy[1] == cellY) return host;
            }
        }
        return null;
    }

    private static int[] readCellXY(View view) {
        if (view == null) return null;
        Object lp = view.getLayoutParams();
        if (lp == null) return null;
        Object cx = DesktopRenderUtils.fieldValue(lp, "cellX");
        Object cy = DesktopRenderUtils.fieldValue(lp, "cellY");
        if (!(cx instanceof Number) || !(cy instanceof Number)) return null;
        return new int[]{((Number) cx).intValue(), ((Number) cy).intValue()};
    }

    /** DragController.mDragObject.originalView — ColorOS may not keep content on DragView. */
    static void collectDragControllerSources(View seed, java.util.HashSet<View> out) {
        try (PopupCaptureTrace.Stage ignoredTrace = PopupCaptureTrace.section("collectDragControllerSources")) {
            if (seed == null || out == null) return;
            try {
                Object launcher = OpenFolderOverlay.resolveLauncherAny(seed);
                Object dragCtrl = DesktopRenderUtils.invokeNoArgs(launcher, "getDragController");
                if (dragCtrl == null) dragCtrl = DesktopRenderUtils.fieldValue(launcher, "mDragController");
                Object dragObj = DesktopRenderUtils.fieldValue(dragCtrl, "mDragObject");
                if (dragObj == null) return;
                // Only suppress the origin while a DragView is still live. After cancel / drop,
                // the DragView is gone and the origin must paint again with the desktop.
                View root = seed.getRootView() != null ? seed.getRootView() : seed;
                if (!hasLiveDragView(root)) return;
                for (String name : new String[] {
                        "originalView", "mOriginalView", "sourceView", "mSourceView"
                }) {
                    addSkipIcon(out, DesktopRenderUtils.fieldValue(dragObj, name));
                }
            } catch (Throwable ignored) { }
        }
    }

    /**
     * When a Folder is open, skip only its FolderIcon on the workspace (not every icon under
     * the floating Folder bounds — that prevented restore on cancel).
     */
    static void collectOpenFolderDesktopSkips(View seed, View openFolder,
            java.util.HashSet<View> out) {
        if (out == null) return;
        if (openFolder == null) openFolder = OpenFolderOverlay.findOpenFolderView(seed);
        if (openFolder == null || !OpenFolderOverlay.isFolderCurrentlyOpen(openFolder)) return;

        Object folderIcon = DesktopRenderUtils.fieldValue(openFolder, "mFolderIcon");
        addSkipIcon(out, folderIcon);

        View workspace = DesktopRenderUtils.findWorkspaceAncestor(seed);
        if (workspace == null) return;
        if (workspace instanceof ViewGroup) {
            ViewGroup pages = (ViewGroup) workspace;
            for (int i = 0; i < pages.getChildCount(); i++) {
                ViewGroup icons = DesktopRenderUtils.shortcutsAndWidgetsOf(pages.getChildAt(i));
                if (icons == null) continue;
                for (int j = 0; j < icons.getChildCount(); j++) {
                    View child = icons.getChildAt(j);
                    if (child == null) continue;
                    View host = DesktopRenderUtils.resolveDesktopItem(child);
                    View target = host != null ? host : child;
                    if (!DesktopRenderUtils.isFolderIcon(target)) continue;
                    Object linked = DesktopRenderUtils.fieldValue(target, "mFolder");
                    if (linked == openFolder) {
                        addSkipIcon(out, target);
                        addSkipIcon(out, child);
                    }
                }
            }
        }
    }

    /**
     * Screen rect of icons to hide while dragging into an open Folder.
     * <ul>
     *   <li>Requires {@code isOpen} — cancel/close clears this immediately.</li>
     *   <li>Requires a live DragView — idle open folder keeps workspace icons.</li>
     *   <li>Uses folder content bounds (not the full Folder chrome/scrim) so half the
     *       desktop is not wiped.</li>
     * </ul>
     */
    static RectF folderDropIconCover(View openFolder, View root) {
        if (openFolder == null || !OpenFolderOverlay.isFolderCurrentlyOpen(openFolder)) return null;
        if (!hasLiveDragView(root)) return null;
        View content = OpenFolderOverlay.folderContentView(openFolder);
        RectF cover = content != null ? DesktopRenderUtils.screenRect(content) : null;
        if (cover == null || cover.width() < 2f || cover.height() < 2f) {
            RectF full = DesktopRenderUtils.screenRect(openFolder);
            if (full == null || full.width() < 2f || full.height() < 2f) return null;
            // Inset past scrim / margins so cancel-adjacent icons outside the page stay visible.
            cover = new RectF(full);
            float insetX = full.width() * 0.12f;
            float insetY = full.height() * 0.12f;
            cover.inset(insetX, insetY);
        }
        if (cover.width() < 2f || cover.height() < 2f) return null;
        return cover;
    }

    static boolean hasLiveDragView(View root) {
        View decor = root != null && root.getRootView() != null ? root.getRootView() : root;
        return DragViewOverlay.findDragViewInTree(decor) != null;
    }

    /** True when icon bounds are mostly under the open-folder drop cover. */
    static boolean iconCoveredByFolderDrop(RectF cover, View view) {
        if (cover == null || view == null) return false;
        RectF icon = DesktopRenderUtils.screenRect(view);
        if (icon == null || icon.isEmpty()) return false;
        if (!RectF.intersects(cover, icon)) return false;
        RectF overlap = new RectF();
        boolean ok = overlap.setIntersect(cover, icon);
        if (!ok) return false;
        float iconArea = Math.max(1f, icon.width() * icon.height());
        float overlapArea = overlap.width() * overlap.height();
        // Require substantial coverage so icons merely near the folder edge still paint;
        // cancel move-out restores them as soon as cover clears.
        return overlapArea / iconArea >= 0.45f;
    }

    private static View resolveFolderIconFromPreview(Object preview) {
        Object host = DesktopRenderUtils.fieldValue(preview, "mInvalidateDelegate");
        if (host instanceof View && DesktopRenderUtils.isFolderIcon((View) host)) return (View) host;
        Object createHost = DesktopRenderUtils.fieldValue(preview, "mHostView");
        if (createHost instanceof View && DesktopRenderUtils.isFolderIcon((View) createHost)) return (View) createHost;
        return null;
    }

    /**
     * Create-folder: paint drop-target + dragged icons as shrunk preview glyphs inside the
     * plate. Prefer a live FolderIcon layout rule; fall back to ColorOS-style 2×2 grid.
     */
    private static void paintCreateFolderPreviewIcons(Object preview, View cellLayout,
            RectF plateDest, Canvas canvas) {
        if (plateDest == null || plateDest.width() < 2f || plateDest.height() < 2f) return;
        java.util.ArrayList<View> hosts = new java.util.ArrayList<>(2);
        java.util.HashSet<View> hostSet = new java.util.HashSet<>();
        collectCreateFolderHosts(cellLayout != null ? cellLayout : DesktopIconOverlay.resolveWorkspaceSeed(), hostSet);
        for (View host : hostSet) {
            if (host != null && DesktopItemRenderer.iconDrawableOf(host) != null && !hosts.contains(host)) {
                // Prefer the resolved desktop item (has the icon drawable).
                View resolved = DesktopRenderUtils.resolveDesktopItem(host);
                View use = resolved != null && DesktopItemRenderer.iconDrawableOf(resolved) != null ? resolved : host;
                if (!hosts.contains(use)) hosts.add(use);
            }
        }
        View root = cellLayout != null
                ? (cellLayout.getRootView() != null ? cellLayout.getRootView() : cellLayout)
                : null;
        View drag = DragViewOverlay.findDragViewInTree(root);
        Drawable dragDrawable = null;
        if (drag != null) {
            View content = DragViewOverlay.dragContentView(drag);
            View origin = DragViewOverlay.dragOriginView(drag);
            View iconSrc = DragViewOverlay.resolveDragIconSource(content, origin);
            dragDrawable = iconSrc != null ? DesktopItemRenderer.iconDrawableOf(iconSrc) : null;
            if (dragDrawable == null && content != null) dragDrawable = DesktopItemRenderer.iconDrawableOf(content);
            if (origin != null && !hosts.contains(origin) && DesktopItemRenderer.iconDrawableOf(origin) != null) {
                // Origin is the moving app — already skipped on desktop; use drawable only.
            }
        }
        java.util.ArrayList<Drawable> glyphs = new java.util.ArrayList<>(2);
        float intrinsic = 0f;
        for (View host : hosts) {
            Drawable d = DesktopItemRenderer.iconDrawableOf(host);
            if (d == null) continue;
            glyphs.add(d);
            float sz = iconIntrinsicSize(host, d);
            if (sz > intrinsic) intrinsic = sz;
        }
        if (dragDrawable != null) {
            glyphs.add(dragDrawable);
            if (intrinsic <= 0f) {
                intrinsic = Math.max(dragDrawable.getIntrinsicWidth(),
                        dragDrawable.getIntrinsicHeight());
            }
        }
        if (glyphs.isEmpty()) return;
        int count = Math.min(4, glyphs.size());
        float plate = Math.min(plateDest.width(), plateDest.height());
        if (intrinsic < 1f) intrinsic = plate;

        // Try OEM layout rule from any FolderIcon (FlexibleFolderIcon uses ColorOS grid).
        boolean laidOut = paintGlyphsWithLayoutRule(cellLayout, glyphs, count, intrinsic, plate,
                plateDest, canvas);
        if (!laidOut) {
            paintGlyphsColorOsGrid(glyphs, count, plate, plateDest, canvas);
        }
    }

    private static float iconIntrinsicSize(View host, Drawable drawable) {
        Object size = DesktopRenderUtils.invokeNoArgs(host, "getIconSize");
        if (size instanceof Number && ((Number) size).floatValue() > 0f) {
            return ((Number) size).floatValue();
        }
        if (drawable != null) {
            int w = drawable.getIntrinsicWidth();
            int h = drawable.getIntrinsicHeight();
            if (w > 0 && h > 0) return Math.max(w, h);
            Rect b = drawable.getBounds();
            if (b != null && !b.isEmpty()) return Math.max(b.width(), b.height());
        }
        return 0f;
    }

    private static boolean paintGlyphsWithLayoutRule(View seed, List<Drawable> glyphs, int count,
            float intrinsic, float plate, RectF plateDest, Canvas canvas) {
        try {
            View folderIcon = findFirstFolderIcon(seed);
            Object rule = folderIcon != null ? DesktopRenderUtils.invokeNoArgs(folderIcon, "getLayoutRule") : null;
            if (rule == null) {
                ClassLoader cl = seed != null ? seed.getClass().getClassLoader()
                        : DesktopIconOverlay.class.getClassLoader();
                for (String name : new String[] {
                        "com.android.launcher3.folder.ClippedFolderIconLayoutRule",
                        "com.android.launcher3.folder.OplusClippedFolderIconLayoutRule",
                        "com.android.launcher3.folder.OplusFolderIconLayoutRule"
                }) {
                    try {
                        Class<?> cls = Class.forName(name, false, cl);
                        rule = cls.getDeclaredConstructor().newInstance();
                        break;
                    } catch (Throwable ignored) { }
                }
            }
            if (rule == null) return false;
            boolean inited = false;
            for (java.lang.reflect.Method m : rule.getClass().getMethods()) {
                if (!"init".equals(m.getName()) || m.getParameterCount() < 2) continue;
                try {
                    Class<?>[] types = m.getParameterTypes();
                    if (types.length == 3 && types[2] == boolean.class) {
                        m.invoke(rule, Math.round(plate), intrinsic, false);
                        inited = true;
                        break;
                    }
                    if (types.length == 2) {
                        m.invoke(rule, Math.round(plate), intrinsic);
                        inited = true;
                        break;
                    }
                } catch (Throwable ignored) { }
            }
            if (!inited) return false;

            java.lang.reflect.Method compute = null;
            for (java.lang.reflect.Method m : rule.getClass().getMethods()) {
                if ("computePreviewItemDrawingParams".equals(m.getName())
                        && m.getParameterCount() == 3) {
                    compute = m;
                    break;
                }
            }
            if (compute == null) return false;

            for (int i = count - 1; i >= 0; i--) {
                Object params = compute.invoke(rule, i, count, null);
                if (params == null) continue;
                float transX = DesktopRenderUtils.floatField(params, "transX");
                float transY = DesktopRenderUtils.floatField(params, "transY");
                float scale = DesktopRenderUtils.floatField(params, "scale");
                if (scale <= 0f) scale = 0.45f;
                float size = intrinsic * scale;
                Drawable drawable = glyphs.get(i);
                Bitmap bitmap = DesktopItemRenderer.bitmapForCanvas(DesktopItemRenderer.fastBitmapOf(drawable), canvas);
                if (bitmap == null || bitmap.isRecycled()) {
                    bitmap = DesktopItemRenderer.rasterizeDrawable(drawable, Math.max(1, Math.round(size)),
                            Math.max(1, Math.round(size)));
                }
                if (bitmap == null || bitmap.isRecycled()) continue;
                float left = plateDest.left + transX * (plateDest.width() / plate);
                float top = plateDest.top + transY * (plateDest.height() / plate);
                float w = size * (plateDest.width() / plate);
                float h = size * (plateDest.height() / plate);
                DesktopRenderUtils.BITMAP_PAINT.setAlpha(255);
                canvas.drawBitmap(bitmap, null, new RectF(left, top, left + w, top + h),
                        DesktopRenderUtils.BITMAP_PAINT);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** ColorOS closed-folder preview is a padded 2×2/3×3 grid (not AOSP circle stack). */
    private static void paintGlyphsColorOsGrid(List<Drawable> glyphs, int count, float plate,
            RectF plateDest, Canvas canvas) {
        int grid = count <= 4 ? 2 : 3;
        float inset = plate * 0.14f;
        float gap = plate * 0.05f;
        float cell = (plate - 2f * inset - gap * (grid - 1)) / grid;
        float icon = cell * 0.88f;
        for (int i = count - 1; i >= 0; i--) {
            int row = i / grid;
            int col = i % grid;
            float cx = inset + col * (cell + gap) + cell * 0.5f;
            float cy = inset + row * (cell + gap) + cell * 0.5f;
            Drawable drawable = glyphs.get(i);
            Bitmap bitmap = DesktopItemRenderer.bitmapForCanvas(DesktopItemRenderer.fastBitmapOf(drawable), canvas);
            if (bitmap == null || bitmap.isRecycled()) {
                bitmap = DesktopItemRenderer.rasterizeDrawable(drawable, Math.max(1, Math.round(icon)),
                        Math.max(1, Math.round(icon)));
            }
            if (bitmap == null || bitmap.isRecycled()) continue;
            float sx = plateDest.width() / plate;
            float sy = plateDest.height() / plate;
            float left = plateDest.left + (cx - icon * 0.5f) * sx;
            float top = plateDest.top + (cy - icon * 0.5f) * sy;
            float w = icon * sx;
            float h = icon * sy;
            DesktopRenderUtils.BITMAP_PAINT.setAlpha(255);
            canvas.drawBitmap(bitmap, null, new RectF(left, top, left + w, top + h), DesktopRenderUtils.BITMAP_PAINT);
        }
    }

    private static View findFirstFolderIcon(View seed) {
        View workspace = DesktopRenderUtils.findWorkspaceAncestor(seed);
        if (workspace == null) workspace = DesktopIconOverlay.resolveWorkspaceSeed();
        if (workspace == null) return null;
        if (workspace instanceof ViewGroup) {
            ViewGroup pages = (ViewGroup) workspace;
            for (int i = 0; i < pages.getChildCount(); i++) {
                ViewGroup icons = DesktopRenderUtils.shortcutsAndWidgetsOf(pages.getChildAt(i));
                if (icons == null) continue;
                for (int j = 0; j < icons.getChildCount(); j++) {
                    View child = icons.getChildAt(j);
                    View host = DesktopRenderUtils.resolveDesktopItem(child);
                    if (DesktopRenderUtils.isFolderIcon(host)) return host;
                    if (DesktopRenderUtils.isFolderIcon(child)) return child;
                }
            }
        }
        return null;
    }

    /**
     * ClippedFolderIconLayoutRule.getPosition for a square preview of size {@code plate}.
     * Returns top-left of the icon in preview-local coordinates.
     */
    private static float[] clippedPreviewPosition(int index, int curNumItems, float plate,
            float iconSize) {
        curNumItems = Math.max(curNumItems, 2);
        double theta0 = Math.PI;
        int direction = -1;
        double thetaShift = 0;
        if (curNumItems == 3) thetaShift = Math.PI / 2;
        else if (curNumItems == 4) thetaShift = Math.PI / 4;
        theta0 += direction * thetaShift;
        int idx = index;
        if (curNumItems == 4 && index == 3) idx = 2;
        else if (curNumItems == 4 && index == 2) idx = 3;
        float radius = (1.15f * plate / 2f)
                * (1f + 0.25f * (curNumItems - 2) / 2f);
        double theta = theta0 + idx * (2 * Math.PI / curNumItems) * direction;
        float half = iconSize / 2f;
        float x = plate / 2f + (float) (radius * Math.cos(theta) / 2) - half;
        float y = plate / 2f + (float) (-radius * Math.sin(theta) / 2) - half;
        return new float[]{x, y};
    }

    /**
     * Folder accept: paint PreviewItemManager glyphs into {@code plateDest}.
     * Cos16 {@code transX/Y} are FolderIcon-space and already include basePreviewOffset;
     * plateDest is the live {@code getBackgroundRect} (offset shifts while accept-scaled).
     * Map with OEM draw under a FolderIcon→plate transform so glyphs stay aligned.
     * <p>
     * Cos14: glyph params are FolderIcon content space ({@code PreviewItemManager.draw}
     * translates by {@code basePreviewOffset} then {@code -previewStyleBoundOffset}).
     * {@code getBackgroundRect} is accept-scaled, so mapping with it offsets the glyphs
     * up-left; use the resting-plate geometry instead so the glyphs scale with the accept
     * plate and stay centered.
     */
    private static void paintFolderPreviewIconsIntoPlate(View folderIcon, RectF plateDest,
            Canvas canvas) {
        if (folderIcon == null || plateDest == null) return;
        Object manager = DesktopRenderUtils.fieldValue(folderIcon, "mPreviewItemManager");
        if (manager == null) return;
        Object background = DesktopRenderUtils.fieldValue(folderIcon, "mBackground");
        boolean cos14 = ColorOsVersion.detect() == ColorOsVersion.Flavor.LEGACY_13_14;
        float originX = DesktopRenderUtils.floatField(background, "basePreviewOffsetX");
        float originY = DesktopRenderUtils.floatField(background, "basePreviewOffsetY");
        float srcW = 0f;
        float srcH = 0f;
        if (cos14) {
            // Resting plate = previewSize * styleBoundFactor centered at basePreviewOffset.
            float previewPx = DesktopRenderUtils.floatField(background, "previewSize");
            float factor = 1f;
            Object fObj = DesktopRenderUtils.fieldValue(background, "mStyleBoundFactor");
            if (fObj instanceof Number) factor = ((Number) fObj).floatValue();
            if (previewPx > 0f && factor > 0f) {
                float restSize = previewPx * factor;
                if (restSize > 0f) {
                    srcW = restSize;
                    srcH = restSize;
                    originX = DesktopRenderUtils.floatField(background, "basePreviewOffsetX")
                            - (restSize - previewPx) / 2f;
                    originY = DesktopRenderUtils.floatField(background, "basePreviewOffsetY")
                            - (restSize - previewPx) / 2f;
                }
            }
        }
        if (srcW <= 0f) {
            Object rectObj = DesktopRenderUtils.invokeNoArgs(background, "getBackgroundRect");
            if (rectObj instanceof Rect) {
                Rect bg = (Rect) rectObj;
                if (bg.width() > 0 && bg.height() > 0) {
                    originX = bg.left;
                    originY = bg.top;
                    srcW = bg.width();
                    srcH = bg.height();
                }
            }
        }
        if (srcW <= 0f || srcH <= 0f) {
            Object previewW = DesktopRenderUtils.fieldValue(background, "mPreviewSizeX");
            Object previewH = DesktopRenderUtils.fieldValue(background, "mPreviewSizeY");
            if (previewW instanceof Number) srcW = ((Number) previewW).floatValue();
            if (previewH instanceof Number) srcH = ((Number) previewH).floatValue();
        }
        if (srcW <= 0f || srcH <= 0f) {
            Object ps = DesktopRenderUtils.fieldValue(background, "previewSize");
            if (ps instanceof Number) {
                float s = ((Number) ps).floatValue();
                if (srcW <= 0f) srcW = s;
                if (srcH <= 0f) srcH = s;
            }
        }
        if (srcW <= 0f || srcH <= 0f) {
            Rect plateLocal = FolderCompositeRenderer.folderPlateLocalBounds(folderIcon);
            if (plateLocal != null && plateLocal.width() > 0 && plateLocal.height() > 0) {
                srcW = plateLocal.width();
                srcH = plateLocal.height();
                if (originX <= 0f && originY <= 0f) {
                    originX = plateLocal.left;
                    originY = plateLocal.top;
                }
            } else {
                srcW = Math.max(1, folderIcon.getWidth());
                srcH = Math.max(1, folderIcon.getHeight());
            }
        }
        float sx = plateDest.width() / Math.max(1f, srcW);
        float sy = plateDest.height() / Math.max(1f, srcH);

        // Same transform OEM uses: FolderIcon local → plateDest. Prefer PreviewItemManager.draw.
        int saveOem = canvas.save();
        try {
            canvas.translate(plateDest.left - originX * sx, plateDest.top - originY * sy);
            canvas.scale(sx, sy);
            if (FolderCompositeRenderer.invokePreviewManagerDraw(manager, canvas)) return;
        } finally {
            canvas.restoreToCount(saveOem);
        }

        List<?> params = FolderCompositeRenderer.resolvePreviewDrawingParams(manager);
        if (params == null || params.isEmpty()) return;
        Object intrinsic = DesktopRenderUtils.invokeNoArgs(manager, "getIntrinsicIconSize");
        float iconSize = intrinsic instanceof Number ? ((Number) intrinsic).floatValue() : 0f;
        for (int i = params.size() - 1; i >= 0; i--) {
            Object param = params.get(i);
            if (param == null) continue;
            Object hidden = DesktopRenderUtils.fieldValue(param, "hidden");
            if (hidden instanceof Boolean && (Boolean) hidden) continue;
            Object d = DesktopRenderUtils.fieldValue(param, "drawable");
            if (!(d instanceof Drawable)) continue;
            Drawable drawable = (Drawable) d;
            float transX = DesktopRenderUtils.floatField(param, "transX");
            float transY = DesktopRenderUtils.floatField(param, "transY");
            float scale = DesktopRenderUtils.floatField(param, "scale");
            if (scale <= 0f) scale = 1f;
            Rect bounds = drawable.getBounds();
            float size = iconSize > 0f ? iconSize
                    : Math.max(1, bounds.isEmpty() ? 48 : bounds.width());
            Bitmap bitmap = DesktopItemRenderer.bitmapForCanvas(DesktopItemRenderer.fastBitmapOf(drawable), canvas);
            if (bitmap == null) {
                bitmap = DesktopItemRenderer.rasterizeDrawable(drawable, Math.round(size), Math.round(size));
            }
            if (bitmap == null || bitmap.isRecycled()) continue;
            // Cos14 glyphs are FolderIcon content space: PreviewItemManager.draw translates
            // by basePreviewOffset then -previewStyleBoundOffset before transX/Y. Map them
            // relative to the resting-plate origin so they align with the accept plate.
            float baseOffX = 0f;
            float baseOffY = 0f;
            float styleOff = 0f;
            if (cos14) {
                baseOffX = DesktopRenderUtils.floatField(background, "basePreviewOffsetX");
                baseOffY = DesktopRenderUtils.floatField(background, "basePreviewOffsetY");
                styleOff = readPreviewStyleBoundOffset(folderIcon);
            }
            float left = plateDest.left + (baseOffX - styleOff + transX - originX) * sx;
            float top = plateDest.top + (baseOffY - styleOff + transY - originY) * sy;
            float w = size * scale * sx;
            float h = size * scale * sy;
            DesktopRenderUtils.BITMAP_PAINT.setAlpha(255);
            canvas.drawBitmap(bitmap, null, new RectF(left, top, left + w, top + h), DesktopRenderUtils.BITMAP_PAINT);
        }
    }

    /** Cos14 preview glyphs sit at basePreviewOffset - previewStyleBoundOffset. */
    private static float readPreviewStyleBoundOffset(View folderIcon) {
        try {
            Object rule = DesktopRenderUtils.fieldValue(folderIcon, "mPreviewLayoutRule");
            if (rule == null) return 0f;
            Object off = DesktopRenderUtils.invokeNoArgs(rule, "getPreviewStyleBoundOffset");
            if (off instanceof Number) return ((Number) off).floatValue();
        } catch (Throwable ignored) { }
        return 0f;
    }

    /**
     * Screen rect for a delegated plate. Cos16 parks mBgView on CellLayout and paints the
     * accept plate there, so its global matrix is authoritative. Cos14 has no mBgView — the
     * FolderIcon capture host stays at the resting position while CellLayout paints the
     * scaled/offset accept plate, so always map through CellLayout cell math (matches the
     * backdrop region the drag-glass sample captured).
     */
    private static RectF plateDestOnScreen(View bgView, View cellLayout, int[] cellPoint, Rect bg) {
        boolean cos14 = ColorOsVersion.detect() == ColorOsVersion.Flavor.LEGACY_13_14;
        if (!cos14 && bgView != null && bgView.getParent() == cellLayout
                && bgView.getWidth() > 0 && bgView.getHeight() > 0) {
            Matrix localToGlobal = new Matrix();
            bgView.transformMatrixToGlobal(localToGlobal);
            RectF dest = new RectF(0f, 0f, bgView.getWidth(), bgView.getHeight());
            localToGlobal.mapRect(dest);
            if (dest.width() >= 1f && dest.height() >= 1f) return dest;
        }
        Matrix cellToGlobal = new Matrix();
        cellLayout.transformMatrixToGlobal(cellToGlobal);
        RectF dest = new RectF(
                cellPoint[0] + bg.left,
                cellPoint[1] + bg.top,
                cellPoint[0] + bg.right,
                cellPoint[1] + bg.bottom);
        cellToGlobal.mapRect(dest);
        return dest;
    }

    /** Park create-folder {@code mBgView} on CellLayout at cell-local plate coords. */
    private static void parkCreateFolderCaptureHost(Object preview, View cellLayout, Rect bg,
            int[] cellPoint) {
        Object bgViewObj = DesktopRenderUtils.fieldValue(preview, "mBgView");
        if (!(bgViewObj instanceof View) || !(cellLayout instanceof ViewGroup)) return;
        if (bg.width() <= 0 || bg.height() <= 0) return;
        View image = (View) bgViewObj;
        ViewGroup parentCell = (ViewGroup) cellLayout;
        try {
            if (image.getParent() != parentCell) {
                if (image.getParent() instanceof ViewGroup) {
                    try {
                        ((ViewGroup) image.getParent()).removeView(image);
                    } catch (Throwable ignored) { }
                }
                parentCell.addView(image);
            }
            image.setVisibility(View.INVISIBLE);
            int left = cellPoint[0] + bg.left;
            int top = cellPoint[1] + bg.top;
            image.measure(
                    View.MeasureSpec.makeMeasureSpec(bg.width(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(bg.height(), View.MeasureSpec.EXACTLY));
            image.layout(left, top, left + bg.width(), top + bg.height());
            GlassDrawable glass = GlassInstaller.get(image);
            if (glass != null) glass.setBounds(0, 0, bg.width(), bg.height());
        } catch (Throwable ignored) { }
    }

    private static boolean isPreviewBoundToFolderIcon(Object preview) {
        Object host = DesktopRenderUtils.fieldValue(preview, "mInvalidateDelegate");
        if (host instanceof View && DesktopRenderUtils.isFolderIcon((View) host)) return true;
        Object createHost = DesktopRenderUtils.fieldValue(preview, "mHostView");
        return createHost instanceof View && DesktopRenderUtils.isFolderIcon((View) createHost);
    }

    /** True when a create-folder / accept plate is CellLayout-delegated (force icons rebuild). */
    static boolean hasDelegatedFolderPreview(View seed) {
        View workspace = DesktopRenderUtils.findWorkspaceAncestor(seed);
        if (workspace == null) {
            Object launcher = OpenFolderOverlay.resolveLauncherAny(seed);
            Object ws = DesktopRenderUtils.invokeNoArgs(launcher, "getWorkspace");
            if (ws instanceof View) workspace = (View) ws;
        }
        if (workspace != null) {
            Object createBg = DesktopRenderUtils.fieldValue(workspace, "mGroupCreatePreviewBg");
            if (isPreviewDrawingDelegated(createBg)) return true;
        }
        if (workspace == null) return false;
        List<ViewGroup> containers = new ArrayList<>();
        if (workspace instanceof ViewGroup) {
            ViewGroup pages = (ViewGroup) workspace;
            for (int i = 0; i < pages.getChildCount(); i++) {
                ViewGroup pageIcons = DesktopRenderUtils.shortcutsAndWidgetsOf(pages.getChildAt(i));
                if (pageIcons != null) containers.add(pageIcons);
            }
        }
        ViewGroup primary = DesktopRenderUtils.shortcutsAndWidgetsOf(workspace);
        if (primary != null && !containers.contains(primary)) containers.add(primary);
        for (ViewGroup icons : containers) {
            for (int i = 0; i < icons.getChildCount(); i++) {
                View child = icons.getChildAt(i);
                if (child == null) continue;
                View host = DesktopRenderUtils.resolveDesktopItem(child);
                View folder = DesktopRenderUtils.isFolderIcon(host) ? host : (DesktopRenderUtils.isFolderIcon(child) ? child : null);
                if (folder == null) continue;
                if (isPreviewDrawingDelegated(DesktopRenderUtils.fieldValue(folder, "mBackground"))) return true;
            }
        }
        return false;
    }

    static boolean isPreviewDrawingDelegated(Object preview) {
        Object delegated = DesktopRenderUtils.invokeNoArgs(preview, "drawingDelegated");
        if (delegated instanceof Boolean) return (Boolean) delegated;
        return DesktopRenderUtils.fieldValue(preview, "mDrawingDelegate") != null;
    }

    private static boolean cellToPoint(View cellLayout, int cellX, int cellY, int[] out) {
        if (out == null || out.length < 2) return false;
        for (Class<?> c = cellLayout.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Method method =
                        c.getDeclaredMethod("cellToPoint", int.class, int.class, int[].class);
                method.setAccessible(true);
                method.invoke(cellLayout, cellX, cellY, out);
                return true;
            } catch (Throwable ignored) { }
        }
        return false;
    }

    private static int intField(Object object, String name) {
        Object value = DesktopRenderUtils.fieldValue(object, name);
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }
}
