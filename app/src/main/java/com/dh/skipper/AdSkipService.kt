package com.dh.skipper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.SharedPreferences
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

class AdSkipService : AccessibilityService() {

    companion object {
        private const val TAG = "AdSkipService"
        private const val PREF_NAME = "skipper_config"
        private const val KEY_ENABLED_APPS = "enabled_apps"
        private val SKIP_KEYWORDS = listOf("跳过", "跳过广告", "Skip", "Skip Ad")
        
        // 【严格匹配】不再允许纯数字。必须包含 s、秒 或 跳过。
        private val COUNTDOWN_PATTERN = Regex("(?i)^(\\d+\\s*[sS秒]|跳过\\s*\\d+|\\d+\\s*跳过)$")
        
        private const val RETRY_DELAY = 400L
        private const val CLICK_COOLDOWN = 2500L
        private const val SAME_NODE_COOLDOWN = 15000L 
        private const val MAX_TEXT_LENGTH = 10
        private const val SCAN_INTERVAL = 400L // 两次扫描之间的最小时间间隔，防止高频触发导致卡顿
        private const val WINDOW_TIMEOUT = 5000L // 窗口过期时间 (5秒内处理启动广告)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastClickTime = 0L
    private var lastScanTime = 0L
    private var lastClickNodeTag = ""
    private var currentPackage = ""
    private var packageStartTime = 0L
    
    // 页面指纹管理器
    private lateinit var fingerprintManager: PageFingerprintManager
    
    // 缓存所有要处理的 APP 页面路径 (包名)
    private var enabledAppPages = mutableSetOf<String>()
    
    // 监听配置变化
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == KEY_ENABLED_APPS) {
            refreshPageCache(prefs)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        fingerprintManager = PageFingerprintManager(this)

        val prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
        refreshPageCache(prefs)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        
        Logger.d(TAG, "Service Connected - Cache Initialized: ${enabledAppPages.size} apps")


    }

    override fun onDestroy() {
        super.onDestroy()
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val currentTime = System.currentTimeMillis()
        val packageName = event?.packageName?.toString() ?: ""
        
        if (packageName.isBlank() || packageName == this.packageName) return

        // 1. 检测应用切换，重置窗口计时
        if (packageName != currentPackage) {
            currentPackage = packageName
            packageStartTime = currentTime
            Logger.d(TAG, ">>> 进入应用: $packageName, 重置 5s 窗口")
        }

        // 2. 核心逻辑：只在窗口期内运行
        if (currentTime - packageStartTime > WINDOW_TIMEOUT) {
            // 超过 5 秒，不再扫描
            return
        }

        if (currentTime - lastScanTime < SCAN_INTERVAL) return

        // 核心逻辑：从缓存中匹配已开启的应用
        if (!isPackageEnabled(packageName)) return

        val type = event?.eventType
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

    private fun isPackageEnabled(packageName: String): Boolean {
        return enabledAppPages.contains(packageName)
    }

    private fun refreshPageCache(prefs: SharedPreferences) {
        val apps = prefs.getStringSet(KEY_ENABLED_APPS, null)
        enabledAppPages = apps?.toMutableSet() ?: mutableSetOf()
        Logger.d(TAG, "Page cache refreshed: $enabledAppPages")
    }

    private fun tryClickWithRetry(retry: Boolean = true) {
        val rootNode = rootInActiveWindow ?: return
        
        val packageName = rootNode.packageName?.toString() ?: ""
        val fingerprint = fingerprintManager.calculateFingerprint(rootNode)
        
        // 1. 优先尝试匹配指纹缓存
        val cachedAction = fingerprintManager.getMatchedAction(packageName, fingerprint)
        if (cachedAction != null) {
            if (performActionClick(rootNode, cachedAction)) {
                Logger.i(TAG, ">>> [命中缓存] 秒跳成功: $packageName")
                rootNode.recycle()
                return
            }
        }

        // 2. 缓存未命中，走全量扫描（学习模式）
        //    发现新的跳过按钮会追加新指纹，一个 app 可以有多条指纹
        if (processNode(rootNode, fingerprint)) {
            // rootNode 会在 processNode 中 recycle
            return
        }

        val windows = windows
        for (window in windows) {
            val windowRoot = window.root
            if (windowRoot != null) {
                if (processNode(windowRoot, fingerprint)) return
            }
        }

        if (retry) {
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({ tryClickWithRetry(false) }, RETRY_DELAY)
        }
    }

    /**
     * 根据指纹缓存的动作执行快速点击
     */
    private fun performActionClick(root: AccessibilityNodeInfo, action: PageFingerprintManager.TargetAction): Boolean {
        // 优先使用 ID 查找
        if (!action.viewId.isNullOrBlank()) {
            val nodes = root.findAccessibilityNodeInfosByViewId(action.viewId)
            for (node in nodes) {
                if (isSkipNode(node) && performClick(node)) {
                    // 注意：performClick 内部没有 recycle node，需要这里处理
                    node.recycle()
                    return true
                }
                node.recycle()
            }
        }
        // 其次使用 Text 查找
        if (!action.text.isNullOrBlank()) {
            val nodes = root.findAccessibilityNodeInfosByText(action.text)
            for (node in nodes) {
                if (isSkipNode(node) && performClick(node)) {
                    node.recycle()
                    return true
                }
                node.recycle()
            }
        }
        return false
    }

    private fun processNode(root: AccessibilityNodeInfo, fingerprint: String = ""): Boolean {
        val foundNode = findSkipNode(root)
        if (foundNode != null) {
            val tag = "${foundNode.packageName}:${foundNode.viewIdResourceName}:${foundNode.text}"
            
            // 扫描成功，记录指纹
            if (fingerprint.isNotBlank()) {
                fingerprintManager.recordFingerprint(foundNode.packageName?.toString() ?: "", fingerprint, foundNode)
            }

            if (performClick(foundNode)) {
                Logger.i(TAG, ">>> SUCCESS: Clicked on $tag")
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
            Logger.d(TAG, "Match Found! Text: $text, Desc: $desc, ID: $viewId")
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
                Logger.d(TAG, "Dispatched gesture click at ($x, $y)")
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
