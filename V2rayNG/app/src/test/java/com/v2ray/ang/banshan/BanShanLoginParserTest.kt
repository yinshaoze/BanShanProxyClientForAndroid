package com.v2ray.ang.banshan

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BanShanLoginParserTest {

    @Test
    fun parsesStringCodeAndPlainDataUrl() {
        val payload = BanShanLoginParser.parse(
            """{"code":"200","msg":"登录成功","data":"https://proxy.example/sub/abc"}"""
        )

        assertEquals(200, payload?.code)
        assertEquals("登录成功", payload?.message)
        assertEquals("https://proxy.example/sub/abc", payload?.subscriptionUrl)
    }

    @Test
    fun parsesNumericCode() {
        val payload = BanShanLoginParser.parse(
            """{"code":401,"msg":"用户名或密码错误","data":"Null"}"""
        )

        assertEquals(401, payload?.code)
        assertNull(payload?.subscriptionUrl)
    }

    @Test
    fun rejectsNullPlaceholderUrl() {
        for (body in listOf(
            """{"code":"200","msg":"ok","data":"Null"}""",
            """{"code":"200","msg":"ok","data":null}""",
            """{"code":"200","msg":"ok","data":""}""",
            """{"code":"200","msg":"ok","data":"ftp://example.com/sub"}""",
            """{"code":"200","msg":"ok","data":"https://"}""",
            """{"code":"200","msg":"ok","data":"https://example.com/a b"}""",
        )) {
            assertNull(BanShanLoginParser.parse(body)?.subscriptionUrl, body)
        }
    }

    @Test
    fun fallsBackToLegacyKeysAndObjectData() {
        val legacy = BanShanLoginParser.parse(
            """{"code":"200","sub_url":"https://example.com/legacy"}"""
        )
        assertEquals("https://example.com/legacy", legacy?.subscriptionUrl)

        val objectData = BanShanLoginParser.parse(
            """{"code":200,"data":{"url":"https://example.com/object"}}"""
        )
        assertEquals("https://example.com/object", objectData?.subscriptionUrl)

        val nestedData = BanShanLoginParser.parse(
            """{"code":200,"data":{"data":"https://example.com/nested"}}"""
        )
        assertEquals("https://example.com/nested", nestedData?.subscriptionUrl)
    }

    @Test
    fun invalidJsonGivesNullPayload() {
        assertNull(BanShanLoginParser.parse(null))
        assertNull(BanShanLoginParser.parse(""))
        assertNull(BanShanLoginParser.parse("<html>500</html>"))
        assertNull(BanShanLoginParser.parse("[1,2,3]"))
    }

    @Test
    fun subscriptionUrlValidation() {
        assertTrue(BanShanLoginParser.isValidSubscriptionUrl("http://example.com/sub"))
        assertTrue(BanShanLoginParser.isValidSubscriptionUrl("HTTPS://example.com/sub?token=1"))
        assertFalse(BanShanLoginParser.isValidSubscriptionUrl("example.com/sub"))
        assertFalse(BanShanLoginParser.isValidSubscriptionUrl("null"))
        assertFalse(BanShanLoginParser.isValidSubscriptionUrl(""))
        assertFalse(BanShanLoginParser.isValidSubscriptionUrl(null))
        assertFalse(BanShanLoginParser.isValidSubscriptionUrl("https://example.com/\nsub"))
    }
}
