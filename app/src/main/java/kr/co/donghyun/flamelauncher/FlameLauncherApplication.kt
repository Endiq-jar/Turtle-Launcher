package kr.co.donghyun.flamelauncher

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Hilt 진입점. AndroidManifest.xml의 application android:name 으로 등록해야
 * @AndroidEntryPoint / @HiltViewModel 이 동작한다.
 */
@HiltAndroidApp
class FlameLauncherApplication : Application()
