package com.tangyuan.trackpad

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View

/**
 * 全屏透明层，只在光标位置画一个「系统风格圆点」：
 *   深色实心点 + 白色描边 + 柔和投影，浅色 / 深色界面都能看清；点击时向外扩一圈涟漪。
 *
 * 用圆点而不是箭头，好处是点本身中心就是点击坐标，指哪儿点哪儿没有视觉偏移。
 *
 * 这一层必须 FLAG_NOT_TOUCHABLE，否则会把点击吃掉。
 */
@SuppressLint("ViewConstructor")
class CursorOverlayView(context: Context) : View(context) {

    /** 深色界面上的微光，让圆点边缘不至于完全融进背景 */
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(30, 255, 255, 255)
    }

    /** 中心实心点：中性深灰，比纯黑亮一点，深色背景上也能看出实体 */
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(240, 62, 70, 88)
    }

    /** 白色外描边，深色界面上靠它勾出圆点轮廓 */
    private val dotStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(230, 255, 255, 255)
    }

    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.TRANSPARENT
    }

    private var sizePx = 66f
    private var cx = 0f
    private var cy = 0f
    private var pulseStart = 0L

    fun setPointerSize(px: Float) {
        sizePx = px.coerceIn(24f, 220f)
        invalidateAll()
    }

    fun updatePosition(x: Float, y: Float) {
        if (x == cx && y == cy) return
        invalidateAround(cx, cy)
        cx = x
        cy = y
        invalidateAround(cx, cy)
    }

    fun pulse() {
        pulseStart = SystemClock.uptimeMillis()
        invalidateAround(cx, cy)
    }

    private fun invalidateAll() {
        invalidate()
    }

    private fun invalidateAround(x: Float, y: Float) {
        val pad = sizePx * 1.6f + 24f
        invalidate(
            (x - pad).toInt(), (y - pad).toInt(),
            (x + pad).toInt(), (y + pad).toInt()
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val now = SystemClock.uptimeMillis()
        val elapsed = now - pulseStart
        var press = 0f

        if (pulseStart > 0 && elapsed < 380L) {
            val t = elapsed / 380f
            press = 1f - t
            // 点击时向外扩的涟漪
            val r = sizePx * (0.46f + 0.62f * t)
            pulsePaint.strokeWidth = sizePx * 0.07f * (1f - t * 0.3f)
            pulsePaint.color = Color.argb(
                (125 * (1f - t)).toInt().coerceIn(0, 255), 70, 150, 255
            )
            canvas.drawCircle(cx, cy, r, pulsePaint)
            postInvalidateDelayed(16L)
        }

        // 按下时圆点轻微放大，给一点点“按到了”的手感
        val scale = 1f + 0.14f * press
        val ringR = sizePx * 0.52f * scale
        val dotR = sizePx * 0.34f * scale

        // 1) 深色界面上的微光
        canvas.drawCircle(cx, cy, ringR, haloPaint)
        // 2) 深色实心点 + 柔和投影（浅色界面上实心感靠它）
        dotPaint.setShadowLayer(
            (sizePx * 0.20f).coerceAtLeast(3f),
            0f,
            (sizePx * 0.05f).coerceAtLeast(1f),
            Color.argb(110, 0, 0, 0)
        )
        canvas.drawCircle(cx, cy, dotR, dotPaint)
        // 3) 白色描边，深色界面上勾出轮廓
        dotStrokePaint.strokeWidth = (sizePx * 0.07f).coerceIn(2f, sizePx * 0.10f)
        canvas.drawCircle(cx, cy, dotR - dotStrokePaint.strokeWidth / 2f, dotStrokePaint)
    }
}
