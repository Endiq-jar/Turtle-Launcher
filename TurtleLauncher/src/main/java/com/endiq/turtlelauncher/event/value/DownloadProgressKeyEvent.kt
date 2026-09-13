package com.endiq.turtlelauncher.event.value

/**
 * 当有新的下载任务时，使用这个任务向LauncherActivity通知任务的键
 * 方便监听这个任务的下载进度
 * @param observe 是否继续监听
 * @see net.kdt.pojavlaunch.LauncherActivity
 */
class DownloadProgressKeyEvent(val progressKey: String, val observe: Boolean)