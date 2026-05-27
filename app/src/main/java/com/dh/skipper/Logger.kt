package com.dh.skipper

import android.util.Log

/**
 * 日志工具类：仅在 Debug 模式下打印日志
 */
object Logger {
    // 显式引用包名下的 BuildConfig，确保混淆或多模块下也能正确识别
    private val DEBUG = BuildConfig.DEBUG

    fun d(tag: String, msg: String) {
        if (DEBUG) Log.d(tag, msg)
    }

    fun i(tag: String, msg: String) {
        if (DEBUG) Log.i(tag, msg)
    }

    fun w(tag: String, msg: String) {
        if (DEBUG) Log.w(tag, msg)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        if (DEBUG) {
            if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
        }
    }
}
