package com.endiq.turtlelauncher.feature.download.enums

import com.endiq.turtlelauncher.feature.download.platform.AbstractPlatformHelper
import com.endiq.turtlelauncher.feature.download.platform.modrinth.ModrinthHelper

enum class Platform(val pName: String, val helper: AbstractPlatformHelper) {
    MODRINTH("Modrinth", ModrinthHelper())
}
