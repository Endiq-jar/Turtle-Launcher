package com.endiq.turtlelauncher.utils.platform

import android.app.ActivityManager
import android.content.Context

class MemoryUtils {
    companion object {
        private var activityManager: ActivityManager? = null

        private fun init(context: Context) {
            activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        }

        @JvmStatic
        fun getTotalDeviceMemory(context: Context): Long {
            activityManager ?: run { init(context) }

            val memInfo = ActivityManager.MemoryInfo()
            activityManager?.getMemoryInfo(memInfo)
            return memInfo.totalMem
        }

        @JvmStatic
        fun getUsedDeviceMemory(context: Context): Long {
            activityManager ?: run { init(context) }

            val memInfo = ActivityManager.MemoryInfo()
            activityManager?.getMemoryInfo(memInfo)
            return memInfo.totalMem - memInfo.availMem
        }

        @JvmStatic
        fun getFreeDeviceMemory(context: Context): Long {
            activityManager ?: run { init(context) }

            val memInfo = ActivityManager.MemoryInfo()
            activityManager?.getMemoryInfo(memInfo)
            return memInfo.availMem
        }

        /** TurtleLauncher: process-wide native heap currently allocated (android.os.Debug),
         *  not device-wide like the other stats here. Since this launcher runs the JVM in
         *  the same process via JNI rather than a separate one, this reflects native memory
         *  from both the launcher's own UI and, once running, the game/renderer libraries
         *  combined - there's no way to separate the two from this API. */
        @JvmStatic
        fun getNativeHeapAllocated(): Long = android.os.Debug.getNativeHeapAllocatedSize()
    }
}