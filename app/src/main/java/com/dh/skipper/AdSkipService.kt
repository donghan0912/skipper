package com.dh.skipper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AdSkipService : AccessibilityService() {

    companion object {
        private const val TAG = "AdSkipService"
        private val SKIP_KEYWORDS = listOf("跳过", "跳过广告", "Skip", "Skip Ad")
        
        // 【严格匹配】不再允许纯数字。必须包含 s、秒 或 跳过。
        private val COUNTDOWN_PATTERN = Regex("(?i)^(\\d+\\s*[sS秒]|跳过\\s*\\d+|\\d+\\s*跳过)$")
        
        private const val RETRY_DELAY = 400L
        private const val CLICK_COOLDOWN = 2500L 
        private const val SAME_NODE_COOLDOWN = 15000L 
        private const val MAX_TEXT_LENGTH = 10 
        private const val SCAN_INTERVAL = 400L // 两次扫描之间的最小时间间隔，防止高频触发导致卡顿
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastClickTime = 0L
    private var lastScanTime = 0L
    private var lastClickNodeTag = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Service Connected - High Performance Mode Enabled")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val currentTime = System.currentTimeMillis()
        
        // 1. 节流优化：如果距离上次扫描不足 SCAN_INTERVAL，直接忽略。
        // 这能极大地减轻在有动画的页面（如进度条、视频播放）时的 CPU 负载。
        if (currentTime - lastScanTime < SCAN_INTERVAL) return
        
        val type = event?.eventType
        val packageName = event?.packageName?.toString() ?: ""
        
        if (packageName.isBlank() || 
            packageName.contains("home") || 
            packageName.contains("systemui") || 
            packageName == this.packageName
        ) return

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            lastScanTime = currentTime
            if (currentTime - lastClickTime > CLICK_COOLDOWN) {
                tryClickWithRetry()
            }
        }
    }

    override fun onInterrupt() {}

    private fun tryClickWithRetry(retry: Boolean = true) {
        val rootNode = rootInActiveWindow
        if (rootNode != null && processNode(rootNode)) return

        val windows = windows
        for (window in windows) {
            val windowRoot = window.root
            if (windowRoot != null && processNode(windowRoot)) return
        }

        if (retry) {
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({ tryClickWithRetry(false) }, RETRY_DELAY)
        }
    }

    private fun processNode(root: AccessibilityNodeInfo): Boolean {
        val foundNode = findSkipNode(root)
        if (foundNode != null) {
            val tag = "${foundNode.packageName}:${foundNode.viewIdResourceName}:${foundNode.text}"
            
            if (performClick(foundNode)) {
                Log.i(TAG, ">>> SUCCESS: Clicked on $tag")
                lastClickTime = System.currentTimeMillis()
                lastClickNodeTag = tag
                foundNode.recycle()
                root.recycle()
                return true
            }
            foundNode.recycle()
        }
        root.recycle()
        return false
    }

    private fun findSkipNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 性能优化：优先使用系统原生 API 快速搜索关键字节点，这比全量递归扫描快得多
        for (keyword in SKIP_KEYWORDS) {
            val nodes = root.findAccessibilityNodeInfosByText(keyword)
            if (nodes != null) {
                for (node in nodes) {
                    if (isSkipNode(node)) return node
                    // 注意：findAccessibilityNodeInfosByText 返回的列表中的 node 需要手动 recycle，
                    // 但这里我们只找到了就返回，其他的没找到的应该在外部或者遍历中管理。
                    // 简化处理：在这个搜索函数内部如果不返回就 recycle。
                }
            }
        }
        
        // 如果关键字快速搜索没找到（比如是正则匹配的倒计时），再进行深度递归搜索
        return deepSearch(root)
    }

    private fun deepSearch(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (isSkipNode(node)) return AccessibilityNodeInfo.obtain(node)
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = deepSearch(child)
            child.recycle()
            if (result != null) return result
        }
        return null
    }

    private fun isSkipNode(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser) return false

        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val viewId = node.viewIdResourceName ?: ""
        
        // 1. 过滤误报 ID (弹幕、角标)
        if (viewId.contains("cover_left_text") || viewId.contains("bili_badge_view")) return false

        // 2. 字数硬过滤
        if (text.length > MAX_TEXT_LENGTH || desc.length > MAX_TEXT_LENGTH) return false
        
        val tag = "${node.packageName}:$viewId:$text"
        if (tag == lastClickNodeTag && System.currentTimeMillis() - lastClickTime < SAME_NODE_COOLDOWN) {
            return false
        }

        // 3. 匹配逻辑
        // 提取 ID 的最后一部分，避免匹配到包名中的 "skip" (例如 com.dh.skipper)
        val idName = if (viewId.contains("/")) viewId.substringAfterLast("/") else viewId

        val isMatch = SKIP_KEYWORDS.any { text.contains(it) || desc.contains(it) } ||
                     COUNTDOWN_PATTERN.matches(text) || 
                     COUNTDOWN_PATTERN.matches(desc) ||
                     (idName.isNotBlank() && idName.contains("skip", true))

        if (isMatch) {
            Log.d(TAG, "Match Found! Text: $text, Desc: $desc, ID: $viewId")
        }

        return isMatch
    }

    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        // 方案一：坐标模拟点击 (最强力，解决微博点不动的问题)
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val x = rect.centerX().toFloat()
        val y = rect.centerY().toFloat()

        if (x > 0 && y > 0) {
            val path = Path()
            path.moveTo(x, y)
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build()
            
            if (dispatchGesture(gesture, null, null)) {
                Log.d(TAG, "Dispatched gesture click at ($x, $y)")
                return true
            }
        }

        // 方案二：常规点击
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true

        // 方案三：父容器点击
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                parent.recycle()
                return true
            }
            val temp = parent.parent
            parent.recycle()
            parent = temp
        }

        return false
    }
}
