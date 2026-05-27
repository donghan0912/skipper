package com.dh.skipper

import android.content.Context
import android.content.SharedPreferences
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

/**
 * 页面指纹管理器：负责页面的特征提取、识别与记录
 * 内存缓存 + SharedPreferences 双层存储，服务重启后不丢失
 */
class PageFingerprintManager(context: Context) {
    companion object {
        private const val TAG = "FingerprintManager"
        private const val MAX_SAMPLE_NODES = 25
        private const val MAX_DEPTH = 6
        private const val MAX_FINGERPRINTS_PER_APP = 10
        private const val PREF_NAME = "skipper_config"
        private const val KEY_FINGERPRINT_CACHE = "fingerprint_cache"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // 内存缓存：packageName -> Map<fingerprint, TargetAction>
    private val fingerprintCache = mutableMapOf<String, MutableMap<String, TargetAction>>()

    init {
        loadFromDisk()
    }

    data class TargetAction(
        val viewId: String?,
        val text: String?,
        val description: String?
    )

    fun calculateFingerprint(root: AccessibilityNodeInfo?): String {
        if (root == null) return ""

        val sb = StringBuilder()
        val pkg = root.packageName?.toString() ?: "unknown"
        sb.append(pkg).append("|")
        sb.append(root.childCount).append("|")

        val samples = mutableListOf<String>()
        collectStructure(root, 0, samples)
        sb.append(samples.joinToString(","))

        return sb.toString().hashCode().toString()
    }

    private fun collectStructure(node: AccessibilityNodeInfo, depth: Int, samples: MutableList<String>) {
        if (samples.size >= MAX_SAMPLE_NODES || depth > MAX_DEPTH) return

        val id = node.viewIdResourceName ?: "n"
        samples.add("$depth:$id")

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectStructure(child, depth + 1, samples)
            child.recycle()
            if (samples.size >= MAX_SAMPLE_NODES) break
        }
    }

    fun getMatchedAction(packageName: String, fingerprint: String): TargetAction? {
        return fingerprintCache[packageName]?.get(fingerprint)
    }

    fun hasRegisteredFingerprint(packageName: String): Boolean {
        return fingerprintCache[packageName]?.isNotEmpty() ?: false
    }

    fun recordFingerprint(packageName: String, fingerprint: String, targetNode: AccessibilityNodeInfo) {
        val action = TargetAction(
            targetNode.viewIdResourceName,
            targetNode.text?.toString(),
            targetNode.contentDescription?.toString()
        )

        val pkgMap = fingerprintCache.getOrPut(packageName) { mutableMapOf() }
        if (pkgMap.size >= MAX_FINGERPRINTS_PER_APP) {
            Logger.w(TAG, ">>> [Skip] $packageName reached max fingerprints ($MAX_FINGERPRINTS_PER_APP)")
            return
        }
        if (!pkgMap.containsKey(fingerprint)) {
            pkgMap[fingerprint] = action
            Logger.i(TAG, ">>> [Record] New fingerprint for $packageName: $fingerprint")
            Logger.d(TAG, ">>> [Action] Target: ID=${action.viewId}, Text=${action.text}")
            saveToDisk()
        }
    }

    /**
     * 从 SharedPreferences 加载指纹缓存到内存
     */
    private fun loadFromDisk() {
        val jsonStr = prefs.getString(KEY_FINGERPRINT_CACHE, null) ?: return
        try {
            val root = JSONObject(jsonStr)
            for (pkg in root.keys()) {
                val pkgObj = root.getJSONObject(pkg)
                val pkgMap = mutableMapOf<String, TargetAction>()
                for (fp in pkgObj.keys()) {
                    val actionObj = pkgObj.getJSONObject(fp)
                    pkgMap[fp] = TargetAction(
                        actionObj.optString("viewId", null),
                        actionObj.optString("text", null),
                        actionObj.optString("description", null)
                    )
                }
                fingerprintCache[pkg] = pkgMap
            }
            Logger.d(TAG, "Loaded ${fingerprintCache.size} apps from disk")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to load fingerprint cache", e)
        }
    }

    /**
     * 将内存中的指纹缓存持久化到 SharedPreferences
     */
    private fun saveToDisk() {
        val root = JSONObject()
        for ((pkg, pkgMap) in fingerprintCache) {
            val pkgObj = JSONObject()
            for ((fp, action) in pkgMap) {
                val actionObj = JSONObject()
                actionObj.put("viewId", action.viewId)
                actionObj.put("text", action.text)
                actionObj.put("description", action.description)
                pkgObj.put(fp, actionObj)
            }
            root.put(pkg, pkgObj)
        }
        prefs.edit().putString(KEY_FINGERPRINT_CACHE, root.toString()).apply()
    }
}
