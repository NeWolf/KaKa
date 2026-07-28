package com.newolf.kaka

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class TaskAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val serviceIntent = Intent(context, AutoPunchService::class.java).apply {
            action = AutoPunchService.ACTION_TIMED_PUNCH
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}