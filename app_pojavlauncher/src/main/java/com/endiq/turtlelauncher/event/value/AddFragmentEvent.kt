package com.endiq.turtlelauncher.event.value

import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity

/**
 * Add a new Fragment to the transaction; LauncherActivity receives and handles it.
 * Guarantees the parent Fragment is the current one when a Fragment is added.
 * @see net.kdt.pojavlaunch.LauncherActivity
 * @see com.endiq.turtlelauncher.utils.ZHTools.addFragment
 */
class AddFragmentEvent(
    val fragmentClass: Class<out Fragment?>,
    val fragmentTag: String?,
    val bundle: Bundle?,
    val fragmentActivityCallback: FragmentActivityCallBack?
) {
    /**
     * Callback handling for the FragmentActivity hosting the current Fragment.
     */
    fun interface FragmentActivityCallBack {
        fun callBack(fragmentActivity: FragmentActivity)
    }
}