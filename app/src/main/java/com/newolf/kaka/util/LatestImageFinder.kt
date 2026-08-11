package com.newolf.kaka.util

import android.content.Context
import java.io.File

/**
 * 查找 KaKa 自己截图目录（`getExternalFilesDir(null)/screenshots/`）中"最新的一张截图"。
 *
 * 该目录由 [`com.newolf.kaka.helper.ScreenshotHelper`](../helper/ScreenshotHelper.kt) 负责写入，
 * FileProvider 已在 [`res/xml/file_paths.xml`](../../../../res/xml/file_paths.xml) 中把
 * 它暴露为 `screenshots` 子路径，因此拿到 File 后可直接走 FileProvider 生成
 * `content://…/screenshots/…` 的可外部读 URI，无需任何存储权限。
 *
 * 选取规则：目录下扩展名为 `.jpg` 且可读的普通文件里 `lastModified()` 最大的一条。
 */
object LatestImageFinder {

    private const val TAG = "LatestImage"

    /**
     * 返回本应用截图目录下"最后一张截图"文件；目录不存在或空返回 null。
     */
    fun findLatestScreenshot(context: Context): File? {
        val dir = context.getExternalFilesDir(null)?.resolve("screenshots")
        if (dir == null || !dir.isDirectory) {
            Logger.w(TAG, "截图目录不存在: $dir")
            return null
        }
        val latest = dir.listFiles { f ->
            f.isFile && f.canRead() && f.name.endsWith(".jpg", ignoreCase = true)
        }?.maxByOrNull { it.lastModified() }
        Logger.i(TAG, "截图目录最新文件: ${latest?.absolutePath}")
        return latest
    }
}