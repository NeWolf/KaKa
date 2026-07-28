package com.newolf.kaka

/**
 * 保存"下一次 KaKa 被拉起时应立刻分享给 QQ 的图片路径"。
 *
 * 使用场景：MIUI 拦截了从无障碍/前台服务直接 startActivity 到 QQ 的调用。
 * 我们改成让无障碍在桌面模拟点击 KaKa 图标（这是"用户操作"，不受后台启动限制），
 * 由此 SettingsActivity 被系统正常拉起 → onCreate 中读取本对象里存的图片路径，
 * 用等价的 SEND intent（component = QQ.JumpActivity）转发给 QQ。
 *
 * 使用 volatile 保证多线程可见性；服务端写入后，UI 线程 onCreate 立即读取。
 * 单条最新即可，不需要队列——若上一次未消费就有新任务，直接覆盖。
 */
object PendingShare {

    @Volatile
    var imagePath: String? = null

    fun consume(): String? {
        val v = imagePath
        imagePath = null
        return v
    }

    fun put(path: String) {
        imagePath = path
    }
}