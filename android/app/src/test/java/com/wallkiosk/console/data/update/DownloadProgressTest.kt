package com.wallkiosk.console.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadProgressTest {

    @Test
    fun `总量已知时算百分比`() {
        assertEquals(0f, DownloadProgress(0, 1000).fraction, 0.0001f)
        assertEquals(0.5f, DownloadProgress(500, 1000).fraction, 0.0001f)
        assertEquals(1f, DownloadProgress(1000, 1000).fraction, 0.0001f)
        assertTrue(DownloadProgress(500, 1000).known)
    }

    @Test
    fun `总量未知时不给百分比`() {
        val p = DownloadProgress(1234, 0)
        assertEquals(0f, p.fraction, 0.0001f)
        assertFalse(p.known)
    }

    @Test
    fun `实际字节超过声明长度也不会超过 100 百分比`() {
        // 有的服务器会把 Content-Length 报小，不能让进度条冲出去
        assertEquals(1f, DownloadProgress(1200, 1000).fraction, 0.0001f)
    }

    @Test
    fun `负数总量当成未知`() {
        assertEquals(0f, DownloadProgress(10, -1).fraction, 0.0001f)
        assertFalse(DownloadProgress(10, -1).known)
    }
}
