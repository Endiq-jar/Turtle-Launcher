package com.endiq.turtlelauncher.event.value

import com.endiq.turtlelauncher.feature.version.install.Addon
import com.endiq.turtlelauncher.feature.version.install.InstallTaskItem

/**
 * 安装任务开始时，将使用这个事件进行通知
 * @see com.endiq.turtlelauncher.ui.fragment.InstallGameFragment
 * @param minecraftVersion MC原版版本
 * @param customVersionName 自定义的版本文件夹名称
 * @param taskMap 安装任务
 */
class InstallGameEvent(
    val minecraftVersion: String,
    val customVersionName: String,
    val taskMap: Map<Addon, InstallTaskItem>
)