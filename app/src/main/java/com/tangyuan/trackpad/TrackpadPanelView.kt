package com.tangyuan.trackpad

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow

/**
 * 悬浮圆盘：中间当触控板，边缘当摇杆。
 *
 *   手指滑动      → 光标跟手，手指走多远光标走多远（滑出圆盘照样继续算）
 *   快刷一下松手  → 按这段实际位移在光标处滑一次屏（滚列表 / 翻页 / 拉通知栏）
 *   推到边上顶住  → 光标持续加速跑，不用反复擦手指
 *   轻点          → 在光标处点击
 *   双指轻点      → 在光标处长按
 *   双指上下滑    → 在光标处翻一页
 *   拖顶部横条    → 挪圆盘；拖到屏幕左右边缘松手 → 收成小球
 */
@SuppressLint("ViewConstructor")
class TrackpadPanelView(context: Context) : View(context) {

    interface Host {
        /** 手势开始：把当前光标位置记成基准，返回 [x, y] */
        fun onGestureStart(): FloatArray

        /** 光标直接定位到屏幕坐标 */
        fun onCursorTo(x: Float, y: Float)

        /** 光标相对挪动（边缘加速用） */
        fun onCursorDelta(dx: Float, dy: Float)

        /** 快刷收尾：从 (sx, sy) 起，按 (dx, dy) 滑一次屏 */
        fun onFlick(sx: Float, sy: Float, dx: Float, dy: Float)

        fun onTap()
        fun onTwoFingerTap()
        fun onScrollFinish(totalDy: Float)
        fun onPanelDrag(dx: Int, dy: Int)
        fun onDragFinished()
        fun onToggleCollapse()
    }

    var host: Host? = null
    var hapticEnabled: Boolean = true

    /**
     * 快刷滑屏：滑得快就锁住光标、按刷过的距离注入一段滑动。
     * 关掉就彻底没有这个功能 —— 多快都只是挪光标，松手什么也不做。
     */
    var flickGuessEnabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            // 手动关掉时，正在锁着的这次也松开
            if (!value && flickLocked) {
                flickLocked = false
                setRecallTarget(1f)
                invalidate()
            }
        }

    /** 跟手倍率：手指走 1px，光标走 gain px */
    var gain: Float = 3.0f

    var collapsed: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** 拖到屏幕左右边缘时点亮提示，松手就会收起 */
    var edgeHint: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB5161A22.toInt() }
    private val fillPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC1C2130.toInt() }
    /** 快刷锁定：底色带一点青，一眼能看出这拨手势不算光标 */
    private val fillFlickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC14282A.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
        color = 0x44FFFFFF
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = 0x1FFFFFFF
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x3DFFFFFF }
    private val knobBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = 0x66FFFFFF
    }
    private val gripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x77FFFFFF }
    private val gripActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val dockRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = 0x99FFFFFF.toInt()
    }
    private val driftRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.4f)
    }
    private val dockHintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        textSize = dp(9f)
        textAlign = Paint.Align.CENTER
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    private val arrow = Path().apply {
        moveTo(0f, 0f)
        lineTo(0f, 17.6f)
        lineTo(4.35f, 13.4f)
        lineTo(6.95f, 19.0f)
        lineTo(9.55f, 17.7f)
        lineTo(6.95f, 12.2f)
        lineTo(11.4f, 12.0f)
        close()
    }
    private val tmpRect = RectF()

    private companion object {
        const val MODE_NONE = 0
        const val MODE_STICK = 1
        const val MODE_GRIP = 2
        const val MODE_SCROLL = 4

        const val TAP_MAX_MS = 320L
        const val TAP_SLOP_DP = 10f

        /** 快刷：手指行程和时长门槛 */
        const val FLICK_MAX_MS = 280L
        const val FLICK_MIN_DP = 24f

        /** 快刷预判窗口：按下后这段时间内飙速，就当场认定是快刷，光标不再跟手 */
        const val FLICK_GUESS_MS = 420L

        /** 快刷预判的速度阈值（dp/s，已做平滑）。慢拖约 100dp/s，快刷能到 400+ */
        const val FLICK_SPEED_DP = 320f

        /** 预判要求的最小位移，避免落指时的抖动被当成快刷 */
        const val FLICK_GUESS_MIN_DP = 6f

        /** 光标收回锚点的时间常数（毫秒），越小收得越快 */
        const val RECALL_MS = 45f

        /** 顶到边缘多久之后开始持续加速 */
        const val DRIFT_DELAY_MS = 130L

        /** 超过行程 80% 才开始加速，避免刚按下就窜出去 */
        const val DRIFT_START = 0.80f
    }

    private var mode = MODE_NONE

    /** 本次手势的基准：手指按下时的光标屏幕坐标 */
    private var anchorX = 0f
    private var anchorY = 0f

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var moved = false
    private var pressed = false

    // 双指翻页
    private var scrollMoved = false
    private var lastScrollY = 0f
    private var scrollAccY = 0f

    // 边缘加速
    private var lastMoveTime = 0L
    private var driftStrength = 0f
    private var driftDirX = 0f
    private var driftDirY = 0f
    private var driftActive = false
    private var driftMoved = false
    private var drifted = false
    private var lastDriftTick = 0L

    // 快刷预判：一旦认准，光标就地锁住，手指再划也不跟
    private var flickLocked = false

    /** 上一帧手指位置，用来算瞬时速度 */
    private var prevX = 0f
    private var prevY = 0f

    /** 平滑后的手指速度（dp/s） */
    private var speedAvg = 0f

    /** 当前手指相对按下的位移，收回动画要按它重算光标 */
    private var curFdx = 0f
    private var curFdy = 0f

    /** 1 = 完全跟手，0 = 完全收在锚点，中间是收回/放开的过渡 */
    private var recallFactor = 1f
    private var recallTarget = 1f
    private var recallAnimating = false
    private var lastRecallTick = 0L

    private var centerX = 0f
    private var centerY = 0f
    private var radius = 1f
    private var knobR = 1f
    private var maxDeflect = 1f
    private var knobOffX = 0f
    private var knobOffY = 0f

    private var knobAnimToken = 0

    private val driftStarter = Runnable {
        if (mode == MODE_STICK && driftStrength > 0.02f &&
            SystemClock.uptimeMillis() - lastMoveTime >= DRIFT_DELAY_MS
        ) {
            driftActive = true
            lastDriftTick = 0L
            postOnAnimation(driftTicker)
            invalidate()
        }
    }

    private val driftTicker = object : Runnable {
        override fun run() {
            if (!driftActive) return
            val now = SystemClock.uptimeMillis()
            val dt = if (lastDriftTick == 0L) 0.016f
            else ((now - lastDriftTick) / 1000f).coerceIn(0.001f, 0.05f)
            lastDriftTick = now
            // 加速速度跟着"跟手倍率"一起变，手感只用一个旋钮调
            val speed = dp(620f) * (gain / 3f) * driftStrength.pow(1.35f)
            host?.onCursorDelta(driftDirX * speed * dt, driftDirY * speed * dt)
            driftMoved = true
            drifted = true
            postOnAnimation(this)
        }
    }

    /** 快刷预判触发后，把已经飘出去的光标顺滑地收回锚点 */
    private val recallTicker = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            val dt = if (lastRecallTick == 0L) 16f
            else (now - lastRecallTick).toFloat().coerceIn(1f, 50f)
            lastRecallTick = now

            val diff = recallTarget - recallFactor
            if (kotlin.math.abs(diff) <= 0.02f) {
                recallFactor = recallTarget
                recallAnimating = false
                pushCursor()
                return
            }
            recallFactor += diff * (dt / RECALL_MS).coerceIn(0.06f, 1f)
            pushCursor()
            postOnAnimation(this)
        }
    }

    /** 按「锚点 + 当前位移 × 收回系数」把光标摆到位 */
    private fun pushCursor() {
        host?.onCursorTo(
            anchorX + curFdx * gain * recallFactor,
            anchorY + curFdy * gain * recallFactor
        )
    }

    /** 目标 0 = 收进锚点，1 = 恢复跟手 */
    private fun setRecallTarget(target: Float) {
        recallTarget = target
        if (recallAnimating) return
        recallAnimating = true
        lastRecallTick = 0L
        postOnAnimation(recallTicker)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        radius = min(w, h) / 2f - dp(1.5f)
        knobR = radius * 0.35f
        // 摇杆头完全留在圆内，推到底也不会盖住顶部的抓手
        maxDeflect = (radius - knobR).coerceAtLeast(1f)
    }

    private fun gripRect(): RectF {
        val w = dp(52f)
        val h = dp(26f)
        return tmpRect.apply {
            left = centerX - w / 2f
            top = 0f
            right = left + w
            bottom = h
        }
    }

    private fun feedback(constant: Int) {
        if (!hapticEnabled) return
        try {
            performHapticFeedback(constant, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
        } catch (_: Throwable) {
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> handleDown(event)
            MotionEvent.ACTION_POINTER_DOWN -> handlePointerDown(event)
            MotionEvent.ACTION_MOVE -> handleMove(event)
            MotionEvent.ACTION_POINTER_UP -> {
                // 抬走一根手指时，平均值会跳一下，这里只重设基准，不累加
                if (mode == MODE_SCROLL) {
                    lastScrollY = averageYExcluding(event, event.actionIndex)
                }
            }
            MotionEvent.ACTION_UP -> handleUp(event)
            MotionEvent.ACTION_CANCEL -> {
                Log.d("TP", "CANCEL x=${event.x} y=${event.y}")
                cancelAll()
            }
        }
        return true
    }

    private fun handleDown(event: MotionEvent) {
        downX = event.x
        downY = event.y
        lastX = event.rawX
        lastY = event.rawY
        downTime = SystemClock.uptimeMillis()
        lastMoveTime = downTime
        moved = false
        drifted = false
        driftMoved = false
        driftStrength = 0f
        scrollMoved = false
        scrollAccY = 0f
        pressed = true
        knobAnimToken++
        stopDrift()

        mode = when {
            collapsed -> MODE_GRIP
            gripRect().contains(event.x, event.y) -> MODE_GRIP
            else -> MODE_STICK
        }
        if (mode == MODE_STICK) {
            knobOffX = 0f
            knobOffY = 0f
            flickLocked = false
            speedAvg = 0f
            curFdx = 0f
            curFdy = 0f
            recallFactor = 1f
            recallTarget = 1f
            recallAnimating = false
            lastRecallTick = 0L
            removeCallbacks(recallTicker)
            prevX = event.x
            prevY = event.y
            val a = host?.onGestureStart()
            anchorX = a?.getOrNull(0) ?: 0f
            anchorY = a?.getOrNull(1) ?: 0f
        }
        invalidate()
    }

    private fun handlePointerDown(event: MotionEvent) {
        if (mode == MODE_STICK || mode == MODE_SCROLL) {
            // 第二根手指进来 → 切到翻页，单指这边停手（光标就地停住）
            stopDrift()
            drifted = true
            knobOffX = 0f
            knobOffY = 0f
            mode = MODE_SCROLL
            scrollMoved = false
            scrollAccY = 0f
            lastScrollY = averageY(event)
            invalidate()
        }
    }

    private fun averageY(event: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until event.pointerCount) sum += event.getY(i)
        return if (event.pointerCount == 0) 0f else sum / event.pointerCount
    }

    private fun averageYExcluding(event: MotionEvent, skipIndex: Int): Float {
        var sum = 0f
        var count = 0
        for (i in 0 until event.pointerCount) {
            if (i == skipIndex) continue
            sum += event.getY(i)
            count++
        }
        return if (count == 0) 0f else sum / count
    }

    private fun handleMove(event: MotionEvent) {
        when (mode) {
            MODE_GRIP -> {
                if (!moved && hypot(event.rawX - lastX, event.rawY - lastY) > dp(4f)) moved = true
                if (moved) {
                    val dx = (event.rawX - lastX).toInt()
                    val dy = (event.rawY - lastY).toInt()
                    if (dx != 0 || dy != 0) host?.onPanelDrag(dx, dy)
                }
                lastX = event.rawX
                lastY = event.rawY
            }

            MODE_STICK -> handleStickMove(event)

            MODE_SCROLL -> {
                val y = averageY(event)
                val dy = y - lastScrollY
                lastScrollY = y
                if (kotlin.math.abs(dy) > 0.5f) {
                    scrollAccY += dy
                    if (kotlin.math.abs(scrollAccY) > dp(12f)) scrollMoved = true
                }
            }
        }
    }

    private fun handleStickMove(event: MotionEvent) {
        val now = SystemClock.uptimeMillis()

        // 先把这一帧的速度算出来（换成 dp/s，不同分辨率手感一致）
        // dt 太小时速度会虚高，太大时因为事件被批量合并会偏低，都夹一下
        val step = hypot(event.x - prevX, event.y - prevY)
        prevX = event.x
        prevY = event.y
        val dt = (now - lastMoveTime).coerceIn(3L, 48L)
        val frameSpeed = step / dt * 1000f / d
        // 平滑一下，单帧毛刺不至于误触发
        speedAvg = if (speedAvg <= 0f) frameSpeed else speedAvg * 0.55f + frameSpeed * 0.45f
        lastMoveTime = now

        // 加速跑过之后手指再动 → 重新锚定，否则光标会跳回跟手位置
        if (driftMoved) {
            stopDrift()
            val a = host?.onGestureStart()
            anchorX = a?.getOrNull(0) ?: 0f
            anchorY = a?.getOrNull(1) ?: 0f
            downX = event.x
            downY = event.y
            driftMoved = false
        }

        val fdx = event.x - downX
        val fdy = event.y - downY
        val dist = hypot(fdx, fdy)
        if (dist > dp(TAP_SLOP_DP)) moved = true
        curFdx = fdx
        curFdy = fdy

        // 前半秒内手指飙起来 → 当场认作快刷，光标收回锚点锁住，后面不再跟手
        if (flickGuessEnabled && !flickLocked && !drifted &&
            now - downTime <= FLICK_GUESS_MS &&
            dist >= dp(FLICK_GUESS_MIN_DP) &&
            speedAvg >= FLICK_SPEED_DP
        ) {
            flickLocked = true
            setRecallTarget(0f)
            feedback(HapticFeedbackConstants.CLOCK_TICK)
            Log.d("TP", "flick locked speed=${speedAvg.toInt()}dp/s dist=${dist.toInt()}")
            invalidate()
        }

        // 摇杆头跟着走（超出行程就顶在边上）—— 快刷时也照跟，手上才有反馈
        val k = if (dist > maxDeflect && dist > 0f) maxDeflect / dist else 1f
        knobOffX = fdx * k
        knobOffY = fdy * k

        if (flickLocked) {
            // 光标锁在锚点，这一拨手势不再驱动光标，也不蓄力加速
            invalidate()
            return
        }

        // 触控板逻辑：按手指的实际位移映射光标
        pushCursor()

        // 推到行程 80% 以外才开始蓄力加速；边推边走不算，停住才启动
        val mag = if (maxDeflect <= 0f) 0f else (dist / maxDeflect).coerceAtMost(1.4f)
        driftStrength = if (mag <= DRIFT_START) 0f
        else ((mag - DRIFT_START) / (1f - DRIFT_START)).coerceIn(0f, 1f)

        if (driftStrength > 0.02f && dist > 0f) {
            driftDirX = fdx / dist
            driftDirY = fdy / dist
            removeCallbacks(driftStarter)
            postDelayed(driftStarter, DRIFT_DELAY_MS)
        } else {
            stopDrift()
        }
        invalidate()
    }

    private fun handleUp(event: MotionEvent) {
        when (mode) {
            MODE_GRIP -> {
                if (moved) {
                    host?.onDragFinished()
                } else if (collapsed && SystemClock.uptimeMillis() - downTime < TAP_MAX_MS) {
                    feedback(HapticFeedbackConstants.CLOCK_TICK)
                    host?.onToggleCollapse()
                }
            }

            MODE_STICK -> {
                stopDrift()
                val elapsed = SystemClock.uptimeMillis() - downTime
                val fdx = event.x - downX
                val fdy = event.y - downY
                val dist = hypot(fdx, fdy)

                var acted = "none"
                when {
                    // 已经用加速跑过去，或者中途变成双指，就不补滑动
                    drifted -> acted = "skip"

                    // 滑的过程中已经认准是快刷：光标落回锚点，从这儿滑
                    flickLocked -> {
                        curFdx = fdx
                        curFdy = fdy
                        recallFactor = 0f
                        recallAnimating = false
                        removeCallbacks(recallTicker)
                        pushCursor()
                        if (dist >= dp(FLICK_MIN_DP)) {
                            feedback(HapticFeedbackConstants.CLOCK_TICK)
                            host?.onFlick(anchorX, anchorY, fdx * gain, fdy * gain)
                            acted = "flick-locked"
                        } else {
                            acted = "locked-short"
                        }
                    }

                    // 快刷：按实际位移滚屏，滑多远滚多远（功能关掉就整段跳过）
                    flickGuessEnabled &&
                        dist >= dp(FLICK_MIN_DP) && elapsed <= FLICK_MAX_MS -> {
                        feedback(HapticFeedbackConstants.CLOCK_TICK)
                        host?.onFlick(anchorX, anchorY, fdx * gain, fdy * gain)
                        acted = "flick"
                    }

                    dist < dp(TAP_SLOP_DP) && elapsed < TAP_MAX_MS -> {
                        feedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        host?.onTap()
                        acted = "tap"
                    }
                }
                Log.d("TP", "up dx=${fdx.toInt()} dy=${fdy.toInt()} t=$elapsed drift=$drifted " +
                    "flick=$flickLocked act=$acted anchor=${anchorX.toInt()},${anchorY.toInt()} " +
                    "cursor=${(anchorX + fdx * gain * recallFactor).toInt()},${(anchorY + fdy * gain * recallFactor).toInt()}")
            }

            MODE_SCROLL -> {
                if (!scrollMoved) {
                    feedback(HapticFeedbackConstants.LONG_PRESS)
                    host?.onTwoFingerTap()
                } else {
                    host?.onScrollFinish(scrollAccY)
                }
            }
        }
        resetState()
    }

    private fun cancelAll() {
        stopDrift()
        resetState()
    }

    private fun stopDrift() {
        removeCallbacks(driftStarter)
        removeCallbacks(driftTicker)
        if (!driftActive) {
            driftStrength = 0f
            return
        }
        driftActive = false
        driftStrength = 0f
        invalidate()
    }

    private fun resetState() {
        mode = MODE_NONE
        pressed = false
        moved = false
        drifted = false
        driftMoved = false
        flickLocked = false
        speedAvg = 0f
        curFdx = 0f
        curFdy = 0f
        recallFactor = 1f
        recallTarget = 1f
        recallAnimating = false
        lastRecallTick = 0L
        scrollMoved = false
        scrollAccY = 0f
        driftStrength = 0f
        removeCallbacks(driftStarter)
        removeCallbacks(driftTicker)
        removeCallbacks(recallTicker)
        driftActive = false
        animateKnobBack()
    }

    private fun animateKnobBack() {
        if (knobOffX == 0f && knobOffY == 0f) {
            invalidate()
            return
        }
        val sx = knobOffX
        val sy = knobOffY
        val t0 = SystemClock.uptimeMillis()
        val token = ++knobAnimToken
        val run = object : Runnable {
            override fun run() {
                if (token != knobAnimToken) return
                val t = ((SystemClock.uptimeMillis() - t0) / 140f).coerceIn(0f, 1f)
                val e = 1f - (1f - t) * (1f - t)
                knobOffX = sx * (1f - e)
                knobOffY = sy * (1f - e)
                invalidate()
                if (t < 1f) postOnAnimation(this)
            }
        }
        postOnAnimation(run)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (collapsed) {
            drawCollapsed(canvas)
            return
        }

        val bg = when {
            flickLocked -> fillFlickPaint
            pressed -> fillPressedPaint
            else -> fillPaint
        }
        canvas.drawCircle(centerX, centerY, radius, bg)
        if (edgeHint) {
            canvas.drawCircle(centerX, centerY, radius - dp(2.5f), dockRingPaint)
        }
        canvas.drawCircle(centerX, centerY, radius, borderPaint)
        if (!edgeHint) {
            canvas.drawCircle(centerX, centerY, radius * 0.66f, ringPaint)
        }

        // 顶到边缘蓄力：内圈亮起来
        if (driftStrength > 0.02f) {
            driftRingPaint.color = Color.argb(
                (40 + 130 * driftStrength).toInt().coerceIn(0, 255), 120, 190, 255
            )
            canvas.drawCircle(centerX, centerY, radius - dp(3f), driftRingPaint)
        } else if (flickLocked) {
            // 快刷锁定：青色内圈，提示「这拨是在滑屏，光标不动」
            driftRingPaint.color = Color.argb(150, 90, 216, 198)
            canvas.drawCircle(centerX, centerY, radius - dp(3f), driftRingPaint)
        }

        // 摇杆头（先画，抓手和文字压在上面，推到底也不会被盖住）
        val kx = centerX + knobOffX
        val ky = centerY + knobOffY
        canvas.drawCircle(kx, ky, knobR, knobPaint)
        canvas.drawCircle(kx, ky, knobR, knobBorderPaint)

        // 顶部抓手（拖动整个圆盘；拖到屏幕左右边缘松手即收起）
        canvas.drawRoundRect(
            centerX - dp(24f), dp(5.5f), centerX + dp(24f), dp(10.5f),
            dp(2.5f), dp(2.5f), if (mode == MODE_GRIP) gripActivePaint else gripPaint
        )

        val hint = when {
            edgeHint -> "松手收起"
            flickLocked -> "快刷"
            driftActive -> "加速中"
            mode == MODE_STICK && moved -> "跟手"
            else -> "触控板"
        }
        canvas.drawText(hint, centerX, centerY + radius * 0.74f, dockHintPaint)
    }

    private fun drawCollapsed(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - dp(1f)
        canvas.drawCircle(cx, cy, r, if (pressed) fillPressedPaint else fillPaint)
        canvas.drawCircle(cx, cy, r, borderPaint)

        val scale = dp(0.8f)
        canvas.save()
        canvas.translate(cx - 5.7f * scale, cy - 9.5f * scale)
        canvas.scale(scale, scale)
        canvas.drawPath(arrow, arrowPaint)
        canvas.restore()
    }
}
