package net.z841973620.colorosliquidglass.glass;

import android.app.KeyguardManager;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.View;

import net.z841973620.colorosliquidglass.GlassConfig;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

/** Paints ordinary notification plates without replacing OEM drawable/state ownership. */
public final class NotificationCardGlass {
    private static final String TAG = "ColorOSLiquidGlass";
    private final Method actualHeight;
    private final Method actualWidth;
    private final Method alignedRight;
    private final Field clipTop;
    private final Field clipBottom;
    private final Field expanding;
    private final Field bottomRounded;
    private final Field bottomClips;
    private final Field cornerRadii;
    private final Field background;
    // The view's attach listener owns State. Weak values avoid map -> glass -> view leaks.
    private final Map<View, WeakReference<State>> states = new WeakHashMap<>();

    public NotificationCardGlass(Class<?> type) throws ReflectiveOperationException {
        actualHeight = method(type, "getActualHeight");
        actualWidth = method(type, "getActualWidth");
        alignedRight = method(type, "isAlignedToRight");
        clipTop = field(type, "mClipTopAmount");
        clipBottom = field(type, "mClipBottomAmount");
        expanding = field(type, "mExpandAnimationRunning");
        bottomRounded = field(type, "mBottomIsRounded");
        bottomClips = field(type, "mBottomAmountClips");
        cornerRadii = field(type, "mCornerRadii");
        background = field(type, "mBackground");
    }

    public void draw(View view, Canvas canvas, GlassConfig config)
            throws ReflectiveOperationException {
        int height = (Integer) actualHeight.invoke(view);
        int width = (Integer) actualWidth.invoke(view);
        int topClip = clipTop.getInt(view);
        int bottomClip = clipBottom.getInt(view);
        boolean animation = expanding.getBoolean(view);
        if (height <= 0 || width <= 0 || (!animation && topClip + bottomClip >= height)) return;

        Drawable original = (Drawable) background.get(view);
        // SystemUI intentionally clears the plate for transparent/group-child rows.
        if (original == null) return;
        float[] radii = (float[]) cornerRadii.get(view);
        int left = (Boolean) alignedRight.invoke(view) ? view.getWidth() - width : 0;
        if (animation) left = (view.getWidth() - width) / 2;
        int bottom = height;
        if (!animation && bottomRounded.getBoolean(view) && bottomClips.getBoolean(view)) {
            bottom -= bottomClip;
        }

        State state = state(view, config);
        boolean locked = state.keyguard.isKeyguardLocked();
        if (state.locked == null || state.locked != locked) {
            if (locked) GlassInstaller.markLockWallpaperOnly(view);
            else GlassInstaller.markWallpaperOnly(view);
            state.locked = locked;
            GlassInstaller.forceCapture(view);
        }
        GlassDrawable glass = state.glass;
        glass.setCornerRadii(radii[0], radii[2], radii[4], radii[6]);
        glass.setBounds(left, 0, left + width, bottom);
        glass.setAlpha(original.getAlpha());
        int save = canvas.save();
        try {
            if (!animation) canvas.clipRect(0, topClip, view.getWidth(), height - bottomClip);
            glass.draw(canvas);
        } finally {
            canvas.restoreToCount(save);
        }
    }

    private State state(View view, GlassConfig config) {
        WeakReference<State> reference = states.get(view);
        State state = reference == null ? null : reference.get();
        if (state == null) {
            GlassInstaller.markWallpaperOnly(view);
            state = new State(view, config);
            states.put(view, new WeakReference<>(state));
            view.addOnAttachStateChangeListener(state);
            Log.i(TAG, "Notification card glass attached: " + view.getClass().getName());
        }
        return state;
    }

    public void remove(View view) {
        WeakReference<State> reference = states.remove(view);
        State state = reference == null ? null : reference.get();
        if (state != null) view.removeOnAttachStateChangeListener(state);
        if (state != null) {
            state.glass.setCallback(null);
            BackdropCapture.unregister(view);
            BackdropCapture.clearWallpaperOnly(view);
        }
    }

    private final class State implements View.OnAttachStateChangeListener {
        final GlassDrawable glass;
        final KeyguardManager keyguard;
        Boolean locked;

        State(View view, GlassConfig config) {
            keyguard = view.getContext().getSystemService(KeyguardManager.class);
            glass = new GlassDrawable(view, config);
            // Invalidation belongs to the host even though glass is not View.mBackground.
            glass.setCallback(view);
        }

        @Override public void onViewAttachedToWindow(View view) {}
        @Override public void onViewDetachedFromWindow(View view) { remove(view); }
    }

    private static Method method(Class<?> type, String name) throws NoSuchMethodException {
        Method method = type.getDeclaredMethod(name);
        method.setAccessible(true);
        return method;
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
