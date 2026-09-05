package com.xuedi.coder.action

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M6 单测：ActionExecutor.extractActions（纯函数）+ friendlyName。
 *
 * 覆盖：无标签 / 单标签 / 多标签 / 未知名过滤 / 双引号 / 单引号 / 无引号 /
 *      大小写不敏感 / 冒号后空格 / open_url+share 白名单 / friendlyName 映射。
 *
 * 注：extractActions 是纯 Kotlin（正则+字符串），不调 Android API，
 *     JVM unit test 直接能跑，CI 无需模拟器。
 */
class ActionExecutorTest {

    @Test
    fun `no action tags returns original text and empty list`() {
        val (text, actions) = ActionExecutor.extractActions("hello world 没有标签")
        assertEquals("hello world 没有标签", text)
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `single copy_to_clipboard with double quotes`() {
        val input = "代码示例 <ACTION: copy_to_clipboard \"println(42)\">"
        val (text, actions) = ActionExecutor.extractActions(input)
        assertEquals("代码示例", text)
        assertEquals(1, actions.size)
        assertEquals("copy_to_clipboard", actions[0].name)
        assertEquals("println(42)", actions[0].argument)
    }

    @Test
    fun `multiple actions extracted in order`() {
        val input = "<ACTION: show_toast \"hi\"> <ACTION: vibrate_once> <ACTION: open_browser \"https://x.com\">"
        val (text, actions) = ActionExecutor.extractActions(input)
        assertEquals("", text)
        assertEquals(3, actions.size)
        assertEquals("show_toast", actions[0].name)
        assertEquals("hi", actions[0].argument)
        assertEquals("vibrate_once", actions[1].name)
        assertEquals("", actions[1].argument)
        assertEquals("open_browser", actions[2].name)
        assertEquals("https://x.com", actions[2].argument)
    }

    @Test
    fun `unknown action name is filtered out`() {
        val input = "<ACTION: delete_everything \"oops\"> <ACTION: show_toast \"ok\">"
        val (_, actions) = ActionExecutor.extractActions(input)
        assertEquals(1, actions.size)
        assertEquals("show_toast", actions[0].name)
    }

    @Test
    fun `single quotes argument`() {
        val input = "<ACTION: copy_to_clipboard 'single quoted'>"
        val (_, actions) = ActionExecutor.extractActions(input)
        assertEquals(1, actions.size)
        assertEquals("single quoted", actions[0].argument)
    }

    @Test
    fun `no quotes argument`() {
        val input = "<ACTION: open_app com.android.settings>"
        val (_, actions) = ActionExecutor.extractActions(input)
        assertEquals(1, actions.size)
        assertEquals("com.android.settings", actions[0].argument)
    }

    @Test
    fun `case insensitive ACTION keyword`() {
        val input = "<action: show_toast \"test\">"
        val (_, actions) = ActionExecutor.extractActions(input)
        assertEquals(1, actions.size)
        assertEquals("show_toast", actions[0].name)
    }

    @Test
    fun `no space after colon`() {
        val input = "<ACTION:show_toast \"test\">"
        val (_, actions) = ActionExecutor.extractActions(input)
        assertEquals(1, actions.size)
        assertEquals("show_toast", actions[0].name)
    }

    @Test
    fun `space between ACTION and colon`() {
        val input = "<ACTION : show_toast \"test\">"
        val (_, actions) = ActionExecutor.extractActions(input)
        assertEquals(1, actions.size)
        assertEquals("show_toast", actions[0].name)
    }

    @Test
    fun `new open_url and share are in whitelist`() {
        val input = "<ACTION: open_url \"https://a.com\"> <ACTION: share \"share me\">"
        val (_, actions) = ActionExecutor.extractActions(input)
        assertEquals(2, actions.size)
        assertEquals("open_url", actions[0].name)
        assertEquals("https://a.com", actions[0].argument)
        assertEquals("share", actions[1].name)
        assertEquals("share me", actions[1].argument)
    }

    @Test
    fun `friendlyName maps known actions to chinese`() {
        assertEquals("复制", ActionExecutor.friendlyName("copy_to_clipboard"))
        assertEquals("打开应用", ActionExecutor.friendlyName("open_app"))
        assertEquals("打开链接", ActionExecutor.friendlyName("open_browser"))
        assertEquals("打开链接", ActionExecutor.friendlyName("open_url"))
        assertEquals("分享", ActionExecutor.friendlyName("share"))
        assertEquals("提示", ActionExecutor.friendlyName("show_toast"))
        assertEquals("震动", ActionExecutor.friendlyName("vibrate_once"))
    }

    @Test
    fun `friendlyName returns raw name for unknown`() {
        assertEquals("whatever", ActionExecutor.friendlyName("whatever"))
    }

    @Test
    fun `action tag removed from cleaned text`() {
        val input = "前文 <ACTION: show_toast \"x\"> 后文"
        val (text, _) = ActionExecutor.extractActions(input)
        assertEquals("前文  后文", text)
    }

    // ---- code81 P2: 宽容解析（PLAIN_ACTION_REGEX，不带尖括号的格式）----

    @Test
    fun `plain open_app with double quotes no brackets`() {
        val input = "好的 open_app \"com.android.settings\""
        val (text, actions) = ActionExecutor.extractActions(input)
        assertEquals(1, actions.size)
        assertEquals("open_app", actions[0].name)
        assertEquals("com.android.settings", actions[0].argument)
    }

    @Test
    fun `plain open_app with single quotes no brackets`() {
        val input = "open_app 'com.tencent.mm'"
        val (_, actions) = ActionExecutor.extractActions(input)
        assertEquals(1, actions.size)
        assertEquals("open_app", actions[0].name)
        assertEquals("com.tencent.mm", actions[0].argument)
    }

    @Test
    fun `plain open_app no quotes no brackets`() {
        val input = "帮我 open_app com.android.settings"
        val (_, actions) = ActionExecutor.extractActions(input)
        assertEquals(1, actions.size)
        assertEquals("open_app", actions[0].name)
        assertEquals("com.android.settings", actions[0].argument)
    }

    @Test
    fun `bracket format takes priority over plain`() {
        // 带尖括号的格式优先匹配，plain 不参与
        val input = "<open_app \"com.android.settings\">"
        val (_, actions) = ActionExecutor.extractActions(input)
        assertEquals(1, actions.size)
        assertEquals("open_app", actions[0].name)
    }
}
