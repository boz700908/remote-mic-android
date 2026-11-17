package com.boz700908.remotemic.inject

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log

/**
 * 只做两件事：把识别文字写进当前焦点输入框；把遥控器按键翻成系统全局动作。
 * 不申请读屏能力以外的权限，也不联网。
 */
class RemoteKeyAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        Injector.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Injector.detach(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Injector.detach(this)
        super.onDestroy()
    }

    internal fun typeText(text: String, replace: Boolean): Boolean {
        if (text.isEmpty()) return false
        val root = rootInActiveWindow ?: return false
        val node = findEditable(root) ?: return false
        return runCatching {
            val current = node.text?.toString().orEmpty()
            val payload = when {
                replace || current.isEmpty() -> text
                current.endsWith(" ") -> current + text
                else -> "$current $text"
            }
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, payload)
            }
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (ok) {
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                runCatching {
                    node.performAction(
                        AccessibilityNodeInfo.ACTION_SET_SELECTION,
                        Bundle().apply {
                            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, payload.length)
                            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, payload.length)
                        },
                    )
                }
            }
            Log.i(TAG, "注入${if (ok) "成功" else "失败"}：$payload")
            ok
        }.getOrDefault(false)
    }

    private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val focused = runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        if (isEditable(focused)) return focused
        return firstEditable(root, 0)
    }

    private fun firstEditable(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > 12) return null
        if (isEditable(node)) return node
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            val hit = firstEditable(child, depth + 1)
            if (hit != null) return hit
        }
        return null
    }

    private fun isEditable(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (!node.isEditable) return false
        return node.isVisibleToUser && node.isEnabled
    }

    internal fun globalBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    internal fun globalHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    internal fun globalRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    internal fun scroll(forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        return runCatching { root.performAction(action) }.getOrDefault(false)
    }

    companion object {
        private const val TAG = "RemoteInject"
    }
}
