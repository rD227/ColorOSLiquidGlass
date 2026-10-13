package com.android.launcher3.folder

import android.content.Context
import android.widget.FrameLayout

class Folder(context: Context) : FrameLayout(context) {
    fun isOpen(): Boolean = true
    companion object {
        var legacyCalls = 0
        @JvmStatic fun getOpen(context: Any): Folder? {
            legacyCalls++
            return null
        }
    }
}
