package com.newolf.kaka.helper

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.newolf.kaka.HeartbeatReceiver
import com.newolf.kaka.KeepAliveJobService
import com.newolf.kaka.util.Logger

class KeepAliveHelper(private val context: Context) {

    companion object {
        private const val TAG = "KeepAlive"
    }

    fun start() {
        scheduleHeartbeatAlarm()
        scheduleJob()
        Logger.i(TAG, "双保活已启动（AlarmManager + JobScheduler）")
    }

    private fun scheduleHeartbeatAlarm() {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, HeartbeatReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context, 1, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 60_000L,
                15 * 60_000L,
                pendingIntent
            )
            Logger.d(TAG, "AlarmManager 心跳已注册（首个 60s 后，周期 15min）")
        } catch (t: Throwable) {
            Logger.e(TAG, "AlarmManager 注册失败: ${t.message}", t)
        }
    }

    private fun scheduleJob() {
        try {
            val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE)
                    as android.app.job.JobScheduler
            val component = android.content.ComponentName(context, KeepAliveJobService::class.java)
            val jobInfo = android.app.job.JobInfo.Builder(100, component)
                .setPeriodic(15 * 60_000L)
                .setPersisted(true)
                .setRequiresDeviceIdle(false)
                .setRequiresCharging(false)
                .build()
            val ret = jobScheduler.schedule(jobInfo)
            Logger.d(TAG, "JobScheduler.schedule 返回码=$ret（1=成功）")
        } catch (t: Throwable) {
            Logger.e(TAG, "JobScheduler 注册失败: ${t.message}", t)
        }
    }
}