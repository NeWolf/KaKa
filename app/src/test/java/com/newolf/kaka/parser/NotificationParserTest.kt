package com.newolf.kaka.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 覆盖真实通知消息的解析规则，对照"模拟收到消息"的行为——
 * 模拟消息由 UI 直接指定 targetChat + isOnWork，而真实消息通过此解析器抽取，
 * 二者产出的 (targetChat, isOnWork) 会被同一个 runPunch 执行。
 */
class NotificationParserTest {

    @Test
    fun parseContent_shangbanKeyword_isOnWorkTrue() {
        val cmd = NotificationParser.parseContent("张三", "记得上班打卡")
        assertEquals("张三", cmd?.targetChat)
        assertEquals(true, cmd?.isOnWork)
    }

    @Test
    fun parseContent_xiabanKeyword_isOnWorkFalse() {
        val cmd = NotificationParser.parseContent("测试群", "下班打卡了没")
        assertEquals("测试群", cmd?.targetChat)
        assertEquals(false, cmd?.isOnWork)
    }

    @Test
    fun parseContent_punchOnlyWithoutHint_isOnWorkNull() {
        val cmd = NotificationParser.parseContent("文件传输助手", "该打卡了")
        assertEquals("文件传输助手", cmd?.targetChat)
        assertNull(cmd?.isOnWork)
    }

    @Test
    fun parseContent_titleContainsPunch_alsoMatched() {
        // 关键字出现在 title 也应命中
        val cmd = NotificationParser.parseContent("提醒:上班打卡", "")
        assertEquals("提醒:上班打卡", cmd?.targetChat)
        assertEquals(true, cmd?.isOnWork)
    }

    @Test
    fun parseContent_nullText_treatedAsEmpty() {
        val cmd = NotificationParser.parseContent("张三", null)
        assertNull("无打卡关键字应返回 null", cmd)
    }

    @Test
    fun parseContent_missingKeyword_returnsNull() {
        assertNull(NotificationParser.parseContent("张三", "吃饭了吗"))
    }

    @Test
    fun parseContent_nullTitle_returnsNull() {
        assertNull(NotificationParser.parseContent(null, "上班打卡"))
    }

    @Test
    fun parseContent_emptyTitle_returnsNull() {
        assertNull(NotificationParser.parseContent("", "上班打卡"))
    }

    @Test
    fun parseContent_shangbanAndXiabanBothPresent_prefersShangban() {
        // 当前实现：先命中 "上班" 分支，行为固定，测试锁定这一契约
        val cmd = NotificationParser.parseContent("测试", "上班/下班都要打卡")
        assertEquals(true, cmd?.isOnWork)
    }

    @Test
    fun parseContent_targetChatUsesTitleVerbatim() {
        // targetChat 应原样使用 title（不做修剪），便于 UI 侧对照回填
        val title = "  昵称  "
        val cmd = NotificationParser.parseContent(title, "上班打卡")
        assertEquals(title, cmd?.targetChat)
    }
}