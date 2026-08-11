package com.newolf.kaka.executor

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.newolf.kaka.helper.ScreenshotHelper
import com.newolf.kaka.util.Logger
import kotlinx.coroutines.*
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class PunchTaskExecutor(
    private val service: AccessibilityService,
    private val screenshotHelper: ScreenshotHelper,
    private val targetChat: String,
    private val isOnWork: Boolean?,
    /**
     * 若目标时段已打卡，是否点"更新打卡"链接刷新时间。
     * - QQ 通知 / 模拟消息 / MQTT：true，用户主动请求，应该更新
     * - 定时任务：false，避免打了一次后每次定时都反复更新
     */
    private val allowUpdate: Boolean,
    private val onFinished: () -> Unit
) {
    companion object {
        private const val TAG = "Executor"
        const val MAX_RETRIES = 3
    }

    suspend fun execute() = withContext(Dispatchers.Main) {
        Logger.i(TAG, "execute() 开始 target=$targetChat isOnWork=$isOnWork")
        // 关键：任务期间保持屏幕亮 + 解除键盘锁，避免锁屏后 startActivity 被拦截 / 无障碍拿不到窗口
        val wakeLock = acquireScreenWakeLock()
        try {
            // 前置 0：若当前处于锁屏（收到 QQ 通知触发时手机通常锁屏），先亮屏 + 请求系统解锁。
            // 有密码锁：会弹出输入 PIN/图案界面，我们轮询等用户输入完成；超时后仍继续，让重试兜底。
            // 无密码/滑动锁：requestDismissKeyguard 会静默 dismiss。
            ensureScreenUnlocked(timeoutMs = 15_000L)

            // 前置：确保 KaKa 自己在前台。这样后续所有 service.startActivity(...) 都被系统视作
            // "前台应用的 startActivity"，避免 MIUI 后台启动拦截。
            ensureSelfInForeground()

            var retryCount = 0
            var success = false
            while (retryCount < MAX_RETRIES) {
                val attemptStart = System.currentTimeMillis()
                try {
                    if (ensureFeishuLaunched()) {
                        navigateToPunch()
                        success = true
                        Logger.i(TAG, "第 ${retryCount + 1} 次尝试成功，耗时 ${System.currentTimeMillis() - attemptStart}ms")
                        break
                    } else {
                        Logger.w(TAG, "第 ${retryCount + 1} 次尝试：启动飞书失败")
                    }
                } catch (e: CancellationException) {
                    Logger.w(TAG, "任务被取消")
                    throw e
                } catch (e: Exception) {
                    Logger.e(TAG, "第 ${retryCount + 1} 次尝试异常: ${e.message}", e)
                }
                retryCount++
                if (retryCount < MAX_RETRIES) {
                    Logger.i(TAG, "等待 2s 后重试")
                    delay(2000)
                }
            }
            if (!success) {
                Logger.e(TAG, "重试耗尽（$MAX_RETRIES 次），任务失败 target=$targetChat")
            }
        } finally {
            releaseWakeLock(wakeLock)
            onFinished()
        }
    }

    /**
     * 获取"点亮屏幕 + 解锁"级别的 WakeLock。
     * SCREEN_BRIGHT_WAKE_LOCK 已在 API 17+ 弃用，但仍工作；ACQUIRE_CAUSES_WAKEUP 标记会真正点亮屏幕。
     * 一并触发 KeyguardManager 请求解除键盘锁（无密码锁屏 / 滑动锁）。
     */
    private fun acquireScreenWakeLock(): android.os.PowerManager.WakeLock? {
        return try {
            val pm = service.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    android.os.PowerManager.ON_AFTER_RELEASE,
                "KaKa:PunchExecuting"
            )
            wl.setReferenceCounted(false)
            // 最多锁 5 分钟，避免异常路径下永远不释放
            wl.acquire(5 * 60_000L)
            Logger.d(TAG, "acquireScreenWakeLock: 已获取 SCREEN_BRIGHT WakeLock")
            wl
        } catch (t: Throwable) {
            Logger.w(TAG, "acquireScreenWakeLock 失败: ${t.message}", t)
            null
        }
    }

    private fun releaseWakeLock(wl: android.os.PowerManager.WakeLock?) {
        try {
            if (wl != null && wl.isHeld) {
                wl.release()
                Logger.d(TAG, "releaseWakeLock: WakeLock 已释放")
            }
        } catch (t: Throwable) {
            Logger.w(TAG, "releaseWakeLock 失败: ${t.message}", t)
        }
    }

    /**
     * 确保屏幕已解锁（keyguard 已 dismiss）。QQ 通知触发的自动打卡场景，手机通常处于锁屏。
     *
     * 策略：
     * 1. 若 [android.app.KeyguardManager.isKeyguardLocked] == false，说明已解锁，直接返回。
     * 2. 通过 fullScreenIntent 拉起 [`RelayLaunchActivity`](../RelayLaunchActivity.kt) 的
     *    `ACTION_WAKE_AND_DISMISS_KEYGUARD`：亮屏 + 调用 requestDismissKeyguard。
     *    - 无密码/滑动锁：Framework 大多数情况会立即 dismiss；但部分 ROM（MIUI/HyperOS）"上划解锁"
     *      锁屏对 requestDismissKeyguard 不响应，需要走手势兜底。
     * 3. **手势兜底**：屏幕已亮但仍处于锁屏时，用无障碍 [`dispatchGesture`](android.accessibilityservice.AccessibilityService.dispatchGesture)
     *    从屏幕下 1/3 处向上快速滑到屏幕上 1/6 处，模拟"上划解锁"。
     * 4. 每次尝试后轮询 `isKeyguardLocked`，未解锁就重试，最多 3 轮；每轮预算 [timeoutMs] / 3。
     * 5. 全部超时后不抛异常，仍继续任务；后续点击若仍失败会有 execute() 的重试兜底。
     */
    private suspend fun ensureScreenUnlocked(timeoutMs: Long) {
        val km = try {
            service.getSystemService(android.content.Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        } catch (_: Throwable) { null }
        if (km == null) {
            Logger.w(TAG, "ensureScreenUnlocked: KeyguardManager 获取失败，跳过")
            return
        }
        if (!km.isKeyguardLocked) {
            Logger.d(TAG, "ensureScreenUnlocked: 未处于锁屏，跳过")
            return
        }

        // 第 1 步：拉起 Relay Activity，触发亮屏 + requestDismissKeyguard
        Logger.i(TAG, "ensureScreenUnlocked: 检测到锁屏，通过 RelayLaunchActivity 请求解锁")
        try {
            val intent = Intent(service, com.newolf.kaka.RelayLaunchActivity::class.java).apply {
                action = com.newolf.kaka.RelayLaunchActivity.ACTION_WAKE_AND_DISMISS_KEYGUARD
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val launched = launchRelayViaFullScreenIntent(intent)
            Logger.d(TAG, "ensureScreenUnlocked: RelayLaunchActivity 拉起 launched=$launched")
        } catch (t: Throwable) {
            Logger.w(TAG, "ensureScreenUnlocked: 拉起 Relay 失败: ${t.message}", t)
        }

        // 第 2 步：先给 Framework 一点时间去 dismiss（无密码锁很多情况下此时已解）
        val settleStart = System.currentTimeMillis()
        while (System.currentTimeMillis() - settleStart < 1500L) {
            if (!km.isKeyguardLocked) {
                Logger.i(TAG, "ensureScreenUnlocked: requestDismissKeyguard 已解锁，用时 ${System.currentTimeMillis() - settleStart}ms")
                delay(500)
                return
            }
            delay(200)
        }

        // 第 3 步：仍锁屏——上划解锁（MIUI/HyperOS 常见的"上划"锁屏）
        val maxSwipeAttempts = 3
        val perAttemptBudget = ((timeoutMs - 1500L) / maxSwipeAttempts).coerceAtLeast(2000L)
        for (attempt in 1..maxSwipeAttempts) {
            Logger.i(TAG, "ensureScreenUnlocked: 第 $attempt 次上划解锁尝试")
            val swipeOk = swipeUpToUnlock()
            Logger.d(TAG, "ensureScreenUnlocked: swipeUp ok=$swipeOk")

            val roundStart = System.currentTimeMillis()
            var tick = 0
            while (System.currentTimeMillis() - roundStart < perAttemptBudget) {
                if (!km.isKeyguardLocked) {
                    Logger.i(TAG, "ensureScreenUnlocked: 第 $attempt 次上划后已解锁，用时 ${System.currentTimeMillis() - roundStart}ms")
                    delay(500)
                    return
                }
                tick++
                if (tick % 4 == 0) {
                    Logger.d(TAG, "ensureScreenUnlocked: 上划后仍锁屏 attempt=$attempt elapsed=${System.currentTimeMillis() - roundStart}ms")
                }
                delay(500)
            }
        }
        Logger.w(TAG, "ensureScreenUnlocked: 上划 $maxSwipeAttempts 次仍未解锁（可能是密码锁 / 手势不生效），继续尝试")
    }

    /**
     * 模拟"上划解锁"手势：从屏幕下 1/3 处竖直向上滑到屏幕上 1/6 处。
     * 距离取屏高的 ~50%，时长 260ms，速度接近真实手指快滑。
     * 走无障碍 [android.accessibilityservice.AccessibilityService.dispatchGesture]，
     * 需要无障碍服务已��用；本类的 [service] 就是它自身，权限天然满足。
     */
    private suspend fun swipeUpToUnlock(): Boolean {
        val dm = service.resources.displayMetrics
        val cx = dm.widthPixels / 2f
        val yStart = dm.heightPixels * 0.75f
        val yEnd = dm.heightPixels * 0.20f
        val path = Path().apply {
            moveTo(cx, yStart)
            lineTo(cx, yEnd)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 260L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return suspendCoroutine { cont ->
            val dispatched = service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) { cont.resume(true) }
                    override fun onCancelled(g: GestureDescription?) {
                        Logger.w(TAG, "swipeUpToUnlock: onCancelled")
                        cont.resume(false)
                    }
                },
                null
            )
            if (!dispatched) {
                Logger.w(TAG, "swipeUpToUnlock: dispatchGesture 返回 false")
                cont.resume(false)
            }
        }
    }

    /**
     * 若 KaKa 当前不在前台，先把自己拉到前台。
     *
     * 为什么：从通知栏/无障碍事件触发任务时，KaKa 通常处于后台。
     * MIUI 会把这种进程发出的 `service.startActivity(第三方 App)` 判为"后台启动"直接拦截，
     * 于是飞书/QQ 都拉不起来。反过来，只要 KaKa 是前台，后续 startActivity 就走"前台调用"，MIUI 不拦。
     *
     * 拉前台的手段：优先 `service.startActivity(SettingsActivity)`（有时能过），
     * 兜底 = HOME + [`clickDesktopIcon("KaKa")`]（视为用户点击，最稳）。
     */
    private suspend fun ensureSelfInForeground() {
        val curPkg = service.rootInActiveWindow?.packageName?.toString()
        Logger.d(TAG, "ensureSelfInForeground: 当前前台 pkg=$curPkg self=${service.packageName}")
        if (curPkg == service.packageName) {
            Logger.d(TAG, "ensureSelfInForeground: KaKa 已在前台，跳过")
            return
        }

        // 尝试 1：直接 startActivity(SettingsActivity)
        try {
            val self = Intent(service, com.newolf.kaka.SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            service.startActivity(self)
            Logger.d(TAG, "ensureSelfInForeground: 已下发 startActivity(SettingsActivity)")
        } catch (t: Throwable) {
            Logger.w(TAG, "ensureSelfInForeground: startActivity 抛异常 ${t.message}", t)
        }
        val ok1 = waitForForegroundPkg(service.packageName, timeoutMs = 3000, tag = "self/路径 1")
        if (ok1) return

        // 尝试 2：HOME → 桌面点 KaKa 图标（clickDesktopIcon 自带翻页扫描）
        Logger.i(TAG, "ensureSelfInForeground: 路径 1 未生效，改走桌面点击 KaKa 图标")
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        delay(500)
        val clicked = clickDesktopIcon(listOf("KaKa"))
        Logger.d(TAG, "ensureSelfInForeground: 点击 KaKa 图标 ok=$clicked")
        if (clicked) {
            val ok2 = waitForForegroundPkg(service.packageName, timeoutMs = 6000, tag = "self/路径 2")
            if (ok2) return
        }
        Logger.w(TAG, "ensureSelfInForeground: 两条路径均未把 KaKa 拉到前台，后续 startActivity 可能被 MIUI 拦")
    }

    private suspend fun ensureFeishuLaunched(): Boolean {
        val curPkg = service.rootInActiveWindow?.packageName?.toString()
        Logger.d(TAG, "ensureFeishuLaunched: 启动飞书 curPkg=$curPkg")
        // 快速路径：已在飞书前台，直接返回，避免 startActivity 把飞书重置到主页
        if (curPkg == "com.ss.android.lark") {
            Logger.d(TAG, "ensureFeishuLaunched: 已在飞书前台，跳过 startActivity")
            return true
        }
        val intent = service.packageManager.getLaunchIntentForPackage("com.ss.android.lark")
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (intent == null) {
            Logger.e(TAG, "未找到飞书应用（com.ss.android.lark），请确认已安装")
            return false
        }
        // 路径 1：直接 startActivity。前提是 KaKa 处于前台（由 execute() 里的 ensureSelfInForeground 保证）。
        // 不主动按 HOME，避免自己退到后台后被 MIUI 判为后台启动而拦。
        Logger.d(TAG, "ensureFeishuLaunched: 尝试 service.startActivity(飞书)")
        try {
            service.startActivity(intent)
            Logger.d(TAG, "ensureFeishuLaunched: startActivity(飞书) 已下发")
        } catch (t: Throwable) {
            Logger.w(TAG, "startActivity(飞书) 抛异常：${t.message}", t)
        }
        var launched = waitForForegroundPkg(
            pkg = "com.ss.android.lark",
            timeoutMs = 6000,
            tag = "路径 1",
        )
        // 路径 2：兜底——回桌面翻页找飞书图标点击（"用户点击"不受后台启动限制）。
        if (!launched) {
            Logger.w(TAG, "路径 1 未把飞书拉到前台，改走路径 2：桌面点击图标兜底")
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            delay(500)
            val clicked = clickDesktopIcon(listOf("飞书", "Lark"))
            Logger.d(TAG, "路径 2：点击飞书图标 ok=$clicked")
            if (clicked) {
                launched = waitForForegroundPkg(
                    pkg = "com.ss.android.lark",
                    timeoutMs = 10000,
                    tag = "路径 2",
                )
            }
        }
        Logger.d(TAG, "飞书是否已切换到前台: $launched")
        // 等待首页渲染稳定，避免底部导航尚未挂载（只有新启动路径才需要）
        if (launched) delay(1500)
        return launched
    }

    /** 每秒打一次进度日志的前台包名等待，避免出现"卡住无日志"错觉。 */
    private suspend fun waitForForegroundPkg(pkg: String, timeoutMs: Long, tag: String): Boolean {
        val start = System.currentTimeMillis()
        var last: String? = "<init>"
        while (System.currentTimeMillis() - start < timeoutMs) {
            val cur = service.rootInActiveWindow?.packageName?.toString()
            if (cur == pkg) {
                Logger.d(TAG, "$tag: 前台已切到 $pkg 耗时=${System.currentTimeMillis() - start}ms")
                return true
            }
            if (cur != last) {
                Logger.d(TAG, "$tag: 前台 pkg=$cur 等待中... elapsed=${System.currentTimeMillis() - start}ms")
                last = cur
            }
            delay(500)
        }
        Logger.w(TAG, "$tag: 等 $pkg 前台化超时 ${timeoutMs}ms，最后前台=$last")
        return false
    }

    private suspend fun navigateToPunch() {
        // 快速路径探测：如果已经在假勤打卡页/申请/统计/设置内（底部 4 tab 可见），
        // 跳过"点工作台 tab → 点假勤应用卡片"，直接进入 tab 切换逻辑。
        // 判据：能同时找到"打卡"和"申请"底部 tab 就视为已在假勤内。
        val alreadyInAttendance = isAlreadyInAttendance()
        Logger.d(TAG, "navigateToPunch: alreadyInAttendance=$alreadyInAttendance")

        if (!alreadyInAttendance) {
            val isPad = com.newolf.kaka.util.DeviceUtils.isTablet(service)
            if (isPad) {
                // 平板：飞书工作台无障碍拿不到"假勤"应用卡片（主区全是空 FrameLayout，
                // 唯一命中的"假勤"是离屏的会话通知条 bounds right<0）。改走"工作台 → 右上角搜索 → 输入'假勤' → 点结果"路径。
                Logger.d(TAG, "navigateToPunch: [pad] 先切到底部Tab 工作台")
                clickBottomTab(listOf("工作台", "Workplace"), timeout = 8000)
                // 切换后给工作台内容加载一点点时间，右上角搜索图标才会渲染
                delay(600)
                Logger.d(TAG, "navigateToPunch: [pad] 走工作台右上角搜索打开'假勤'")
                searchAndOpenAttendance(timeoutMs = 10000)
            } else {
                Logger.d(TAG, "navigateToPunch: 点击 底部Tab 工作台")
                // 底部 Tab 需要限定在屏幕下方，避免被消息列表的"工作台通知"抢占
                clickBottomTab(listOf("工作台", "Workplace"), timeout = 8000)
                Logger.d(TAG, "navigateToPunch: 点击 工作台 应用卡片 假勤")
                // 应用卡片：可点击祖先内只有一个 TextView 且等于目标，避免命中"假勤助手"聊天
                clickAppCard(listOf("假勤", "Attendance"), timeout = 8000)
            }
            // 假勤是 H5/Lark 小程序，首次加载耗时较久；用 waitForCondition 探测底部 tab 出现即视为加载完成，
            // 而不是硬 delay 2s——命中即返回，最多兜底 3s。
            val h5Ready = waitForCondition(3000) { isAlreadyInAttendance() }
            Logger.d(TAG, "navigateToPunch: 假勤 H5 是否加载完成 ready=$h5Ready rootPkg=${service.rootInActiveWindow?.packageName}")

            // 兜底：如果 clickAppCard 误点进了"假勤助手"聊天会话，会话里的打卡提醒消息一般会带"去打卡"按钮。
            maybeClickGoPunchInChat()
        } else {
            Logger.d(TAG, "navigateToPunch: 已在假勤内，跳过工作台/假勤卡片点击")
        }

        // 底部 4 个 tab：打卡 / 申请 / 统计 / 设置。默认可能不在"打卡" tab 上。
        // 只在**不在打卡 tab**时才切换，避免重复点导致定位重启动。
        // 判据：找到"打卡" Tab 节点后，看它自身（或 clickable 祖先）的 isSelected；
        //       Selected=true → 已在打卡 tab，无需操作；否则 performClick 切换。
        ensureAttendancePunchTab()

        // 打卡圆按钮是 WebView/Canvas 渲染，Accessibility 拿不到文本节点。
        // 你的设备上按钮位置稳定，直接坐标点击，不再走文本查找 (省 8s 超时)。
        // isOnWork 只用于日志/后续 QQ 分享目标区分；圆按钮点击本身与"上班/下班"无关（假勤 tab 会显示当前状态）。
        val isForClockIn = when (isOnWork) {
            true -> true
            false -> false
            null -> {
                val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                val auto = hour < 12
                Logger.i(TAG, "isOnWork=auto，按当前小时=$hour 自动选择：${if (auto) "上班" else "下班"}打卡")
                auto
            }
        }
        Logger.d(TAG, "navigateToPunch: 点击打卡按钮前，先等飞书完成定位")
        waitForLocationReady()

        // 若目标时段已打卡，页面右侧会出现"更新打卡"链接。
        // - allowUpdate=true（QQ/模拟/MQTT）：无脑先尝试点"更新打卡"坐标 → 检测确认弹窗
        //   - 弹窗出现（说明真的已打卡）：点"确定" → 继续常规打卡流程
        //   - 弹窗没出现（未打卡 / 坐标落空）：直接进入常规打卡流程
        // - allowUpdate=false（定时任务）：跳过更新，避免每次定时都刷新
        // 之所以不走"读取已打卡文本"的探测：飞书打卡页整体是 WebView，无障碍读不到 "已打卡/更新打卡" 关键字。
        if (allowUpdate) {
            Logger.d(TAG, "navigateToPunch: allowUpdate=true，先探测性点击'更新打卡'（坐标落空无副作用）")
            dumpTopWindow(prefixTag = "before-update-tap")
            val updated = tapUpdatePunchAndConfirm(isForClockIn)
            Logger.i(TAG, "navigateToPunch: 更新流程返回 updated=$updated")
            dumpTopWindow(prefixTag = "after-update-tap")
            if (updated) {
                // 点完"更新打卡 → 确定"后，飞书重新显示按钮（可能又要等定位）
                delay(800)
                waitForLocationReady()
            }
        } else {
            Logger.d(TAG, "navigateToPunch: allowUpdate=false（定时任务），跳过'更新打卡'探测")
        }

        Logger.d(TAG, "navigateToPunch: 点击打卡按钮 isForClockIn=$isForClockIn")
        clickPunchButton(isForClockIn)

        // 圆按钮点击后飞书内部会有回弹/打卡成功动画，缩短等待到 2s（原来 2s dump + 3s 共 5s）
        delay(2000)
        dumpTopWindow(prefixTag = "after-punch-click")
        takeScreenshotAndReply()
    }

    /**
     * 检测当前目标时段（上/下班）是否已打卡：
     * 页面出现"已打卡 HH:mm"标签 + 右侧"更新打卡"链接。
     * "更新打卡"是 WebView 内元素，无障碍拿不到；这里改用**页面标题探测**：
     * 只要页面里能找到"已打卡"或"无需打卡"文本节点即认为处于已打卡状态。
     * 这些文字有可能被无障碍读到；读不到时保守返回 false（不做更新）。
     */
    private fun isAlreadyPunched(isForClockIn: Boolean): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val keywords = listOf("已打卡", "无需打卡", "更新打卡")
        for (kw in keywords) {
            val hits = try {
                root.findAccessibilityNodeInfosByText(kw)
            } catch (_: Throwable) { null }
            if (hits?.any { it.isVisibleToUser } == true) {
                Logger.d(TAG, "isAlreadyPunched: forClockIn=$isForClockIn 命中关键字='$kw'")
                return true
            }
        }
        Logger.d(TAG, "isAlreadyPunched: forClockIn=$isForClockIn 未命中任何'已打卡'关键字（WebView 未透传，视为未打卡）")
        return false
    }

    /**
     * 点击 WebView 内的"更新打卡"链接 → 等 WebView 内确认弹窗 → 点"确定"。
     * "更新打卡"链接和弹窗都在 WebView 里，无障碍拿不到节点，只能坐标 tap。
     *
     * 坐标基于 1280×2772 实测截图（isForClockIn 决定上/下班卡片）：
     *   - 上班"更新打卡"链接中心 y ≈ 屏高 × 0.243（对应 top~bottom ≈ 660~700）
     *   - 下班"更新打卡"链接中心 y ≈ 屏高 × 0.355（对应 top~bottom ≈ 960~1010）
     *   - x 都在 屏宽 × 0.73（右侧蓝色文字"更新打卡"位置）
     *   - 弹窗"确定"按钮中心 x=屏宽×0.694 y=屏高×0.395
     */
    private suspend fun tapUpdatePunchAndConfirm(isForClockIn: Boolean): Boolean {
        val dm = service.resources.displayMetrics
        // "更新打卡"链接坐标（按 1280×2772 屏实测）：
        //   - 下班"更新打卡" bounds x=953~1147, y=1338~1404 → 中心 (1050, 1371) → 比例 (0.820, 0.495)
        //   - 上班"更新打卡"（暂无实测）：按下班向上偏移 428px（两卡片高度差）估算 y=943 → 比例 0.340
        val linkX = dm.widthPixels * 0.820f
        val linkY = if (isForClockIn) dm.heightPixels * 0.340f else dm.heightPixels * 0.495f
        // "确定" bounds x=640~1130, y=1339~1556 → 中心 (885, 1447.5) → 比例 (0.691, 0.522)
        val confirmX = dm.widthPixels * 0.691f
        val confirmY = dm.heightPixels * 0.522f

        val beforeWindows = try { service.windows?.size ?: 0 } catch (_: Throwable) { 0 }
        Logger.d(TAG, "tapUpdatePunchAndConfirm: [step 1] 坐标点击'更新打卡' isForClockIn=$isForClockIn tap($linkX,$linkY) beforeWindows=$beforeWindows screen=${dm.widthPixels}x${dm.heightPixels}")
        val linkOk = tapAt(linkX, linkY, durationMs = 120)
        Logger.i(TAG, "tapUpdatePunchAndConfirm: [step 1] tap 结果 ok=$linkOk")

        // 关键：轮询等待弹窗真正弹起（WebView 内 dialog 有较长动画+异步渲染，1s 常常不够）。
        // 任一信号成立就认为"弹窗已弹"：
        //   a) windows.size 增加（原生 AlertDialog）
        //   b) 无障碍找到"确定"节点（原生按钮）
        //   c) 已达轮询上限（2500ms）但仍无信号 → 视为 WebView 内弹窗（不透传信号），依然继续走坐标 tap
        val pollTimeout = 2500L
        val pollStart = System.currentTimeMillis()
        var windowIncreased = false
        var confirmNode: AccessibilityNodeInfo? = null
        while (System.currentTimeMillis() - pollStart < pollTimeout) {
            val cur = try { service.windows?.size ?: 0 } catch (_: Throwable) { 0 }
            if (cur > beforeWindows) {
                windowIncreased = true
                Logger.d(TAG, "tapUpdatePunchAndConfirm: [step 2] windows 数量增加 $beforeWindows→$cur，用时 ${System.currentTimeMillis() - pollStart}ms")
                break
            }
            val n = try {
                service.rootInActiveWindow?.findAccessibilityNodeInfosByText("确定")
                    ?.firstOrNull { it.isVisibleToUser && it.text?.toString() == "确定" }
            } catch (_: Throwable) { null }
            if (n != null) {
                confirmNode = n
                Logger.d(TAG, "tapUpdatePunchAndConfirm: [step 2] 无障碍找到'确定'节点，用时 ${System.currentTimeMillis() - pollStart}ms")
                break
            }
            delay(200)
        }
        Logger.d(TAG, "tapUpdatePunchAndConfirm: [step 2] 轮询结束 windowIncreased=$windowIncreased confirmNode=${confirmNode != null}")

        // 分支 A：无障碍拿到"确定"节点 → ACTION_CLICK（最稳）
        if (confirmNode != null) {
            val rect = Rect().also { confirmNode.getBoundsInScreen(it) }
            Logger.d(TAG, "tapUpdatePunchAndConfirm: [step 3] 无障碍点击'确定' bounds=$rect clickable=${confirmNode.isClickable}")
            val clickable = if (confirmNode.isClickable) confirmNode else firstClickableAncestor(confirmNode)
            val ok = clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
            Logger.i(TAG, "tapUpdatePunchAndConfirm: [step 3] ACTION_CLICK ok=$ok")
            if (ok) return true
            if (rect.width() > 0 && rect.height() > 0) {
                Logger.d(TAG, "tapUpdatePunchAndConfirm: [step 3] ACTION_CLICK 失败，坐标 tap $rect")
                tapAt(rect.exactCenterX(), rect.exactCenterY(), durationMs = 120)
                return true
            }
        }

        // 分支 B：坐标 tap"确定"。
        // 即便 windowIncreased=false 也照 tap—— WebView 内弹窗不会新增 window，
        // 只要 tap 之前 y=1447 位置在打卡页里都是安全空白区，落空无副作用。
        Logger.d(TAG, "tapUpdatePunchAndConfirm: [step 4] 坐标点击'确定' tap($confirmX,$confirmY) windowIncreased=$windowIncreased")
        val cOk = tapAt(confirmX, confirmY, durationMs = 120)
        Logger.i(TAG, "tapUpdatePunchAndConfirm: [step 4] 坐标 tap'确定' ok=$cOk")
        // 未检测到任何"弹窗弹起"信号时，无法判断 tap 是否真击到了确定；
        // 保守做法：仍返回 true，让后续的 waitForLocationReady + clickPunchButton 继续兜底
        // （即使这次没点到确定，主流程也只是重复点了一次药丸打卡按钮，飞书会因为"重复打卡"而无操作）。
        return true
    }

    /**
     * 假勤打卡页首次进入会显示"定位中"（按钮变浅蓝 + 底部黑色 toast），
     * 此时点击按钮不生效。这里 poll 无障碍节点，等"定位中"文本消失或超时。
     * 大部分情况下 2~6 秒完成；超时兜底改用固定等待。
     */
    private suspend fun waitForLocationReady(timeoutMs: Long = 10000) {
        val start = System.currentTimeMillis()
        var lastSeen = -1L
        while (System.currentTimeMillis() - start < timeoutMs) {
            val root = service.rootInActiveWindow
            val stillLocating = root != null && run {
                val hits = try {
                    root.findAccessibilityNodeInfosByText("定位中")
                } catch (_: Throwable) { null }
                hits?.any { it.isVisibleToUser } == true
            }
            if (!stillLocating) {
                if (lastSeen >= 0) {
                    Logger.d(TAG, "waitForLocationReady: 定位完成，用时 ${System.currentTimeMillis() - start}ms")
                }
                return
            }
            if (lastSeen < 0) {
                Logger.d(TAG, "waitForLocationReady: 检测到'定位中'，等待…")
            }
            lastSeen = System.currentTimeMillis()
            delay(500)
        }
        // 超时兜底：仍再等 1s 让"定位中"最后有机会消失
        Logger.w(TAG, "waitForLocationReady: 超时 ${timeoutMs}ms，'定位中'未消失，继续尝试点击")
        delay(1000)
    }

    /**
     * 若当前误停留在"假勤助手"聊天会话页（可能因 clickAppCard 命中了同名会话），
     * 聊天里的打卡提醒消息一般会带"去打卡"原生按钮，点它即可跳到打卡 H5 页。
     * 打卡 H5 本身是 WebView，无障碍拿不到"去打卡"节点；因此**能通过无障碍找到该节点**就是
     * "我在聊天里"的强信号；找不到就当作已在打卡页，跳过。
     */
    private suspend fun maybeClickGoPunchInChat() {
        val candidates = listOf("去打卡", "去打卡 >", "立即打卡", "去打卡卡片")
        val root = service.rootInActiveWindow ?: return
        var hit: AccessibilityNodeInfo? = null
        for (t in candidates) {
            val hits = try { root.findAccessibilityNodeInfosByText(t) } catch (_: Throwable) { null }
            hit = hits?.firstOrNull { it.isVisibleToUser } ?: continue
            Logger.i(TAG, "maybeClickGoPunchInChat: 命中'$t'，说明当前是聊天会话，点它跳到打卡页")
            break
        }
        if (hit == null) {
            Logger.d(TAG, "maybeClickGoPunchInChat: 未命中任何'去打卡'节点，视为已在打卡页，跳过")
            return
        }
        val clickable = if (hit.isClickable) hit else firstClickableAncestor(hit)
        val ok = clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
        Logger.i(TAG, "maybeClickGoPunchInChat: 点击'去打卡' ok=$ok")
        if (ok) {
            // 等打卡 H5 加载
            delay(2000)
            Logger.d(TAG, "maybeClickGoPunchInChat: 已进入打卡页 rootPkg=${service.rootInActiveWindow?.packageName}")
        } else {
            // ACTION_CLICK 不生效时坐标 tap
            val rect = Rect().also { hit.getBoundsInScreen(it) }
            if (rect.width() > 0 && rect.height() > 0) {
                Logger.d(TAG, "maybeClickGoPunchInChat: ACTION_CLICK 失败，坐标 tap $rect")
                tapAt(rect.exactCenterX(), rect.exactCenterY(), durationMs = 120)
                delay(2000)
            }
        }
    }

    /**
     * 确保当前处于假勤的"打卡" Tab（页面底部 4 个 Tab：打卡 / 申请 / 统计 / 设置）。
     * 判据：找到底部（y > 屏高 70%）的"打卡"文本节点，看其自身或可点击祖先的 isSelected。
     * - isSelected=true：已经在打卡 tab，直接返回（避免重复点导致定位重启动）
     * - isSelected=false 或找不到 selected 状态：performClick 切换
     * - 完全找不到"打卡" Tab 节点：可能页面还在加载或已经在打卡页，静默跳过
     */
    /**
     * 判断当前是否已在假勤 App 内（打卡/申请/统计/设置 4 tab 页面）。
     * 特征：底部（y ≥ 屏高 × 70%）同时存在"打卡"和"申请"两个 Tab 文本。
     */
    private fun isAlreadyInAttendance(): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val screenH = service.resources.displayMetrics.heightPixels
        val bottomThreshold = (screenH * 0.7f).toInt()
        fun hasBottomTab(text: String): Boolean {
            val hits = try { root.findAccessibilityNodeInfosByText(text) } catch (_: Throwable) { null }
                ?: return false
            return hits.any { n ->
                if (n.text?.toString() != text || !n.isVisibleToUser) return@any false
                val r = Rect().also { n.getBoundsInScreen(it) }
                r.top >= bottomThreshold
            }
        }
        return hasBottomTab("打卡") && hasBottomTab("申请")
    }

    private suspend fun ensureAttendancePunchTab() {
        val screenH = service.resources.displayMetrics.heightPixels
        val bottomThreshold = (screenH * 0.7f).toInt()
        val root = service.rootInActiveWindow
        if (root == null) {
            Logger.d(TAG, "ensureAttendancePunchTab: rootInActiveWindow==null，跳过")
            return
        }
        // 底部"打卡" Tab 节点候选
        val tabTextNode = try {
            root.findAccessibilityNodeInfosByText("打卡")
                .firstOrNull { n ->
                    val r = Rect().also { n.getBoundsInScreen(it) }
                    n.text?.toString() == "打卡" && n.isVisibleToUser && r.top >= bottomThreshold
                }
        } catch (_: Throwable) { null }
        if (tabTextNode == null) {
            Logger.d(TAG, "ensureAttendancePunchTab: 未找到底部'打卡' Tab 节点，可能仍在加载或已在打卡页")
            return
        }
        // 判定 selected：Tab 文本本身通常不带 selected 状态，看其 clickable 祖先
        val clickable = if (tabTextNode.isClickable) tabTextNode else firstClickableAncestor(tabTextNode)
        val selected = clickable?.isSelected == true || tabTextNode.isSelected
        val rect = Rect().also { (clickable ?: tabTextNode).getBoundsInScreen(it) }
        Logger.d(TAG, "ensureAttendancePunchTab: 找到'打卡' Tab bounds=$rect selected=$selected clickable=${clickable != null}")
        if (selected) {
            Logger.d(TAG, "ensureAttendancePunchTab: 已在打卡 Tab，跳过")
            return
        }
        val ok = clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
        Logger.i(TAG, "ensureAttendancePunchTab: 当前不在打卡 Tab，切换 ok=$ok")
        if (!ok && rect.width() > 0 && rect.height() > 0) {
            Logger.d(TAG, "ensureAttendancePunchTab: ACTION_CLICK 失败，坐标 tap $rect")
            tapAt(rect.exactCenterX(), rect.exactCenterY(), durationMs = 120)
        }
        // 等 tab 切换动画完成
        delay(800)
    }

    /**
     * 打卡页面是 WebView，无障碍无法拿到"上班打卡/下班打卡"文本节点，只能坐标点击。
     *
     * 手机（1280×2772 竖屏）实测：
     *   - 上班打卡按钮 中心 y ≈ 屏高 × 0.416，x 居中
     *   - 下班打卡按钮 中心 y ≈ 屏高 × 0.570，x 居中
     *
     * 平板（2136×3200 竖屏）实测（用户在真机上校准）：
     *   - 上班打卡按钮 中心比例 (0.48, 0.61)
     *   - 下班打卡按钮 中心比例 (0.48, 0.694)
     * 平板打卡卡片略偏左（x=0.48 而非 0.5），且卡片位于屏幕中下部（y 更大），必须单独走 pad 分支。
     *
     * 按钮距顶部是固定 dp 布局，同类机型（手机/平板）之间用比例换算最稳。
     */
    private suspend fun clickPunchButton(isForClockIn: Boolean) {
        val dm = service.resources.displayMetrics
        val isPad = com.newolf.kaka.util.DeviceUtils.isTablet(service)
        val xRatio: Float
        val yRatio: Float
        if (isPad) {
            // 平板实测比例
            xRatio = 0.48f
//            yRatio = if (isForClockIn) 0.31f else 0.394f
            yRatio = if (isForClockIn) 0.61f else 0.694f
        } else {
            // 手机：卡片水平居中
            xRatio = 0.5f
            yRatio = if (isForClockIn) 0.416f else 0.570f
        }
        val cx = dm.widthPixels * xRatio
        val cy = dm.heightPixels * yRatio
        Logger.d(TAG, "clickPunchButton: 点击 isForClockIn=$isForClockIn isPad=$isPad ratio=($xRatio,$yRatio) tap($cx,$cy) screen=${dm.widthPixels}x${dm.heightPixels}")
        val ok = tapAt(cx, cy, durationMs = 120)
        Logger.d(TAG, "clickPunchButton: tap ok=$ok")
        // WebView 偶发首次不响应，再点一次兜底
        if (!ok) {
            delay(300)
            Logger.d(TAG, "clickPunchButton: 第 1 次未响应，再点一次")
            tapAt(cx, cy, durationMs = 120)
        }
    }

    /** 用屏幕坐标点击中央大按钮的兜底方法（不再单独使用，由 clickPunchButton 内联比例兜底）。保留供调试。 */
    private suspend fun clickCenterPunchButton() {
        val dm = service.resources.displayMetrics
        val cx = dm.widthPixels / 2f
        val cy = dm.heightPixels * 0.355f
        Logger.d(TAG, "clickCenterPunchButton: tap($cx, $cy) screen=${dm.widthPixels}x${dm.heightPixels}")
        val ok = tapAt(cx, cy, durationMs = 150)
        Logger.d(TAG, "clickCenterPunchButton: dispatchGesture ok=$ok")
    }

    /** 使用 dispatchGesture 在屏幕坐标 (x, y) 处点击一次。 */
    private suspend fun tapAt(x: Float, y: Float, durationMs: Long = 150): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return suspendCoroutine { cont ->
            val dispatched = service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) {
                        cont.resume(true)
                    }
                    override fun onCancelled(g: GestureDescription?) {
                        Logger.w(TAG, "tapAt: dispatchGesture onCancelled at ($x,$y)")
                        cont.resume(false)
                    }
                },
                null
            )
            if (!dispatched) {
                Logger.w(TAG, "tapAt: dispatchGesture 返回 false at ($x,$y)")
                cont.resume(false)
            }
        }
    }

    /**
     * QQ 分享面板兜底：找不到 targetChat 时，点击"最近转发"分区下方的第一个头像。
     *
     * 布局参考（用户截图）：分享面板从上到下是「取消/选择聊天/多选」→「搜索」→
     * 「最近转发」（横向头像栏，第一个头像=最近转发对象）→「最近聊天」（纵向列表）。
     *
     * 实现策略（简单直接）：
     *   accessibility 上报的头像 bounds 在实际 QQ 版本里位置飘忽（RecyclerView 复用问题）、
     *   而且头像本身多为 clickable=false、父链 tile 面积不稳，导致 tap 命中率差。
     *   用户实测在本机（1440×3007）"最近转发"首个头像可点区域约 x=50~282, y=715~1060，
     *   中心 (166, 887) 稳定命中。这里按屏幕**百分比**换算硬编码，主流分辨率上都能覆盖：
     *     - x: 8% (166/1440 ≈ 0.115  略偏保守取 0.115)
     *     - y: 29.5% (887/3007 ≈ 0.295)
     *
     * 若后续遇到新机型或 QQ 布局大改导致偏离，可回退到基于"最近转发"标签 bounds 的动态推算。
     */
    private suspend fun tryTapFirstRecentForward(): Boolean {
        val dm = service.resources.displayMetrics
        val sw = dm.widthPixels
        val sh = dm.heightPixels
        // 参考坐标 (166, 887) on 1440×3007 → 百分比 (0.115, 0.295)
        val fx = sw * 0.115f
        val fy = sh * 0.295f
        Logger.i(
            TAG,
            "点击'最近转发'第一个（固定坐标）tap#1($fx,$fy) duration=150 screen=${sw}x${sh}"
        )
        tapAt(fx, fy, durationMs = 150)
        // QQ 头像 tile 对过短手势会丢弃；等 1s 观察是否离开"选择聊天"页。
        // 判定标准：只要 root 里还能找到 desc="选择聊天" 就说明没进入会话。
        delay(1000)
        val stillOnPicker = try {
            val root = service.rootInActiveWindow
            root?.let {
                val descHits = mutableListOf<AccessibilityNodeInfo>()
                collectNodesByDesc(it, setOf("选择聊天"), descHits)
                descHits.any { n -> n.isVisibleToUser }
            } ?: false
        } catch (_: Throwable) { false }
        if (stillOnPicker) {
            Logger.d(TAG, "'最近转发'首次 tap 后仍在'选择聊天'页，二次 tap 兜底 duration=250")
            // 二次 tap：更长时长 + 略微下移 8px（避开头像圆边缘缺陷）
            tapAt(fx, fy + 8f, durationMs = 250)
        }
        return true
    }

    /** 递归收集 root 子树中 bounds 完全落在 [top, bottom]（屏幕 y）竖带内、可视、非根级容器的节点。 */
    private fun collectVisibleNodesInBand(
        node: AccessibilityNodeInfo?,
        top: Int,
        bottom: Int,
        screenW: Int,
        out: MutableList<AccessibilityNodeInfo>,
    ) {
        if (node == null) return
        try {
            if (node.isVisibleToUser) {
                val r = Rect().also { node.getBoundsInScreen(it) }
                if (r.width() > 0 && r.height() > 0 &&
                    r.top >= top && r.bottom <= bottom &&
                    r.width() < screenW  // 排除满屏容器
                ) {
                    out.add(node)
                }
            }
        } catch (_: Throwable) { /* ignore */ }
        for (i in 0 until node.childCount) {
            collectVisibleNodesInBand(node.getChild(i), top, bottom, screenW, out)
        }
    }

    /**
     * 递归遍历 root 子树，把 `contentDescription` **精确等于** wantedDescs 里任意值的节点收集到 out。
     * 用途：QQ 底部"发送给 X"确认弹窗里的蓝色"发送"按钮通常是自绘 View，
     * text=null 只有 desc="发送"，仅靠 findAccessibilityNodeInfosByText 会漏掉。
     */
    private fun collectNodesByDesc(
        node: AccessibilityNodeInfo?,
        wantedDescs: Set<String>,
        out: MutableList<AccessibilityNodeInfo>,
    ) {
        if (node == null) return
        try {
            val d = node.contentDescription?.toString()
            if (d != null && d in wantedDescs) out.add(node)
        } catch (_: Throwable) { /* ignore */ }
        for (i in 0 until node.childCount) {
            collectNodesByDesc(node.getChild(i), wantedDescs, out)
        }
    }

    /** 尝试用文本点击，失败返回 false（不抛异常）。 */
    private suspend fun tryClickByText(texts: List<String>, timeout: Long): Boolean {
        val node = waitForNode(timeout) { root -> findClickableByTexts(root, texts) }
        if (node == null) return false
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Logger.d(TAG, "tryClickByText 命中 texts=$texts performClick=$ok node[cls=${node.className} text=${node.text} desc=${node.contentDescription} bounds=$bounds]")
        delay(1000)
        return ok
    }

    /** 只点屏幕底部区域（y > 70% 高度）的可点击祖先，用于底部导航 Tab。
     *  智能跳过：若目标 Tab 的可点击祖先已 `isSelected=true`，说明已在该 Tab，直接返回。
     */
    private suspend fun clickBottomTab(texts: List<String>, timeout: Long) {
        val screenH = service.resources.displayMetrics.heightPixels
        val threshold = (screenH * 0.7f).toInt()
        val node = waitForNode(timeout) { root ->
            findClickableByTexts(root, texts, filter = { clickable, matched ->
                val rect = Rect().also { clickable.getBoundsInScreen(it) }
                val matchedRect = Rect().also { matched.getBoundsInScreen(it) }
                rect.top >= threshold || matchedRect.top >= threshold
            })
        }
        // 已在目标 Tab（selected=true）时无需重复点击，避免重置页面滚动/触发下载等副作用
        if (node?.isSelected == true) {
            Logger.d(TAG, "clickBottomTab: 目标 Tab $texts 已 selected，跳过点击")
            return
        }
        performClickOrThrow(node, texts, "clickBottomTab")
    }

    /**
     * 用于工作台应用网格的图标卡片：可点击祖先里应用名 TextView 是主体文本，
     * 用来排除"假勤助手"等会话（会话是长条列表项，图标应用是正方形卡片）。
     *
     * 判定规则（多信号）：
     *   1) 可点击祖先内必须包含目标 target（应用名）
     *   2) 可点击祖先形状要像"图标格子"：**宽高比接近 1（正方形），或高 ≤ 宽 × 1.6**
     *      - 应用卡片 (icon + 应用名)：约 1:1.1 ~ 1:1.3
     *      - 聊天会话行：约 4:1 或 5:1（宽 >> 高）
     *      注：聊天会话虽然是"高 << 宽"，但它长度大，我们其实要挑"高 ≈ 宽"的
     *   3) 加强：不含明显的"聊天时间"格式（HH:mm/刚刚/分钟前）—— 双保险
     */
    private suspend fun clickAppCard(texts: List<String>, timeout: Long) {
        val chatSignals = Regex("""(^\d{1,2}:\d{2}$|刚刚|分钟前|小时前|昨天)""")
        val node = waitForNode(timeout) { root ->
            findClickableByTexts(
                root = root,
                texts = texts,
                filter = { clickable, matched ->
                    // ---------- A：精确匹配 ----------
                    // matched 节点自身的 text 或 contentDescription 必须**等于**目标词（如"假勤"）。
                    // 这一步把"多维表格卡片被拼接 desc（如 '假勤, 多维表格, 日历'）子串命中"这类误匹配全部排除。
                    val mText = matched.text?.toString()
                    val mDesc = matched.contentDescription?.toString()
                    val target = texts.firstOrNull { it == mText || it == mDesc }
                    if (target == null) {
                        Logger.d(TAG, "clickAppCard 跳过(非精确)：mText='$mText' mDesc='$mDesc' 期望 in $texts")
                        return@findClickableByTexts false
                    }

                    // ---------- B：叶子文本节点约束 ----------
                    // 应用卡片的文字标签一定是一个叶子节点（无子节点）；
                    // 若 matched 有子节点，大概率是网格/整卡片容器，desc 是拼接出来的（会串到"多维表格"等）。
                    if (matched.childCount > 0) {
                        Logger.d(TAG, "clickAppCard 跳过(非叶子)：matched.childCount=${matched.childCount} cls=${matched.className} target=$target")
                        return@findClickableByTexts false
                    }

                    // ---------- 保留形状/聊天信号过滤 ----------
                    val descTexts = collectTextsUnder(clickable).filter { it.isNotBlank() }
                    val looksLikeChat = descTexts.any { chatSignals.containsMatchIn(it) }
                    // 用可点击祖先的 bounds 宽高比判定形状：应用卡片近似正方形；聊天会话是横向长条
                    val rect = Rect().also { clickable.getBoundsInScreen(it) }
                    val matchedRect = Rect().also { matched.getBoundsInScreen(it) }
                    val w = rect.width().coerceAtLeast(1)
                    val h = rect.height().coerceAtLeast(1)
                    // 应用卡片：w/h 大约 0.5~1.6；聊天会话：w/h > 2.5
                    val ratio = w.toFloat() / h.toFloat()
                    val looksLikeSquareCard = ratio in 0.5f..1.6f
                    val looksLikeAppCard = !looksLikeChat && looksLikeSquareCard
                    if (!looksLikeAppCard) {
                        Logger.d(TAG, "clickAppCard 跳过(形状/聊天)：target=$target descTexts=$descTexts bounds=$rect matchedBounds=$matchedRect ratio=%.2f looksLikeChat=$looksLikeChat looksLikeSquareCard=$looksLikeSquareCard".format(ratio))
                    } else {
                        Logger.d(TAG, "clickAppCard 命中应用卡片：target=$target descTexts=$descTexts bounds=$rect matchedBounds=$matchedRect ratio=%.2f".format(ratio))
                    }
                    looksLikeAppCard
                },
                // 多个"假勤"图标共存时（如"我的常用"+"应用商店"里都出现），优先选左上那个：
                // y 更小的更靠近页面顶部（常用应用位）；y 相同时 x 更小（更靠左）。
                preferBy = Comparator { a, b ->
                    val ra = Rect().also { a.getBoundsInScreen(it) }
                    val rb = Rect().also { b.getBoundsInScreen(it) }
                    val cyA = ra.centerY(); val cyB = rb.centerY()
                    if (cyA != cyB) cyA.compareTo(cyB) else ra.centerX().compareTo(rb.centerX())
                }
            )
        }
        performClickOrThrow(node, texts, "clickAppCard")
    }

    /**
     * 平板专用：从"工作台"页进入，点击**右上角搜索图标**，输入"假勤"，选中"应用"分组下的结果。
     *
     * 手机版无障碍能拿到工作台应用卡片的文本节点，pad 版工作台主区渲染为空 FrameLayout（Compose/WebView 未透传语义），
     * 因此 [clickAppCard] 100% 失败。工作台自带的右上角搜索是原生控件，可靠性高。
     *
     * 注意：**必须先 clickBottomTab("工作台")** —— pad 上不同 tab 都有自己的搜索入口，
     * 消息 tab 顶部的 EditText 搜索的是聊天记录，云文档 tab 搜索的是文档；只有工作台的搜索才会命中"应用"分组的假勤。
     * 因此本方法只接受"工作台页右上角的搜索图标"作为入口（屏幕上半部 + 右侧半屏 + clickable，且优先 desc/text=="搜索"）。
     *
     * 步骤：
     *   1) 在工作台页右上角找搜索图标并点击（不接受屏幕上方的 EditText，避免误进消息搜索）
     *   2) 输入"假勤"（ACTION_SET_TEXT）
     *   3) 结果按分组归属过滤：只接受"应用" / "Apps" 分组下的候选（排除机器人 / 订阅号）
     */
    private suspend fun searchAndOpenAttendance(timeoutMs: Long) {
        // ---- Step 1: 点工作台右上角搜索图标（放大镜，无 desc / text） ----
        // pad 上飞书工作台顶栏右侧一排图标按钮，最右边是"账号/更多"，倒数第 2 个是搜索放大镜。
        // 图标本身通常没有 text/desc（Compose 图标节点不透传语义），所以只能"按位置枚举 clickable 节点"。
        Logger.d(TAG, "searchAndOpenAttendance: [step 1] 定位工作台右上角搜索图标（顶栏右侧倒数第 2 个 clickable）")
        val dm = service.resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val topBandBottom = (screenH * 0.15f).toInt() // 顶栏在屏高 0~15%
        val rightBandLeft = (screenW * 0.5f).toInt() // 右半屏

        val icon = waitForNode(4000) { root ->
            // 收集"顶栏右侧半屏内"所有可点击节点，去掉父子重叠（保留最小的那个，即真正的图标本体）
            val all = mutableListOf<AccessibilityNodeInfo>()
            collectClickable(root, all, maxDepth = 40)
            val topRight = all.asSequence()
                .filter { n ->
                    if (!n.isVisibleToUser) return@filter false
                    val r = Rect().also { n.getBoundsInScreen(it) }
                    if (r.width() <= 0 || r.height() <= 0) return@filter false
                    // 顶栏 + 右半屏
                    r.top in 0..topBandBottom && r.right >= rightBandLeft &&
                        // 排除大块容器（宽度 > 屏宽的 40% 一般是整个顶栏 / 输入框，不是图标）
                        r.width() < (screenW * 0.4f).toInt()
                }
                .toList()
            // 去重：如果 a 完全包含 b，只保留 b（更小的图标本体）
            val minimal = topRight.filter { a ->
                val ra = Rect().also { a.getBoundsInScreen(it) }
                topRight.none { b ->
                    if (b === a) return@none false
                    val rb = Rect().also { b.getBoundsInScreen(it) }
                    ra.contains(rb) && (ra.width() * ra.height()) > (rb.width() * rb.height())
                }
            }
            // 按 x 从右到左排序
            val ordered = minimal.sortedByDescending { Rect().also { r -> it.getBoundsInScreen(r) }.right }
            if (ordered.isNotEmpty()) {
                val summary = ordered.take(6).joinToString(" | ") { n ->
                    val r = Rect().also { n.getBoundsInScreen(it) }
                    "cls=${n.className} text='${n.text}' desc='${n.contentDescription}' bounds=$r"
                }
                Logger.d(TAG, "searchAndOpenAttendance: [step 1] 顶栏右侧候选(${ordered.size}, 从右到左): $summary")
            }
            // 倒数第 2 个 == 从右往左数第 2 个 == ordered[1]
            ordered.getOrNull(1)
        }
        if (icon == null) {
            dumpTopWindow(prefixTag = "search-entry-not-found")
            Logger.w(TAG, "searchAndOpenAttendance: 工作台顶栏未找到足够的右侧图标 screen=${screenW}x${screenH}")
            throw RuntimeException("找不到工作台右上角搜索图标")
        }
        val iconRect = Rect().also { icon.getBoundsInScreen(it) }
        val iconOk = icon.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Logger.i(TAG, "searchAndOpenAttendance: [step 1] 点击工作台搜索图标（倒数第 2 个）text='${icon.text}' desc='${icon.contentDescription}' bounds=$iconRect performClick=$iconOk")
        if (!iconOk && iconRect.width() > 0 && iconRect.height() > 0) {
            tapAt(iconRect.exactCenterX(), iconRect.exactCenterY(), 120)
        }
        delay(600)

        // ---- Step 2: 找搜索页 EditText 并输入"假勤" ----
        val editor = findSearchEditText() ?: waitForNode(3000) { root -> findSearchEditTextIn(root) }
        if (editor == null) {
            dumpTopWindow(prefixTag = "search-editor-not-found")
            Logger.w(TAG, "searchAndOpenAttendance: 点开工作台搜索后仍找不到 EditText")
            throw RuntimeException("找不到搜索 EditText")
        }
        val edRect = Rect().also { editor.getBoundsInScreen(it) }
        val hintForLog = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try { editor.hintText?.toString() } catch (_: Throwable) { null }
        } else null
        Logger.d(TAG, "searchAndOpenAttendance: [step 2] 找到 EditText bounds=$edRect text=${editor.text} hint=$hintForLog")

        // 若 EditText 不是 focused，先 focus 一次（ACTION_FOCUS + ACTION_CLICK 都试）；否则 SET_TEXT 可能不生效
        if (!editor.isFocused) {
            editor.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            editor.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            delay(200)
        }
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "假勤")
        }
        val setOk = editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        Logger.d(TAG, "searchAndOpenAttendance: [step 2] SET_TEXT ok=$setOk")
        if (!setOk) {
            // 兜底：坐标 tap 聚焦 + 再 SET_TEXT 一次
            if (edRect.width() > 0 && edRect.height() > 0) {
                tapAt(edRect.exactCenterX(), edRect.exactCenterY(), 120)
                delay(300)
                val retry = editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                Logger.d(TAG, "searchAndOpenAttendance: [step 2] SET_TEXT 重试 ok=$retry")
            }
        }
        // 飞书搜索是即时搜索（无需按回车），但给点时间等结果渲染
        delay(1200)

        // Step 2.5（可选优化）：如果搜索结果页有"应用"分类 tab，先点一下把结果收敛到"应用"分组，
        //                     减少与"机器人"/"订阅号"同名结果撞车的可能。找不到无副作用，静默跳过。
        tryClickAppFilterTab()

        // ---- Step 3: 命中结果并点击 ----
        val resultNode = waitForNode(timeoutMs) { root ->
            val hits = try { root.findAccessibilityNodeInfosByText("假勤") } catch (_: Throwable) { null }
                ?: return@waitForNode null
            if (hits.isNotEmpty()) {
                val summary = hits.take(8).joinToString(" | ") { n ->
                    val r = Rect().also { n.getBoundsInScreen(it) }
                    "text='${n.text}' desc='${n.contentDescription}' clickable=${n.isClickable} childCount=${n.childCount} bounds=$r"
                }
                Logger.d(TAG, "searchAndOpenAttendance: [step 3] 搜索结果候选(${hits.size}): $summary")
            }

            // ---- 分组标签定位 ----
            // 飞书搜索结果按"应用/机器人/订阅号/消息/文档/联系人/群组…"分组；标签是屏幕上一行独立的 TextView。
            // 我们必须只取"应用"分组下的那条"假勤"，排除"机器人"/"订阅号"分组下的同名条目。
            // 策略：把所有可见的分组标签按 y 从小到大排序；对每个候选，找到"y 不大于候选 y"的最近标签，只保留标签=="应用"的。
            val groupLabels = listOf(
                "应用", "机器人", "订阅号", "消息", "文档", "联系人", "群组", "群聊", "云文档",
                "Apps", "Bots", "Feed"
            )
            val labelNodes = groupLabels.flatMap { g ->
                (try { root.findAccessibilityNodeInfosByText(g) } catch (_: Throwable) { null } ?: emptyList())
                    .filter { it.isVisibleToUser && it.text?.toString() == g }
            }
            val labelYs = labelNodes.map { ln ->
                val r = Rect().also { ln.getBoundsInScreen(it) }
                ln.text.toString() to r.top
            }.sortedBy { it.second }
            Logger.d(TAG, "searchAndOpenAttendance: [step 3] 分组标签 y 序列 = $labelYs")

            hits.asSequence()
                .filter { n ->
                    if (!n.isVisibleToUser) return@filter false
                    val r = Rect().also { n.getBoundsInScreen(it) }
                    if (r.width() <= 0 || r.height() <= 0) return@filter false // 排除离屏节点
                    if (r.top < 0 || r.top > screenH) return@filter false
                    val mt = n.text?.toString()
                    val md = n.contentDescription?.toString()
                    val exact = mt == "假勤" || md == "假勤"
                    if (!exact) return@filter false
                    // 排除自身有子节点（多半是聊天条目容器把 desc 拼成"假勤, 昨天, 打卡周报..."）
                    if (n.childCount > 0) return@filter false
                    if (!(n.isClickable || firstClickableAncestor(n) != null)) return@filter false

                    // 分组归属判定：候选 y 之上（含）最近的分组标签必须是"应用"或"Apps"
                    val hitTop = r.top
                    val nearestLabel = labelYs.lastOrNull { it.second <= hitTop }
                    val belongsToAppGroup = nearestLabel?.first == "应用" || nearestLabel?.first == "Apps"
                    if (!belongsToAppGroup) {
                        Logger.d(TAG, "searchAndOpenAttendance: [step 3] 跳过(非'应用'分组): hitTop=$hitTop nearestLabel=$nearestLabel bounds=$r")
                    } else {
                        Logger.d(TAG, "searchAndOpenAttendance: [step 3] 命中(应用分组): hitTop=$hitTop nearestLabel=$nearestLabel bounds=$r")
                    }
                    belongsToAppGroup
                }
                .sortedWith(compareBy { Rect().also { r -> it.getBoundsInScreen(r) }.top })
                .firstOrNull()
        }
        if (resultNode == null) {
            dumpTopWindow(prefixTag = "search-result-not-found")
            Logger.w(TAG, "searchAndOpenAttendance: 搜索结果里未找到'假勤'")
            throw RuntimeException("搜索结果里没有'假勤'")
        }
        val rBounds = Rect().also { resultNode.getBoundsInScreen(it) }
        val clickable = if (resultNode.isClickable) resultNode else firstClickableAncestor(resultNode)
        val ok = clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
        Logger.i(TAG, "searchAndOpenAttendance: 点击'假勤'结果 bounds=$rBounds performClick=$ok")
        if (!ok && rBounds.width() > 0 && rBounds.height() > 0) {
            tapAt(rBounds.exactCenterX(), rBounds.exactCenterY(), 120)
        }
        delay(600)
    }

    /**
     * 尝试点击搜索结果页顶部的"应用"分类 tab（把结果收敛到应用分组）。
     * 特征：text 或 desc 精确等于"应用"或"Apps"，位于屏幕**上半部**（tab 在页面顶部），
     *      不要求 isSelected—如果已经选中，再点一次也没副作用。
     * 找不到就静默返回（一些飞书版本没有分类 tab，直接展示分组列表）。
     */
    private suspend fun tryClickAppFilterTab() {
        val screenH = service.resources.displayMetrics.heightPixels
        val topHalf = screenH / 2
        val node = try {
            val root = service.rootInActiveWindow ?: return
            listOf("应用", "Apps").firstNotNullOfOrNull { kw ->
                (try { root.findAccessibilityNodeInfosByText(kw) } catch (_: Throwable) { null } ?: emptyList())
                    .firstOrNull { n ->
                        if (!n.isVisibleToUser) return@firstOrNull false
                        // 只接受精确等于（避免命中"应用商店/应用中心"等）
                        val ok = n.text?.toString() == kw || n.contentDescription?.toString() == kw
                        if (!ok) return@firstOrNull false
                        val r = Rect().also { n.getBoundsInScreen(it) }
                        // 分类 tab 一定在屏幕上半部；避免命中下面的"应用"分组标题本身
                        r.top in 0..topHalf && (n.isClickable || firstClickableAncestor(n) != null)
                    }
            }
        } catch (_: Throwable) { null }
        if (node == null) {
            Logger.d(TAG, "tryClickAppFilterTab: 未找到'应用'分类 tab，跳过（走分组标签路径即可）")
            return
        }
        val clickable = if (node.isClickable) node else firstClickableAncestor(node)
        val ok = clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
        val r = Rect().also { (clickable ?: node).getBoundsInScreen(it) }
        Logger.d(TAG, "tryClickAppFilterTab: 点击'应用' tab bounds=$r ok=$ok")
        if (!ok && r.width() > 0 && r.height() > 0) tapAt(r.exactCenterX(), r.exactCenterY(), 120)
        delay(500)
    }

    /** 全局找一次可用的搜索 EditText；找不到返回 null。 */
    private fun findSearchEditText(): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        return findSearchEditTextIn(root)
    }

    /**
     * 在给定 root 下 DFS 寻找"搜索用" EditText：
     *  - className == android.widget.EditText 或 isEditable=true
     *  - 且 text/hint/desc 含"搜索"/"Search" 关键字（或 EditText 只有一个时也接受）
     */
    private fun findSearchEditTextIn(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val allEditors = mutableListOf<AccessibilityNodeInfo>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var count = 0
        while (stack.isNotEmpty() && count < 3000) {
            val n = stack.removeLast() ?: continue
            count++
            val cls = n.className?.toString().orEmpty()
            val editable = cls == "android.widget.EditText" || n.isEditable
            if (editable && n.isVisibleToUser) allEditors.add(n)
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.addLast(it) }
        }
        if (allEditors.isEmpty()) return null
        // 优先"搜索"关键字命中
        val kw = Regex("搜索|Search", RegexOption.IGNORE_CASE)
        return allEditors.firstOrNull { ed ->
            val t = ed.text?.toString().orEmpty()
            val h = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try { ed.hintText?.toString().orEmpty() } catch (_: Throwable) { "" }
            } else ""
            val d = ed.contentDescription?.toString().orEmpty()
            kw.containsMatchIn(t) || kw.containsMatchIn(h) || kw.containsMatchIn(d)
        } ?: allEditors.firstOrNull() // 兜底：全屏只有一个 EditText 就用它
    }

    private fun collectTextsUnder(root: AccessibilityNodeInfo?, out: MutableList<String> = mutableListOf(), depth: Int = 0): List<String> {
        if (root == null || depth > 20) return out
        root.text?.toString()?.let { if (it.isNotEmpty()) out.add(it) }
        for (i in 0 until root.childCount) collectTextsUnder(root.getChild(i), out, depth + 1)
        return out
    }

    /**
     * DFS 收集当前树里所有 isClickable=true 的节点。用于"按位置枚举顶栏图标按钮"等场景。
     * 深度限制避免飞书页面偶发深层树把栈打爆。
     */
    private fun collectClickable(root: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>, depth: Int = 0, maxDepth: Int = 30) {
        if (root == null || depth > maxDepth) return
        if (root.isClickable) out.add(root)
        for (i in 0 until root.childCount) collectClickable(root.getChild(i), out, depth + 1, maxDepth)
    }

    private suspend fun performClickOrThrow(
        node: AccessibilityNodeInfo?,
        texts: List<String>,
        source: String
    ) {
        if (node != null) {
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Logger.d(TAG, "$source 命中 texts=$texts performClick=$ok bounds=$bounds")
            if (!ok) {
                // ACTION_CLICK 失败的常见原因：可点击祖先其实是 RecyclerView / 网格容器，
                // 系统只接受手势输入而不响应 ACTION_CLICK。这里坐标 tap 兜底。
                if (bounds.width() > 0 && bounds.height() > 0) {
                    val tapOk = tapAt(bounds.exactCenterX(), bounds.exactCenterY(), durationMs = 120)
                    Logger.i(TAG, "$source performClick 失败，改用坐标 tap 中心 ok=$tapOk")
                } else {
                    Logger.w(TAG, "$source performClick 失败且 bounds 无效，无法兜底 tap bounds=$bounds")
                }
            }
            delay(500) // 之前是 1000ms，缩短一半
        } else {
            val pkg = service.rootInActiveWindow?.packageName
            dumpTopWindow(prefixTag = "$source-fail")
            Logger.e(TAG, "$source 超时：texts=$texts 当前顶层 pkg=$pkg")
            throw RuntimeException("找不到控件: $texts")
        }
    }

    private suspend fun clickByText(texts: List<String>, timeout: Long) {
        val node = waitForNode(timeout) { root ->
            findClickableByTexts(root, texts)
        }
        if (node != null) {
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            val nodeText = node.text?.toString()
            val nodeDesc = node.contentDescription?.toString()
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Logger.d(TAG, "clickByText 命中 texts=$texts performClick=$ok node[cls=${node.className} text=$nodeText desc=$nodeDesc bounds=$bounds]")
            delay(1000)
        } else {
            val pkg = service.rootInActiveWindow?.packageName
            // 打印一次窗口 dump，辅助定位
            dumpTopWindow(prefixTag = "clickByText-fail")
            Logger.e(TAG, "clickByText 超时：texts=$texts 当前顶层 pkg=$pkg")
            throw RuntimeException("找不到控件: $texts")
        }
    }

    /**
     * 在所有窗口中，按文本查找可点击节点。
     * 匹配策略：
     *  1) 候选词按优先级顺序**串行**处理：先精确的（如"上班打卡"），命中就返回；
     *     不再评估后续更宽泛的词（如"上班"），避免误匹配"上班时间"等噪音
     *  2) 对每个词：findAccessibilityNodeInfosByText（子串搜 text）+ DFS 搜 contentDescription
     *  3) 命中节点若自身不可点击，向上冒泡最多 12 层找可点击祖先
     *  4) 同一词内：精确匹配（text==词）优先于子串匹配
     *  @param filter 额外过滤器：接收 (可点击祖先, 匹配到的文本节点)，返回是否接受该候选
     *  @param preferBy 同一词的多个命中之间的排序器（越靠前越优先）；默认按遍历顺序
     */
    private fun findClickableByTexts(
        root: AccessibilityNodeInfo,
        texts: List<String>,
        filter: ((AccessibilityNodeInfo, AccessibilityNodeInfo) -> Boolean)? = null,
        preferBy: Comparator<AccessibilityNodeInfo>? = null,
    ): AccessibilityNodeInfo? {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        roots.add(root)
        try {
            service.windows?.forEach { w ->
                w.root?.let { if (it != root) roots.add(it) }
            }
        } catch (_: Throwable) { }

        // 每个候选词独立搜索，按顺序返回第一个命中的
        for (text in texts) {
            val exactHits = mutableListOf<AccessibilityNodeInfo>()
            val fuzzyHits = mutableListOf<AccessibilityNodeInfo>()
            fun accept(clickable: AccessibilityNodeInfo, matched: AccessibilityNodeInfo, exact: Boolean) {
                if (filter != null && !filter(clickable, matched)) return
                if (exact) exactHits.add(clickable) else fuzzyHits.add(clickable)
            }
            for (r in roots) {
                val byText = try { r.findAccessibilityNodeInfosByText(text) } catch (_: Throwable) { null }
                byText?.forEach { m ->
                    val clickable = firstClickableAncestor(m) ?: return@forEach
                    val exact = m.text?.toString() == text || m.contentDescription?.toString() == text
                    accept(clickable, m, exact)
                }
                collectByDescription(r, text, ::accept)
            }
            // 若指定了排序器，先给候选去重再排序；否则保持遍历顺序
            val chosen = if (preferBy != null) {
                (exactHits.distinct().sortedWith(preferBy).firstOrNull()
                    ?: fuzzyHits.distinct().sortedWith(preferBy).firstOrNull())
            } else {
                exactHits.firstOrNull() ?: fuzzyHits.firstOrNull()
            }
            if (chosen != null) return chosen
        }
        return null
    }

    private fun collectByDescription(
        node: AccessibilityNodeInfo?,
        target: String,
        onHit: (AccessibilityNodeInfo, AccessibilityNodeInfo, Boolean) -> Unit,
        depth: Int = 0
    ) {
        if (node == null || depth > 40) return
        val desc = node.contentDescription?.toString()
        val txt = node.text?.toString()
        if (!desc.isNullOrEmpty() && desc.contains(target)) {
            firstClickableAncestor(node)?.let { onHit(it, node, desc == target) }
        } else if (!txt.isNullOrEmpty() && txt.contains(target)) {
            firstClickableAncestor(node)?.let { onHit(it, node, txt == target) }
        }
        for (i in 0 until node.childCount) {
            collectByDescription(node.getChild(i), target, onHit, depth + 1)
        }
    }

    private fun firstClickableAncestor(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth < 12) {
            if (cur.isClickable) return cur
            cur = cur.parent
            depth++
        }
        return null
    }

    /**
     * 向上找最"像 tile 容器"的祖先节点：面积至少是自己的 1.2 倍、且宽度仍然小于屏幕宽度。
     * 用途：QQ 分享面板"最近转发"里头像节点本身只是圆图（129×203），
     * 但真正对点击生效的目标是包含头像 + 下方昵称文字的整个 tile（约 230×280）。
     * tap tile 中心比 tap 头像中心更容易触发 QQ 的 item click。
     *
     * 找不到合适祖先时返回自己。
     */
    private fun tileContainerOrSelf(
        node: AccessibilityNodeInfo,
        screenW: Int,
    ): AccessibilityNodeInfo {
        val selfRect = Rect().also { node.getBoundsInScreen(it) }
        val selfArea = selfRect.width().toLong() * selfRect.height()
        var cur: AccessibilityNodeInfo? = node.parent
        var best: AccessibilityNodeInfo = node
        var bestArea = selfArea
        var depth = 0
        while (cur != null && depth < 6) {
            val r = Rect().also { cur!!.getBoundsInScreen(it) }
            val a = r.width().toLong() * r.height()
            // 硬约束：祖先 bounds 必须**完全包含** node bounds。
            // 否则 QQ ListView/RecyclerView 复用等场景下，parent 汇报的 bounds 可能来自
            // 缓存里"下一行的容器"（bounds y 甚至比 node y 还大），导致 tap 到列表其它行。
            val contains = r.left <= selfRect.left && r.top <= selfRect.top &&
                r.right >= selfRect.right && r.bottom >= selfRect.bottom
            if (contains &&
                r.width() in 1..(screenW - 1) &&
                a >= selfArea * 12 / 10 &&    // 至少 1.2 倍面积（含昵称）
                a <= selfArea * 5              // 但不超过自身 5 倍（避免跳到整个头像栏容器）
            ) {
                if (a > bestArea) {
                    best = cur!!
                    bestArea = a
                }
            }
            cur = cur.parent
            depth++
        }
        return best
    }

    /**
     * 用通知栏 fullScreenIntent 拉起 RelayLaunchActivity，绕过 MIUI 后台启动限制。
     *
     * fullScreenIntent 相当于"来电全屏通知"，系统会把 PendingIntent 视为高优先级用户通知，
     * 强制切到前台，从而突破 startActivity 后台限制。
     * 使用完立即 cancel 通知，屏幕上不会真的看到通知条。
     */
    private fun launchRelayViaFullScreenIntent(target: android.content.Intent): Boolean {
        try {
            val ctx = service
            val pi = android.app.PendingIntent.getActivity(
                ctx, 0x1234, target,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val channelId = "kaka_relay_channel"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
                if (nm.getNotificationChannel(channelId) == null) {
                    val ch = android.app.NotificationChannel(
                        channelId, "KaKa 中转", android.app.NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "打卡完成后拉起 QQ 用"
                        setSound(null, null)
                        enableVibration(false)
                        lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
                    }
                    nm.createNotificationChannel(ch)
                }
            }
            val notifId = 8899
            val notif = androidx.core.app.NotificationCompat.Builder(ctx, channelId)
                .setSmallIcon(com.newolf.kaka.R.drawable.ic_notification)
                .setContentTitle("KaKa")
                .setContentText("正在打开 QQ…")
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setCategory(androidx.core.app.NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(pi, true)
                .setAutoCancel(true)
                .build()
            val nm = androidx.core.app.NotificationManagerCompat.from(ctx)
            nm.notify(notifId, notif)
            Logger.d(TAG, "launchRelayViaFullScreenIntent: notify done")
            // 立即撤销通知本身（fullScreenIntent 会在 notify 时被系统触发一次）
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                nm.cancel(notifId)
            }, 1500)
            return true
        } catch (t: Throwable) {
            Logger.w(TAG, "fullScreenIntent 启动失败，降级为直接 startActivity: ${t.message}", t)
        }
        // 降级：直接 startActivity（在部分 ROM/权限下也可能成功）
        return try {
            service.startActivity(target)
            Logger.d(TAG, "launchRelayViaFullScreenIntent: 降级 service.startActivity 成功")
            true
        } catch (t: Throwable) {
            Logger.e(TAG, "launchRelayViaFullScreenIntent: 降级也失败: ${t.message}", t)
            false
        }
    }

    /**
     * 在当前桌面（MIUI Home 或系统 launcher）找到指定 label 的应用图标并点击。
     *
     * 桌面上应用图标一般以 TextView / ImageView 的形式挂在 workspace/hotseat 里。
     * 匹配优先级：text 精确 > contentDescription 精确 > contentDescription 包含。
     * 命中后向上找可点击祖先并触发 ACTION_CLICK；点不到时兜底用坐标 tap。
     *
     * @return 是否触发了一次点击。是否真的成功进入应用需要调��方通过前台包名变化来验证。
     */
    private suspend fun clickDesktopIcon(labels: List<String>): Boolean {
        // 等桌面窗口就绪（MIUI 主屏是 com.miui.home，AOSP 是 com.android.launcher3 等）
        val ready = waitForCondition(2500) {
            val pkg = service.rootInActiveWindow?.packageName?.toString() ?: return@waitForCondition false
            isLauncherPkg(pkg)
        }
        Logger.d(TAG, "clickDesktopIcon: ready=$ready pkg=${service.rootInActiveWindow?.packageName} labels=$labels")
        if (!ready) return false

        // 首屏尝试
        service.rootInActiveWindow?.let { root ->
            for (label in labels) {
                if (tryClickIconByLabel(root, label)) return true
            }
        }

        // 未命中：分两阶段翻页
        //   1) 尝试向"下一页"（手指从右向左滑）3 次
        //   2) 若中途被划到负一屏（前台 pkg 不再是 launcher），先滑回来再改方向
        //   3) 再从当前位置向"上一页"翻 3 次
        // 为了标识"划不动"，我们记录翻页前后桌面窗口的第一个可见节点 text 特征：
        // 若翻页后特征基本没变，就当作"已到边界"直接换方向。
        val phases = listOf(true, false) // true = 向下一页；false = 向上一页
        for (rightToLeft in phases) {
            var lastSignature = launcherSignature()
            repeat(4) { step ->
                Logger.d(TAG, "clickDesktopIcon: 翻页 ${if (rightToLeft) "→下一页" else "←上一页"} step=${step + 1}")
                val swiped = swipeHorizontally(rightToLeft = rightToLeft)
                if (!swiped) {
                    Logger.w(TAG, "clickDesktopIcon: 翻页手势 dispatch 失败，换方向")
                    return@repeat
                }
                delay(500)
                // 若离开了 launcher（比如误滑进负一屏），先返回桌面再中止本方向
          val curPkg = service.rootInActiveWindow?.packageName?.toString()
                if (curPkg != null && !isLauncherPkg(curPkg)) {
                    Logger.w(TAG, "clickDesktopIcon: 翻页后前台变为 $curPkg（非 launcher），HOME 回桌面并换方向")
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                    delay(500)
                    return@repeat
                }
                // 翻页后再试
                val root = service.rootInActiveWindow
                if (root != null) {
                    for (label in labels) {
                        if (tryClickIconByLabel(root, label)) return true
                    }
                }
                // 判断"划不动"：连续两次签名一样，说明已到边界
                val sig = launcherSignature()
                if (sig != null && sig == lastSignature) {
                    Logger.d(TAG, "clickDesktopIcon: 签名未变（sig=$sig），认为已到边界，换方向")
                    return@repeat
                }
                lastSignature = sig
            }
        }
        Logger.w(TAG, "clickDesktopIcon: 已双向翻遍桌面仍未找到 labels=$labels")
        return false
    }

    /** 判断某个包名是否是桌面 launcher。 */
    private fun isLauncherPkg(pkg: String): Boolean {
        return pkg.contains("launcher", ignoreCase = true) ||
            pkg == "com.miui.home" ||
            pkg == "com.mi.android.globallauncher"
    }

    /** 取当前桌面上首个可见图标的文本作为"当前屏签名"，用于判断"再划一次是否还在同一屏"。 */
    private fun launcherSignature(): String? {
        val root = service.rootInActiveWindow ?: return null
        val sb = StringBuilder()
        collectFirstLabels(root, sb, budget = intArrayOf(6))
        return sb.toString().ifEmpty { null }
    }

    private fun collectFirstLabels(node: AccessibilityNodeInfo?, sb: StringBuilder, budget: IntArray) {
        if (node == null || budget[0] <= 0) return
        if (node.isVisibleToUser) {
            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()
            val v = when {
                !text.isNullOrBlank() -> text
                !desc.isNullOrBlank() -> desc
                else -> null
            }
            if (v != null) {
                sb.append(v).append('|')
                budget[0]--
            }
        }
        for (i in 0 until node.childCount) {
            if (budget[0] <= 0) return
            collectFirstLabels(node.getChild(i), sb, budget)
        }
    }

    /** 在当前 root 里按 text/desc 精确/包含匹配尝试点击一个 label。命中并点击成功返回 true。 */
    private suspend fun tryClickIconByLabel(root: AccessibilityNodeInfo, label: String): Boolean {
        // 1) 按 text 精确匹配
        val byText = try { root.findAccessibilityNodeInfosByText(label) } catch (_: Throwable) { null }
        byText?.firstOrNull { it.text?.toString() == label && it.isVisibleToUser }?.let { hit ->
            if (performClickWithFallback(hit, label, "text 精确")) return true
        }
        // 2) 按 contentDescription 精确
        val descHits = mutableListOf<AccessibilityNodeInfo>()
        collectByDescription(
            node = root,
            target = label,
            onHit = { _: AccessibilityNodeInfo, origin: AccessibilityNodeInfo, _: Boolean ->
                descHits.add(origin)
            }
        )
        descHits.firstOrNull { it.contentDescription?.toString() == label && it.isVisibleToUser }?.let { hit ->
            if (performClickWithFallback(hit, label, "desc 精确")) return true
        }
        // 3) 按 contentDescription 包含
        descHits.firstOrNull { it.isVisibleToUser }?.let { hit ->
            if (performClickWithFallback(hit, label, "desc 包含")) return true
        }
        return false
    }

    /** 在屏幕中部横向滑一次；rightToLeft=true 表示手指从右向左滑（切到下一页）。 */
    private suspend fun swipeHorizontally(rightToLeft: Boolean, durationMs: Long = 300): Boolean {
        val dm = service.resources.displayMetrics
        val y = dm.heightPixels * 0.5f
        val xStart: Float
        val xEnd: Float
        if (rightToLeft) {
            xStart = dm.widthPixels * 0.85f
            xEnd = dm.widthPixels * 0.15f
        } else {
            xStart = dm.widthPixels * 0.15f
            xEnd = dm.widthPixels * 0.85f
        }
        val path = Path().apply {
            moveTo(xStart, y)
            lineTo(xEnd, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return suspendCoroutine { cont ->
            val dispatched = service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) { cont.resume(true) }
                    override fun onCancelled(g: GestureDescription?) { cont.resume(false) }
                },
                null
            )
            if (!dispatched) cont.resume(false)
        }
    }

    /** 优先 ACTION_CLICK；祖先或自身都不可点击时降级为坐标 tap。 */
    private suspend fun performClickWithFallback(
        node: AccessibilityNodeInfo,
        label: String,
        matchedBy: String,
    ): Boolean {
        val clickable = if (node.isClickable) node else firstClickableAncestor(node)
        if (clickable != null) {
            val ok = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Logger.d(TAG, "clickDesktopIcon: 已点击 '$label' via $matchedBy(ACTION_CLICK) ok=$ok")
            if (ok) return true
        }
        // 兜底：拿节点中心坐标 tap
        val rect = Rect().also { node.getBoundsInScreen(it) }
        if (rect.width() <= 0 || rect.height() <= 0) {
            Logger.w(TAG, "clickDesktopIcon: '$label' via $matchedBy 无有效 bounds，跳过")
            return false
        }
        val cx = rect.exactCenterX()
        val cy = rect.exactCenterY()
        Logger.d(TAG, "clickDesktopIcon: 降级 tap '$label' via $matchedBy at ($cx,$cy) bounds=$rect")
        return tapAt(cx, cy)
    }

    /**
     * 尝试自动关闭 MIUI 安全中心的拦截弹窗（"允许后台弹出界面？"这类）。
     * 命中按钮文本时点击并返回 true；找不到返回 false。
     */
    private fun tryDismissMiuiPermissionDialog(): Boolean {
        // MIUI 弹窗常见按钮文案，按优先级尝试
        val positiveTexts = listOf("允许", "始终允许", "本次允许", "继续", "同意", "确定")
        val root = service.rootInActiveWindow ?: return false
        for (text in positiveTexts) {
            val hits = try { root.findAccessibilityNodeInfosByText(text) } catch (_: Throwable) { null } ?: continue
            for (m in hits) {
                if (m.text?.toString() != text) continue  // 精确匹配，避免点到"不允许"
                val clickable = firstClickableAncestor(m) ?: continue
                val ok = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Logger.d(TAG, "tryDismissMiuiPermissionDialog: 点击 '$text' ok=$ok")
                if (ok) return true
            }
        }
        return false
    }

    private fun dumpTopWindow(prefixTag: String) {
        try {
            val active = service.rootInActiveWindow
            val sb = StringBuilder()
            sb.append("[$prefixTag] activePkg=").append(active?.packageName).append("\n")
            val all = mutableListOf<AccessibilityNodeInfo>()
            active?.let { all.add(it) }
            try {
                service.windows?.forEach { w ->
                    w.root?.let { if (all.none { r -> r == it }) all.add(it) }
                }
            } catch (_: Throwable) { }
            all.forEachIndexed { idx, r ->
                sb.append("--- window#").append(idx).append(" pkg=").append(r.packageName).append(" ---\n")
                dumpNode(r, 0, sb, maxDepth = 16, maxLines = intArrayOf(120))
            }
            Logger.d(TAG, sb.toString())
        } catch (t: Throwable) {
            Logger.w(TAG, "dumpTopWindow 失败: ${t.message}")
        }
    }

    private fun dumpNode(
        node: AccessibilityNodeInfo?,
        depth: Int,
        sb: StringBuilder,
        maxDepth: Int,
        maxLines: IntArray
    ) {
        if (node == null || depth > maxDepth || maxLines[0] <= 0) return
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        if (!text.isNullOrBlank() || !desc.isNullOrBlank() || node.isClickable) {
            sb.append("  ".repeat(depth))
                .append(node.className)
                .append(" clickable=").append(node.isClickable)
                .append(" text=").append(text)
                .append(" desc=").append(desc)
                .append("\n")
            maxLines[0] = maxLines[0] - 1
        }
        for (i in 0 until node.childCount) {
            dumpNode(node.getChild(i), depth + 1, sb, maxDepth, maxLines)
            if (maxLines[0] <= 0) return
        }
    }

    private suspend fun waitForNode(
        timeoutMs: Long,
        finder: (AccessibilityNodeInfo) -> AccessibilityNodeInfo?
    ): AccessibilityNodeInfo? {
        val start = System.currentTimeMillis()
        var nullRootTicks = 0
        while (System.currentTimeMillis() - start < timeoutMs) {
            val root = service.rootInActiveWindow
            if (root == null) {
                // 页面切换的过渡瞬间可能为 null，不要立即退出，继续轮询
                nullRootTicks++
                if (nullRootTicks % 5 == 1) {
                    Logger.d(TAG, "waitForNode: rootInActiveWindow == null，继续等待 tick=$nullRootTicks")
                }
                delay(300)
                continue
            }
            val node = finder(root)
            if (node != null) return node
            delay(300)
        }
        Logger.d(TAG, "waitForNode 超时 ${timeoutMs}ms")
        return null
    }

    private suspend fun waitForCondition(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val start = System.currentTimeMillis()
        var tick = 0
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (condition()) return true
            tick++
            // 每 3 tick（~900ms）打一次心跳，避免"没日志=协程死了"和"没日志=condition 一直false"混淆
            if (tick % 3 == 0) {
                Logger.d(TAG, "waitForCondition: tick=$tick elapsed=${System.currentTimeMillis() - start}ms timeout=${timeoutMs}ms")
            }
            delay(300)
        }
        Logger.d(TAG, "waitForCondition: 超时 ${timeoutMs}ms")
        return false
    }

    private suspend fun takeScreenshotAndReply() {
        val imageFile: File? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Logger.d(TAG, "takeScreenshot: 通过无障碍截屏")
            suspendCoroutine<File?> { cont ->
                service.takeScreenshot(
                    android.view.Display.DEFAULT_DISPLAY,
                    service.mainExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                            try {
                                val bitmap = android.graphics.Bitmap.wrapHardwareBuffer(
                                    result.hardwareBuffer, result.colorSpace
                                )
                                val file = screenshotHelper.saveAndCompress(bitmap)
                                Logger.i(TAG, "截屏保存: ${file?.absolutePath}")
                                result.hardwareBuffer.close()
                                cont.resume(file)
                            } catch (t: Throwable) {
                                Logger.e(TAG, "截屏保存失败: ${t.message}", t)
                                cont.resume(null)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            Logger.e(TAG, "截屏 onFailure errorCode=$errorCode")
                            cont.resume(null)
                        }
                    }
                )
            }
        } else {
            Logger.w(TAG, "系统版本 ${Build.VERSION.SDK_INT} < R，无法通过无障碍截屏，跳过截屏")
            null
        }
        // 在同一协程内顺序执行回复流程，异常仍会被 execute() 的 catch 捕获
        try {
            replyWithQQ(imageFile)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Logger.e(TAG, "replyWithQQ 异常：${t.message}", t)
        }
    }

    private suspend fun replyWithQQ(imageFile: File?) {
        Logger.d(TAG, "replyWithQQ: 分享到 QQ target=$targetChat imageFile=${imageFile?.absolutePath}")

        if (imageFile == null) {
            Logger.w(TAG, "无截图文件，跳过 QQ 分享")
            return
        }

        // ==============================================================
        // MIUI 后台启动限制的多级绕过策略
        // ==============================================================
        // 从无障碍/前台服务 startActivity(第三方 App) 会被 MIUI 拦，且未锁屏时
        // fullScreenIntent 也只显示为普通通知不自动跳 Activity。
        // 因此按如下顺序尝试，任一成功就停：
        //   A. HOME → service.startActivity(RelayLaunchActivity)   —— 桌面场景 KaKa 自启
        //   B. HOME → 桌面上模拟点击 KaKa 图标 → SettingsActivity.onCreate 消费 PendingShare
        //   C. fullScreenIntent 拉 Relay 兜底
        //   D. 桌面上模拟点击 QQ 图标 —— 只能把 QQ 拉起，用户手动分享兜底
        // ==============================================================
        val relayIntent = Intent(service, com.newolf.kaka.RelayLaunchActivity::class.java).apply {
            action = com.newolf.kaka.RelayLaunchActivity.ACTION_OPEN_QQ_AND_SEND_IMAGE
            putExtra(com.newolf.kaka.RelayLaunchActivity.EXTRA_IMAGE_PATH, imageFile.absolutePath)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        // A：先 HOME 让当前前台 App 退出，再直接 service.startActivity(Relay)
        try {
            val homeOk = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            Logger.d(TAG, "回到桌面 GLOBAL_ACTION_HOME ok=$homeOk")
            waitForCondition(2000) {
                val pkg = service.rootInActiveWindow?.packageName?.toString()
                pkg != null && pkg != "com.ss.android.lark"
            }
            service.startActivity(relayIntent)
            Logger.d(TAG, "路径 A：已发起 service.startActivity(Relay)")
        } catch (t: Throwable) {
            Logger.w(TAG, "路径 A：HOME + startActivity(Relay) 抛异常: ${t.message}", t)
        }
        var relayReady = waitForCondition(3500) {
            val pkg = service.rootInActiveWindow?.packageName?.toString()
            pkg == service.packageName ||
                pkg == "com.tencent.mobileqq" ||
                pkg == "com.android.intentresolver" ||
                pkg == "android"
        }
        Logger.d(TAG, "路径 A 结果 relayReady=$relayReady pkg=${service.rootInActiveWindow?.packageName}")

        // B：桌面上找 KaKa 图标并点击（用户点击图标不受后台启动限制）
        if (!relayReady) {
            Logger.i(TAG, "路径 A 未生效，改走路径 B：模拟点击桌面上的 KaKa 图标")
            com.newolf.kaka.PendingShare.put(imageFile.absolutePath)
            val clickedKaka = clickDesktopIcon(listOf("KaKa"))
            Logger.d(TAG, "路径 B：点击 KaKa 图标 ok=$clickedKaka")
            if (clickedKaka) {
                relayReady = waitForCondition(4000) {
                    val pkg = service.rootInActiveWindow?.packageName?.toString()
                    pkg == service.packageName ||
                        pkg == "com.tencent.mobileqq" ||
                        pkg == "com.android.intentresolver" ||
                        pkg == "android"
                }
                Logger.d(TAG, "路径 B 结果 relayReady=$relayReady pkg=${service.rootInActiveWindow?.packageName}")
            }
            if (!relayReady) {
                // 若 B 没能触发 SettingsActivity.onCreate 消费，就清掉待分享状态，避免下次 UI 打开时错误分享
                com.newolf.kaka.PendingShare.consume()
            }
        }

        // C：fullScreenIntent 兜底
        if (!relayReady) {
            Logger.i(TAG, "路径 B 未生效，改走路径 C：fullScreenIntent")
            val ok = launchRelayViaFullScreenIntent(relayIntent)
            Logger.d(TAG, "路径 C：fullScreenIntent 发送 ok=$ok")
            relayReady = waitForCondition(4000) {
                val pkg = service.rootInActiveWindow?.packageName?.toString()
                pkg == service.packageName ||
                    pkg == "com.tencent.mobileqq" ||
                 pkg == "com.android.intentresolver" ||
                    pkg == "android"
            }
            Logger.d(TAG, "路径 C 结果 relayReady=$relayReady pkg=${service.rootInActiveWindow?.packageName}")
        }

        // D：最后兜底——桌面点 QQ 图标把 QQ 拉起，让用户手动分享
        if (!relayReady) {
            Logger.w(TAG, "路径 A/B/C 均未生效，走路径 D：模拟点击桌面上的 QQ 图标（仅打开 QQ）")
            val clickedQQ = clickDesktopIcon(listOf("QQ"))
            Logger.d(TAG, "路径 D：点击 QQ 图标 ok=$clickedQQ")
        }

        // 等待分享面板 / QQ 联系人选择器出现
        Logger.d(TAG, "等待 QQ 分享面板加载 target=$targetChat")
        // 观察前台 pkg，判断走到了哪种路径。5s 通常足够；QQ 起来一般在 1~2s
        val pathReady = waitForCondition(5000) {
            val pkg = service.rootInActiveWindow?.packageName
            pkg == "com.tencent.mobileqq" || pkg == "com.android.intentresolver" || pkg == "android"
        }
        Logger.d(TAG, "分享面板加载结果 ready=$pathReady pkg=${service.rootInActiveWindow?.packageName}")
        if (!pathReady) {
            Logger.w(TAG, "分享面板/QQ 未在预期时间内出现，跳过后续。可能 MIUI 拦截了 startActivity。")
            return
        }

        // 智能跳过：QQ 分享 Intent 我们用了 component=JumpActivity 直连（见 RelayLaunchActivity.sendImageToQQ），
        // 系统不会再弹 IntentResolver 分享面板；直接落到 QQ 内部（前台 pkg=com.tencent.mobileqq）。
        // 只要前台已经是 QQ，就必须**跳过** handleShareTargetPicker/handleJustOnceDialog：
        // 那两个函数用文本 fuzzy 匹配"好友"、"仅一次"，在 QQ 首屏会误点"好友" tab / 首页
        // "分享给好友"按钮，导致 QQ 状态被打乱，最终发不出去（联系人列表进不去 / 目标搜不到）。
        val curPkg = service.rootInActiveWindow?.packageName?.toString()
        val alreadyInQQ = curPkg == "com.tencent.mobileqq"
        if (alreadyInQQ) {
            Logger.d(TAG, "已在 QQ 内（pkg=$curPkg），跳过系统分享面板 + '仅一次'处理，直接选联系人")
        } else {
            // 只有当 QQ 未被直接拉起、系统弹出 IntentResolver 时才走这两个
            handleShareTargetPicker()
            handleJustOnceDialog()
        }

        // 至此 QQ 应该已弹出联系人选择器
        Logger.d(TAG, "步骤 3: 等待 QQ 联系人选择器 target=$targetChat")
        try {
            // 3.1 先等分享 UI 真正加载完成（QQ 分享 Activity 顶部会出现"发送给"/"最近聊天"/"选择"/"联系人"等标题字样）。
            //     不等就直接 findByText(targetChat) 时，容易命中 QQ 首页的其他控件（如拼音索引"L"、某个 tab 标签）。
            val sharePanelReady = waitForCondition(6000) {
                val root = service.rootInActiveWindow ?: return@waitForCondition false
                val markers = listOf("发送给", "最近聊天", "最近联系", "选择联系人", "选择好友", "联系人")
                markers.any { marker ->
                    try {
                        root.findAccessibilityNodeInfosByText(marker)
                            ?.any { it.isVisibleToUser } == true
                    } catch (_: Throwable) { false }
                }
            }
            Logger.d(TAG, "步骤 3.1: 分享 UI 特征就绪 ready=$sharePanelReady")
            if (!sharePanelReady) {
                dumpTopWindow(prefixTag = "share-panel-not-ready")
                Logger.w(TAG, "分享面板未就绪，跳过发送")
                return
            }

            // 3.2 找联系人：**text 精确匹配 targetChat**，或 **contentDescription 以 "{targetChat}," 开头**
            //     （pad/新版 QQ 会话行 text=null，仅在 desc 里拼接 "昵称, ,N条未读,最近一句,时间"，
            //     所以只用 text== 会全部超时；用 desc.startsWith 保证 "L" 只命中 "L, ..."，不误伤 "李四, ..."）。
            //     同时必须是可点击节点或有可点击祖先，避免误点 QQ 首页的拼音索引/tab 等非联系人条目。
            val chatNode = waitForNode(4000) { root ->
                val hits = try {
                    root.findAccessibilityNodeInfosByText(targetChat)
                } catch (_: Throwable) { null } ?: return@waitForNode null
                // 打印一次候选，方便诊断
                if (hits.isNotEmpty()) {
                    val summary = hits.take(8).joinToString(" | ") { n ->
                        val r = Rect().also { n.getBoundsInScreen(it) }
                        "text='${n.text}' desc='${n.contentDescription}' clickable=${n.isClickable} bounds=$r"
                    }
                    Logger.d(TAG, "步骤 3.2 候选(${hits.size}): $summary")
                }
                // 命中判定：
                //  - text 严格 == targetChat，或
                //  - desc 严格 == targetChat（例如"最近聊天"横向头像栏，desc 仅昵称）
                //  - desc 以 "targetChat + 分隔符" 开头（QQ 语义化拼接："NeWolf, ,3条未读,..."；
                //    QQ 会同时使用半角"," 和全角"，"，且"当前聊天"提示条常见）
                //  分隔符是关键——避免 "L" 命中 "Linus, ..." 这类前缀撞名。
                //
                // 关键教训：QQ 分享面板里联系人行、"当前聊天"提示条大多 clickable=false，
                // 只有顶层 FrameLayout 是根级 clickable=true（我们不能点根）。因此这里**不**再要求
                // 节点本身或祖先 clickable=true，命中后统一交给下面的坐标 tap 兜底点击。
                val descPrefixes = listOf(
                    "$targetChat,",   // 半角逗号
                    "$targetChat，",  // 全角逗号
                    "$targetChat "    // 空格
                )
                val strict = hits.filter { n ->
                    if (!n.isVisibleToUser) return@filter false
                    val nText = n.text?.toString()
                    val nDesc = n.contentDescription?.toString()
                    val textMatch = nText == targetChat
                    val descMatch = nDesc != null &&
                        (nDesc == targetChat || descPrefixes.any { nDesc.startsWith(it) })
                    textMatch || descMatch
                }
                // 挑选优先级（关键：desc 含"当前聊天"的候选**不能**当作 targetChat 命中——
                // 它是 QQ 分享面板的"继续发送到上一次分享的会话"提示条，恰好可能显示目标昵称，
                // 但点它不保证发到 targetChat；真正的联系人 tile 是"最近聊天"横向头像栏里的
                // 头像块 / 联系人列表行）：
                //  a) 排除"当前聊天"后，**取 top 最小的**（视觉上最靠上的）候选。
                //     QQ 分享面板从上到下的分区顺序固定：搜索栏 → 最近转发（横向头像栏）→
                //     最近聊天列表 → 联系人列表。**"最近转发"里有就直接点它**，不要再去下面的
                //     列表里挑——列表里的行常常是 744×6 之类的空 label / 折叠节点，tap 中心点不到
                //     有效 UI。同时**明确不要求 clickable=true**——QQ 分享面板里几乎所有 item 的
                //     clickable 都是 false（真正的点击响应挂在根级 FrameLayout 上），强行要求
                //     clickable 反而会漏命中；命中后统一交给下游的坐标 tap 兜底。
                //  b) 兜底：仍找不到时才允许"当前聊天"提示条（等价于"发到最近一次的会话"）。
                val nonCurrent = strict.filter { n ->
                    val d = n.contentDescription?.toString().orEmpty()
                    !d.contains("当前聊天")
                }                // **不要求 clickable=true**：QQ 分享面板里几乎所有 item 的 clickable 都是 false
                // （点击响应挂在根级 FrameLayout 上），强行要求 clickable 会漏命中；命中后交给下游 tap 兜底。
                // **取 top 最小的**（视觉上最靠上的）候选——QQ 分享面板从上到下固定是：
                // 搜索栏 → 最近转发（横向头像栏）→ 最近聊天列表 → 联系人列表。
                // "最近转发"里有就直接用它，不再去下面的列表里挑（列表里的候选常常是 744×6 之类
                // 的空 label / 折叠节点，tap 中心点不到有效 UI）。
                val dm = service.resources.displayMetrics
                val sw = dm.widthPixels
                val sh = dm.heightPixels
                nonCurrent.filter { n ->
                    val r = Rect().also { n.getBoundsInScreen(it) }
                    r.width() > 0 && r.height() > 0 &&
                        r.left >= 0 && r.top >= 0 &&
                        r.right <= sw && r.bottom <= sh &&
                        r.width() < sw
                }.minByOrNull { n ->
                    val r = Rect().also { n.getBoundsInScreen(it) }
                    r.top
                }
                    ?: strict.firstOrNull { n ->
                        val d = n.contentDescription?.toString().orEmpty()
                        d.contains("当前聊天")
                    }
            }
            if (chatNode == null) {
                dumpTopWindow(prefixTag = "chat-node-not-found")
                Logger.w(TAG, "在 QQ 分享面板未找到目标：$targetChat；尝试兜底点击'最近转发'第一个")
                // 兜底：找"最近转发"标题节点，取其正下方最近的一个可视节点当作第一个头像 tap。
                // "最近转发"是 QQ 分享面板顶部横向栏的分区标题（见截图），下面第一个头像即最近一次转发对象。
                val fallbackOk = tryTapFirstRecentForward()
                if (!fallbackOk) {
                    Logger.w(TAG, "'最近转发'兜底也未命中；请手动完成发送")
                    return
                }
                // 兜底后同样等 QQ 弹出发送确认
                delay(1800)
                // 继续走 3.3 找发送按钮
            } else {
                val chatBounds = Rect().also { chatNode.getBoundsInScreen(it) }
                // **忽略 clickable=false，直接坐标 tap chatBounds 中心**。
                // 教训：QQ 分享面板里几乎所有联系人 item 的 clickable 都是 false（点击响应挂在
                // 根级 FrameLayout 上），先试 performClick 只会浪费一次调用还得等回调；直接按
                // chatBounds 中心坐标 tap 才是通用解。chatBounds 是无障碍树里当前实际命中节点
                // 的位置，比任何硬编码坐标都稳。
                if (chatBounds.width() > 0 && chatBounds.height() > 0) {
                    val cx = chatBounds.exactCenterX()
                    val cy = chatBounds.exactCenterY()
                    Logger.i(TAG, "点击联系人 target=$targetChat bounds=$chatBounds 坐标 tap ($cx,$cy) duration=150")
                    tapAt(cx, cy, durationMs = 150)
                    // 二次兜底：若 1s 后仍在"选择聊天"页（QQ 忽略了首次 tap，例如小头像点太短），
                    // 换一个稍长的 duration 再点一次同一坐标。
                    delay(1000)
                    val stillOnPicker = try {
                        val r = service.rootInActiveWindow
                        r?.let {
                            val d = mutableListOf<AccessibilityNodeInfo>()
                            collectNodesByDesc(it, setOf("选择聊天"), d)
                            d.any { n -> n.isVisibleToUser }
                        } ?: false
                    } catch (_: Throwable) { false }
                    if (stillOnPicker) {
                        Logger.d(TAG, "首次 tap 后仍在'选择聊天'页，二次 tap 兜底 ($cx,$cy) duration=250")
                        tapAt(cx, cy, durationMs = 250)
                    }
                } else {
                    Logger.w(TAG, "点击联系人：chatBounds 无效 $chatBounds，跳过 tap")
                }
                // 等 QQ 弹出"发送给 X？"的确认弹窗。pad 上动画+异步渲染更慢，1800ms 更稳。
                delay(1800)
            }

            // 3.3 找发送按钮：只接受 text 精确等于"发送"或"确定"；**不再接受单字"发"**，
            //     否则会命中"发红包/发消息"等无关按钮，把 QQ 状态搞乱。
            //     补充：pad/RecyclerView 里可能挂载离屏的"发送"按钮节点（bounds y 远超屏高），
            //           `isVisibleToUser` 有时也不准；这里必须**硬约束 bounds 在屏幕内**，
            //           并把候选打成日志以便诊断。
            val dm = service.resources.displayMetrics
            val screenW = dm.widthPixels
            val screenH = dm.heightPixels
            val confirmBtn = waitForNode(6000) { root ->
                val allHits = mutableListOf<AccessibilityNodeInfo>()
                // 1) 按 text 精确等于"发送"/"确定"搜（走 QQ 内部 findByText 索引）
                for (t in listOf("发送", "确定")) {
                    val hits = try { root.findAccessibilityNodeInfosByText(t) } catch (_: Throwable) { null }
                    if (hits != null) allHits.addAll(hits.filter { it.text?.toString() == t })
                }
                // 2) 递归遍历整树，按 contentDescription 精确等于"发送"/"确定"补充候选。
                //    原因：QQ 底部"发送给 X"确认弹窗里的蓝色"发送"按钮多为自绘 View，text=null，
                //    只有 desc="发送"；只用 findByText 会全部漏掉，导致 waitForNode 6s 超时。
                collectNodesByDesc(root, setOf("发送", "确定"), allHits)
                if (allHits.isNotEmpty()) {
                    val summary = allHits.take(8).joinToString(" | ") { n ->
                        val r = Rect().also { n.getBoundsInScreen(it) }
                        "text='${n.text}' desc='${n.contentDescription}' visible=${n.isVisibleToUser} clickable=${n.isClickable} bounds=$r"
                    }
                    Logger.d(TAG, "3.3 '发送/确定'候选(${allHits.size}): $summary screen=${screenW}x${screenH}")
                }
                allHits.firstOrNull { n ->
                    val r = Rect().also { n.getBoundsInScreen(it) }
                    // 硬约束：bounds 必须完全在屏幕内且有正宽正高
                    val onScreen = r.width() > 0 && r.height() > 0 &&
                        r.left >= 0 && r.top >= 0 &&
                        r.right <= screenW && r.bottom <= screenH
                    onScreen && n.isVisibleToUser
                }
            }
            if (confirmBtn == null) {
                dumpTopWindow(prefixTag = "confirm-btn-not-found")
                Logger.w(TAG, "分享面板未找到'发送/确定'按钮（可能都在离屏 RecyclerView 缓存里）")
                // pad 兜底：QQ 分享确认按钮通常固定在屏幕右下角（约屏宽 90%, 屏高 92% 附近）。
                // 只在**平板**上做这个兜底 tap；手机上按钮通常都能拿到节点，不用瞎点。
                if (com.newolf.kaka.util.DeviceUtils.isTablet(service)) {
                    val fx = screenW * 0.93f
                    val fy = screenH * 0.92f
                    Logger.w(TAG, "3.3 [pad] 兜底坐标 tap 右下角 ($fx,$fy) screen=${screenW}x${screenH}")
                    tapAt(fx, fy, durationMs = 120)
                    delay(800)
                }
            } else {
                val btnBounds = Rect().also { confirmBtn.getBoundsInScreen(it) }
                // 依次尝试：
                //   1) confirmBtn 自身 performClick
                //   2) 各级 clickable 祖先 performClick（QQ 部分自绘按钮 clickable=true 只是 semantics 声明，
                //      真正 onClick 挂在外层容器）
                //   3) 坐标 tap（更长时长 60ms，接近真人短按 + 二次 tap 兜底）
                var ok = false
                if (confirmBtn.isClickable) {
                    ok = confirmBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Logger.i(TAG, "点击'发送' [self] target=$targetChat bounds=$btnBounds performClick=$ok")
                }
                if (!ok) {
                    // 冒泡尝试 clickable 祖先（最多 6 层）
                    var cur: AccessibilityNodeInfo? = confirmBtn.parent
                    var lvl = 0
                    while (cur != null && lvl < 6 && !ok) {
                        if (cur.isClickable) {
                            ok = cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            val pRect = Rect().also { cur!!.getBoundsInScreen(it) }
                            Logger.i(TAG, "点击'发送' [ancestor lvl=$lvl] cls=${cur.className} bounds=$pRect performClick=$ok")
                            if (ok) break
                        }
                        cur = cur.parent
                        lvl++
                    }
                }
                if (!ok && btnBounds.width() > 0 && btnBounds.height() > 0 &&
                    btnBounds.right <= screenW && btnBounds.bottom <= screenH) {
                    // 3) 坐标 tap 兜底。QQ 部分自绘按钮吃 60ms 的短按更稳；
                    //    单点 tap 若无效，会 delay 后再尝试一次。
                    Logger.d(TAG, "'发送' performClick 全部失败，坐标 tap 兜底 $btnBounds")
                    val cx = btnBounds.exactCenterX()
                    val cy = btnBounds.exactCenterY()
                    val tapOk1 = tapAt(cx, cy, durationMs = 60)
                    Logger.d(TAG, "'发送' 兜底 tap#1 ok=$tapOk1 duration=60ms")
                    delay(700)
                    val stillHere = service.rootInActiveWindow?.findAccessibilityNodeInfosByText("发送")
                        ?.any { it.text?.toString() == "发送" && it.isVisibleToUser } == true
                    if (stillHere) {
                        // 二次 tap：换个更长的 duration（180ms 长按）—— 部分 QQ 版本对短按无反应但对长按稍长的手势有效
                        Logger.d(TAG, "'发送' 仍可见，二次 tap 兜底 duration=180ms")
                        val tapOk2 = tapAt(cx, cy, durationMs = 180)
                        Logger.d(TAG, "'发送' 兜底 tap#2 ok=$tapOk2")
                        delay(700)
                        val stillHere2 = service.rootInActiveWindow?.findAccessibilityNodeInfosByText("发送")
                            ?.any { it.text?.toString() == "发送" && it.isVisibleToUser } == true
                        if (stillHere2) {
                            Logger.w(TAG, "'发送' 两次坐标 tap 后仍可见，可能 QQ 按钮真的不吃手势事件；建议手动确认发送")
                            dumpTopWindow(prefixTag = "send-btn-not-responsive")
                        } else {
                            Logger.d(TAG, "'发送' 按钮二次 tap 后已消失，视为已发送")
                        }
                    } else {
                        Logger.d(TAG, "'发送' 按钮已消失，视为已发送")
                    }
                }
                // 发送后短暂等待动画，避免立刻 HOME 造成视觉突兀
                delay(800)
            }
        } finally {
            // 无论成功/失败，都把前台退回桌面，避免残留在 QQ 上打扰用户；
            // 若支持则再触发一次锁屏（API 28+ GLOBAL_ACTION_LOCK_SCREEN），恢复到"待机"状态。
            Logger.d(TAG, "replyWithQQ 收尾：返回桌面 + 尝试息屏")
            val homeOk = try {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            } catch (t: Throwable) {
                Logger.w(TAG, "GLOBAL_ACTION_HOME 失败: ${t.message}")
                false
            }
            Logger.d(TAG, "收尾: HOME ok=$homeOk")
            // 息屏之前给一小段停留，避免动画未落完就锁屏
            delay(500)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val lockOk = try {
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                } catch (t: Throwable) {
                    Logger.w(TAG, "GLOBAL_ACTION_LOCK_SCREEN 失败: ${t.message}")
                    false
                }
                Logger.d(TAG, "收尾: LOCK_SCREEN ok=$lockOk")
            } else {
                Logger.d(TAG, "收尾: 当前 API<28 不支持 GLOBAL_ACTION_LOCK_SCREEN，跳过息屏")
            }
        }
    }


//    /**
//     * 处理系统级"分享方式选择器"。
//     * 分享 image/* 时，如果 setPackage("com.tencent.mobileqq") 后 QQ 有多个可接收 activity
//     * （如"QQ 好友"、"QQ 空间"、"发到我的电脑"），会先弹一个选择器。
//     * 优先点"发送给好友"/"QQ 好友"这类目标；找不到会 dump 一次帮助定位。
//     */
    private suspend fun handleShareTargetPicker() {
        Logger.d(TAG, "handleShareTargetPicker: 进入")
        val candidates = listOf(
            "发送给好友", "发送给 QQ 好友", "发送到 QQ 好友", "发送给QQ好友",
            "分享给好友", "分享给 QQ 好友", "分享到 QQ 好友",
            "QQ 好友", "QQ好友", "好友"
        )
        // 分享用 component=JumpActivity 直连时通常不会出现该面板；短超时即可
        val node = waitForNode(800) { root ->
            candidates.firstNotNullOfOrNull { t ->
                root.findAccessibilityNodeInfosByText(t)
                    .firstOrNull { it.isVisibleToUser }
            }
        }
        if (node == null) {
            Logger.d(TAG, "handleShareTargetPicker: 未出现分享方式选择器（预期路径）")
            return
        }
        val clickable = if (node.isClickable) node else firstClickableAncestor(node)
        if (clickable == null) {
            Logger.w(TAG, "handleShareTargetPicker: 找到目标节点但没有可点击祖先")
            return
        }
        val ok = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Logger.d(TAG, "handleShareTargetPicker: 已点击分享目标 text=${node.text} desc=${node.contentDescription} ok=$ok")
        delay(500)
    }

    /**
     * 系统的"仅本次 / 总是"弹窗（IntentResolver "Just once / Always"）。
     * 用户选择"仅本次"更保守——每次都会重新弹选择器，但避免误关联。
     */
    private suspend fun handleJustOnceDialog() {
        Logger.d(TAG, "handleJustOnceDialog: 进入")
        val candidates = listOf(
            "仅一次", "仅本次", "仅此一次", "本次", "只此一次",
            "Just once", "JUST ONCE", "Just Once"
        )
        // 直连 JumpActivity 也不会触发这个系统级选择器；短超时即可
        val node = waitForNode(500) { root ->
            candidates.firstNotNullOfOrNull { t ->
                root.findAccessibilityNodeInfosByText(t)
                    .firstOrNull { it.isVisibleToUser && it.text?.toString() == t }
            }
        }
        if (node == null) {
            Logger.d(TAG, "handleJustOnceDialog: 未出现'仅一次/总是'弹窗（预期路径）")
            return
        }
        val clickable = if (node.isClickable) node else firstClickableAncestor(node)
        val ok = clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
        Logger.d(TAG, "handleJustOnceDialog: 已点击'${node.text}' ok=$ok")
        delay(500)
    }

    /**
     * 用包名启动 QQ（getLaunchIntentForPackage）。
     * 说明：MIUI 对"后台启动 Activity"有限制，但需要 KaKa 手动开好以下权限：
     *  1) 应用管理 → 权限管理 → 后台弹出界面：允许
     *  2) 应用管理 → 权限管理 → 显示悬浮窗：允许
     *  3) 省电与电池 → 应用配置 → KaKa：无限制
     * 权限齐全后，本方法就能正常把 QQ 切前台。
     */
    private suspend fun bringQQToFront(): Boolean {
        val intent = service.packageManager.getLaunchIntentForPackage("com.tencent.mobileqq")
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (intent == null) {
            Logger.e(TAG, "bringQQToFront: 未找到 QQ（com.tencent.mobileqq）")
            return false
        }
        try {
            service.startActivity(intent)
            Logger.d(TAG, "bringQQToFront: startActivity(QQ) 已下发")
        } catch (t: Throwable) {
            Logger.e(TAG, "bringQQToFront: startActivity 抛异常: ${t.message}", t)
            return false
        }

        // 等待 QQ 进入前台，最多 12s
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 12000) {
            val pkg = service.rootInActiveWindow?.packageName
            if (pkg == "com.tencent.mobileqq") return true
            // MIUI 弹窗兜底
            if (pkg == "com.miui.securitycenter" || pkg == "com.lbe.security.miui") {
                if (tryDismissMiuiPermissionDialog()) {
                    Logger.i(TAG, "已自动处理 MIUI 拦截弹窗 pkg=$pkg")
                }
            }
            if ((System.currentTimeMillis() - start) % 2000 < 400) {
                Logger.d(TAG, "等待 QQ 进入前台... 当前pkg=$pkg")
            }
            delay(300)
        }
        return false
    }
}
