package com.tangyuan.trackpad

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private var tick by mutableIntStateOf(0)

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }

        setContent {
            AppTheme {
                LaunchedEffect(Unit) {
                    while (true) {
                        kotlinx.coroutines.delay(800L)
                        tick++
                    }
                }
                HomeScreen(
                    tick = tick,
                    onToggle = { toggleService() },
                    onOverlay = { openOverlaySettings() },
                    onA11y = { openA11ySettings() },
                    onNotif = { requestNotifPermission() },
                    onOpenA11ySettings = { openA11ySettings() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        tick++
    }

    private fun toggleService() {
        if (TrackpadService.isRunning) {
            startService(
                Intent(this, TrackpadService::class.java).setAction(TrackpadService.ACTION_STOP)
            )
            tick++
            return
        }
        if (!Perms.hasOverlay(this)) {
            openOverlaySettings()
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, TrackpadService::class.java))
        tick++
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        runCatching { startActivity(intent) }
            .onFailure { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
    }

    private fun openA11ySettings() {
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun HomeScreen(
    tick: Int,
    onToggle: () -> Unit,
    onOverlay: () -> Unit,
    onA11y: () -> Unit,
    onNotif: () -> Unit,
    onOpenA11ySettings: () -> Unit
) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs.of(ctx) }

    val overlayOk = remember(tick) { Perms.hasOverlay(ctx) }
    val a11yOk = remember(tick) { Perms.hasAccessibility(ctx) }
    val notifOk = remember(tick) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
    }
    val running = remember(tick) { TrackpadService.isRunning }

    var sensitivity by remember {
        mutableStateOf(prefs.getFloat(Prefs.KEY_SENSITIVITY, Prefs.DEFAULT_SENSITIVITY))
    }
    var cursorSize by remember {
        mutableStateOf(prefs.getFloat(Prefs.KEY_CURSOR_SIZE, Prefs.DEFAULT_CURSOR_SIZE))
    }
    var padW by remember {
        mutableStateOf(prefs.getFloat(Prefs.KEY_PAD_W, Prefs.DEFAULT_PAD_W))
    }
    var haptic by remember {
        mutableStateOf(prefs.getBoolean(Prefs.KEY_HAPTIC, Prefs.DEFAULT_HAPTIC))
    }
    var flickGuess by remember {
        mutableStateOf(prefs.getBoolean(Prefs.KEY_FLICK_GUESS, Prefs.DEFAULT_FLICK_GUESS))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp)
    ) {
        Spacer(Modifier.height(20.dp))
        Text(
            "虚拟触摸板",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "右下角浮一个半透明圆盘：手指在上面滑，光标跟手走多远；快速一刷松手，屏幕就按你刷过的距离滚。拖顶部横条到屏幕侧边能收起来。",
            fontSize = 13.sp,
            lineHeight = 19.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(18.dp))

        StatusCard(running = running, a11yOk = a11yOk, onToggle = onToggle)
        Spacer(Modifier.height(14.dp))

        SectionCard(title = "权限") {
            PermissionRow(
                title = "悬浮窗",
                desc = "画光标和触控板，必须开",
                ok = overlayOk,
                actionText = if (overlayOk) "已授权" else "去授权",
                onAction = onOverlay
            )
            Spacer(Modifier.height(12.dp))
            PermissionRow(
                title = "无障碍手势注入",
                desc = "在光标处模拟点击/滑动，不读取屏幕内容",
                ok = a11yOk,
                actionText = if (a11yOk) "已开启" else "去开启",
                onAction = onA11y
            )
            Spacer(Modifier.height(12.dp))
            PermissionRow(
                title = "通知",
                desc = "常驻通知用来一键关闭",
                ok = notifOk,
                actionText = if (notifOk) "已授权" else "去授权",
                onAction = onNotif
            )
        }

        Spacer(Modifier.height(14.dp))

        SectionCard(title = "手感") {
            SliderRow(
                label = "跟手倍率",
                value = "${((sensitivity * 10).roundToInt() / 10f)}x",
                value2 = sensitivity,
                range = 1.5f..6f,
                onChange = {
                    sensitivity = it
                    prefs.edit().putFloat(Prefs.KEY_SENSITIVITY, it).apply()
                }
            )
            SliderRow(
                label = "光标大小",
                value = "${cursorSize.roundToInt()} dp",
                value2 = cursorSize,
                range = 14f..44f,
                onChange = {
                    cursorSize = it
                    prefs.edit().putFloat(Prefs.KEY_CURSOR_SIZE, it).apply()
                }
            )
            SliderRow(
                label = "摇杆大小",
                value = "${padW.roundToInt()} dp",
                value2 = padW,
                range = 100f..210f,
                onChange = {
                    padW = it
                    prefs.edit().putFloat(Prefs.KEY_PAD_W, it).apply()
                }
            )
            Spacer(Modifier.height(6.dp))
            SwitchRow(
                title = "快刷滑屏",
                desc = "手一划快就锁住光标，按刷过的距离滚屏。关掉后快刷只是挪光标，不滑屏",
                checked = flickGuess,
                onChange = {
                    flickGuess = it
                    prefs.edit().putBoolean(Prefs.KEY_FLICK_GUESS, it).apply()
                }
            )
            Spacer(Modifier.height(12.dp))
            SwitchRow(
                title = "震动反馈",
                desc = "轻点和长按时给一下手感",
                checked = haptic,
                onChange = {
                    haptic = it
                    prefs.edit().putBoolean(Prefs.KEY_HAPTIC, it).apply()
                }
            )
        }

        Spacer(Modifier.height(14.dp))

        SectionCard(title = "怎么用") {
            GuideLine("手指滑动", "光标跟手：滑多远走多远，滑出圆盘也照样算")
            GuideLine("快速一刷", "光标锁住，松手按刷过的距离滚屏；关掉快刷滑屏就只挪光标")
            GuideLine("推到边上顶住", "光标开始持续加速跑，长距离不用反复擦手指")
            GuideLine("轻点圆盘", "在光标处点击（连点两次 = 双击）")
            GuideLine("双指轻点", "在光标处长按，相当于右键菜单")
            GuideLine("双指上下滑", "在光标处翻一页，幅度更大")
            GuideLine("拖顶部横条", "挪圆盘；拖到屏幕左右边缘松手就收成小球")
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(12.dp)
            ) {
                Text(
                    "放进状态栏：下拉通知栏 → 点铅笔/编辑 → 把「虚拟触控板」拖进快捷开关。之后点一下就能开关。",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!a11yOk) {
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = onOpenA11ySettings) {
                    Text("立刻去开无障碍服务", fontSize = 13.sp)
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        Text(
            "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · 手势注入不读取屏幕内容",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 24.dp)
        )
    }
}

@Composable
private fun StatusCard(running: Boolean, a11yOk: Boolean, onToggle: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = cs.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(if (running) Color(0xFF21C07A) else cs.outline)
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    if (running) "运行中" else "未开启",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onSurface
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (running) (if (a11yOk) "点击已就绪" else "缺无障碍") else "点下面开启",
                    fontSize = 12.sp,
                    color = if (running && !a11yOk) cs.error else cs.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onToggle,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (running) cs.surfaceVariant else cs.primary,
                    contentColor = if (running) cs.onSurface else cs.onPrimary
                )
            ) {
                Text(
                    if (running) "关闭触控板" else "开启触控板",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    desc: String,
    ok: Boolean,
    actionText: String,
    onAction: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (ok) Color(0xFF21C07A) else cs.error)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
            Text(desc, fontSize = 11.5.sp, lineHeight = 16.sp, color = cs.onSurfaceVariant)
        }
        TextButton(onClick = onAction, enabled = !ok) {
            Text(
                actionText,
                fontSize = 13.sp,
                color = if (ok) cs.onSurfaceVariant else cs.primary
            )
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: String,
    value2: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Column(Modifier.padding(top = 4.dp, bottom = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.weight(1f))
            Text(value, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = value2,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                desc,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary)
        )
    }
}

@Composable
private fun GuideLine(name: String, desc: String) {
    Row(
        Modifier.padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        Text(
            "· ",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        Text(
            name,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            "  $desc",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
