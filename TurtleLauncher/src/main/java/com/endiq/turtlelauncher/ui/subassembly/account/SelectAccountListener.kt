package com.endiq.turtlelauncher.ui.subassembly.account

import net.endiq.launcher.value.MinecraftAccount

interface SelectAccountListener {
    fun onSelect(account: MinecraftAccount)
}
