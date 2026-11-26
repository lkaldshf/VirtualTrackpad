package com.tangyuan.trackpad

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * 只做一件事：把「光标位置的点击 / 长按 / 滑动」注入到系统里。
 * 它不读屏幕内容，也不监听任何事件，纯粹是个手势发射器。
 */
class TrackpadAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        inst = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (inst === this) inst = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (inst === this) inst = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    private fun dispatch(path: Path, durationMs: Long, onEnd: (() -> Unit)?): Boolean {
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(1L, 3000L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onEnd?.invoke()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                onEnd?.invoke()
            }
        }, handler)
    }

    fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatch(path, 48L, null)
    }

    fun longPress(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatch(path, 620L, null)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long, onEnd: (() -> Unit)? = null): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        return dispatch(path, durationMs, onEnd)
    }

    companion object {
        @Volatile
        private var inst: TrackpadAccessibilityService? = null

        val isReady: Boolean get() = inst != null

        fun tap(x: Float, y: Float): Boolean = inst?.tap(x, y) ?: false

        fun longPress(x: Float, y: Float): Boolean = inst?.longPress(x, y) ?: false

        fun swipe(
            x1: Float, y1: Float, x2: Float, y2: Float,
            durationMs: Long, onEnd: (() -> Unit)? = null
        ): Boolean = inst?.swipe(x1, y1, x2, y2, durationMs, onEnd) ?: false
    }
}
