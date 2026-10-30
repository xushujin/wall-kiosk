package com.wallkiosk.console.data.update

/**
 * Release 的 body 是 Markdown，更新对话框里用的是纯文本控件（NeuText），
 * 直接显示会看到字面的 `**` 和 `[文字](链接)`。
 *
 * 这里只做"够用"的降级，不是完整的 Markdown 渲染：
 * 更新说明本来就该一眼看完，不需要标题层级和代码高亮。
 * 不引 Markdown 库也是同一个理由——为几行说明加一个渲染引擎不划算。
 */
object ReleaseNotes {

    private val linkRe = Regex("""\[([^\]]*)]\(([^)\s]+)\)""")
    private val headingRe = Regex("""(?m)^#{1,6}\s*""")
    private val bulletRe = Regex("""(?m)^[ \t]*[-*+][ \t]+""")
    private val blankLinesRe = Regex("""\n{3,}""")

    fun plain(raw: String): String {
        var s = raw.replace("\r\n", "\n").replace("\r", "\n")

        // [文字](地址) → 文字（地址）；文字和地址一样时就只留一份
        s = linkRe.replace(s) { m ->
            val text = m.groupValues[1].trim()
            val url = m.groupValues[2].trim()
            when {
                text.isEmpty() -> url
                text == url -> url
                else -> "$text（$url）"
            }
        }

        s = headingRe.replace(s, "")
        s = bulletRe.replace(s, "· ")
        s = s.replace("**", "").replace("__", "").replace("`", "")
        s = blankLinesRe.replace(s, "\n\n")

        return s.trim()
    }
}
