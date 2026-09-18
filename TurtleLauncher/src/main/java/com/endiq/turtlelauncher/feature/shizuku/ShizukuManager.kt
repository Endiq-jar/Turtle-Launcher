package com.endiq.turtlelauncher.feature.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import com.endiq.turtlelauncher.feature.log.Logging
import rikka.shizuku.Shizuku

object ShizukuManager {
    private const val TAG = "ShizukuManager"

    /** Arbitrary, app-local code used to match the async permission result. */
    const val REQUEST_CODE_PERMISSION = 0x5A11

    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<(ShizukuStatus) -> Unit>()

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        refreshAndNotify()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        refreshAndNotify()
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
            if (requestCode != REQUEST_CODE_PERMISSION) return@OnRequestPermissionResultListener
            refreshAndNotify()
        }

    @Volatile
    var status: ShizukuStatus = ShizukuStatus()
        private set

    @Volatile
    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
        }.onFailure {
            Logging.w(TAG, "Could not register Shizuku listeners - Shizuku support disabled", it)
        }
        appContext = context.applicationContext
        refresh()
    }

    @Volatile
    private var appContext: Context? = null

    /** Registers a UI callback, invoked on the main thread. Returns an unregister lambda. */
    @Synchronized
    fun addListener(listener: (ShizukuStatus) -> Unit): () -> Unit {
        listeners.add(listener)
        mainHandler.post { listener(status) }
        return { synchronized(this@ShizukuManager) { listeners.remove(listener) } }
    }

    /** Convenience: is Shizuku up AND are we authorized to use it right now? */
    val isReady: Boolean
        get() = status.state == ShizukuState.READY

    /**
     * Asks Shizuku for authorization. The result arrives asynchronously through
     * [Shizuku.OnRequestPermissionResultListener]; listeners are notified when it lands.
     *
     * @return true if a request was actually sent (i.e. we are in the state where one is
     * meaningful). Shizuku shows its own confirmation UI to the user.
     */
    fun requestPermission(): Boolean {
        if (status.state != ShizukuState.PERMISSION_DENIED) return false
        return runCatching {
            Shizuku.requestPermission(REQUEST_CODE_PERMISSION)
            true
        }.onFailure {
            Logging.w(TAG, "Could not request the Shizuku permission", it)
        }.getOrDefault(false)
    }

    private fun refreshAndNotify() {
        refresh()
        val snapshot = status
        val snapshotListeners = synchronized(this) { listeners.toList() }
        mainHandler.post {
            snapshotListeners.forEach { runCatching { it(snapshot) } }
        }
    }

    private fun refresh() {
        status = runCatching {
            if (!Shizuku.pingBinder()) {
                return@runCatching ShizukuStatus(
                    state = if (isShizukuInstalled()) ShizukuState.NOT_RUNNING else ShizukuState.NOT_INSTALLED,
                    detail = if (isShizukuInstalled()) "Shizuku is installed but its service is not running"
                    else "Neither Shizuku nor Sui was found on this device"
                )
            }
            if (Shizuku.isPreV11()) {
                return@runCatching ShizukuStatus(
                    state = ShizukuState.NOT_INSTALLED,
                    detail = "This Shizuku version is too old (pre-v11) to be usable"
                )
            }
            val granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                return@runCatching ShizukuStatus(
                    state = ShizukuState.PERMISSION_DENIED,
                    detail = "Shizuku is running but has not authorized this app yet"
                )
            }
            val uid = runCatching { Shizuku.getUid() }.getOrDefault(-1)
            ShizukuStatus(
                state = ShizukuState.READY,
                version = runCatching { Shizuku.getVersion() }.getOrDefault(-1),
                uid = uid,
                detail = if (uid == 0) "Running with root privilege"
                else "Running with ADB shell privilege"
            )
        }.getOrDefault(ShizukuStatus(detail = "Shizuku is not available on this device"))
    }

    private fun isShizukuInstalled(): Boolean {
        val context = appContext ?: return false
        return runCatching {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        }.getOrDefault(false)
    }
}
