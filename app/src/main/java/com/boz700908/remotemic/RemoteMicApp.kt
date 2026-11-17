package com.boz700908.remotemic

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.boz700908.remotemic.core.BridgeState
import com.boz700908.remotemic.inject.Injector
import com.boz700908.remotemic.inject.KeyInjector

class RemoteMicApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (nm != null) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "遥控麦连接服务",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "保持与小米蓝牙遥控器的连接并接收语音" }
            nm.createNotificationChannel(ch)
        }
        KeyInjector.init(this)
        BridgeState.a11yEnabled.value = Injector.isEnabled(this)
    }

    companion object {
        const val CHANNEL_ID = "bridge"
    }
}
