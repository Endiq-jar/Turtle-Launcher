package com.endiq.turtlelauncher.event.sticky

import com.endiq.turtlelauncher.feature.version.install.Addon
import com.endiq.turtlelauncher.feature.version.install.InstallTask

/**
 * Broadcast after an install task is selected.
 * @param addon whose install task was selected
 * @param selectedVersion the selected version
 * @param task the selected task
 * @see com.endiq.turtlelauncher.feature.version.install.Addon
 */
class SelectInstallTaskEvent(val addon: Addon, val selectedVersion: String, val task: InstallTask)