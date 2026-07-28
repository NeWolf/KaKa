package com.newolf.kaka.util

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo

suspend fun AccessibilityNodeInfo.waitForNode(
    finder: (AccessibilityNodeInfo) -> AccessibilityNodeInfo?,
    timeoutMs: Long = 8000,
    intervalMs: Long = 300
): AccessibilityNodeInfo? {
    val start = SystemClock.elapsedRealtime()
    while (SystemClock.elapsedRealtime() - start < timeoutMs) {
        val node = finder(this)
        if (node != null) return node
        // 这里不能直接 delay，因为无障碍回调在主线程，需要在外部循环配合协程
        // 我们将会在 PunchTaskExecutor 中配合 rootInActiveWindow 实现
        // 因此这个扩展函数调整为非挂起，在循环中由调用方提供 root 和 delay
    }
    return null
}