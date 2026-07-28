package com.newolf.kaka.helper

import android.graphics.Bitmap
import com.newolf.kaka.util.Logger
import java.io.File
import java.io.FileOutputStream
class ScreenshotHelper(private val screenshotDir: File) {
    fun saveAndCompress(bitmap: Bitmap?, fileName: String = "punch_${System.currentTimeMillis()}.jpg"): File? {
        if (bitmap == null) return null
        return try {
            val file = File(screenshotDir, fileName)
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 50, it) }
            Logger.d("Screenshot", "截图保存至 ${file.absolutePath}, 大小: ${file.length() / 1024}KB")
            file
        } catch (e: Exception) {
            Logger.d("Screenshot", "保存截图失败: ${e.message}")
            null
        }
    }
}