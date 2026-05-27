package com.dh.skipper

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 页面指纹管理器：负责页面的特征提取、识别与记录
 */
class PageFingerprintManager {
    companion object {
        private const val TAG = "FingerprintManager"
        private const val MAX_SAMPLE_NODES = 25 // 采样节点数，平衡精度与性能
        private const val MAX_DEPTH = 6         // 采样深度
    }

    // 内存缓存：packageName -> Map<fingerprint, TargetAction>
    private val fingerprintCache = mutableMapOf<String, MutableMap<String, TargetAction>>()

    /**
     * 目标点击动作的特征
     */
    data class TargetAction(
        val viewId: String?,
        val text: String?,
        val description: String?
    )

    /**
     * 生成页面指纹
     * 逻辑：组合 包名 + 根节点子数 + 深度优先采样前 N 个节点的 ID 结构
     */
    fun calculateFingerprint(root: AccessibilityNodeInfo?): String {
        if (root == null) return ""
        
        val sb = StringBuilder()
        // 获取包名，如果获取不到则使用占位符
        val pkg = root.packageName?.toString() ?: "unknown"
        sb.append(pkg).append("|")
        sb.append(root.childCount).append("|")
        
        val samples = mutableListOf<String>()
        collectStructure(root, 0, samples)
        sb.append(samples.joinToString(","))
        
        // 使用 Hash 值作为指纹
        return sb.toString().hashCode().toString()
    }

    /**
     * 递归采样 UI 树的结构特征
     */
    private fun collectStructure(node: AccessibilityNodeInfo, depth: Int, samples: MutableList<String>) {
        if (samples.size >= MAX_SAMPLE_NODES || depth > MAX_DEPTH) return
        
        val id = node.viewIdResourceName ?: "n" // "n" 代表 null
        samples.add("$depth:$id")
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectStructure(child, depth + 1, samples)
            child.recycle()
            if (samples.size >= MAX_SAMPLE_NODES) break
        }
    }

    /**
     * 查找是否有匹配的指纹动作
     */
    fun getMatchedAction(packageName: String, fingerprint: String): TargetAction? {
        return fingerprintCache[packageName]?.get(fingerprint)
    }

    /**
     * 检查该应用是否已经记录过任何指纹
     */
    fun hasRegisteredFingerprint(packageName: String): Boolean {
        return fingerprintCache[packageName]?.isNotEmpty() ?: false
    }

    /**
     * 录制指纹：将当前页面指纹与发现的“跳过”按钮绑定
     */
    fun recordFingerprint(packageName: String, fingerprint: String, targetNode: AccessibilityNodeInfo) {
        val action = TargetAction(
            targetNode.viewIdResourceName,
            targetNode.text?.toString(),
            targetNode.contentDescription?.toString()
        )
        
        val pkgMap = fingerprintCache.getOrPut(packageName) { mutableMapOf() }
        if (!pkgMap.containsKey(fingerprint)) {
            pkgMap[fingerprint] = action
            Logger.i(TAG, ">>> [Record] New fingerprint for $packageName: $fingerprint")
            Logger.d(TAG, ">>> [Action] Target: ID=${action.viewId}, Text=${action.text}")
        }
    }
}
