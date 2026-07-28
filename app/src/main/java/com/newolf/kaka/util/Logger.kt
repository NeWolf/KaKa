package com.newolf.kaka.util

import android.content.Context
import android.os.Process
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 集中日志：同时输出到 logcat 和文件。
 *
 * 设计：
 * - 多级别（V/D/I/W/E）+ 可选 Throwable，异常堆栈会完整保留到文件与 logcat。
 * - 懒初始化：未调用 [init] 时也可安全使用——只走 logcat，不写文件、不崩溃。
 *   这样 [BootReceiver] 之类的场景即便先于服务启动也不会 NPE。
 * - 文件 IO 在单线程后台执行，不阻塞主线程。
 * - 错误单独再写入 `error_log.txt`，便于快速筛查。
 *
 * 日志文件位置：`getExternalFilesDir(null)/logs/`
 *   - `punch_log.txt`：全量日志
 *   - `error_log.txt`：仅 W/E 与异常堆栈
 */
object Logger {

    private const val LOGCAT_TAG_PREFIX = "KaKa/"
    private const val LOG_FILE_NAME = "punch_log.txt"
    private const val ERROR_FILE_NAME = "error_log.txt"

    // 单文件最大 1MB，超过后重命名为 .1 备份
    private const val MAX_FILE_BYTES = 1024L * 1024L

    private val sdf = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "KaKa-Logger").apply { isDaemon = true }
    }

    @Volatile
    private var logFile: File? = null

    @Volatile
    private var errorFile: File? = null

    /** 允许写入的最低级别，默认全部输出。 */
    @Volatile
    var minLevel: Int = Log.VERBOSE

    fun init(context: Context) {
        val dir = File(context.getExternalFilesDir(null), "logs").apply { mkdirs() }
        logFile = File(dir, LOG_FILE_NAME)
        errorFile = File(dir, ERROR_FILE_NAME)
        // 启动后先记录一行边界，便于 grep
        i("Logger", "=== 日志初始化 pid=${Process.myPid()} dir=${dir.absolutePath} ===")
    }

    fun v(tag: String, msg: String, tr: Throwable? = null) = log(Log.VERBOSE, tag, msg, tr)
    fun d(tag: String, msg: String, tr: Throwable? = null) = log(Log.DEBUG, tag, msg, tr)
    fun i(tag: String, msg: String, tr: Throwable? = null) = log(Log.INFO, tag, msg, tr)
    fun w(tag: String, msg: String, tr: Throwable? = null) = log(Log.WARN, tag, msg, tr)
    fun e(tag: String, msg: String, tr: Throwable? = null) = log(Log.ERROR, tag, msg, tr)

    private fun log(level: Int, tag: String, msg: String, tr: Throwable?) {
        if (level < minLevel) return

        val logcatTag = LOGCAT_TAG_PREFIX + tag
        if (tr == null) Log.println(level, logcatTag, msg)
        else Log.println(level, logcatTag, msg + "\n" + Log.getStackTraceString(tr))

        // 未初始化则只走 logcat；仍然可以在 Studio 里看到实时输出
        val fullMsg = buildLine(level, tag, msg, tr)
        val mainFile = logFile
        val errFile = errorFile
        if (mainFile == null) return

        ioExecutor.execute {
            try {
                writeWithRotation(mainFile, fullMsg)
                if (level >= Log.WARN && errFile != null) {
                    writeWithRotation(errFile, fullMsg)
                }
            } catch (t: Throwable) {
                // 写文件失败也不要影响业务
                Log.w(LOGCAT_TAG_PREFIX + "Logger", "写日志失败: ${t.message}")
            }
        }
    }

    private fun buildLine(level: Int, tag: String, msg: String, tr: Throwable?): String {
        val sb = StringBuilder(msg.length + 64)
        sb.append(sdf.format(Date()))
            .append(' ').append(levelChar(level)).append('/')
            .append(tag).append('(').append(Process.myPid()).append(')')
            .append(": ").append(msg).append('\n')
        if (tr != null) {
            val sw = StringWriter()
            tr.printStackTrace(PrintWriter(sw))
            sb.append(sw.toString())
            if (!sw.toString().endsWith('\n')) sb.append('\n')
        }
        return sb.toString()
    }

    private fun levelChar(level: Int): Char = when (level) {
        Log.VERBOSE -> 'V'
        Log.DEBUG -> 'D'
        Log.INFO -> 'I'
        Log.WARN -> 'W'
        Log.ERROR -> 'E'
        else -> '?'
    }

    private fun writeWithRotation(file: File, line: String) {
        try {
            if (file.exists() && file.length() > MAX_FILE_BYTES) {
                val backup = File(file.parentFile, file.name + ".1")
                if (backup.exists()) backup.delete()
                file.renameTo(backup)
            }
            file.appendText(line)
        } catch (t: Throwable) {
            Log.w(LOGCAT_TAG_PREFIX + "Logger", "文件写入失败: ${t.message}")
        }
    }

    /** 读取最后 [maxLines] 行日志，便于导出或崩溃时展示。 */
    fun readTail(maxLines: Int = 200): String {
        val f = logFile ?: return ""
        if (!f.exists()) return ""
        return try {
            val all = f.readLines()
            all.takeLast(maxLines).joinToString("\n")
        } catch (t: Throwable) {
            ""
        }
    }

    /** 供测试使用：临时把日志文件重定向到指定目录（未调用 [init] 时也能测试）。 */
    internal fun installForTest(dir: File) {
        dir.mkdirs()
        logFile = File(dir, LOG_FILE_NAME)
        errorFile = File(dir, ERROR_FILE_NAME)
    }

    /** 供测试使用：强制清理并同步执行队列。 */
    internal fun flushForTest() {
        ioExecutor.submit { }.get()
    }
}