package com.boz700908.remotemic.inject

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.boz700908.remotemic.IKeyService
import rikka.shizuku.Shizuku

/**
 * [KeyUserService] 的客户端：负责绑定、断线回收与重试。
 *
 * 绑定是异步的，这里所有接口都不阻塞：没就绪就返回 null，调用方回落命令通道。
 * 应用启动时 [prewarm] 一次，正常情况用户按键时服务早已连上。
 */
object KeyServiceClient {

    @Volatile
    private var service: IKeyService? = null

    @Volatile
    private var binding = false

    @Volatile
    private var lastAttempt = 0L

    private var args: Shizuku.UserServiceArgs? = null

    private val lock = Any()

    /** 服务是否可用（已连接且 binder 存活）。 */
    val ready: Boolean
        get() = runCatching { service?.asBinder()?.isBinderAlive == true }.getOrDefault(false)

    /** 当前可用的服务，没有则 null。 */
    fun get(): IKeyService? {
        val current = service ?: return null
        val alive = runCatching { current.asBinder().isBinderAlive }.getOrDefault(false)
        if (!alive) {
            service = null
            return null
        }
        return current
    }

    /** 取服务，没就绪时顺手发起一次绑定（不等待）。 */
    fun ensure(context: Context): IKeyService? {
        get()?.let { return it }
        val now = SystemClock.uptimeMillis()
        if (now - lastAttempt > RETRY_MS) {
            lastAttempt = now
            prewarm(context)
        }
        return get()
    }

    /** 异步绑定，可重复调用。 */
    fun prewarm(context: Context) {
        if (ready) return
        val app = context.applicationContext ?: context
        Thread({ bind(app) }, "key-service-bind").apply { isDaemon = true }.start()
    }

    /** 解绑（仅在明确要断开时使用，daemon 模式下服务进程会继续存活）。 */
    fun release() {
        val current = args ?: return
        runCatching { Shizuku.unbindUserService(current, connection, false) }
            .onFailure { Log.w(TAG, "解绑注入服务失败", it) }
        service = null
        binding = false
    }

    private fun bind(context: Context) {
        if (!Shizuku.pingBinder() || !granted()) return
        val now = SystemClock.uptimeMillis()
        synchronized(lock) {
            if (binding && now - lastAttempt < PENDING_MS) return
            binding = true
        }
        val a = argsLocked(context)
        runCatching {
            Shizuku.bindUserService(a, connection)
            Log.i(TAG, "已请求绑定注入服务")
        }.onFailure {
            Log.w(TAG, "绑定注入服务失败", it)
            binding = false
        }
    }

    private fun argsLocked(context: Context): Shizuku.UserServiceArgs = synchronized(lock) {
        args ?: Shizuku.UserServiceArgs(ComponentName(context, KeyUserService::class.java))
            .daemon(true)
            .processNameSuffix("keys")
            .debuggable(false)
            .version(SERVICE_VERSION)
            .tag(TAG_NAME)
            .also { args = it }
    }

    private fun granted(): Boolean = runCatching {
        if (Shizuku.isPreV11()) true
        else Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private val death = IBinder.DeathRecipient {
        Log.w(TAG, "注入服务 binder 已死亡")
        service = null
        binding = false
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            binding = false
            if (binder == null) {
                Log.w(TAG, "注入服务连接成功但 binder 为空（服务可能启动失败）")
                service = null
                return
            }
            runCatching { binder.linkToDeath(death, 0) }
                .onFailure { Log.w(TAG, "linkToDeath 失败", it) }
            service = IKeyService.Stub.asInterface(binder)
            lastAttempt = SystemClock.uptimeMillis()
            Log.i(TAG, "注入服务已连接")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "注入服务断开")
            service = null
            binding = false
        }
    }

    /** 服务代码变更时改这个版本号，Shizuku 会重建 user service 进程。 */
    private const val SERVICE_VERSION = 4
    private const val TAG_NAME = "remotemic-keys"
    private const val RETRY_MS = 5_000L
    private const val PENDING_MS = 3_000L
    private const val TAG = "KeyServiceClient"
}
