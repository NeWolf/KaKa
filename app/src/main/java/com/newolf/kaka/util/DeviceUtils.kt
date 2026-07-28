package com.newolf.kaka.util

import android.content.Context
import android.content.res.Configuration
import android.util.DisplayMetrics
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 设备类型判断工具
 *
 * 综合以下三种判断策略提升准确率：
 * 1. Configuration.smallestScreenWidthDp >= 600dp （Google 官方推荐方式）
 * 2. Configuration.screenLayout 的 SCREENLAYOUT_SIZE 判断（LARGE / XLARGE）
 * 3. 物理屏幕对角线尺寸 >= 7 英寸（兜底判断）
 *
 * 任一条件满足即认为是平板。
 */
object DeviceUtils {

    /**
     * 判断当前设备是否为平板
     *
     * @param context 上文，建议传入 applicationContext
     * @return true 表示平板，false 表示手机
     */
    fun isTablet(context: Context): Boolean {
        return isTabletBySmallestWidth(context) ||
                isTabletByScreenLayout(context) ||
                isTabletByPhysicalSize(context)
    }

    /**
     * 方式一：通过 smallestScreenWidthDp 判断
     * Google 官方推荐：>= 600dp 视为平板（7 寸及以上）
     */
    private fun isTabletBySmallestWidth(context: Context): Boolean {
        return context.resources.configuration.smallestScreenWidthDp >= 600
    }

    /**
     * 方式二：通过 Configuration.screenLayout 判断
     * SCREENLAYOUT_SIZE_LARGE 或 SCREENLAYOUT_SIZE_XLARGE 视为平板
     */
    private fun isTabletByScreenLayout(context: Context): Boolean {
        val screenLayout = context.resources.configuration.screenLayout and
                Configuration.SCREENLAYOUT_SIZE_MASK
        return screenLayout == Configuration.SCREENLAYOUT_SIZE_LARGE ||
                screenLayout == Configuration.SCREENLAYOUT_SIZE_XLARGE
    }

    /**
     * 方式三：通过物理屏幕对角线英寸数判断
     * 对角线 >= 7 英寸视为平板
     */
    private fun isTabletByPhysicalSize(context: Context): Boolean {
        return try {
            val metrics: DisplayMetrics = context.resources.displayMetrics
            val widthInches = metrics.widthPixels / metrics.xdpi.toDouble()
            val heightInches = metrics.heightPixels / metrics.ydpi.toDouble()
            val diagonalInches = sqrt(widthInches.pow(2.0) + heightInches.pow(2.0))
            diagonalInches >= 7.0
        } catch (e: Throwable) {
            false
        }
    }
}

/**
 * Context 扩展：便于直接调用 context.isTablet()
 */
fun Context.isTablet(): Boolean = DeviceUtils.isTablet(this)