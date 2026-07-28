package com.newolf.kaka.helper

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.newolf.kaka.util.Logger
import java.util.Calendar
import java.util.Locale

/**
 * 中等可见的胶囊悬浮窗，用于向系统证明 KaKa "有可见 UI"，
 * 降低被 MIUI/HyperOS App Freezer 冻结的概率，同时让用户能直观确认服务在跑。
 *
 * 设计：
 * - 尺寸约 60dp × 28dp 的圆角胶囊，深色半透明背景 + 白色文字（显示当前 HH:mm）
 * - 每 5 分钟自动刷新为最新的 "HH:mm"；对齐到下一个整 5 分钟边界，避免漂移
 * - 默认贴在屏幕右上角，向下偏移 status bar 的高度以避开刘海 / 时间
 * - 使用 [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY]（Android O+）
 * - 依赖 [Settings.canDrawOverlays] 权限；无权限时静默返回并打日志，调用方需引导用户去开启
 * - 设置了 FLAG_NOT_FOCUSABLE + FLAG_NOT_TOUCHABLE，不会拦截任何用户操作
 */
class FloatingBadgeManager(private val context: Context) {

    companion object {
        private const val TAG = "FloatBadge"
        private const val WIDTH_DP = 60
        private const val HEIGHT_DP = 28
        /** 刷新间隔：5 分钟。 */
        private const val REFRESH_INTERVAL_MS = 5 * 60 * 1000L
    }

    private var wm: WindowManager? = null
    private var view: View? = null
    private var label: TextView? = null

    /** 主线程 Handler，用来 5 分钟刷新一次显示的 HH:mm。 */
    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            updateTimeText()
            // 对齐到下一个整 5 分钟：避免长期漂移
            handler.postDelayed(this, delayToNextTick())
        }
    }

    fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(context)

    /** 启动悬浮窗；若权限缺失或已启动，返回 false / true 并打日志。 */
    fun start(): Boolean {
        if (view != null) {
            Logger.d(TAG, "start: 已在运行，跳过")
            return true
        }
        if (!hasOverlayPermission()) {
            Logger.w(TAG, "start: 悬浮窗权限未开启，无法启动。请在设置中授予")
            return false
        }
        return try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val badge = buildBadgeView()
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val params = WindowManager.LayoutParams(
                dp(WIDTH_DP), dp(HEIGHT_DP),
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = dp(4)
                // 下移一个状态栏高度 + 少许边距，避免被刘海/圆角/时间遮挡
                y = statusBarHeightPx() + dp(4)
            }
            windowManager.addView(badge, params)
            wm = windowManager
            view = badge
            // 挂载后先刷一次显示当前时间，再排下一个 tick
            updateTimeText()
            handler.postDelayed(refreshRunnable, delayToNextTick())
            Logger.i(TAG, "start: 悬浮窗已挂载 size=${WIDTH_DP}x${HEIGHT_DP}dp yOffset=${params.y}px, 已启动 5min 刷新")
            true
        } catch (t: Throwable) {
            Logger.e(TAG, "start 失败: ${t.message}", t)
            false
        }
    }

    fun stop() {
        // 先停定时刷新，防止在 removeView 之后仍触发一次 updateTimeText 空跑
        handler.removeCallbacks(refreshRunnable)
        val v = view ?: return
        try {
            wm?.removeView(v)
            Logger.d(TAG, "stop: 悬浮窗已移除，刷新任务已停")
        } catch (t: Throwable) {
            Logger.w(TAG, "stop 失败: ${t.message}", t)
        }
        view = null
        label = null
        wm = null
    }

    /** 圆角深色胶囊 + 居中白字（时间）。 */
    private fun buildBadgeView(): View {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
            // 深色半透明，避免过亮扰视线
            setColor(Color.argb(0xCC, 0x11, 0x11, 0x11))
            setStroke(dp(1), Color.argb(0x66, 0xFF, 0xFF, 0xFF))
        }
        val tv = TextView(context).apply {
            text = currentHHmm()
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            includeFontPadding = false
            isSingleLine = true
        }
        label = tv
        return FrameLayout(context).apply {
            background = bg
            addView(
                tv,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ).apply { gravity = Gravity.CENTER }
            )
        }
    }

    /** 刷新 badge 文本到当前 HH:mm。主线程调用。 */
    private fun updateTimeText() {
        val text = currentHHmm()
        label?.text = text
        Logger.d(TAG, "updateTimeText: 已刷新为 $text")
    }

    /** 当前时间 "HH:mm"（24h）。 */
    private fun currentHHmm(): String {
        val c = Calendar.getInstance()
        val h = c.get(Calendar.HOUR_OF_DAY)
        val m = c.get(Calendar.MINUTE)
        return String.format(Locale.US, "%02d:%02d", h, m)
    }

    /**
     * 计算到下一个整 5 分钟时刻的毫秒数，用于对齐 tick（例如现在 08:37，下一次 08:40）。
     * 兜底：不小于 1s，且不大于 REFRESH_INTERVAL_MS。
     */
    private fun delayToNextTick(): Long {
        val c = Calendar.getInstance()
        val curMin = c.get(Calendar.MINUTE)
        val curSec = c.get(Calendar.SECOND)
        val curMs = c.get(Calendar.MILLISECOND)
        val minutesToNext = 5 - (curMin % 5)
        val delay = minutesToNext * 60_000L - curSec * 1000L - curMs
        return delay.coerceIn(1_000L, REFRESH_INTERVAL_MS)
    }

    private fun statusBarHeightPx(): Int {
        val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) context.resources.getDimensionPixelSize(resId) else dp(24)
    }

    private fun dp(v: Int): Int {
        val d = context.resources.displayMetrics.density
        return (v * d + 0.5f).toInt().coerceAtLeast(1)
    }
}