package com.tangyuan.trackpad

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

object Perms {

    fun hasOverlay(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    fun hasAccessibility(ctx: Context): Boolean {
        if (TrackpadAccessibilityService.isReady) return true
        val expected = ComponentName(ctx, TrackpadAccessibilityService::class.java)
        val long = expected.flattenToString()
        val short = expected.flattenToShortString()
        val raw = Settings.Secure.getString(
            ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return raw.split(':').any { it.equals(long, true) || it.equals(short, true) }
    }
}
