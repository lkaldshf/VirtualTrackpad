package com.tangyuan.trackpad

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlin.math.hypot

/**
 * 常驻前台服务：挂着「光标层 + 触控板面板」两个悬浮窗。
 */
class TrackpadService : Service(), TrackpadPanelView.Host,
    SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        const val ACTION_START = "com.tangyuan.trackpad.action.START"
        const val ACTION_STOP = "com.tangyuan.trackpad.action.STOP"

        private const val CHANNEL_ID = "trackpad_running"
        private const val NOTIF_ID = 1101

        @Volatile
        var isRunning = false
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var wm: WindowManager

    private var cursorView: CursorOverlayView? = null
    private var cursorLp: WindowManager.LayoutParams? = null

    private var panelView: TrackpadPanelView? = null
    private var panelLp: WindowManager.LayoutParams? = null

    private var screenW = 1080
    private var screenH = 2400

    private var cursorX = 0f
    private var cursorY = 0f

    private var sensitivity = Prefs.DEFAULT_SENSITIVITY
    private var collapsed = false

    private var lastToastAt = 0L
    private var savePositionRunnable: Runnable? = null
    private var expandedX = -1
    private var expandedY = -1

    /** 本次手势按下时的光标位置：跟手和快刷都以它为基准 */
    private var anchorX = 0f
    private var anchorY = 0f

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification())

        if (!Settings.canDrawOverlays(this)) {
            toast("没有悬浮窗权限，先回应用里授权")
            stopSelf()
            return
        }

        prefs = Prefs.of(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        sensitivity = prefs.getFloat(Prefs.KEY_SENSITIVITY, Prefs.DEFAULT_SENSITIVITY)
        collapsed = prefs.getBoolean(Prefs.KEY_COLLAPSED, false)
        // 记住上次展开时的位置，重启后点小球展开能回到原处
        expandedX = prefs.getInt(Prefs.KEY_PAD_X, -1)
        expandedY = prefs.getInt(Prefs.KEY_PAD_Y, -1)

        refreshScreenSize()
        cursorX = screenW / 2f
        cursorY = screenH / 2f

        addCursorOverlay()
        addPanel()
        prefs.registerOnSharedPreferenceChangeListener(this)
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        try {
            prefs.unregisterOnSharedPreferenceChangeListener(this)
        } catch (_: Throwable) {
        }
        savePositionRunnable?.let { handler.removeCallbacks(it) }
        panelView?.let { runCatching { wm.removeView(it) } }
        cursorView?.let { runCatching { wm.removeView(it) } }
        panelView = null
        cursorView = null
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handler.post {
            refreshScreenSize()
            cursorX = cursorX.coerceIn(0f, screenW - 1f)
            cursorY = cursorY.coerceIn(0f, screenH - 1f)
            cursorView?.updatePosition(cursorX, cursorY)
            applyPanelLayout(recenter = false)
        }
    }

    // ---------------- 悬浮窗搭建 ----------------

    private fun refreshScreenSize() {
        val point = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            Point(b.width(), b.height())
        } else {
            val p = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(p)
            p
        }
        screenW = point.x
        screenH = point.y
    }

    private fun addCursorOverlay() {
        val view = CursorOverlayView(this)
        view.setPointerSize(dp(prefs.getFloat(Prefs.KEY_CURSOR_SIZE, Prefs.DEFAULT_CURSOR_SIZE)))

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }

        runCatching { wm.addView(view, lp) }.onFailure {
            toast("光标层添加失败：${it.message}")
            stopSelf()
            return
        }
        cursorView = view
        cursorLp = lp
        view.updatePosition(cursorX, cursorY)
    }

    private fun addPanel() {
        val view = TrackpadPanelView(this)
        view.host = this
        view.hapticEnabled = prefs.getBoolean(Prefs.KEY_HAPTIC, Prefs.DEFAULT_HAPTIC)
        view.flickGuessEnabled = prefs.getBoolean(Prefs.KEY_FLICK_GUESS, Prefs.DEFAULT_FLICK_GUESS)
        view.gain = sensitivity
        view.collapsed = collapsed

        val size = panelSizePx()
        val lp = WindowManager.LayoutParams(
            size.x,
            size.y,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (collapsed) {
                x = prefs.getInt(Prefs.KEY_BUBBLE_X, Int.MIN_VALUE)
                y = prefs.getInt(Prefs.KEY_BUBBLE_Y, Int.MIN_VALUE)
                if (x == Int.MIN_VALUE || y == Int.MIN_VALUE) {
                    x = (screenW - size.x - dp(10f)).toInt()
                    y = (screenH - size.y - dp(220f)).toInt()
                }
            } else {
                x = prefs.getInt(Prefs.KEY_PAD_X, Int.MIN_VALUE)
                y = prefs.getInt(Prefs.KEY_PAD_Y, Int.MIN_VALUE)
                if (x == Int.MIN_VALUE || y == Int.MIN_VALUE) {
                    x = (screenW - size.x - dp(16f)).toInt()
                    y = (screenH - size.y - dp(72f)).toInt()
                }
            }
            if (!collapsed) {
                expandedX = x
                expandedY = y
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }

        runCatching { wm.addView(view, lp) }.onFailure {
            toast("触控板添加失败：${it.message}")
            stopSelf()
            return
        }
        panelView = view
        panelLp = lp
        clampPanel()
    }

    private fun panelSizePx(): Point {
        if (collapsed) {
            val s = dp(54f).toInt()
            return Point(s, s)
        }
        val dia = dp(prefs.getFloat(Prefs.KEY_PAD_W, Prefs.DEFAULT_PAD_W)).toInt()
        return Point(dia, dia)
    }

    private fun applyPanelLayout(recenter: Boolean) {
        val view = panelView ?: return
        val lp = panelLp ?: return
        val size = panelSizePx()
        lp.width = size.x
        lp.height = size.y
        if (recenter) {
            lp.x = (screenW - size.x - dp(16f)).toInt()
            lp.y = (screenH - size.y - dp(72f)).toInt()
        }
        lp.x = lp.x.coerceIn(0, (screenW - size.x).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(0, (screenH - size.y).coerceAtLeast(0))
        runCatching { wm.updateViewLayout(view, lp) }
    }

    private fun clampPanel() {
        val lp = panelLp ?: return
        lp.x = lp.x.coerceIn(0, (screenW - lp.width).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(0, (screenH - lp.height).coerceAtLeast(0))
        panelView?.let { runCatching { wm.updateViewLayout(it, lp) } }
    }

    // ---------------- 触控板回调 ----------------

    override fun onGestureStart(): FloatArray {
        anchorX = cursorX
        anchorY = cursorY
        return floatArrayOf(cursorX, cursorY)
    }

    override fun onCursorTo(x: Float, y: Float) {
        cursorX = x.coerceIn(0f, screenW - 1f)
        cursorY = y.coerceIn(0f, screenH - 1f)
        cursorView?.updatePosition(cursorX, cursorY)
    }

    override fun onCursorDelta(dx: Float, dy: Float) {
        onCursorTo(cursorX + dx, cursorY + dy)
    }

    override fun onFlick(sx: Float, sy: Float, dx: Float, dy: Float) {
        val path = fitPath(sx, sy, dx, dy) ?: return
        val x0 = path[0]
        val y0 = path[1]
        val x1 = path[2]
        val y1 = path[3]
        val len = hypot(x1 - x0, y1 - y0)

        cursorView?.pulse()
        // 距离越长滑得稍久一点，短促一点才有"甩"的感觉
        val duration = (110f + len / dp(1f) * 0.30f).toLong().coerceIn(110L, 320L)
        injectSafely(x0, y0) {
            TrackpadAccessibilityService.swipe(x0, y0, x1, y1, duration)
        }
    }

    /**
     * 把「从光标起、按手指位移滑一段」的路径整体挪进屏幕内。
     *
     * 不能简单截短：光标贴在屏幕顶边时，往上滑的起点就已经在边上了，
     * 截短的结果是长度变 0，等于白刷。整体平移才能保住这段距离。
     */
    private fun fitPath(sx: Float, sy: Float, dx: Float, dy: Float): FloatArray? {
        val m = dp(2f)
        val w = screenW - 2 * m
        val h = screenH - 2 * m
        var vx = dx
        var vy = dy
        val len = hypot(vx, vy)
        if (len < dp(16f)) return null

        val maxLen = hypot(w, h) * 0.9f
        if (len > maxLen) {
            val k = maxLen / len
            vx *= k
            vy *= k
        }

        var x0 = sx
        var y0 = sy
        if (vx > 0f) x0 = x0.coerceIn(m, m + (w - vx).coerceAtLeast(0f))
        else if (vx < 0f) x0 = x0.coerceIn(m - vx, screenW - m)
        if (vy > 0f) y0 = y0.coerceIn(m, m + (h - vy).coerceAtLeast(0f))
        else if (vy < 0f) y0 = y0.coerceIn(m - vy, screenH - m)

        return floatArrayOf(x0, y0, x0 + vx, y0 + vy)
    }

    /** 注入点的坐标可能压在圆盘自己身上，先把圆盘藏一下再发 */
    private fun injectSafely(x: Float, y: Float, action: () -> Boolean) {
        if (!TrackpadAccessibilityService.isReady) {
            warnNoAccessibility()
            return
        }
        val view = panelView
        if (view != null && isOverPanel(x, y)) {
            view.visibility = android.view.View.INVISIBLE
            handler.postDelayed({
                // 返回 false 通常是上一笔手势还没走完，静默跳过就行
                action()
                handler.postDelayed({ view.visibility = android.view.View.VISIBLE }, 360L)
            }, 70L)
        } else {
            action()
        }
    }

    private fun isOverPanel(x: Float, y: Float): Boolean {
        val lp = panelLp ?: return false
        return x >= lp.x && x <= lp.x + lp.width && y >= lp.y && y <= lp.y + lp.height
    }

    override fun onTap() {
        cursorView?.pulse()
        injectSafely(cursorX, cursorY) { TrackpadAccessibilityService.tap(cursorX, cursorY) }
    }

    override fun onTwoFingerTap() {
        cursorView?.pulse()
        injectSafely(cursorX, cursorY) { TrackpadAccessibilityService.longPress(cursorX, cursorY) }
    }

    override fun onScrollFinish(totalDy: Float) {
        // 手指已经离开圆盘，这时候再注入，不会跟自身的触摸流打架
        val gain = 2.6f
        var dy = totalDy * gain
        if (kotlin.math.abs(dy) < dp(30f)) return

        val maxLen = screenH * 0.55f
        if (dy > maxLen) dy = maxLen
        if (dy < -maxLen) dy = -maxLen
        val startX = cursorX
        val startY = cursorY
        val endY = (startY + dy).coerceIn(dp(2f), screenH - dp(2f))
        if (kotlin.math.abs(endY - startY) < dp(26f)) return

        cursorView?.pulse()
        val duration = (150f + kotlin.math.abs(endY - startY) / dp(1f) * 0.6f)
            .toLong().coerceIn(150L, 420L)
        injectSafely(startX, startY) {
            TrackpadAccessibilityService.swipe(startX, startY, startX, endY, duration)
        }
    }

    override fun onPanelDrag(dx: Int, dy: Int) {
        val lp = panelLp ?: return
        lp.x = (lp.x + dx).coerceIn(0, (screenW - lp.width).coerceAtLeast(0))
        lp.y = (lp.y + dy).coerceIn(0, (screenH - lp.height).coerceAtLeast(0))
        panelView?.let { runCatching { wm.updateViewLayout(it, lp) } }
        // 展开状态拖到屏幕左右边缘 → 点亮「松手收起」提示
        panelView?.edgeHint = !collapsed && isDockedToEdge(lp)
    }

    /** 圆盘是否已经贴住屏幕左/右边缘（松手就收成小球） */
    private fun isDockedToEdge(lp: WindowManager.LayoutParams): Boolean {
        val margin = dp(20f)
        return lp.x <= margin || lp.x + lp.width >= screenW - margin
    }

    override fun onDragFinished() {
        val lp = panelLp ?: return
        panelView?.edgeHint = false
        savePositionRunnable?.let { handler.removeCallbacks(it) }

        // 拖横条贴边松手 → 收成小球；位置仍保留上一次的位置，展开能回原处
        if (!collapsed && isDockedToEdge(lp)) {
            toggleCollapse(edgeDock = true)
            return
        }

        val isBubble = collapsed
        val r = Runnable {
            val e = prefs.edit()
            if (isBubble) {
                e.putInt(Prefs.KEY_BUBBLE_X, lp.x).putInt(Prefs.KEY_BUBBLE_Y, lp.y)
            } else {
                expandedX = lp.x
                expandedY = lp.y
                e.putInt(Prefs.KEY_PAD_X, lp.x).putInt(Prefs.KEY_PAD_Y, lp.y)
            }
            e.apply()
        }
        savePositionRunnable = r
        handler.postDelayed(r, 350L)
    }

    override fun onToggleCollapse() = toggleCollapse(edgeDock = false)

    private fun toggleCollapse(edgeDock: Boolean) {
        val lp = panelLp ?: return
        collapsed = !collapsed
        prefs.edit().putBoolean(Prefs.KEY_COLLAPSED, collapsed).apply()
        panelView?.collapsed = collapsed

        val size = panelSizePx()
        if (collapsed) {
            val oldH = lp.height
            if (edgeDock) {
                // 贴边收起：不动展开位置，下次点小球还回拖动前的原处
                panelView?.edgeHint = false
            } else {
                expandedX = lp.x
                expandedY = lp.y
                prefs.edit().putInt(Prefs.KEY_PAD_X, lp.x).putInt(Prefs.KEY_PAD_Y, lp.y).apply()
            }
            // 收起：小球贴到最近的一侧
            val centerX = lp.x + lp.width / 2f
            lp.width = size.x
            lp.height = size.y
            lp.x = if (centerX < screenW / 2f) {
                dp(10f).toInt()
            } else {
                (screenW - size.x - dp(10f)).toInt()
            }
            lp.y = (lp.y + (oldH - size.y) / 2)
                .coerceIn(dp(40f).toInt(), (screenH - size.y - dp(40f)).toInt())
            // 记住小球位置，重启后还在同一个地方
            prefs.edit()
                .putInt(Prefs.KEY_BUBBLE_X, lp.x)
                .putInt(Prefs.KEY_BUBBLE_Y, lp.y)
                .apply()
        } else {
            // 展开：回到收起前的位置
            lp.width = size.x
            lp.height = size.y
            lp.x = if (expandedX >= 0) expandedX else (screenW - size.x - dp(16f)).toInt()
            lp.y = if (expandedY >= 0) expandedY else (screenH - size.y - dp(72f)).toInt()
            lp.x = lp.x.coerceIn(0, (screenW - size.x).coerceAtLeast(0))
            lp.y = lp.y.coerceIn(0, (screenH - size.y).coerceAtLeast(0))
        }
        panelView?.let { runCatching { wm.updateViewLayout(it, lp) } }
    }

    // ---------------- 设置变更 ----------------

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
        when (key) {
            Prefs.KEY_SENSITIVITY -> {
                sensitivity = prefs.getFloat(Prefs.KEY_SENSITIVITY, Prefs.DEFAULT_SENSITIVITY)
                panelView?.gain = sensitivity
            }

            Prefs.KEY_CURSOR_SIZE -> cursorView?.setPointerSize(
                dp(prefs.getFloat(Prefs.KEY_CURSOR_SIZE, Prefs.DEFAULT_CURSOR_SIZE))
            )

            Prefs.KEY_HAPTIC -> panelView?.hapticEnabled =
                prefs.getBoolean(Prefs.KEY_HAPTIC, Prefs.DEFAULT_HAPTIC)

            Prefs.KEY_FLICK_GUESS -> panelView?.flickGuessEnabled =
                prefs.getBoolean(Prefs.KEY_FLICK_GUESS, Prefs.DEFAULT_FLICK_GUESS)

            Prefs.KEY_PAD_W -> {
                val lp = panelLp ?: return
                val size = panelSizePx()
                lp.width = size.x
                lp.height = size.y
                lp.x = lp.x.coerceIn(0, (screenW - size.x).coerceAtLeast(0))
                lp.y = lp.y.coerceIn(0, (screenH - size.y).coerceAtLeast(0))
                panelView?.let { runCatching { wm.updateViewLayout(it, lp) } }
            }
        }
    }

    // ---------------- 通知 / 提示 ----------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "虚拟触摸板运行中",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持光标层与触控板常驻"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 11,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 12,
            Intent(this, TrackpadService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("虚拟触摸板已开启")
            .setContentText("显示光标，从状态栏快捷开关也能关闭")
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)
            .addAction(0, "关闭", stopIntent)
            .build()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun warnNoAccessibility() {
        val now = System.currentTimeMillis()
        if (now - lastToastAt < 4000) return
        lastToastAt = now
        toast("点击没生效：设置 → 无障碍 → 把「虚拟触摸板」关掉再打开一次")
    }
}
