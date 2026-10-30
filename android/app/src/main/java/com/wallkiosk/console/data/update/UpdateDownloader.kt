package com.wallkiosk.console.data.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** 下载进度。total 未知时是 0，此时 fraction 恒为 0，界面要显示"已下载 x MB"而不是百分比 */
data class DownloadProgress(val downloaded: Long, val total: Long) {
    val fraction: Float
        get() = if (total > 0L) {
            (downloaded.toDouble() / total).coerceIn(0.0, 1.0).toFloat()
        } else {
            0f
        }

    val known: Boolean get() = total > 0L
}

/**
 * 下载更新包。
 *
 * 三个必须自己处理的地方：
 *   1. 重定向。GitHub 的 release 附件会 302 到 CDN，换域名是常态，所以手动跟，
 *      不依赖 HttpURLConnection 的自动跟随（跨 host 时行为不统一）。
 *   2. 进度节流。64KB 缓冲下每读一次就回调会让界面每秒重组几十次，按 1% 或 150ms 压一次。
 *   3. 取消。协程取消时立刻中断并把半截文件删掉，不留垃圾也不留错文件。
 *
 * 先写 .part 再改名，中途失败不会留下一个"看起来完整"的包。
 */
class UpdateDownloader {

    suspend fun download(
        url: String,
        dest: File,
        expectedSize: Long = 0L,
        onProgress: (DownloadProgress) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val tmp = File(dest.absolutePath + ".part")
        tmp.parentFile?.mkdirs()
        if (tmp.exists() && !tmp.delete()) throw UpdateException("清不掉上次残留的临时文件")

        var completed = false
        try {
            var current = url
            var redirects = 0

            while (true) {
                ensureActive()
                val conn = URL(current).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = CONNECT_TIMEOUT_MS
                    conn.readTimeout = READ_TIMEOUT_MS
                    conn.instanceFollowRedirects = false
                    conn.setRequestProperty("User-Agent", UPDATE_USER_AGENT)

                    val code = conn.responseCode
                    if (code in 300..399) {
                        val location = conn.getHeaderField("Location")
                        if (location.isNullOrBlank() || redirects >= MAX_REDIRECTS) {
                            throw UpdateException("下载地址跳转异常（HTTP $code）")
                        }
                        current = URL(URL(current), location).toString()
                        redirects++
                        continue
                    }
                    if (code !in 200..299) throw UpdateException("下载失败（HTTP $code）")

                    val declared = conn.contentLengthLong.takeIf { it > 0L } ?: expectedSize
                    stream(conn, tmp, declared, onProgress)
                    break
                } finally {
                    conn.disconnect()
                }
            }

            completed = true
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            dest
        } finally {
            if (!completed) tmp.delete()
        }
    }

    private suspend fun CoroutineScope.stream(
        conn: HttpURLConnection,
        tmp: File,
        declared: Long,
        onProgress: (DownloadProgress) -> Unit,
    ) {
        var written = 0L
        var lastEmitAt = 0L
        var lastFraction = -1f
        val buffer = ByteArray(BUFFER_SIZE)

        conn.inputStream.use { input ->
            FileOutputStream(tmp).use { output ->
                while (true) {
                    ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    written += n

                    val progress = DownloadProgress(written, declared)
                    val now = System.nanoTime()
                    if (progress.fraction - lastFraction >= EMIT_STEP ||
                        now - lastEmitAt >= EMIT_INTERVAL_NS
                    ) {
                        lastFraction = progress.fraction
                        lastEmitAt = now
                        onProgress(progress)
                    }
                }
            }
        }

        // 服务器声明了长度就必须对上，对不上说明连接中途断了，
        // 这时候装上去只会得到"解析包时出现问题"，不如在这里就报错
        if (declared > 0L && written != declared) {
            throw UpdateException("下载不完整（$written / $declared 字节）")
        }
        onProgress(DownloadProgress(written, if (declared > 0L) declared else written))
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 30_000
        const val BUFFER_SIZE = 64 * 1024
        const val MAX_REDIRECTS = 5
        const val EMIT_STEP = 0.01f
        const val EMIT_INTERVAL_NS = 150_000_000L
    }
}
