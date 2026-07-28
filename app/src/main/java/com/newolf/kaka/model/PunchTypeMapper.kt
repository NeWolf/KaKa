package com.newolf.kaka.model

/**
 * 集中管理"上班/下班/自动"三态在不同表示间的转换。
 *
 * - display: UI 上给用户看到的字符串（"自动" / "上班" / "下班"）
 * - stored : MMKV / Intent 中传递的字符串（"auto" / "上班" / "下班"）
 * - isOnWork: 内部布尔三态（true=上班, false=下班, null=自动）
 */
object PunchTypeMapper {

    const val DISPLAY_AUTO = "自动"
    const val DISPLAY_ON = "上班"
    const val DISPLAY_OFF = "下班"

    const val STORED_AUTO = "auto"
    const val STORED_ON = "上班"
    const val STORED_OFF = "下班"

    val displayOptions: List<String> = listOf(DISPLAY_AUTO, DISPLAY_ON, DISPLAY_OFF)

    fun displayToStored(display: String): String = when (display) {
        DISPLAY_ON -> STORED_ON
        DISPLAY_OFF -> STORED_OFF
        else -> STORED_AUTO
    }

    fun storedToDisplay(stored: String?): String = when (stored) {
        STORED_ON -> DISPLAY_ON
        STORED_OFF -> DISPLAY_OFF
        else -> DISPLAY_AUTO
    }

    /** 将存储字符串解析为业务侧的三态布尔。 */
    fun storedToIsOnWork(stored: String?): Boolean? = when (stored) {
        STORED_ON -> true
        STORED_OFF -> false
        else -> null
    }
}