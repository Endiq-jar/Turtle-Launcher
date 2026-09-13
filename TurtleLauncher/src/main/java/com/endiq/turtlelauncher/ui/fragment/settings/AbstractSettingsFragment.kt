package com.endiq.turtlelauncher.ui.fragment.settings

import androidx.annotation.CallSuper
import com.endiq.anim.AnimPlayer
import com.endiq.turtlelauncher.event.single.SettingsChangeEvent
import com.endiq.turtlelauncher.event.value.SettingsPageSwapEvent
import com.endiq.turtlelauncher.ui.fragment.FragmentWithAnim
import net.kdt.pojavlaunch.prefs.LauncherPreferences
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe

abstract class AbstractSettingsFragment(layoutId: Int, private val category: SettingCategory) : FragmentWithAnim(layoutId) {
    @Subscribe
    fun event(event: SettingsChangeEvent) {
        onChange()
    }

    @Subscribe
    fun event(event: SettingsPageSwapEvent) {
        if (event.index == category.ordinal) {
            slideIn()
        }
    }

    override fun onStart() {
        super.onStart()
        EventBus.getDefault().register(this)
    }

    override fun onStop() {
        super.onStop()
        EventBus.getDefault().unregister(this)
    }

    @CallSuper
    protected open fun onChange() {
        LauncherPreferences.loadPreferences()
    }

    override fun slideOut(animPlayer: AnimPlayer) {}
}