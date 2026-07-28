package com.newolf.kaka

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import com.newolf.kaka.executor.PunchTaskExecutor
import com.newolf.kaka.helper.FloatingBadgeManager
import com.newolf.kaka.helper.KeepAliveHelper
import com.newolf.kaka.helper.ScreenshotHelper
import com.newolf.kaka.model.PunchTypeMapper
import com.newolf.kaka.model.TaskState
import com.newolf.kaka.parser.NotificationParser
import com.newolf.kaka.remote.MqttCommandReceiver
import com.newolf.kaka.util.CrashReporter
import com.newolf.kaka.util.Logger
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.*

class AutoPunchService : AccessibilityService() {
    companion object {
        private const val TAG = "Service"
        const val ACTION_TIMED_PUNCH = "com.example.autopunch.TIMED_PUNCH"
        const val ACTION_SIMULATE_MESSAGE = "com.newolf.kaka.SIMULATE_MESSAGE"
        const val EXTRA_TARGET_CHAT = "extra_target_chat"
        const val EXTRA_PUNCH_TYPE = "extra_punch_type" // "上班" / "下班" / "auto"
        const val NOTIFICATION_CHANNEL_ID = "auto_punch_foreground"
        const val NOTIFICATION_ID = 1001
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var keepAliveHelper: KeepAliveHelper
    private lateinit var screenshotHelper: ScreenshotHelper
    private var floatingBadge: FloatingBadgeManager? = null
    private var currentState = TaskState.IDLE
    private var mmkv: MMKV? = null
    private var mqttReceiver: MqttCommandReceiver? = null

    override fun onCreate() {
        super.onCreate()
        // 先装崩溃处理器再干别的，任何 onCreate 阶段的异常都能落盘
        CrashReporter.install(this)
        Logger.i(TAG, "onCreate: 开始初始化 AutoPunchService")
        try {
            MMKV.initialize(this)
            mmkv = MMKV.defaultMMKV()
            screenshotHelper = ScreenshotHelper(
                getExternalFilesDir(null)!!.resolve("screenshots").apply { mkdirs() }
            )
            keepAliveHelper = KeepAliveHelper(this)
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, buildNotification("服务运行中"))
            keepAliveHelper.start()
            // 尝试挂 1x1 微型悬浮窗，向系统展示 KaKa 有可见 UI，降低被 MIUI/HyperOS 冻结概率。
            // 无权限时安静失败——用户可去设置页点"申请悬浮窗权限"授予后重启服务。
            floatingBadge = FloatingBadgeManager(this).also {
                val ok = it.start()
                Logger.i(TAG, "悬浮窗启动 ok=$ok hasPerm=${it.hasOverlayPermission()}")
            }
            if (mmkv?.decodeBool("timed_enabled", false) == true) {
                Logger.i(TAG, "定时任务已启用，调度下一个随机任务")
                scheduleNextRandomTask()
            }
            startMqttIfEnabled()
            Logger.i(TAG, "onCreate 完成")
        } catch (t: Throwable) {
            Logger.e(TAG, "onCreate 失败", t)
            throw t
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            handleNotification(event)
        }
    }

    private fun handleNotification(event: AccessibilityEvent) {
        if (event.packageName != "com.tencent.mobileqq") return
        val notification = event.parcelableData as? Notification
        // 通知走无障碍拿到的 parcelableData 在部分 Android 版本 / OEM ROM 上会是 null（尤其 QQ）。
        // 兜底：直接用 event.text 里的字符串组合，通常是"标题：正文"或["标题","正文"]的样子。
        val command = if (notification != null) {
            val nc = NotificationParser.parse(notification)
            if (nc == null) {
                Logger.v(
                    TAG,
                    "onAccessibilityEvent: Notification 已解析但不符合打卡指令（title=${notification.extras.getString(Notification.EXTRA_TITLE)}）"
                )
            }
            nc
        } else {
            val fallback = event.text?.joinToString(separator = " ") { it?.toString().orEmpty() }
                ?.trim()
                .orEmpty()
            Logger.d(TAG, "onAccessibilityEvent: Notification=null，走 event.text 兜底: '$fallback'")
            if (fallback.isEmpty()) null else NotificationParser.parseEventText(fallback)
        } ?: return

        Logger.i(TAG, "解析到打卡指令 target=${command.targetChat} isOnWork=${command.isOnWork}")
        runPunch(command.targetChat, command.isOnWork, source = "NOTIFICATION")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Logger.i(TAG, "onStartCommand action=$action flags=$flags startId=$startId")
        when (action) {
            ACTION_TIMED_PUNCH -> {
                val targetChat = mmkv?.decodeString("target_chat", "文件传输助手") ?: "文件传输助手"
                val stored = mmkv?.decodeString("timed_punch_type", "auto")
                val isOnWork = parsePunchType(stored)
                Logger.i(TAG, "定时打卡触发 target=$targetChat storedType=$stored isOnWork=$isOnWork")
                runPunch(targetChat, isOnWork, source = "TIMED")
            }
            ACTION_SIMULATE_MESSAGE -> {
                val defaultChat = mmkv?.decodeString("target_chat", "文件传输助手") ?: "文件传输助手"
                val targetChat = intent.getStringExtra(EXTRA_TARGET_CHAT)
                    ?.takeIf { it.isNotBlank() } ?: defaultChat
                val storedType = intent.getStringExtra(EXTRA_PUNCH_TYPE)
                val isOnWork = parsePunchType(storedType)
                Logger.i(
                    TAG,
                    "收到模拟消息 target=$targetChat storedType=$storedType isOnWork=$isOnWork"
                )
                runPunch(targetChat, isOnWork, source = "SIMULATE")
            }
            else -> Logger.w(TAG, "未识别的 action=$action，忽略")
        }
        return START_STICKY
    }

    private fun parsePunchType(value: String?): Boolean? = PunchTypeMapper.storedToIsOnWork(value)

    private fun runPunch(targetChat: String, isOnWork: Boolean?, source: String) {
        if (currentState != TaskState.IDLE) {
            Logger.w(TAG, "[$source] 当前忙碌（state=$currentState），忽略新指令 target=$targetChat")
            return
        }
        // 定时任务已打卡后不再重复更新，避免每次定时都刷新时间；
        // QQ 通知 / 模拟消息 / MQTT 是用户主动触发，允许"更新打卡"。
        val allowUpdate = source != "TIMED"
        Logger.i(TAG, "[$source] 开始执行打卡任务 target=$targetChat isOnWork=$isOnWork allowUpdate=$allowUpdate")
        currentState = TaskState.PUNCHING
        scope.launch {
            val start = System.currentTimeMillis()
            try {
                // 任务级超时：单次打卡任务硬上限 60s（含飞书启动+假勤加载+定位+点击+QQ 分享）。
                // 超时后自动取消，避免 MIUI 冻结导致协程永挂而 currentState 卡死在 PUNCHING。
                val result = withTimeoutOrNull(60_000L) {
                    PunchTaskExecutor(this@AutoPunchService, screenshotHelper, targetChat, isOnWork, allowUpdate) {
                        Logger.i(TAG, "[$source] 任务结束（Executor onFinished），耗时 ${System.currentTimeMillis() - start}ms")
                        scheduleNextRandomTaskIfNeeded()
                    }.execute()
                    "done"
                }
                if (result == null) {
                    Logger.w(TAG, "[$source] 任务超时 60s，强制取消 target=$targetChat")
                }
            } catch (e: CancellationException) {
                Logger.w(TAG, "[$source] 任务被取消")
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "[$source] 任务异常: ${e.message}", e)
            } finally {
                // 无论正常完成 / 超时 / 异常，一律重置状态，确保下次触发不会被 PUNCHING 挡住
                currentState = TaskState.IDLE
                Logger.i(TAG, "[$source] runPunch 收尾：state=IDLE 总耗时 ${System.currentTimeMillis() - start}ms")
            }
        }
    }

    private fun startMqttIfEnabled() {
        val enabled = mmkv?.decodeBool("mqtt_enabled", false) ?: false
        if (!enabled) {
            Logger.d(TAG, "MQTT 未启用，跳过连接")
            return
        }
        if (mqttReceiver != null) {
            Logger.d(TAG, "MQTT 已连接，跳过重复初始化")
            return
        }
        val broker = mmkv?.decodeString("mqtt_broker", "tcp://broker.emqx.io:1883")
            ?: "tcp://broker.emqx.io:1883"
        val topic = mmkv?.decodeString("mqtt_topic", "punch/command") ?: "punch/command"
        Logger.i(TAG, "初始化 MQTT broker=$broker topic=$topic")
        mqttReceiver = MqttCommandReceiver(brokerUrl = broker, commandTopic = topic) { type ->
            val targetChat = mmkv?.decodeString("target_chat", "文件传输助手") ?: "文件传输助手"
            val isOnWork = when (type) {
                "on" -> true
                "off" -> false
                else -> null
            }
            Logger.i(TAG, "MQTT 指令 type=$type target=$targetChat isOnWork=$isOnWork")
            runPunch(targetChat, isOnWork, source = "MQTT")
        }
        mqttReceiver?.connect()
    }

    private fun scheduleNextRandomTaskIfNeeded() {
        if (mmkv?.decodeBool("timed_enabled", false) == true) scheduleNextRandomTask()
    }

    private fun scheduleNextRandomTask() {
        // 与前文实现相同，根据时间范围计算随机时间，并设置 AlarmManager
        // 代码略
    }

    private fun buildNotification(text: String): Notification {
        val intent = PendingIntent.getActivity(
            this, 0, Intent(this, SettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("AutoService")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(intent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID, "AutoService", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        Logger.i(TAG, "onDestroy")
        try {
            mqttReceiver?.disconnect()
        } catch (t: Throwable) {
            Logger.w(TAG, "MQTT 断开异常", t)
        }
        try {
            floatingBadge?.stop()
        } catch (t: Throwable) {
            Logger.w(TAG, "悬浮窗停止异常", t)
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onInterrupt() {
        Logger.w(TAG, "onInterrupt：无障碍服务被中断，state 重置为 IDLE")
        currentState = TaskState.IDLE
    }
}