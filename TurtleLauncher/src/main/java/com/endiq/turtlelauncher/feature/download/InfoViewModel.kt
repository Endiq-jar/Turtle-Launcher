package com.endiq.turtlelauncher.feature.download

import androidx.lifecycle.ViewModel
import com.endiq.turtlelauncher.feature.download.item.InfoItem
import com.endiq.turtlelauncher.feature.download.platform.AbstractPlatformHelper


class InfoViewModel : ViewModel() {
    var platformHelper: AbstractPlatformHelper? = null
    var infoItem: InfoItem? = null
}