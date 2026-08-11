package com.newolf.kaka

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.content.FileProvider
import com.newolf.kaka.util.Logger
import java.io.File

/**
 * 透明中转 Activity。
 *
 * 目的一：绕过 MIUI 对"从无障碍/前台服务后台 startActivity 拉起其他 app"的限制。
 * 无障碍服务先把本 Activity 拉起（拉起自己一般被系统允许），
 * 本 Activity 处于前台再启动 QQ / 发送 SEND intent，此时 MIUI 判定为"用户操作"不会拦截。
 *
 * 目的二：作为"锁屏之上出现"的入口，先亮屏 + 请求系统解锁弹窗，
 * 让 QQ 通知触发的自动打卡流程能在锁屏时也走通。
 *
 * 用法：
 *   Intent(context, RelayLaunchActivity::class.java).apply {
 *       action = RelayLaunchActivity.ACTION_OPEN_QQ_AND_SEND_IMAGE
 *       putExtra(EXTRA_IMAGE_PATH, "/sdcard/.../punch.jpg")  // 可选：要分享的图片
 *       addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
 *   }
 */
class RelayLaunchActivity : Activity() {

    companion object {
        private const val TAG = "Relay"
        const val ACTION_OPEN_QQ = "com.newolf.kaka.OPEN_QQ"
        const val ACTION_OPEN_QQ_AND_SEND_IMAGE = "com.newolf.kaka.OPEN_QQ_AND_SEND_IMAGE"
        /** 仅用于亮屏 + 请求系统弹解锁 UI，成功后立即 finish。 */
        const val ACTION_WAKE_AND_DISMISS_KEYGUARD = "com.newolf.kaka.WAKE_AND_DISMISS_KEYGUARD"
        const val EXTRA_IMAGE_PATH = "extra_image_path"

        const val QQ_PACKAGE = "com.tencent.mobileqq"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 关键：允许在锁屏之上显示，并在启动时点亮屏幕。
        // 这两个 API 在 API 27+ 生效，取代旧的 FLAG_SHOW_WHEN_LOCKED | FLAG_TURN_SCREEN_ON。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        Logger.i(TAG, "onCreate action=${intent?.action} extras=${intent?.extras?.keySet()}")
        try {
            when (intent?.action) {
                ACTION_OPEN_QQ -> {
                    dismissKeyguardIfNeeded()
                    openQQ()
                }
                ACTION_OPEN_QQ_AND_SEND_IMAGE -> {
                    dismissKeyguardIfNeeded()
                    val path = intent.getStringExtra(EXTRA_IMAGE_PATH)
                    if (path.isNullOrBlank()) {
                        Logger.w(TAG, "EXTRA_IMAGE_PATH 为空，退化为仅打开 QQ")
                        openQQ()
                    } else {
                        sendImageToQQ(File(path))
                    }
                }
                ACTION_WAKE_AND_DISMISS_KEYGUARD -> {
                    // 只做亮屏 + 请求解锁弹窗，后续由 PunchTaskExecutor 轮询等待 keyguard 解除
                    dismissKeyguardIfNeeded()
                }
                else -> {
                    Logger.w(TAG, "未识别的 action=${intent?.action}，直接结束")
                }
            }
        } catch (t: Throwable) {
            Logger.e(TAG, "转发失败: ${t.message}", t)
        }
        // WAKE_AND_DISMISS_KEYGUARD：短命，让系统 dismiss 弹起后就退，避免遮挡
        // 其它 action：延迟 1.2s finish，避免 Relay 尚未完全前台化就退出，
        // 导致后续 startActivity(QQ) 被系统重新判定为"后台启动"。
        val finishDelay = if (intent?.action == ACTION_WAKE_AND_DISMISS_KEYGUARD) 300L else 1200L
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!isFinishing) finish()
        }, finishDelay)
    }

    /**
     * 请求系统弹出"解锁"UI（若当前处于锁屏状态）。
     * - 无密码/滑动锁：系统会静默 dismiss，`onDismissSucceeded` 回调
     * - 有密码锁：系统会立即弹出输入 PIN/图案界面，用户输入后回调
     * - 已解锁：直接回调 `onDismissSucceeded`
     * 需要 DISABLE_KEYGUARD 权限（Manifest 已声明）。
     */
    private fun dismissKeyguardIfNeeded() {
        try {
            val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
            if (!km.isKeyguardLocked) {
                Logger.d(TAG, "dismissKeyguardIfNeeded: 未处于锁屏，跳过")
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() {
                        Logger.i(TAG, "requestDismissKeyguard: onDismissSucceeded")
                    }
                    override fun onDismissError() {
                        Logger.w(TAG, "requestDismissKeyguard: onDismissError")
                    }
                    override fun onDismissCancelled() {
                        Logger.w(TAG, "requestDismissKeyguard: onDismissCancelled（用户可能取消/超时）")
                    }
                })
            } else {
                @Suppress("DEPRECATION")
                km.newKeyguardLock("KaKa").disableKeyguard()
                Logger.d(TAG, "dismissKeyguardIfNeeded: 已调用 disableKeyguard（旧 API）")
            }
        } catch (t: Throwable) {
            Logger.w(TAG, "dismissKeyguardIfNeeded 失败: ${t.message}", t)
        }
    }

    private fun openQQ() {
        val intent = packageManager.getLaunchIntentForPackage(QQ_PACKAGE)
        if (intent == null) {
            Logger.e(TAG, "未找到 QQ")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
        Logger.i(TAG, "已启动 QQ")
    }

    private fun sendImageToQQ(imageFile: File) {
        if (!imageFile.exists()) {
            Logger.e(TAG, "图片文件不存在: ${imageFile.absolutePath}")
            return
        }
        val uri: Uri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            imageFile
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            // 直接指向 QQ 的分享入口 JumpActivity，跳过"QQ好友/空间/我的电脑"选择面板，
            // 由 QQ 自己弹联系人选择器，剩余的选联系人 + 点发送由无障碍继续处理。
            component = android.content.ComponentName(
                QQ_PACKAGE,
                "com.tencent.mobileqq.activity.JumpActivity"
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION and Intent.FLAG_ACTIVITY_NEW_TASK and
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            setPackage(QQ_PACKAGE)
        }
        try {
            startActivity(send)
            Logger.i(TAG, "已触发 QQ 图片分享 uri=$uri")
        } catch (t: Throwable) {
            Logger.e(TAG, "startActivity(SEND) 失败: ${t.message}", t)
        }
    }
}