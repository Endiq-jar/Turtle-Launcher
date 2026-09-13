package com.endiq.turtlelauncher.feature.shizuku

/**
 * How far along the Shizuku/Sui setup this install currently is.
 *
 * Shizuku (a normal app, started over adb or as root) and Sui (a Magisk module) both expose
 * the same API, so nothing here distinguishes them - the only difference that matters to us
 * is the privilege level, which is reported back as [ShizukuStatus.uid] (0 = root,
 * 2000 = the ADB shell user).
 */
enum class ShizukuState {
    /** No Shizuku and no Sui on the device at all. */
    NOT_INSTALLED,

    /** Shizuku is installed but its service has not been started since the last boot. */
    NOT_RUNNING,

    /** Service is up, but this app has not been granted (or was denied) access. */
    PERMISSION_DENIED,

    /** Service is up and we are authorized. Shell commands are possible. */
    READY
}

/**
 * A point-in-time snapshot of Shizuku availability.
 *
 * @param uid privilege level the remote service runs as: 0 for root, 2000 for the ADB shell
 *            user, -1 when unknown. ADB can do less than root (it cannot read another app's
 *            private data, for example), so callers should treat some commands as best-effort.
 */
data class ShizukuStatus(
    val state: ShizukuState = ShizukuState.NOT_INSTALLED,
    val version: Int = -1,
    val uid: Int = -1,
    val detail: String = ""
) {
    val isRoot: Boolean get() = uid == 0
}
