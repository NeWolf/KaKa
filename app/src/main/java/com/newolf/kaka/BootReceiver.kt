package com.newolf.kaka

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.newolf.kaka.util.Logger
import com.tencent.mmkv.MMKV

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "Boot"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Logger.i(TAG, "onReceive action=${intent.action}")
        try {
            MMKV.initialize(context)
            val mmkv = MMKV.defaultMMKV()
            val enabled = mmkv?.decodeBool("timed_enabled", false) == true
            Logger.d(TAG, "timed_enabled=$enabled")
            if (enabled) {
                val serviceIntent = Intent(context, AutoPunchService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                Logger.i(TAG, "已请求启动 AutoPunchService")
            }
        } catch (t: Throwable) {
            Logger.e(TAG, "开机启动失败: ${t.message}", t)
        }
    }
}