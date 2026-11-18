package com.boz700908.remotemic.inject

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.boz700908.remotemic.core.BridgeState
import com.boz700908.remotemic.core.KeymapStore
import com.boz700908.remotemic.service.BridgeService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 无障碍能做的三件事：
 *  1. 把识别文字写进当前焦点输入框（[typeText]）；
 *  2. 系统全局动作（[globalBack]、[scroll] 等）；
 *  3. 按键过滤打开后接管遥控器的普通按键（[onKeyEvent]），以及焦点位置的点按 / 长按（[pointerClick]）。
 *
 * 第 3 条是「没 root 也没 Shizuku」时唯一能发动作的路子：遥控器走 HID 键盘进系统，
 * 系统会先把按键送到这里，我们查映射表做动作并吞掉原键。**只有配过动作的键才会被吞**，
 * 其余按键照常交给应用，不影响当普通键盘用。
 */
class RemoteKeyAccessibilityService : AccessibilityService() {

    /** 已经吞掉按下事件的键码，抬起时一起吞掉，免得系统收到半对按键。 */
    private val swallowed = ConcurrentHashMap.newKeySet<Int>()

    /** 我们注入的按键也会回到 [onKeyEvent]，用它挡住自己触发自己（act:back → 注入 BACK → 再触发）。 */
    private val dispatching = AtomicBoolean(false)

    override fun onServiceConnected() {
        super.onServiceConnected()
        Injector.attach(this)
        // XML 已声明 canRequestFilterKeyEvents，这里再显式要一次：
        // 部分 ROM 只认运行时 flags，不设的话 onKeyEvent 根本不会被调用。
        runCatching {
            serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
                flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            }
        }.onFailure { Log.w(TAG, "申请按键过滤失败", it) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Injector.detach(this)
        swallowed.clear()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Injector.detach(this)
        swallowed.clear()
        super.onDestroy()
    }

    /* ----------------------------- 按键接管 ----------------------------- */

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) return swallowed.contains(keyCode)
                if (!handleKey(keyCode)) return false
                swallowed.add(keyCode)
                true
            }
            KeyEvent.ACTION_UP -> swallowed.remove(keyCode)
            else -> false
        }
    }

    /**
     * 查映射表并执行；返回 true 表示这个键已被我们认领（调用方吞掉原键）。
     * 没识别出机型、按键不在描述符里、或者这个键没配动作 —— 一律返回 false 照常转发。
     */
    private fun handleKey(keyCode: Int): Boolean {
        if (!dispatching.compareAndSet(false, true)) return false
        return try {
            val inputs = BridgeState.remoteInputs.value
            if (inputs.isEmpty()) return false
            val input = inputs.firstOrNull { it.legacyKey != 0 && it.legacyKey == keyCode } ?: return false
            val action = KeymapStore.actionFor(BridgeState.deviceId.value, input.id) ?: return false
            BridgeState.lastKey.value = input.label
            Log.i(TAG, "接管按键 ${input.label}($keyCode) → $action")
            if (ActionRunner.isVoice(action)) {
                askVoice()
            } else {
                ActionRunner.run(this, action, keyCode).handled
            }
        } finally {
            dispatching.set(false)
        }
    }

    /** 语音键交给 BridgeService 抢麦克风；服务没在跑就顺手拉起来。 */
    private fun askVoice(): Boolean = runCatching {
        BridgeService.send(this, BridgeService.ACTION_VOICE)
        true
    }.getOrElse {
        Log.w(TAG, "唤起语音失败", it)
        false
    }

    /* ----------------------------- 指针动作 ----------------------------- */

    /**
     * 在当前焦点控件中心点一下；没有焦点就点屏幕中心。
     * [holdMs] 超过系统长按阈值（500 ms）就是长按。
     *
     * 走 dispatchGesture 而不走无障碍 ACTION_CLICK：后者要求目标节点自己支持点击，
     * 遥控器场景下常常对着一片没有 click action 的区域；手势在屏幕上真正按下去更接近真手指。
     */
    internal fun pointerClick(holdMs: Long): Boolean {
        val (x, y) = focusCenter()
        val path = Path().apply { moveTo(x, y) }
        val duration = holdMs.coerceIn(MIN_TAP_MS, MAX_POINTER_MS)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, duration))
            .build()
        return runCatching { dispatchGesture(gesture, null, null) }.getOrElse {
            Log.w(TAG, "手势注入失败", it)
            false
        }
    }

    private fun focusCenter(): Pair<Float, Float> {
        val node = runCatching {
            val root = rootInActiveWindow
            root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: root?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        }.getOrNull()
        val rect = Rect()
        if (node != null && runCatching { node.getBoundsInScreen(rect) }.isSuccess && !rect.isEmpty) {
            return rect.exactCenterX() to rect.exactCenterY()
        }
        val m = resources.displayMetrics
        return m.widthPixels / 2f to m.heightPixels / 2f
    }

    /* ----------------------------- 文字注入 ----------------------------- */

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

    /* ----------------------------- 全局动作 ----------------------------- */

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

        /** 点击最短持续时间：手势不允许 0，40 ms 肉眼看就是一下点击。 */
        private const val MIN_TAP_MS = 40L

        private const val MAX_POINTER_MS = 30_000L
    }
}
