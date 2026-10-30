package com.wallkiosk.console.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 这组用例锁的是一个真实踩到的坑。
 *
 *   GET /contents/<不存在的文件>
 *     GitHub → 404 + {"message":"Not Found"}
 *     Gitee  → 200 + []
 *
 * 代码原来直接 `JSONObject(body)`，于是 Gitee 上抛
 * "Value [] of type org.json.JSONArray cannot be converted to JSONObject"，
 * 而且它不是 RepoException，上层的 404 分支接不住，原样显示在界面上。
 */
class JsonSafeTest {

    @Test
    fun `Gitee 的空数组响应不会被当成对象`() {
        assertNull(JsonSafe.objectOrNull("[]"))
    }

    @Test
    fun `空数组能正常当数组解析`() {
        val arr = JsonSafe.arrayOrNull("[]")
        assertNotNull(arr)
        assertEquals(0, arr?.length() ?: -1)
    }

    @Test
    fun `正常的对象响应能解析`() {
        val obj = JsonSafe.objectOrNull("""{"name":"screens.conf","sha":"abc123"}""")
        assertEquals("screens.conf", obj?.optString("name"))
        assertEquals("abc123", obj?.optString("sha"))
    }

    @Test
    fun `对象响应不会被当成数组`() {
        assertNull(JsonSafe.arrayOrNull("""{"message":"Not Found"}"""))
    }

    @Test
    fun `HTML 错误页不会抛异常`() {
        assertNull(JsonSafe.objectOrNull("<html><body>502 Bad Gateway</body></html>"))
        assertNull(JsonSafe.arrayOrNull("<html>"))
    }

    @Test
    fun `空响应体不会抛异常`() {
        assertNull(JsonSafe.objectOrNull(""))
        assertNull(JsonSafe.arrayOrNull(""))
    }
}
