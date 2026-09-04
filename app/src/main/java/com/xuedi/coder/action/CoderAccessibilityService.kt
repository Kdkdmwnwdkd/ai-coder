package com.xuedi.coder.action

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * 【code78 新增】无障碍服务 —— 让 AI 能操控手机上的其他 App。
 *
 * 首次使用：用户在 设置 → 无障碍 → AI编程助手 点一下"允许"。
 * 之后永久生效，App 可随意调用。
 *
 * 指令协议（由 ActionExecutor.executeOne 的 accessibility_action 派发）：
 *   open_app|<包名>|<要搜的词>   → 打开 App + 自动搜关键词
 *   type|<文字>                  → 往当前聚焦输入框输入文字
 *   tap|<控件文本>               → 点击屏幕上文字=X 的控件（找不到就不执行）
 *   swipe_up / swipe_down        → 上下滑动屏幕
 *   back                         → 返回键
 *   home                         → Home 键
 *
 * 注入模式：dispatch() 把指令存进 intentToRun，Service 在 onAccessibilityEvent 里
 * 检测到自己活着就 pop 一条执行。这样 ActionExecutor 不需要持有 Service 实例。
 */
class CoderAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 有指令就 pop 一条跑（每条指令耗时 <2s，不累积）
        val cmd = pendingCommands.poll() ?: return
        android.util.Log.i(TAG, "🎯 执行指令: ${cmd.joinToString(" | ")}")
        runCatching { execute(cmd) }
    }

    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        super.onServiceConnected()
        android.util.Log.i(TAG, "✅ CoderAccessibilityService 已启动")
    }

    // ------------------------------------------------------------------
    //  外部入口：ActionExecutor 调这个派指令
    // ------------------------------------------------------------------

    companion object {
        private const val TAG = "AccessService"
        private val pendingCommands = java.util.concurrent.ConcurrentLinkedQueue<List<String>>()
        private val isAlive = java.util.concurrent.atomic.AtomicBoolean(false)
        private var INSTANCE: CoderAccessibilityService? = null

        /** Service 内部注册自己 */
        fun register(s: CoderAccessibilityService) {
            INSTANCE = s
            isAlive.set(true)
        }
        fun unregister(s: CoderAccessibilityService) {
            if (INSTANCE === s) { INSTANCE = null; isAlive.set(false) }
        }

        /**
         * 派发一条指令。返回 false 表示服务没授权/没启动（调用方应引导用户去设置）。
         */
        fun dispatch(ctx: Context, parts: List<String>): Boolean {
            if (!isAlive.get()) return false
            pendingCommands.add(parts.toList())
            return true
        }
    }

    // ------------------------------------------------------------------
    //  指令执行
    // ------------------------------------------------------------------

    private fun execute(parts: List<String>) {
        val cmd = parts.firstOrNull() ?: return
        when (cmd) {
            "open_app" -> executeOpenApp(parts)
            "type" -> executeType(parts.getOrElse(1) { "" })
            "tap" -> executeTapByText(parts.getOrElse(1) { "" })
            "swipe_up" -> executeSwipe(direction = -1)
            "swipe_down" -> executeSwipe(direction = 1)
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    /** open_app|<包名>|<要搜的词> — 打开 App → 等 2s → 自动搜 */
    private fun executeOpenApp(parts: List<String>) {
        val pkg = parts.getOrNull(1) ?: return
        val keyword = parts.getOrNull(2)
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
        } else {
            Log.w(TAG, "open_app: 没找到包 $pkg")
            return
        }
        // 等 2s 让 App 完全启动
        sleepHuman(2000, 500)
        // 自动搜索（快手/抖音/B站 通用逻辑：点"搜索"→输入词→点回车）
        if (!keyword.isNullOrBlank()) {
            tapByText("搜索")
            sleepHuman(800, 300)
            tapByText("放大镜")
            sleepHuman(500, 200)
            executeType(keyword)
            sleepHuman(600, 200)
            // 搜索框里通常有个"搜索"/"回车"按钮，或者直接点 IME 的回车
            pressImeEnterOrTapSearch()
        }
    }

    /** 搜索/放大镜图标按钮 */
    private fun tapByText(vararg keys: String): Boolean {
        val root = rootInActiveWindow ?: return false
        // 广度优先遍历，找文字包含任意 key 的可点击控件
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val n = queue.poll()!!
            val t = n.text?.toString() ?: ""
            if (t.isNotBlank()) {
                for (k in keys) {
                    if (t.contains(k)) {
                        if (clickOrPerformAction(n)) {
                            Log.i(TAG, "  点击了 '$t'")
                            return true
                        }
                    }
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return false
    }

    /** type|文字 — 往当前聚焦输入框输入 */
    private fun executeType(text: String) {
        if (text.isBlank()) return
        val root = rootInActiveWindow ?: return
        // 优先找聚焦的可编辑输入框
        val focused = findFocusedEditable(root)
        if (focused != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val args = android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text as CharSequence)
                }
                focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
            // 再不行就用手势逐字打字（dispatchGesture）
        } else {
            // 没聚焦 → 找第一个可编辑输入框点击聚焦 → 再输入
            val editable = findFirstEditable(root)
            if (editable != null) clickOrPerformAction(editable)
            sleepHuman(300, 150)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val focused2 = findFocusedEditable(rootInActiveWindow ?: return)
                if (focused2 != null) {
                    val args = android.os.Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text as CharSequence)
                    }
                    focused2.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                }
            }
        }
        Log.i(TAG, "  输入: ${text.take(30)}...")
    }

    private fun executeTapByText(text: String) {
        if (text.isBlank()) return
        val ok = tapByText(text)
        if (!ok) {
            Log.w(TAG, "  没找到文字='$text' 的可点击控件")
        }
    }

    /** 按方向滑动屏幕（direction = -1 向上 / +1 向下） */
    private fun executeSwipe(direction: Int) {
        val root = rootInActiveWindow ?: return
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val cx = w / 2 + rnd(-30, 30)
        val startY = (if (direction < 0) h * 0.7 else h * 0.3).toInt() + rnd(-50, 50)
        val endY   = (if (direction < 0) h * 0.3 else h * 0.7).toInt() + rnd(-50, 50)
        val path = Path().apply { moveTo(cx.toFloat(), startY.toFloat()); lineTo(cx.toFloat(), endY.toFloat()) }
        val desc = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        dispatchGesture(desc, null, null)
    }

    private fun pressImeEnterOrTapSearch() {
        // 先尝试点"搜索"按钮，不行就发送 IME 回车
        val ok = tapByText("搜索", "搜一下", "go", "GO", "确定")
        if (!ok) {
            // 没找到按钮，就用手势点屏幕右下角（搜索按钮通常在那里）
            val root = rootInActiveWindow ?: return
            val dm2 = resources.displayMetrics
            val x = (dm2.widthPixels * 0.85f) + rnd(-20, 20)
            val y = (dm2.heightPixels * 0.9f) + rnd(-20, 20)
            val path = Path().apply { moveTo(x, y) }
            val desc = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build()
            dispatchGesture(desc, null, null)
        }
    }

    // ------------------------------------------------------------------
    //  辅助函数
    // ------------------------------------------------------------------

    private fun clickOrPerformAction(n: AccessibilityNodeInfo): Boolean {
        if (n.isClickable) {
            n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return true
        }
        // 父节点链上找 clickable 的
        var p = n.parent
        while (p != null) {
            if (p.isClickable) {
                p.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
            p = p.parent
        }
        // 都不 clickable → 模拟点它的矩形中心
        val rect = android.graphics.Rect()
        n.getBoundsInScreen(rect)
        val cx = rect.centerX().toFloat() + rnd(-10, 10)
        val cy = rect.centerY().toFloat() + rnd(-10, 10)
        val path = Path().apply { moveTo(cx, cy) }
        val desc = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        dispatchGesture(desc, null, null)
        return true
    }

    private fun findFocusedEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused?.isEditable == true) return focused
        return null
    }

    private fun findFirstEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val n = queue.poll()!!
            if (n.isEditable && !n.text?.toString().isNullOrBlank()) {
                // 已经有文字的跳过，找空的
            }
            if (n.isEditable) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    /** 人类随机化 —— 避免行为指纹被风控识别 */
    private fun sleepHuman(baseMs: Long, jitterMs: Long) {
        val wait = baseMs + Random.nextLong(-jitterMs, jitterMs + 1)
        Thread.sleep(max(0, wait))
    }
    private fun rnd(lo: Int, hi: Int) = Random.nextInt(min(lo, hi), max(lo, hi) + 1)

    override fun onDestroy() {
        super.onDestroy()
        unregister(this)
    }
}
