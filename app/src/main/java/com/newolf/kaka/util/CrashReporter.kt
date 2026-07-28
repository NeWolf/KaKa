package com.newolf.kaka.util

import android.content.Context

/**
 * 全局未捕获异常处理：把崩溃信息写入 [Logger] 的错误文件，然后交回给系统默认处理器（保留系统 tombstone）。
 * 调用位置：[com.newolf.kaka.AutoPunchService.onCreate]（服务进程启动时）。
 */
object CrashReporter {

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        Logger.init(context)
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Logger.e(
                    tag = "Crash",
                    msg = "线程 ${thread.name} 未捕获异常：${throwable.javaClass.simpleName}: ${throwable.message}",
                    tr = throwable,
                )
                // 保证异步日志线程尽量把内容写完
                Logger.flushForTest()
            } catch (_: Throwable) {
                // 崩溃处理中再崩溃，只能忽略
            }
            default?.uncaughtException(thread, throwable)
        }
    }
}