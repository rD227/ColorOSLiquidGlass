package net.z841973620.colorosliquidglass.glass

import android.view.View
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Cache the OEM API contract, never the changing open-folder state. */
internal object OpenFolderQuery {
    private const val QUERY_CLASS = "com.android.launcher3.AbstractFloatingView"
    private const val CONTEXT_CLASS = "com.android.launcher3.views.ActivityContext"
    private const val FOLDER_CLASS = "com.android.launcher3.folder.Folder"

    class Result(val available: Boolean, val folder: View?)

    private val unavailable = Result(false, null)
    private val noOpenFolder = Result(true, null)
    private class Lookup(val method: Method?, val failure: String)

    // ClassValue separates different OEM class loaders without retaining a Launcher instance.
    private val methods = object : ClassValue<Lookup>() {
        override fun computeValue(type: Class<*>): Lookup {
            PopupCaptureTrace.outcome("folder_query_cache", "resolve")
            try {
                val context = Class.forName(CONTEXT_CLASS, false, type.classLoader)
                if (!context.isAssignableFrom(type)) return Lookup(null, "signature_mismatch")
                val owner = Class.forName(QUERY_CLASS, false, type.classLoader)
                val method = owner.getMethod("getOpenFolder", context)
                if (!Modifier.isStatic(method.modifiers) || method.returnType.name != FOLDER_CLASS ||
                    !View::class.java.isAssignableFrom(method.returnType)) {
                    return Lookup(null, "signature_mismatch")
                }
                return Lookup(method, "")
            } catch (_: ClassNotFoundException) {
                return Lookup(null, "class_missing")
            } catch (_: NoSuchMethodException) {
                return Lookup(null, "method_missing")
            } catch (_: SecurityException) {
                return Lookup(null, "access_denied")
            } catch (_: LinkageError) {
                // An OEM signature may reference a type absent from this process's class loader.
                return Lookup(null, "linkage_failed")
            }
        }
    }

    @JvmStatic fun query(launcher: Any): Result {
        val lookup = methods.get(launcher.javaClass)
        val method = lookup.method
        if (method == null) {
            PopupCaptureTrace.outcome(QUERY_CLASS, lookup.failure)
            return unavailable
        }
        try {
            val folder = method.invoke(null, launcher) as View?
            PopupCaptureTrace.outcome(QUERY_CLASS, if (folder == null) "returned_null" else "found")
            return if (folder == null) noOpenFolder else Result(true, folder)
        } catch (_: IllegalAccessException) {
            PopupCaptureTrace.outcome(QUERY_CLASS, "access_denied")
        } catch (_: InvocationTargetException) {
            // The OEM query can fail during initialization/teardown. Use the existing scan for
            // this sample; keep the Method so the next sample can query current state again.
            PopupCaptureTrace.outcome(QUERY_CLASS, "invoke_failed")
        } catch (_: LinkageError) {
            PopupCaptureTrace.outcome(QUERY_CLASS, "linkage_failed")
        }
        return unavailable
    }
}
