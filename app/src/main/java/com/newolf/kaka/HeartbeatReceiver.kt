package com.newolf.kaka

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.newolf.kaka.util.Logger

class HeartbeatReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "Heartbeat"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val running = isServiceRunning(context)
        Logger.d(TAG, "onReceive: service is running=$running")
        if (!running) {
            Logger.i(TAG, "服务已挂，尝试拉起 AutoPunchService")
            val serviceIntent = Intent(context, AutoPunchService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (t: Throwable) {
                Logger.e(TAG, "拉起服务失败: ${t.message}", t)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val services = am.getRunningServices(Integer.MAX_VALUE)
        return services.any { it.service.className == AutoPunchService::class.java.name }
    }
}