package com.endiq.turtlelauncher.event.value

import com.endiq.turtlelauncher.feature.version.install.Addon
import com.endiq.turtlelauncher.feature.version.install.InstallTaskItem

/**
 * Broadcast when an install task starts.
 * @see com.endiq.turtlelauncher.ui.fragment.InstallGameFragment
 * @param minecraftVersion the vanilla MC version
 * @param customVersionName custom version folder name
 * @param taskMap the install tasks
 */
class InstallGameEvent(
    val minecraftVersion: String,
    val customVersionName: String,
    val taskMap: Map<Addon, InstallTaskItem>
)