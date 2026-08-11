package com.newolf.kaka

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.ComponentName
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newolf.kaka.model.PunchTypeMapper
import com.newolf.kaka.ui.theme.KaKaTheme
import com.newolf.kaka.util.LatestImageFinder
import com.newolf.kaka.util.Logger
import com.tencent.mmkv.MMKV

class SettingsActivity : ComponentActivity() {

    /**
     * Android 13+ runtime 通知权限申请回调。
     * 拒绝时提示用户手动开启；同意时静默继续。
     */
    private val requestNotifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                Toast.makeText(this, "已授予通知权限", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "通知权限被拒绝，可到系统设置手动开启", Toast.LENGTH_LONG).show()
                // 兜底：直接把用户带到本应用的通知设置页
                openAppNotificationSettings()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MMKV.initialize(this)
        // 若有待分享给 QQ 的图片（由无障碍在桌面模拟点击 KaKa 图标进入本 Activity 后消费），
        // 立即以"前台 Activity"身份转发给 QQ，绕过 MIUI 后台启动限制。
        maybeForwardPendingShareToQQ()
        enableEdgeToEdge()
        // 自动依次申请三项必要权限：通知 → 悬浮窗 → 无障碍
        // 只在缺失时才引导，已授予的跳过；不阻塞 UI。
        autoRequestRequiredPermissions()
        setContent {
            KaKaTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { inner ->
                    SettingsScreen(
                        modifier = Modifier.padding(inner),
                        onSave = ::saveAndRestartService,
                        onSimulateMessage = ::simulateIncomingMessage,
                        onOpenBatteryOptimization = ::openBatteryOptimization,
                        onOpenAccessibilitySettings = ::openAccessibilitySettings,
                        onOpenOverlayPermission = ::openOverlayPermission,
                        onRequestNotificationPermission = ::requestNotificationPermission,
                        onTestSendLatestImage = ::onTestSendLatestImageClick,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 桌面点击 KaKa 图标进入本 Activity（复用实例）时不会重走 onCreate，需要这里再兜底一次。
        maybeForwardPendingShareToQQ()
    }

    override fun onResume() {
        super.onResume()
        // 双保险：Activity 每次进入可见都尝试消费一次，防止极端时序下写入晚于 onCreate/onNewIntent。
        maybeForwardPendingShareToQQ()
    }

    private fun saveAndRestartService() {
        // 重启服务使设置生效
        val serviceIntent = Intent(this, AutoPunchService::class.java)
        stopService(serviceIntent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
    }

    private fun openBatteryOptimization() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } else {
            Toast.makeText(this, "已在白名单", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    /** 跳转"应用悬浮窗/在其他应用上层显示"设置页；已授予时提示。 */
    private fun openOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "已授予悬浮窗权限", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            Toast.makeText(this, "请手动到设置里授予悬浮窗权限", Toast.LENGTH_LONG).show()
        }
    }

    // ================= 权限相关：通知 / 无障碍 =================

    /** 通知权限：Android 13+ 走 runtime 权限；<13 直接跳应用通知设置页。 */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                Toast.makeText(this, "已授予通知权限", Toast.LENGTH_SHORT).show()
                return
            }
            requestNotifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            // < Android 13：无 runtime 权限，直接把用户带到本应用的通知设置页
            openAppNotificationSettings()
        }
    }

    /** 跳转到"本应用的通知设置"页面（跨 API 版本兼容）。 */
    private fun openAppNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        } else {
            @Suppress("DEPRECATION")
            Intent("android.settings.APP_NOTIFICATION_SETTINGS")
                .putExtra("app_package", packageName)
                .putExtra("app_uid", applicationInfo.uid)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            Toast.makeText(this, "请手动到系统设置里开启通知权限", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 无障碍服务是否已开启（检查用户是否在系统设置里勾选了本应用的 AutoPunchService）。
     * 读 [Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES] 字符串并匹配 `pkg/service` 组合。
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(this, AutoPunchService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    /**
     * onCreate 阶段自动检查三项权限，缺哪个申请哪个（一次只弹一个，避免多个系统页交叠）：
     *   1. 通知权限（Android 13+ runtime，其他版本用户可从按钮再点）
     *   2. 悬浮窗权限
     *   3. 无障碍服务
     * 只在**未授予**时才跳；已授予的静默跳过，不打扰用户。
     */
    private fun autoRequestRequiredPermissions() {
        // 1) 通知：仅 Android 13+ 走 runtime；低版本不自动跳，避免用户懵
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestNotifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        // 2) 悬浮窗
        if (!Settings.canDrawOverlays(this)) {
            openOverlayPermission()
            return
        }
        // 3) 无障碍
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "请在无障碍设置中开启 KaKa", Toast.LENGTH_LONG).show()
            openAccessibilitySettings()
        }
    }

    private fun simulateIncomingMessage(targetChat: String, punchType: String) {
        val storedType = punchTypeToStored(punchType)
        val intent = Intent(this, AutoPunchService::class.java).apply {
            action = AutoPunchService.ACTION_SIMULATE_MESSAGE
            putExtra(AutoPunchService.EXTRA_TARGET_CHAT, targetChat)
            putExtra(AutoPunchService.EXTRA_PUNCH_TYPE, storedType)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "已发送模拟消息", Toast.LENGTH_SHORT).show()
    }

    /** 消费 [PendingShare]，若有内容则立刻把图片交给 QQ 分享。 */
    @SuppressLint("WrongConstant")
    private fun maybeForwardPendingShareToQQ() {
        val path = PendingShare.consume() ?: return
        val file = java.io.File(path)
        if (!file.exists()) {
            com.newolf.kaka.util.Logger.w("Settings", "PendingShare 图片不存在: $path")
            return
        }
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "${packageName}.fileprovider", file
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
//                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
                addFlags(0x18000001 or Intent.FLAG_ACTIVITY_NO_HISTORY)
                component = android.content.ComponentName(
                    "com.tencent.mobileqq",
                    "com.tencent.mobileqq.activity.JumpActivity"
                )
//                setPackage("com.tencent.mobileqq")
            }
            startActivity(send)
            com.newolf.kaka.util.Logger.i("Settings", "已从 SettingsActivity 转发图片给 QQ uri=$uri")
        } catch (t: Throwable) {
            com.newolf.kaka.util.Logger.e("Settings", "转发 QQ 分享失败: ${t.message}", t)
        }
    }

    // ================= 测试：发送本地截图目录里的最后一张图片给 QQ =================

    /**
     * 首页"测试发送图片"按钮入口：
     * 读取本应用截图目录（`getExternalFilesDir(null)/screenshots/`）里最新的 .jpg 文件，
     * 通过 FileProvider 生成 URI，以 ACTION_SEND 直连 QQ 的 JumpActivity。
     * 目录归应用私有，无需运行时权限。
     */
    @SuppressLint("WrongConstant")
    private fun onTestSendLatestImageClick() {
        val file = LatestImageFinder.findLatestScreenshot(this)
        if (file == null || !file.exists()) {
            Toast.makeText(this, "截图目录里没有找到图片", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "${packageName}.fileprovider", file
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(0x18000001 or Intent.FLAG_ACTIVITY_NO_HISTORY)

                component = ComponentName(
                    "com.tencent.mobileqq",
                    "com.tencent.mobileqq.activity.JumpActivity"
                )
                setPackage("com.tencent.mobileqq")
            }
            startActivity(send)
            Toast.makeText(this, "已触发 QQ 分享: ${file.name}", Toast.LENGTH_SHORT).show()
            Logger.i(
                "Settings", "测试发送最后一张截图 file=${file.absolutePath} uri=$uri, send = $send"
            )
        } catch (t: Throwable) {
            Toast.makeText(this, "拉起 QQ 失败：${t.message}", Toast.LENGTH_LONG).show()
            Logger.e("Settings", "测试发送图片失败: ${t.message}", t)
        }
    }
}

private val PUNCH_TYPES = PunchTypeMapper.displayOptions

private fun punchTypeToStored(display: String): String = PunchTypeMapper.displayToStored(display)

private fun storedToPunchType(value: String?): String = PunchTypeMapper.storedToDisplay(value)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onSave: () -> Unit,
    onSimulateMessage: (targetChat: String, punchType: String) -> Unit,
    onOpenBatteryOptimization: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlayPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onTestSendLatestImage: () -> Unit,
) {
    val context = LocalContext.current
    val mmkv = remember { MMKV.defaultMMKV() }

    var timedEnabled by remember { mutableStateOf(mmkv.decodeBool("timed_enabled", false)) }
    var startHour by remember { mutableStateOf(mmkv.decodeInt("start_hour", 8).toString()) }
    var startMin by remember { mutableStateOf(mmkv.decodeInt("start_min", 30).toString()) }
    var endHour by remember { mutableStateOf(mmkv.decodeInt("end_hour", 9).toString()) }
    var endMin by remember { mutableStateOf(mmkv.decodeInt("end_min", 0).toString()) }
    var targetChat by remember {
        mutableStateOf(mmkv.decodeString("target_chat", "NeWolf") ?: "NeWolf")
    }
    var punchType by remember {
        mutableStateOf(storedToPunchType(mmkv.decodeString("timed_punch_type", "auto")))
    }
    var punchTypeExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "远程打卡设置",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(text = "定时打卡", modifier = Modifier.weight(1f))
            Switch(checked = timedEnabled, onCheckedChange = { timedEnabled = it })
        }

        Text(text = "开始时间（时:分）")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = startHour,
                onValueChange = { startHour = it.filter(Char::isDigit).take(2) },
                label = { Text("时") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Text(text = ":")
            OutlinedTextField(
                value = startMin,
                onValueChange = { startMin = it.filter(Char::isDigit).take(2) },
                label = { Text("分") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
        }

        Text(text = "结束时间（时:分）")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = endHour,
                onValueChange = { endHour = it.filter(Char::isDigit).take(2) },
                label = { Text("时") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Text(text = ":")
            OutlinedTextField(
                value = endMin,
                onValueChange = { endMin = it.filter(Char::isDigit).take(2) },
                label = { Text("分") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
        }

        Text(text = "默认回复目标（QQ昵称/群名）")
        OutlinedTextField(
            value = targetChat,
            onValueChange = { targetChat = it },
            placeholder = { Text("NeWolf") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Text(text = "定时打卡类型")
        ExposedDropdownMenuBox(
            expanded = punchTypeExpanded,
            onExpandedChange = { punchTypeExpanded = it },
        ) {
            OutlinedTextField(
                value = punchType,
                onValueChange = {},
                readOnly = true,
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = punchTypeExpanded)
                },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth(),
            )
            DropdownMenu(
                expanded = punchTypeExpanded,
                onDismissRequest = { punchTypeExpanded = false },
            ) {
                PUNCH_TYPES.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            punchType = option
                            punchTypeExpanded = false
                        },
                    )
                }
            }
        }

        Button(
            onClick = {
                val sh = startHour.toIntOrNull()
                val sm = startMin.toIntOrNull()
                val eh = endHour.toIntOrNull()
                val em = endMin.toIntOrNull()
                if (sh == null || sm == null || eh == null || em == null ||
                    sh !in 0..23 || eh !in 0..23 || sm !in 0..59 || em !in 0..59
                ) {
                    Toast.makeText(context, "输入不合法", Toast.LENGTH_SHORT).show()
                    return@Button
                }
                mmkv.encode("timed_enabled", timedEnabled)
                mmkv.encode("start_hour", sh)
                mmkv.encode("start_min", sm)
                mmkv.encode("end_hour", eh)
                mmkv.encode("end_min", em)
                mmkv.encode("target_chat", targetChat)
                mmkv.encode("timed_punch_type", punchTypeToStored(punchType))
                onSave()
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            Text("保存设置")
        }

        Button(
            onClick = {
                val finalChat = targetChat.ifBlank { "NeWolf" }
                onSimulateMessage(finalChat, punchType)
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("模拟收到消息（立即执行）")
        }

        Button(onClick = onOpenBatteryOptimization, modifier = Modifier.fillMaxWidth()) {
            Text("关闭电池优化")
        }

        Button(onClick = onOpenAccessibilitySettings, modifier = Modifier.fillMaxWidth()) {
            Text("打开无障碍设置")
        }

        Button(onClick = onOpenOverlayPermission, modifier = Modifier.fillMaxWidth()) {
            Text("申请悬浮窗权限（防冻结）")
        }

        Button(onClick = onRequestNotificationPermission, modifier = Modifier.fillMaxWidth()) {
            Text("申请通知权限")
        }

        Button(onClick = onTestSendLatestImage, modifier = Modifier.fillMaxWidth()) {
            Text("测试：发送最后一张图片到 QQ")
        }
    }
}