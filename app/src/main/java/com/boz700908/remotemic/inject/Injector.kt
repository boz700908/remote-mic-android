package com.boz700908.remotemic.inject

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

/** 无障碍服务实例的静态入口：识别结果靠它注入到焦点输入框。 */
object Injector {

    @Volatile
    private var service: RemoteKeyAccessibilityService? = null

    fun attach(s: RemoteKeyAccessibilityService) {
        service = s
    }

    fun detach(s: RemoteKeyAccessibilityService) {
        if (service === s) service = null
    }

    val available: Boolean get() = service != null

    fun typeText(text: String, replace: Boolean): Boolean =
        service?.typeText(text, replace) ?: false

    fun globalBack(): Boolean = service?.globalBack() ?: false

    fun globalHome(): Boolean = service?.globalHome() ?: false

    fun globalRecents(): Boolean = service?.globalRecents() ?: false

    fun scroll(forward: Boolean): Boolean = service?.scroll(forward) ?: false

    fun isEnabled(context: Context): Boolean {
        val expected = ComponentName(context, RemoteKeyAccessibilityService::class.java)
            .flattenToString()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }
}
