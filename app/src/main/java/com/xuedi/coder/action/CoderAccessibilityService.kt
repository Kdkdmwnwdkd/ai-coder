package com.xuedi.coder.action

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
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
        // code89: 指令执行已改为 dispatch() 直接 post 到 execHandler，不再依赖事件触发。
        // 这里保留兜底：若队列里还有未执行的指令（极端情况），也 post 到后台线程执行。
        val cmd = pendingCommands.poll() ?: return
        val inst = INSTANCE ?: return
        execHandler?.post {
            android.util.Log.i(TAG, "🎯 [事件兜底] 执行指令: ${cmd.joinToString(" | ")}")
            runCatching { inst.execute(cmd) }
                .onFailure { android.util.Log.e(TAG, "❌ 执行异常: ${it.message}", it) }
        }
    }

    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        super.onServiceConnected()
        register(this)
        android.util.Log.i(TAG, "✅ CoderAccessibilityService 已启动并注册")
    }

    /** 显示 Toast 提示（在主线程），让用户能看到执行进度 */
    private fun toast(msg: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
        Log.i(TAG, "📢 $msg")
    }

    // ------------------------------------------------------------------
    //  外部入口：ActionExecutor 调这个派指令
    // ------------------------------------------------------------------

    companion object {
        private const val TAG = "AccessService"
        private val pendingCommands = java.util.concurrent.ConcurrentLinkedQueue<List<String>>()
        private val isAlive = java.util.concurrent.atomic.AtomicBoolean(false)
        private var INSTANCE: CoderAccessibilityService? = null
        private var execThread: android.os.HandlerThread? = null
        private var execHandler: android.os.Handler? = null

        /** Service 内部注册自己 */
        fun register(s: CoderAccessibilityService) {
            INSTANCE = s
            isAlive.set(true)
            if (execThread == null) {
                execThread = android.os.HandlerThread("AccessExec").apply { start() }
                execHandler = android.os.Handler(execThread!!.looper)
            }
            android.util.Log.i(TAG, "✅ register: 服务已注册，execThread 已就绪")
        }
        fun unregister(s: CoderAccessibilityService) {
            if (INSTANCE === s) {
                INSTANCE = null
                isAlive.set(false)
                execThread?.quitSafely()
                execThread = null
                execHandler = null
            }
        }

        /**
         * 派发一条指令。返回 false 表示服务没授权/没启动（调用方应引导用户去设置）。
         *
         * 【code89 修复】不再依赖 onAccessibilityEvent 来消费队列（屏幕静止时根本不触发）。
         * 改为：直接把执行任务 post 到专用后台线程 execHandler，立即执行。
         */
        fun dispatch(ctx: Context, parts: List<String>): Boolean {
            if (!isAlive.get()) {
                android.util.Log.w(TAG, "❌ dispatch 失败: 无障碍服务未启动/未授权")
                return false
            }
            val inst = INSTANCE
            if (inst == null) {
                android.util.Log.w(TAG, "❌ dispatch 失败: INSTANCE 为空")
                return false
            }
            val cmd = parts.toList()
            pendingCommands.add(cmd)
            android.util.Log.i(TAG, "📥 dispatch 入队: ${cmd.joinToString(" | ")}")
            // 立即在后台线程执行，不依赖系统无障碍事件
            execHandler?.post {
                val queued = pendingCommands.poll() ?: return@post
                android.util.Log.i(TAG, "🎯 开始执行指令: ${queued.joinToString(" | ")}")
                runCatching { inst.execute(queued) }
                    .onFailure { android.util.Log.e(TAG, "❌ 执行指令异常: ${it.message}", it) }
                android.util.Log.i(TAG, "🏁 指令执行完毕: ${queued.firstOrNull()}")
            } ?: run {
                android.util.Log.e(TAG, "❌ execHandler 为空，无法执行")
                return false
            }
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
            "send_message" -> executeSendMessage(parts)
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
        // 等 2.5s 让 App 完全启动（首屏加载）
        sleepHuman(2500, 500)
        // 处理应用分身选择对话框
        handleAppChooser()
        // 自动搜索（快手/抖音/B站 通用逻辑：点搜索图标→输入词→点回车）
        if (!keyword.isNullOrBlank()) {
            // 1. 找搜索入口：文字"搜索"/"放大镜" 或 contentDescription="搜索"
            var found = tapByText("搜索", "放大镜", "search", "Search") ||
                    tapByContentDesc("搜索", "放大镜", "search", "Search")
            // 2. 兜底：快手/抖音等 App 的搜索图标只有图片没有文字/desc，直接点右上角
            if (!found) {
                Log.i(TAG, "  文字/desc 没找到搜索入口，点击右上角搜索图标坐标")
                found = tapTopRightSearchIcon()
            }
            if (found) {
                sleepHuman(1200, 300)
                // 3. 输入关键词
                executeType(keyword)
                sleepHuman(1000, 200)
                // 4. 按回车搜索
                pressImeEnterOrTapSearch()
            } else {
                Log.w(TAG, "  没找到搜索入口，只打开了 App")
            }
        }
    }

    /**
     * send_message|<包名>|<联系人>|<消息内容> — 打开 App → 找联系人 → 打开聊天 → 输入 → 发送
     *
     * code91 修复要点：
     *   1) 搜索后必须按 IME 回车触发搜索（否则 QQ 只显示输入框文字，不出结果列表）
     *   2) 点搜索结果时跳过可编辑节点（避免把搜索框本身当成联系人点了）
     *   3) 打开联系人后验证确实进入了聊天页（顶部搜索框消失），否则重试
     *   4) 输入消息时定位"底部"输入框，而非顶部搜索框
     *   5) 发送按钮兼容文字/ContentDescription/输入框右侧图标点击
     */
    private fun executeSendMessage(parts: List<String>) {
        val pkg = parts.getOrNull(1) ?: return
        val contact = parts.getOrNull(2)?.trim().orEmpty()
        val message = parts.getOrNull(3)?.trim().orEmpty()
        if (contact.isEmpty() || message.isEmpty()) {
            Log.w(TAG, "send_message: contact 或 message 为空")
            return
        }
        Log.i(TAG, "send_message: pkg=$pkg contact='$contact' msg='${message.take(20)}'")
        toast("正在打开应用…")

        // 1. 打开 App
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(launch)
        } else {
            Log.w(TAG, "send_message: 没找到包 $pkg")
            toast("未安装该应用")
            return
        }
        sleepHuman(3000, 500)

        // 1.5 处理应用分身选择对话框（如"选择应用 / 微信 / 微信分身"）
        handleAppChooser()

        // 2. 找联系人：先在聊天列表里找（含滚动），找不到再搜
        toast("查找联系人：$contact")
        var opened = openContactFromList(contact)
        if (!opened) {
            opened = openContactViaSearch(contact)
        }
        if (!opened) {
            Log.w(TAG, "send_message: 没找到联系人 '$contact'")
            toast("未找到联系人：$contact")
            return
        }

        // 验证确实进入了聊天页（顶部搜索框应消失）
        sleepHuman(1500, 300)
        if (isSearchBoxStillVisible()) {
            Log.w(TAG, "send_message: 仍在搜索页，聊天未打开，重试一次")
            performGlobalAction(GLOBAL_ACTION_BACK)
            sleepHuman(800, 200)
            opened = openContactViaSearch(contact)
            if (!opened) {
                toast("未找到联系人：$contact")
                return
            }
            sleepHuman(1500, 300)
        }
        Log.i(TAG, "send_message: 已打开与 '$contact' 的聊天")
        toast("已打开聊天：$contact")
        sleepHuman(800, 200)

        // 3. 输入消息：定位底部聊天输入框（不是顶部搜索框）
        tapBottomInputBox()
        sleepHuman(700, 200)
        executeType(message)
        sleepHuman(1200, 300)

        // 4. 点发送按钮
        toast("正在发送消息…")
        var sent = tapByText("发送", "send", "Send", "SEND", "发 送") ||
                tapByContentDesc("发送", "send", "Send", "发送消息", "send message", "发送按钮")
        if (!sent) {
            sleepHuman(600, 200)
            sent = tapByText("发送", "send", "Send", "发 送") ||
                    tapByContentDesc("发送", "send", "Send", "发送消息")
            if (!sent) {
                // 点击输入框右侧（QQ 发送按钮常是图标，在输入框右边）
                sent = tapRightOfBottomInputBox()
            }
        }
        Log.i(TAG, "send_message: 给 '$contact' 发送流程完成 (sent=$sent)")
        toast(if (sent) "消息已发送" else "发送流程完成")
    }

    /**
     * 处理应用分身选择对话框。
     * 打开有分身的 App 时，系统会弹出"选择应用 / 微信 / 微信分身"对话框。
     * 检测到后自动点击第一个非"分身"的应用（即本应用）。
     */
    private fun handleAppChooser() {
        val root = getRoot() ?: return
        // 检测是否有"选择应用"对话框
        val hasChooser = tapByText("选择应用", "选择打开方式", "Open with", "选择")
        if (!hasChooser) return
        Log.i(TAG, "  handleAppChooser: 检测到应用选择对话框，选择本应用")
        sleepHuman(500, 200)
        // 点击第一个不带"分身"字样的应用选项
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val n = queue.poll()!!
            val t = n.text?.toString() ?: ""
            val desc = n.contentDescription?.toString() ?: ""
            val combined = "$t $desc"
            // 跳过"选择应用"、"设为默认"等标题/按钮，以及带"分身"的选项
            if (combined.isNotBlank() &&
                !combined.contains("选择应用") &&
                !combined.contains("选择打开方式") &&
                !combined.contains("设为默认") &&
                !combined.contains("仅一次") &&
                !combined.contains("始终") &&
                !combined.contains("分身") &&
                !combined.contains("取消")) {
                if (clickOrPerformAction(n)) {
                    Log.i(TAG, "  handleAppChooser: 点击了本应用 '$t'")
                    sleepHuman(1500, 300)
                    return
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        // 兜底：如果上面没找到，直接点第一个可点击项
        Log.w(TAG, "  handleAppChooser: 没找到明确的本应用选项，尝试点第一个可点击项")
        tapFirstClickable(root)
        sleepHuman(1500, 300)
    }

    /** 点击第一个可点击节点（兜底用） */
    private fun tapFirstClickable(root: AccessibilityNodeInfo) {
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val n = queue.poll()!!
            if (n.isClickable) {
                if (clickOrPerformAction(n)) return
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
    }

    /** 在聊天列表中找联系人（含上下滚动） */
    private fun openContactFromList(contact: String): Boolean {
        dumpVisibleTexts("列表查找第0屏")
        for (scroll in 0..3) {
            if (tapByText(contact, skipEditable = true)) return true
            if (scroll < 3) {
                executeSwipe(direction = -1)  // 向上滚动看更多
                sleepHuman(700, 200)
                if (scroll == 0) dumpVisibleTexts("列表查找第1屏")
            }
        }
        // 滚回顶部
        for (i in 0..2) {
            executeSwipe(direction = 1)
            sleepHuman(500, 100)
        }
        return false
    }

    /** 点击右上角搜索图标（微信/QQ 等搜索图标可能没有 text/contentDescription） */
    private fun tapTopRightSearchIcon(): Boolean {
        val dm = resources.displayMetrics
        val x = (dm.widthPixels - dm.density * 50).toInt() + rnd(-15, 15)
        val y = (dm.density * 55).toInt() + rnd(-15, 15)
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val desc = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        val ok = dispatchGesture(desc, null, null)
        Log.i(TAG, "  tapTopRightSearchIcon: 点击 ($x,$y) dispatchGesture=$ok")
        return ok
    }

    /** 通过搜索找联系人：点搜索 → 输入 → 等实时结果 → 点结果 */
    private fun openContactViaSearch(contact: String): Boolean {
        Log.i(TAG, "send_message: 尝试搜索 '$contact'")
        var searchOk = tapByText("搜索", "搜索联系人", "放大镜", "search", "Search", "🔍") ||
                tapByContentDesc("搜索", "放大镜", "search", "Search", "Search contacts", "搜索联系人", "联系人")
        if (!searchOk) {
            // 兜底：微信等 App 的搜索图标可能没有 text/contentDescription，直接点右上角
            Log.i(TAG, "send_message: 文字/desc 没找到搜索入口，尝试点击右上角搜索图标坐标")
            searchOk = tapTopRightSearchIcon()
        }
        if (!searchOk) {
            Log.w(TAG, "send_message: 没找到搜索入口")
            return false
        }
        sleepHuman(1200, 300)
        // dump 当前界面，方便诊断
        dumpVisibleTexts("搜索页输入前")
        // 输入联系人名
        executeType(contact)
        sleepHuman(1500, 300)  // QQ 实时搜索，等结果出来
        dumpVisibleTexts("搜索页输入后")
        // 先直接尝试点结果（QQ 搜索是实时的，输入完就有结果）
        for (attempt in 1..3) {
            if (tapByText(contact, skipEditable = true)) return true
            sleepHuman(700, 200)
        }
        // 没结果 → 尝试触发搜索（IME回车 或 键盘右下角）
        Log.i(TAG, "  实时搜索没结果，尝试触发搜索")
        pressImeEnter()
        sleepHuman(1200, 300)
        for (attempt in 1..3) {
            if (tapByText(contact, skipEditable = true)) return true
            sleepHuman(700, 200)
        }
        return false
    }

    /** 按 IME 回车（触发搜索/确认）。注意：不要点"搜索"文字，否则可能退出搜索页 */
    private fun pressImeEnter() {
        val root = getRoot() ?: return
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findFirstEditable(root)
        if (focused != null) {
            // ACTION_IME_ENTER = 0x01020026（API 26+，直接用数值）
            val ok = focused.performAction(0x01020026)
            Log.i(TAG, "  pressImeEnter: ACTION_IME_ENTER=$ok")
            if (!ok) {
                // 兜底：点键盘右下角（回车/搜索键），不要点"搜索"文字
                tapBottomRight()
                sleepHuman(300, 100)
            }
        }
    }

    /** dump 当前界面所有可见的 text 和 contentDescription（诊断用） */
    /**
     * 获取当前最可能的窗口根节点。
     * rootInActiveWindow 有时拿不到目标 App 的窗口（如应用分身对话框关闭后、
     * 或多窗口场景），此时回退到 getWindows() 里找第一个有内容的窗口。
     */
    private fun getRoot(): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { return it }
        // 回退：扫描所有窗口，返回第一个有 root 的
        for (w in windows) {
            val r = w.root ?: continue
            if (r.childCount > 0) return r
        }
        return null
    }

    private fun dumpVisibleTexts(tag: String) {
        val root = getRoot() ?: return
        val sb = StringBuilder()
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var count = 0
        while (queue.isNotEmpty() && count < 300) {
            val n = queue.poll()!!
            val t = n.text?.toString() ?: ""
            val desc = n.contentDescription?.toString() ?: ""
            if (t.isNotBlank() || desc.isNotBlank()) {
                sb.append("[$t][$desc] ")
                count++
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        Log.i(TAG, "  dump[$tag]: ${sb.toString().take(600)}")
    }

    /** 判断顶部搜索框是否仍可见（用于判断是否还在搜索页） */
    private fun isSearchBoxStillVisible(): Boolean {
        val root = getRoot() ?: return false
        val dm = resources.displayMetrics
        val topLimit = dm.heightPixels * 0.3f
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val n = queue.poll()!!
            if (n.isEditable || (n.className?.toString()?.contains("EditText") == true)) {
                val rect = android.graphics.Rect()
                n.getBoundsInScreen(rect)
                if (rect.centerY() < topLimit) {
                    Log.i(TAG, "  isSearchBoxStillVisible: 顶部仍有输入框 y=${rect.centerY()}")
                    return true
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return false
    }

    /** 点击底部聊天输入框（y > 屏幕 50%），避免误点顶部搜索框 */
    private fun tapBottomInputBox() {
        val root = getRoot() ?: run {
            Log.w(TAG, "  tapBottomInputBox: 找不到窗口根节点")
            return
        }
        val dm = resources.displayMetrics
        val midY = dm.heightPixels * 0.5f
        // 收集所有可编辑节点，选 y 最大的（最靠底部）
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val n = queue.poll()!!
            val cls = n.className?.toString() ?: ""
            if (n.isEditable || cls.contains("EditText") || cls.contains("Input")) {
                candidates.add(n)
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        val bottom = candidates
            .mapNotNull { n ->
                val r = android.graphics.Rect()
                n.getBoundsInScreen(r)
                if (r.centerY() > midY) n to r.centerY() else null
            }
            .maxByOrNull { it.second }?.first
        if (bottom != null) {
            clickOrPerformAction(bottom)
            Log.i(TAG, "  点击了底部输入框: cls=${bottom.className}")
            return
        }
        // 兜底：点击屏幕底部中间
        Log.w(TAG, "  没找到底部输入框，点击底部区域")
        val x = dm.widthPixels / 2f
        val y = dm.heightPixels * 0.85f
        val path = Path().apply { moveTo(x, y) }
        dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build(), null, null)
    }

    /** 点击底部输入框的右侧（发送按钮常为图标，无文字） */
    private fun tapRightOfBottomInputBox(): Boolean {
        val root = getRoot() ?: return false
        val dm = resources.displayMetrics
        val midY = dm.heightPixels * 0.5f
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var bottomRect: android.graphics.Rect? = null
        while (queue.isNotEmpty()) {
            val n = queue.poll()!!
            val cls = n.className?.toString() ?: ""
            if (n.isEditable || cls.contains("EditText")) {
                val r = android.graphics.Rect()
                n.getBoundsInScreen(r)
                if (r.centerY() > midY && (bottomRect == null || r.centerY() > bottomRect.centerY())) {
                    bottomRect = r
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        if (bottomRect != null) {
            val x = bottomRect.right + 40f + rnd(-10, 10)
            val y = bottomRect.centerY().toFloat() + rnd(-10, 10)
            val path = Path().apply { moveTo(x, y) }
            dispatchGesture(GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build(), null, null)
            Log.i(TAG, "  点击输入框右侧发送区域: x=$x y=$y")
            return true
        }
        return false
    }

    /** 找并点击屏幕上的输入框，用于发消息前聚焦 */
    private fun tapInputBox() {
        val root = getRoot() ?: run {
            Log.w(TAG, "  tapInputBox: 找不到窗口根节点")
            return
        }
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val n = queue.poll()!!
            val cls = n.className?.toString() ?: ""
            // 更宽泛的匹配：EditText / 可编辑 / className 含 Input / 底部输入区域
            val isInputBox = cls.contains("EditText") ||
                    n.isEditable ||
                    cls.contains("Input") ||
                    cls.contains("input")
            if (isInputBox) {
                if (clickOrPerformAction(n)) {
                    Log.i(TAG, "  点击了输入框: cls=$cls editable=${n.isEditable}")
                    return
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        // 兜底：点击屏幕底部中间区域（输入框通常在底部）
        Log.w(TAG, "  没找到输入框，尝试点击底部区域")
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val y = dm.heightPixels * 0.85f
        val path = Path().apply { moveTo(x, y) }
        val desc = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        dispatchGesture(desc, null, null)
    }

    /**
     * 输入文字：优先用 ACTION_SET_TEXT，失败则用剪贴板粘贴（最可靠）。
     * QQ/微信等应用会拦截 ACTION_SET_TEXT，剪贴板粘贴基本都能工作。
     * 微信的输入框不暴露 isEditable，需要用坐标点击聚焦 + 剪贴板粘贴兜底。
     */
    private fun executeType(text: String) {
        if (text.isBlank()) return
        val root = getRoot()

        // 1. 先找聚焦的输入框
        var target = findFocusedEditable()

        // 2. 没聚焦 → 找可编辑/输入框节点点击聚焦
        if (target == null && root != null) {
            target = findFirstEditable(root)
            if (target != null) {
                clickOrPerformAction(target)
                sleepHuman(500, 200)
                target = findFocusedEditable() ?: target
            }
        }

        // 3. 还是找不到 → 微信等不暴露 isEditable 的情况，用坐标点底部聚焦
        if (target == null) {
            Log.w(TAG, "  executeType: 没找到可编辑节点，尝试点击底部输入区聚焦")
            tapBottomAreaToFocus()
            sleepHuman(600, 200)
            target = findFocusedEditable()
        }

        if (target == null) {
            // 4. 最后兜底：直接剪贴板粘贴到坐标
            Log.w(TAG, "  executeType: 仍无输入框，直接剪贴板粘贴到底部区域")
            pasteViaClipboardAtBottom(text)
            return
        }

        // 方案 1：ACTION_SET_TEXT（直接设置文本）
        var inputOk = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            inputOk = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.i(TAG, "  ACTION_SET_TEXT 结果=$inputOk")
        }

        // 方案 2：剪贴板粘贴（QQ/微信等拦截 ACTION_SET_TEXT 时用这个）
        if (!inputOk) {
            Log.i(TAG, "  ACTION_SET_TEXT 失败，改用剪贴板粘贴")
            pasteViaClipboard(text)
        }

        Log.i(TAG, "  输入: ${text.take(30)}...")
    }

    /** 点击屏幕底部中间区域聚焦输入框（微信等不暴露 isEditable 的应用） */
    private fun tapBottomAreaToFocus() {
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f + rnd(-30, 30)
        val y = dm.heightPixels * 0.85f + rnd(-20, 20)
        val path = Path().apply { moveTo(x, y) }
        val desc = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        dispatchGesture(desc, null, null)
    }

    /** 直接把文本粘贴到屏幕底部输入区（找不到输入框节点时的最后兜底） */
    private fun pasteViaClipboardAtBottom(text: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("ai_coder_msg", text))
            sleepHuman(300, 100)
            // 长按底部输入区
            val dm = resources.displayMetrics
            val x = dm.widthPixels / 2f + rnd(-30, 30)
            val y = dm.heightPixels * 0.85f + rnd(-20, 20)
            val path = Path().apply { moveTo(x, y) }
            val desc = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 800))  // 长按
                .build()
            dispatchGesture(desc, null, null)
            sleepHuman(700, 200)
            // 点"粘贴"
            val pasted = tapByText("粘贴", "Paste", "paste")
            Log.i(TAG, "  pasteViaClipboardAtBottom: 粘贴=$pasted")
        } catch (e: Exception) {
            Log.e(TAG, "  pasteViaClipboardAtBottom 异常: ${e.message}")
        }
    }

    /** 通过剪贴板粘贴文本：复制到剪贴板 → 长按输入框 → 点粘贴 */
    private fun pasteViaClipboard(text: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("ai_coder_msg", text))
            sleepHuman(300, 100)

            val root = getRoot() ?: return
            val editable = findFocusedEditable() ?: findFirstEditable(root) ?: return

            // 长按输入框
            editable.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            sleepHuman(600, 200)

            // 点"粘贴"
            val pasted = tapByText("粘贴", "Paste", "paste")
            if (!pasted) {
                // 有些应用弹出的菜单里"粘贴"可能在 contentDescription
                tapByContentDesc("粘贴", "Paste", "paste")
            }
            Log.i(TAG, "  剪贴板粘贴完成 pasted=$pasted")
        } catch (e: Exception) {
            Log.e(TAG, "  剪贴板粘贴异常: ${e.message}", e)
        }
    }

    /** 点击屏幕右下角（发送按钮通常在那里） */
    private fun tapBottomRight() {
        val dm = resources.displayMetrics
        val x = (dm.widthPixels * 0.9f) + rnd(-15, 15)
        val y = (dm.heightPixels * 0.92f) + rnd(-15, 15)
        val path = Path().apply { moveTo(x, y) }
        val desc = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        dispatchGesture(desc, null, null)
    }

    /**
     * 按文字查找并点击控件（同时匹配 text 和 contentDescription）。
     * @param skipEditable true 时跳过可编辑节点（搜索结果点击时避免点到搜索框本身）
     */
    private fun tapByText(vararg keys: String, skipEditable: Boolean = false): Boolean {
        val root = getRoot() ?: return false
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var scanned = 0
        while (queue.isNotEmpty() && scanned < 2000) {
            val n = queue.poll()!!
            scanned++
            if (skipEditable && (n.isEditable || n.className?.toString()?.contains("EditText") == true)) {
                for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
                continue
            }
            val t = n.text?.toString() ?: ""
            val desc = n.contentDescription?.toString() ?: ""
            if (t.isNotBlank() || desc.isNotBlank()) {
                for (k in keys) {
                    if (t.contains(k) || desc.contains(k)) {
                        if (clickOrPerformAction(n)) {
                            Log.i(TAG, "  点击了文字='$t' desc='$desc'")
                            return true
                        }
                    }
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return false
    }

    /** 按 contentDescription 查找可点击控件（搜索图标通常没有 text 但有 contentDescription） */
    private fun tapByContentDesc(vararg keys: String): Boolean {
        val root = getRoot() ?: return false
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var scanned = 0
        while (queue.isNotEmpty() && scanned < 2000) {
            val n = queue.poll()!!
            scanned++
            val desc = n.contentDescription?.toString() ?: ""
            if (desc.isNotBlank()) {
                for (k in keys) {
                    if (desc.contains(k, ignoreCase = true)) {
                        if (clickOrPerformAction(n)) {
                            Log.i(TAG, "  点击了 contentDescription='$desc'")
                            return true
                        }
                    }
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return false
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
        val root = getRoot() ?: return
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
            val root = getRoot() ?: return
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

    private fun findFocusedEditable(): AccessibilityNodeInfo? {
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        // 微信等输入框可能不设 isEditable，只要是聚焦节点就用
        return focused
    }

    /** 找最靠底部的可编辑节点（聊天输入框在底部，搜索框在顶部） */
    private fun findFirstEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val dm = resources.displayMetrics
        val midY = dm.heightPixels * 0.5f
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val n = queue.poll()!!
            val cls = n.className?.toString() ?: ""
            // 放宽匹配：isEditable / EditText / focusable + (Input类名)
            val isInput = n.isEditable ||
                    cls.contains("EditText") ||
                    cls.contains("Input") ||
                    (n.isFocusable && (cls.contains("input") || cls.contains("Edit")))
            if (isInput) {
                candidates.add(n)
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        // 优先选底部（y > 50% 屏幕）的输入框；没有则选任意一个
        return candidates
            .mapNotNull { n ->
                val r = android.graphics.Rect()
                n.getBoundsInScreen(r)
                if (r.centerY() > midY) n to r.centerY() else null
            }
            .maxByOrNull { it.second }?.first
            ?: candidates.firstOrNull()
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
