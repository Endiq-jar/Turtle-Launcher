package kr.co.donghyun.turtlelauncher

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Hilt 진입점. AndroidManifest.xml의 application android:name 으로 등록해야
 * @AndroidEntryPoint / @HiltViewModel 이 동작한다.
 */
@HiltAndroidApp
class TurtleLauncherApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Original Turtle diagnostics/warm-start features run silently in the process.
        kr.co.donghyun.turtlelauncher.feature.turtle.TurtleRuntimeSupport.start(this)
    }
}
