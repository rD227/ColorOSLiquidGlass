package com.android.launcher3

import com.android.launcher3.folder.Folder
import com.android.launcher3.views.ActivityContext

/** Probe-only query contract; no hooks or production Launcher instance needed. */
class AbstractFloatingView {
    companion object {
        var folder: Folder? = null
        var fail = false
        var calls = 0
        @JvmStatic fun getOpenFolder(context: ActivityContext): Folder? {
            calls++
            if (fail) error("Expected OEM query failure")
            return folder
        }

        // A same-name, one-argument method is insufficient to identify the correct contract.
        @JvmStatic fun getOpenFolder(wrongOverload: String): Folder? = error("Wrong overload")
    }
}
