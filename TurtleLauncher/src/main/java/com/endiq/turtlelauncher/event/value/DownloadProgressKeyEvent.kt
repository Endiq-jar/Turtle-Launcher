package com.endiq.turtlelauncher.event.value

/**
 * Notifies LauncherActivity of a new download task key.
 * Makes it easy to observe the download progress of this task.
 * @param observe whether to keep observing
 * @see net.endiq.launcher.LauncherActivity
 */
class DownloadProgressKeyEvent(val progressKey: String, val observe: Boolean)