package com.deniscerri.ytdl.util

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat

object AccessibilityUtil {

    fun isScreenReaderActive(context: Context?): Boolean {
        val appCtx = context?.applicationContext ?: return false
        val am = appCtx.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false
        if (!am.isEnabled) return false
        if (am.isTouchExplorationEnabled) return true
        val spoken = runCatching {
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN)
        }.getOrNull()
        return !spoken.isNullOrEmpty()
    }

    fun announcePolitely(view: View?, message: CharSequence?) {
        if (view == null || message.isNullOrBlank()) return
        val ctx = view.context ?: return
        if (!isScreenReaderActive(ctx) || !view.isAttachedToWindow) return
        @Suppress("DEPRECATION")
        runCatching { view.announceForAccessibility(message) }
    }

    fun setHeading(view: View?, isHeading: Boolean = true) {
        if (view == null) return
        ViewCompat.setAccessibilityHeading(view, isHeading)
    }

    fun setStateDescription(view: View?, description: CharSequence?) {
        if (view == null) return
        ViewCompat.setStateDescription(view, description)
    }

    fun setClickLabel(view: View?, label: CharSequence?) {
        if (view == null) return
        ViewCompat.replaceAccessibilityAction(
            view,
            AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
            label,
            null
        )
    }

    fun setLongClickLabel(view: View?, label: CharSequence?) {
        if (view == null) return
        ViewCompat.replaceAccessibilityAction(
            view,
            AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK,
            label,
            null
        )
    }

    fun sendFocusedEvent(view: View?) {
        if (view == null || !view.isAttachedToWindow) return
        runCatching {
            view.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED)
        }
    }
}
