package com.newolf.kaka.parser

import android.app.Notification
import com.newolf.kaka.util.Logger

data class PunchCommand(
    val targetChat: String,
    val isOnWork: Boolean?,
)

object NotificationParser {

    private const val TAG = "NotifParser"

    /** 关键字（含此关键字的消息才会被识别为打卡指令）。 */
    private const val KEYWORD_PUNCH = "打卡"
    private const val KEYWORD_ON = "上班"
    private const val KEYWORD_OFF = "下班"

    /**
     * 纯字符串解析入口，便于单元测试。
     * @param title 通知标题（同时作为回复目标 targetChat）
     * @param text  通知正文，为 null 时按空字符串处理
     */
    fun parseContent(title: String?, text: String?): PunchCommand? {
        if (title.isNullOrEmpty()) return null
        val content = "$title ${text.orEmpty()}"
        if (!content.contains(KEYWORD_PUNCH)) return null
        val isOnWork = when {
            content.contains(KEYWORD_ON) -> true
            content.contains(KEYWORD_OFF) -> false
            else -> null
        }
        return PunchCommand(targetChat = sanitizeChatName(title), isOnWork = isOnWork)
    }

    /**
     * QQ 通知的 title 常带 "(N条新消息)" / "(N 条)" 之类后缀，
     * 分享时按此字符串在联系人列表里找不到节点。这里把括号内的计数尾巴去掉，
     * 只保留纯昵称/群名。
     */
    private fun sanitizeChatName(raw: String): String {
        var name = raw.trim()
        // 半角与全角括号都处理；同时匹配"新消息"/"条"/"未读"关键字作为兜底判据
        val patterns = listOf(
            Regex("""\s*[（(][^)）]*(新消息|未读|条)[^)）]*[)）]\s*$"""),
            Regex("""\s*\[[^\]]*(新消息|未读|条)[^\]]*\]\s*$"""),
        )
        for (p in patterns) {
            val hit = p.find(name)
            if (hit != null) {
                name = name.removeRange(hit.range).trim()
                break
            }
        }
        return name
    }

    fun parse(notification: Notification): PunchCommand? {
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        // QQ 有时把内容塞在 EXTRA_BIG_TEXT / EXTRA_TEXT_LINES / EXTRA_SUB_TEXT / conversation_title 里，
        // 逐个尝试并拼在一起，以最大化命中"打卡/上班/下班"关键字。
        val texts = buildList {
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.let(::add)
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.let(::add)
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.let(::add)
            extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.let(::add)
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                ?.mapNotNull { it?.toString() }
                ?.forEach(::add)
        }
        val text = texts.joinToString(separator = " ").ifBlank { null }
        Logger.d(TAG, "parse Notification: title='$title' text='$text'")
        return parseContent(title, text)
    }

    /**
     * 无障碍事件里，Notification 有时为 null，只能拿到 event.text（CharSequence 列表拼接后的字符串）。
     * 常见样式：
     *   "NeWolf: 打卡 上班"
     *   "NeWolf 打卡 上班"
     *   "[NeWolf] 打卡"
     * 这里把冒号 / 中括号 / 全角冒号当作 title 与正文分隔符，尽量抽出 targetChat。
     */
    fun parseEventText(eventText: String): PunchCommand? {
        if (eventText.isBlank()) return null
        if (!eventText.contains(KEYWORD_PUNCH)) {
            Logger.v(TAG, "parseEventText: 未包含关键字，跳过 text='$eventText'")
            return null
        }
        // 切分 title 与正文
        val separators = arrayOf(":", "：", "] ", "】")
        var title: String = eventText
        var text: String = ""
        for (sep in separators) {
            val idx = eventText.indexOf(sep)
            if (idx > 0) {
                title = eventText.substring(0, idx).trimStart('[', '【').trim()
                text = eventText.substring(idx + sep.length).trim()
                break
            }
        }
        val isOnWork = when {
            eventText.contains(KEYWORD_ON) -> true
            eventText.contains(KEYWORD_OFF) -> false
            else -> null
        }
        Logger.d(TAG, "parseEventText: title='$title' text='$text' isOnWork=$isOnWork")
        return PunchCommand(targetChat = sanitizeChatName(title), isOnWork = isOnWork)
    }
}