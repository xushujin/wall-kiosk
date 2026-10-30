package com.wallkiosk.console.data.update

/**
 * 版本号比较，按 semver 的规则来。
 *
 * 只做一件事：判断 "v1.0.1" 和 "1.1.0" 谁大。
 * 纯逻辑，不碰网络也不碰 Android API，可以直接在 JVM 单测里跑。
 *
 * 规则：
 *   v1.0.1  == 1.0.1        前缀 v 和空白不算数
 *   1.0     == 1.0.0        段数不同时缺的段按 0 算
 *   1.0.1+b5 == 1.0.1       build 元数据不参与比较
 *   1.0.1-beta < 1.0.1      有预发布标签的比同号正式版小
 *   1.0.1-beta.2 > 1.0.1-beta.1
 */
object Version {

    /** 去掉 v 前缀、空白和 build 元数据（+ 之后的部分） */
    fun normalize(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1)
        val plus = s.indexOf('+')
        if (plus >= 0) s = s.substring(0, plus)
        return s.trim()
    }

    /** a < b 返回 -1，相等 0，a > b 返回 1 */
    fun compare(a: String, b: String): Int {
        val left = split(normalize(a))
        val right = split(normalize(b))

        val n = maxOf(left.core.size, right.core.size)
        for (i in 0 until n) {
            val x = left.core.getOrNull(i) ?: 0
            val y = right.core.getOrNull(i) ?: 0
            if (x != y) return if (x < y) -1 else 1
        }

        val preA = left.pre
        val preB = right.pre
        // 主版本号相同的情况下，带预发布标签的比正式版小
        if (preA == null && preB == null) return 0
        if (preA == null) return 1
        if (preB == null) return -1
        return comparePre(preA, preB)
    }

    fun isNewer(remote: String, local: String): Boolean = compare(remote, local) > 0

    private class Parts(val core: List<Int>, val pre: List<String>?)

    private fun split(v: String): Parts {
        val dash = v.indexOf('-')
        val corePart = if (dash >= 0) v.substring(0, dash) else v
        val prePart = if (dash >= 0) v.substring(dash + 1) else null

        val core = corePart.split('.').map { seg ->
            // 非数字段（比如 1.x.0）当 0 处理，宁可比较得保守也不抛异常
            seg.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }
        val pre = prePart
            ?.split('.')
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }

        return Parts(core, pre)
    }

    private fun comparePre(a: List<String>, b: List<String>): Int {
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrNull(i) ?: return -1
            val y = b.getOrNull(i) ?: return 1
            val nx = x.toIntOrNull()
            val ny = y.toIntOrNull()
            when {
                // 纯数字标识符按数值比
                nx != null && ny != null -> if (nx != ny) return if (nx < ny) -1 else 1
                // 数字标识符优先级低于字母标识符
                nx != null -> return -1
                ny != null -> return 1
                x != y -> return if (x < y) -1 else 1
            }
        }
        return 0
    }
}
