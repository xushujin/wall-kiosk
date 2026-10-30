package com.wallkiosk.console.data

/**
 * config/screens.conf 与 config/hosts/<主机名>.conf 的解析、行级编辑、校验。
 *
 * 这一份逻辑必须和 bin/wall.sh 的 normalize_conf / validate_conf 保持一致，
 * 也必须和 web/index.html 里 ==PURE-BEGIN== 那一段保持一致。三处分叉的表现是
 * 「页面说没问题，主机却不生效」，非常难查。
 *
 * 改动这里的规则时，记得同步改另外两处，并跑 test/console.mjs。
 */
object ScreensConf {

    const val DEFAULT_PATH = "config/screens.conf"
    private const val HOSTS_DIR = "config/hosts"

    fun hostsDir(): String = HOSTS_DIR
    fun hostPath(name: String): String = "$HOSTS_DIR/$name.conf"

    private val ZOOM_RE = Regex("^[0-9]+(\\.[0-9]+)?$")
    private val REFRESH_RE = Regex("^[0-9]+$")
    private val SCHEME_RE = Regex("^https?://", RegexOption.IGNORE_CASE)

    fun parse(text: String): List<ConfRow> {
        val rows = ArrayList<ConfRow>()
        // 统一按 \n 切，顺带吃掉 CRLF 的 \r（保存时会写成 LF）
        for (line in text.split("\n")) {
            val raw = line.removeSuffix("\r")
            val trimmed = raw.trim()
            when {
                trimmed.isEmpty() -> rows.add(ConfRow.Blank(raw))
                trimmed.startsWith("#") -> rows.add(ConfRow.Comment(raw))
                else -> {
                    val parts = raw.split("|")
                    fun field(i: Int): String = parts.getOrNull(i)?.trim().orEmpty()
                    rows.add(
                        ConfRow.Data(
                            raw,
                            ScreenEntry(
                                iface = field(0),
                                url = field(1),
                                zoom = field(2),
                                refresh = field(3),
                            )
                        )
                    )
                }
            }
        }
        return rows
    }

    fun entries(text: String): List<ScreenEntry> =
        parse(text).filterIsInstance<ConfRow.Data>().map { it.entry }

    /** 末尾的空字段去掉，避免写出 "a|b||" 这种尾巴 */
    fun format(e: ScreenEntry): String {
        val f = mutableListOf(e.iface, e.url, e.zoom, e.refresh)
        while (f.size > 2 && f.last().isBlank()) f.removeAt(f.size - 1)
        return f.joinToString("|") { it.trim() }
    }

    /**
     * 只替换/删除命中的那一行，注释、空行、顺序全部保持原样。
     * @param targetIface 要改的接口名；null 表示新增
     * @param entry null 表示删除
     */
    fun applyEdit(text: String, targetIface: String?, entry: ScreenEntry?): String {
        val rows = parse(text).toMutableList()

        var idx = -1
        if (targetIface != null) {
            idx = rows.indexOfFirst { it is ConfRow.Data && it.entry.iface == targetIface }
        }

        when {
            idx >= 0 -> {
                if (entry == null) rows.removeAt(idx)
                else rows[idx] = ConfRow.Data(format(entry), entry)
            }
            entry != null -> {
                // 文件以换行结尾时 split 会留一个尾部空串，插到它前面，
                // 否则会在末尾多出一个空行
                var at = rows.size
                if (at > 0) {
                    val last = rows[at - 1]
                    if (last is ConfRow.Blank && last.raw.isEmpty()) at -= 1
                }
                rows.add(at, ConfRow.Data(format(entry), entry))
            }
        }

        return rows.joinToString("\n") { it.raw }
    }

    fun validate(text: String): List<ConfError> = validate(entries(text))

    fun validate(entries: List<ScreenEntry>): List<ConfError> {
        val errs = ArrayList<ConfError>()
        val seen = HashMap<String, Int>()

        for (e in entries) {
            val tag = e.iface.ifBlank { "(空接口名)" }

            if (e.iface.isBlank()) errs.add(ConfError(tag, "缺少接口名"))

            if (e.url.isBlank()) {
                errs.add(ConfError(tag, "缺少地址"))
            } else if (!SCHEME_RE.containsMatchIn(e.url)) {
                errs.add(ConfError(tag, "地址不是 http(s)：${e.url}"))
            }

            if (e.zoom.isNotBlank() && !ZOOM_RE.matches(e.zoom)) {
                errs.add(ConfError(tag, "缩放不是数字：${e.zoom}"))
            }

            if (e.refresh.isNotBlank() && !REFRESH_RE.matches(e.refresh)) {
                errs.add(ConfError(tag, "定时重启不是整数：${e.refresh}"))
            }

            if (e.iface.isNotBlank()) {
                val n = (seen[e.iface] ?: 0) + 1
                seen[e.iface] = n
                if (n == 2) errs.add(ConfError(tag, "接口名重复（后一条会覆盖前一条）"))
            }
        }

        // wall.sh 的 validate_conf 不把空文件当错，但 reload 时解析结果为空会拒绝应用，
        // 所以这里提前拦住，免得用户提交一个注定不生效的文件。
        if (entries.isEmpty()) {
            errs.add(ConfError("-", "没有任何屏幕配置，主机启动时会报「无可用配置」"))
        }

        return errs
    }

    /** 新建主机时的初始内容：带注释头 + 至少一条配置 */
    fun newHostTemplate(name: String, first: ScreenEntry): String = buildString {
        append("# ").append(name).append(" 的大屏配置\n")
        append("# 一行一屏，格式：接口名|页面地址|缩放|定时重启分钟\n")
        append("\n")
        append(format(first)).append("\n")
    }
}
