package net.z841973620.colorosliquidglass.glass;

import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import java.util.ArrayList;
import java.util.List;

/** 桌面合成共用的坐标、View 查找、反射访问、画笔和递归深度。 */
final class DesktopRenderUtils {
    private DesktopRenderUtils() {}

    static final Paint BITMAP_PAINT =
            new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    static final Paint TITLE_PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);
    static final ThreadLocal<Integer> DEPTH =
            ThreadLocal.withInitial(() -> 0);

    static void assignField(Object target, String name, Object value) {
        try {
            java.lang.reflect.Field f = null;
            for (Class<?> c = target.getClass(); c != null && f == null; c = c.getSuperclass()) {
                try { f = c.getDeclaredField(name); } catch (Throwable ignored) { }
            }
            if (f == null) return;
            f.setAccessible(true);
            Class<?> type = f.getType();
            if (value instanceof Number) {
                Number n = (Number) value;
                if (type == int.class || type == Integer.class) f.set(target, n.intValue());
                else if (type == float.class || type == Float.class) f.set(target, n.floatValue());
                else if (type == long.class || type == Long.class) f.set(target, n.longValue());
                else f.set(target, value);
            } else {
                f.set(target, value);
            }
        } catch (Throwable ignored) { }
    }

    static float floatField(Object object, String name) {
        Object value = fieldValue(object, name);
        return value instanceof Number ? ((Number) value).floatValue() : 0f;
    }

    static RectF mapItemRectToTarget(View item, Rect localBounds,
            Matrix globalToTarget) {
        Matrix itemToGlobal = new Matrix();
        item.transformMatrixToGlobal(itemToGlobal);
        RectF screen = new RectF(localBounds);
        itemToGlobal.mapRect(screen);
        RectF dest = new RectF(screen);
        globalToTarget.mapRect(dest);
        return dest;
    }

    static ViewGroup shortcutsAndWidgetsOf(View seed) {
        if (seed == null) return null;
        Object direct = invokeNoArgs(seed, "getShortcutsAndWidgets");
        if (direct instanceof ViewGroup) return (ViewGroup) direct;
        for (View current = seed; current != null; ) {
            Object widgets = invokeNoArgs(current, "getShortcutsAndWidgets");
            if (widgets instanceof ViewGroup) return (ViewGroup) widgets;
            String name = current.getClass().getName();
            if (name.endsWith("ShortcutAndWidgetContainer") && current instanceof ViewGroup) {
                return (ViewGroup) current;
            }
            ViewParent parent = current.getParent();
            if (!(parent instanceof View)) break;
            current = (View) parent;
        }
        return null;
    }

    /**
     * All ShortcutAndWidgetContainers whose page intersects the glass.
     * Cross-page drag must not depend on a single (possibly stale) CellLayout seed —
     * paint every on-screen Workspace page under the finger, plus Hotseat (bottom dock).
     */
    private static List<ViewGroup> iconContainersUnderGlass(View seed, View glassHost) {
        RectF region = screenRect(glassHost);
        return iconContainersUnderRegion(seed != null ? seed : glassHost, region);
    }

    static List<ViewGroup> iconContainersUnderRegion(View seed, RectF region) {
        try (PopupCaptureTrace.Stage ignoredTrace = PopupCaptureTrace.section("iconContainersUnderRegion")) {
            List<ViewGroup> out = new ArrayList<>();
            if (region == null) return out;
            ViewGroup primary = shortcutsAndWidgetsOf(seed);
            if (primary != null) out.add(primary);
            View workspace = findWorkspaceAncestor(seed);
            if (workspace instanceof ViewGroup) {
                ViewGroup pages = (ViewGroup) workspace;
                for (int i = 0; i < pages.getChildCount(); i++) {
                    View page = pages.getChildAt(i);
                    if (page == null || page.getWidth() <= 0 || page.getHeight() <= 0) continue;
                    if (!intersectsRegion(region, page)) continue;
                    ViewGroup icons = shortcutsAndWidgetsOf(page);
                    if (icons != null && !out.contains(icons)) out.add(icons);
                }
            }
            // Hotseat is a CellLayout sibling of Workspace (not a Workspace page). Same paint
            // path as desktop icons/folders — include when the glass overlaps the dock.
            View hotseat = findHotseat(seed, workspace);
            if (hotseat != null && hotseat.getWidth() > 0 && hotseat.getHeight() > 0
                    && intersectsRegion(region, hotseat)) {
                ViewGroup icons = shortcutsAndWidgetsOf(hotseat);
                if (icons != null && !out.contains(icons)) out.add(icons);
            }
            return out;
        }
    }

    /**
     * ColorOS: {@code Launcher.getHotseat()} → {@code OplusHotseat} extends CellLayout.
     * Fall back to scanning the Workspace parent (DragLayer) for a *Hotseat* child.
     */
    private static View findHotseat(View start, View workspace) {
        Object launcher = findLauncher(start, workspace);
        Object hotseat = invokeNoArgs(launcher, "getHotseat");
        if (hotseat instanceof View) return (View) hotseat;

        ViewParent parent = workspace != null ? workspace.getParent()
                : (start != null ? start.getParent() : null);
        if (parent instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) parent;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child == null) continue;
                String name = child.getClass().getSimpleName();
                if (name != null && name.contains("Hotseat")) return child;
            }
        }
        return null;
    }

    /**
     * ColorOS: {@code Workspace.getPageIndicator()} → {@code OplusPageIndicator}
     * (dots between icon grid and Hotseat). Also accept DragLayer siblings named *PageIndicator*.
     */
    static View findPageIndicator(View start, View workspace) {
        if (workspace != null) {
            Object indicator = invokeNoArgs(workspace, "getPageIndicator");
            if (indicator == null) indicator = fieldValue(workspace, "mPageIndicator");
            if (indicator instanceof View) return (View) indicator;
        }
        Object launcher = findLauncher(start, workspace);
        Object ws = invokeNoArgs(launcher, "getWorkspace");
        if (ws != null && ws != workspace) {
            Object indicator = invokeNoArgs(ws, "getPageIndicator");
            if (indicator == null) indicator = fieldValue(ws, "mPageIndicator");
            if (indicator instanceof View) return (View) indicator;
        }
        ViewParent parent = workspace != null ? workspace.getParent()
                : (start != null ? start.getParent() : null);
        if (parent instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) parent;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child == null) continue;
                String name = child.getClass().getName();
                if (name != null && name.contains("PageIndicator")) return child;
            }
        }
        return null;
    }

    static Object findLauncher(View start, View workspace) {
        Object launcher = null;
        if (workspace != null) {
            launcher = invokeNoArgs(workspace, "getLauncher");
            if (launcher == null) launcher = fieldValue(workspace, "mLauncher");
            if (launcher == null) launcher = fieldValue(workspace, "mActivity");
        }
        if (launcher == null && start != null) {
            for (View current = start; current != null; ) {
                Object got = invokeNoArgs(current, "getLauncher");
                if (got == null) got = fieldValue(current, "mLauncher");
                if (got == null) got = fieldValue(current, "mActivity");
                if (got != null) return got;
                String name = current.getClass().getSimpleName();
                if (name != null && name.contains("Launcher") && !name.contains("AppWidget")) {
                    return current;
                }
                ViewParent parent = current.getParent();
                if (!(parent instanceof View)) break;
                current = (View) parent;
            }
        }
        return launcher;
    }

    static View findWorkspaceAncestor(View start) {
        for (View current = start; current != null; ) {
            String name = current.getClass().getSimpleName();
            if (name != null && name.contains("Workspace") && !name.contains("Cell")) {
                return current;
            }
            Object got = invokeNoArgs(current, "getWorkspace");
            if (got instanceof View) return (View) got;
            ViewParent parent = current.getParent();
            if (!(parent instanceof View)) break;
            current = (View) parent;
        }
        return null;
    }

    private static View applicationChild(View item) {
        Object child = invokeNoArgs(item, "getApplicationChild");
        return child instanceof View ? (View) child : item;
    }

    static View resolveDesktopItem(View child) {
        if (child == null) return null;
        if (isWrapContainer(child)) {
            Object target = invokeNoArgs(child, "curWidgetView");
            if (!(target instanceof View)) target = invokeNoArgs(child, "getTargetView");
            if (target instanceof View) return (View) target;
        }
        return applicationChild(child);
    }

    private static boolean intersectsOnScreen(View a, View b) {
        RectF ra = screenRect(a);
        RectF rb = screenRect(b);
        return ra != null && rb != null && RectF.intersects(ra, rb);
    }

    static boolean intersectsRegion(RectF region, View view) {
        if (region == null) return false;
        RectF rb = screenRect(view);
        return rb != null && RectF.intersects(region, rb);
    }

    static RectF screenRect(View view) {
        if (view == null || view.getWidth() <= 0 || view.getHeight() <= 0) return null;
        Matrix matrix = new Matrix();
        view.transformMatrixToGlobal(matrix);
        RectF rect = new RectF(0f, 0f, view.getWidth(), view.getHeight());
        matrix.mapRect(rect);
        return rect;
    }

    static boolean isFolderIcon(View view) {
        for (Class<?> c = view == null ? null : view.getClass(); c != null; c = c.getSuperclass()) {
            if ("FolderIcon".equals(c.getSimpleName())) return true;
        }
        return false;
    }

    private static boolean isWrapContainer(View view) {
        for (Class<?> c = view == null ? null : view.getClass(); c != null; c = c.getSuperclass()) {
            String name = c.getSimpleName();
            if (name == null) continue;
            if (name.contains("WrapWidget") || name.contains("WrapCard")
                    || name.contains("WrapMultiSize") || name.contains("WrapAdaptive")) {
                return true;
            }
        }
        return false;
    }

    static boolean isWidgetOrCard(View view) {
        for (Class<?> c = view == null ? null : view.getClass(); c != null; c = c.getSuperclass()) {
            String name = c.getSimpleName();
            if (name == null) continue;
            if (name.contains("AppWidgetHostView")
                    || name.contains("LauncherAppWidget")
                    || name.equals("LauncherCardView")
                    || name.equals("TitleCardView")
                    || name.contains("CardHostView")) {
                return true;
            }
        }
        return isWrapContainer(view);
    }

    static Object fieldValue(Object object, String name) {
        for (Class<?> c = object == null ? null : object.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(object);
            } catch (Throwable ignored) { }
        }
        return null;
    }

    static Object invokeNoArgs(Object object, String name) {
        for (Class<?> c = object == null ? null : object.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Method method = c.getDeclaredMethod(name);
                method.setAccessible(true);
                return method.invoke(object);
            } catch (Throwable ignored) { }
        }
        return null;
    }
}
