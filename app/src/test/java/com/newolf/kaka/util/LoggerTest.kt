package com.newolf.kaka.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Logger 单元测试：验证"快速定位错误"所需的关键行为——
 * 1) 未初始化不崩（BootReceiver 等场景先于 init 调用是常态）
 * 2) 多级别写入 punch_log.txt
 * 3) W/E 级别同时写入 error_log.txt，便于筛查
 * 4) 传入 Throwable 时完整保留堆栈
 * 5) 低于 minLevel 的日志被过滤
 */
class LoggerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun logBeforeInit_doesNotCrash() {
        // 不调用 installForTest / init，直接写入 —— 只走 logcat 分支，不应抛任何异常
        Logger.v("Test", "verbose")
        Logger.d("Test", "debug")
        Logger.i("Test", "info")
        Logger.w("Test", "warn")
        Logger.e("Test", "error")
        // 走到这一步就算成功
        assertTrue(true)
    }

    @Test
    fun writesToFile_afterInstall() {
        val dir = tempFolder.newFolder("logs")
        Logger.installForTest(dir)
        Logger.minLevel = android.util.Log.VERBOSE
        Logger.i("Feature", "hello world")
        Logger.flushForTest()

        val logFile = File(dir, "punch_log.txt")
        assertTrue("主日志文件应被创建", logFile.exists())
        val content = logFile.readText()
        assertTrue("应包含 tag/msg", content.contains("Feature") && content.contains("hello world"))
        assertTrue("应包含级别标记 I", content.contains(" I/"))
    }

    @Test
    fun errorLevel_writtenToErrorFile() {
        val dir = tempFolder.newFolder("logs")
        Logger.installForTest(dir)
        Logger.minLevel = android.util.Log.VERBOSE

        Logger.d("Feature", "普通调试")
        Logger.e("Feature", "崩了")
        Logger.flushForTest()

        val errFile = File(dir, "error_log.txt")
        assertTrue(errFile.exists())
        val err = errFile.readText()
        assertTrue("error_log 只包含 W/E", err.contains("崩了"))
        assertFalse("error_log 不应包含 debug", err.contains("普通调试"))
    }

    @Test
    fun exceptionStackTrace_isPreservedInFile() {
        val dir = tempFolder.newFolder("logs")
        Logger.installForTest(dir)
        Logger.minLevel = android.util.Log.VERBOSE

        val ex = IllegalStateException("boom")
        Logger.e("Feature", "task failed", ex)
        Logger.flushForTest()

        val content = File(dir, "punch_log.txt").readText()
        assertTrue("必须保留异常类型", content.contains("IllegalStateException"))
        assertTrue("必须保留异常 message", content.contains("boom"))
        assertTrue("必须包含堆栈 at ...", content.contains("at com.newolf.kaka.util.LoggerTest"))
    }

    @Test
    fun minLevel_filtersLowerLevels() {
        val dir = tempFolder.newFolder("logs")
        Logger.installForTest(dir)
        Logger.minLevel = android.util.Log.WARN

        Logger.d("Feature", "debug 应该被过滤")
        Logger.i("Feature", "info 应该被过滤")
        Logger.w("Feature", "warn 应该保留")
        Logger.flushForTest()

        val content = File(dir, "punch_log.txt").readText()
        assertFalse(content.contains("debug 应该被过滤"))
        assertFalse(content.contains("info 应该被过滤"))
        assertTrue(content.contains("warn 应该保留"))

        // 复原，避免影响其他测试
        Logger.minLevel = android.util.Log.VERBOSE
    }

    @Test
    fun readTail_returnsRecentLines() {
        val dir = tempFolder.newFolder("logs")
        Logger.installForTest(dir)
        Logger.minLevel = android.util.Log.VERBOSE
        for (i in 1..10) Logger.i("Feature", "line-$i")
        Logger.flushForTest()

        val tail = Logger.readTail(3)
        assertNotNull(tail)
        assertTrue("应包含最后一行", tail.contains("line-10"))
        assertFalse("不应包含较早的行", tail.contains("line-1 "))
        // 大致按 3 行返回（每次 log 目前写一行）
        assertEquals(3, tail.lines().size)
    }
}