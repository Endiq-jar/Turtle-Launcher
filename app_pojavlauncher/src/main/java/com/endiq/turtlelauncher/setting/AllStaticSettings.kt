package com.endiq.turtlelauncher.setting

/**
 * Static setting values for settings that only apply temporarily.
 * These values are not persisted; they disappear on restart!
 */
class AllStaticSettings {
    companion object {
        /**
         * Notch cutout width. Int.
         */
        @JvmField var notchSize = 0

        /**
         * Scale factor. Float.
         */
        @JvmField var scaleFactor = AllSettings.resolutionRatio.getValue() / 100f

        /**
         * Disable double-tap item swapping. Boolean.
         */
        @JvmField var disableDoubleTap = AllSettings.disableDoubleTap.getValue()

        /**
         * Long-press trigger delay. Int.
         */
        @JvmField var timeLongPressTrigger = AllSettings.timeLongPressTrigger.getValue()

        /**
         * Enable gyroscope controls. Boolean.
         */
        @JvmField var enableGyro = AllSettings.enableGyro.getValue()

        /**
         * Gyroscope sensitivity. Int.
         */
        @JvmField var gyroSensitivity = AllSettings.gyroSensitivity.getValue()

        /**
         * Invert the gyroscope X axis. Boolean.
         */
        @JvmField var gyroInvertX = AllSettings.gyroInvertX.getValue()

        /**
         * Invert the gyroscope Y axis. Boolean.
         */
        @JvmField var gyroInvertY = AllSettings.gyroInvertY.getValue()

        /**
         * Use the control proxy. Boolean.
         */
        @JvmField var useControllerProxy = false
    }
}