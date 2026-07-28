package com.newolf.kaka

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Intent
import android.os.Build
import com.newolf.kaka.util.Logger

class KeepAliveJobService : JobService() {

    companion object {
        private const val TAG = "KeepAliveJob"
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        val running = isServiceRunning()
        Logger.d(TAG, "onStartJob: service running=$running")
        if (!running) {
            Logger.i(TAG, "服务不在，尝试拉起 AutoPunchService")
            val intent = Intent(this, AutoPunchService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } catch (t: Throwable) {
                Logger.e(TAG, "拉起服务失败: ${t.message}", t)
            }
        }
        jobFinished(params, false)
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        Logger.d(TAG, "onStopJob")
        return true
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(): Boolean {
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        val services = am.getRunningServices(Integer.MAX_VALUE)
        return services.any { it.service.className == AutoPunchService::class.java.name }
    }
}