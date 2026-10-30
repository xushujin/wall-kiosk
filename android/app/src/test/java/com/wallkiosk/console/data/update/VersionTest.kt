package com.wallkiosk.console.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTest {

    @Test
    fun `v 前缀和空白不影响比较`() {
        assertEquals(0, Version.compare("v1.0.1", "1.0.1"))
        assertEquals(0, Version.compare("  v1.0.1 ", "1.0.1"))
        assertEquals(0, Version.compare("V2.3.4", "v2.3.4"))
    }

    @Test
    fun `段数不同时缺的段按 0 算`() {
        assertEquals(0, Version.compare("1.0", "1.0.0"))
        assertEquals(0, Version.compare("1", "1.0.0"))
        assertEquals(1, Version.compare("1.0.1", "1.0"))
    }

    @Test
    fun `按段比较而不是按字符串比较`() {
        // 字符串比较会得出 "1.10.0" < "1.9.0"，这正是要用数字比的原因
        assertEquals(1, Version.compare("1.10.0", "1.9.0"))
        assertEquals(1, Version.compare("1.0.10", "1.0.9"))
        assertEquals(-1, Version.compare("0.9.0", "1.0.0"))
    }

    @Test
    fun `预发布版比同号正式版小`() {
        assertEquals(-1, Version.compare("1.0.1-beta", "1.0.1"))
        assertEquals(1, Version.compare("1.0.1", "1.0.1-rc1"))
        assertEquals(-1, Version.compare("2.0.0-alpha", "1.9.9"))
    }

    @Test
    fun `预发布标识符按 semver 规则比`() {
        assertEquals(1, Version.compare("1.0.1-beta.2", "1.0.1-beta.1"))
        assertEquals(-1, Version.compare("1.0.1-beta.1", "1.0.1-beta.2"))
        // 数字标识符优先级低于字母标识符
        assertEquals(-1, Version.compare("1.0.1-1", "1.0.1-alpha"))
        // 前缀短的小
        assertEquals(-1, Version.compare("1.0.1-alpha", "1.0.1-alpha.1"))
    }

    @Test
    fun `build 元数据不参与比较`() {
        assertEquals(0, Version.compare("1.0.1+build.5", "1.0.1"))
        assertEquals(0, Version.compare("1.0.1+b1", "1.0.1+b2"))
    }

    @Test
    fun `非数字段当 0 处理不抛异常`() {
        assertEquals(0, Version.compare("1.x.0", "1.0.0"))
        assertEquals(0, Version.compare("", "0"))
        assertEquals(1, Version.compare("1.x", "0.9"))
    }

    @Test
    fun `isNewer 判断方向`() {
        assertTrue(Version.isNewer("v1.1.0", "1.0.1"))
        assertFalse(Version.isNewer("v1.0.1", "1.0.1"))
        assertFalse(Version.isNewer("v1.0.0", "1.0.1"))
    }

    @Test
    fun `normalize 只做清理不改内容`() {
        assertEquals("1.0.1", Version.normalize("v1.0.1"))
        assertEquals("1.0.1", Version.normalize("1.0.1+abc"))
        assertEquals("1.0.1-beta", Version.normalize("v1.0.1-beta"))
    }
}
