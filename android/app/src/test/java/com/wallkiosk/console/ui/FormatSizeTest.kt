package com.wallkiosk.console.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatSizeTest {

    @Test
    fun `0 表示服务器没给长度，不是空文件`() {
        assertEquals("未知大小", formatSize(0))
        assertEquals("未知大小", formatSize(-1))
    }

    @Test
    fun `小于 1KB 直接给字节`() {
        assertEquals("1 B", formatSize(1))
        assertEquals("999 B", formatSize(999))
    }

    @Test
    fun `KB 和 MB 各保留一位小数`() {
        assertEquals("1.0 KB", formatSize(1024))
        assertEquals("2.0 KB", formatSize(2048))
        assertEquals("1.0 MB", formatSize(1024L * 1024L))
    }

    @Test
    fun `和实际发布的包对得上`() {
        // v1.0.1 的 APK 是 986292 字节
        assertEquals("963.2 KB", formatSize(986292))
    }

    @Test
    fun `GB 不会继续往上溢出单位`() {
        assertEquals("1.0 GB", formatSize(1024L * 1024L * 1024L))
        // 再大也只是数字变大，单位停在 GB
        assertEquals("2048.0 GB", formatSize(2048L * 1024L * 1024L * 1024L))
    }
}
