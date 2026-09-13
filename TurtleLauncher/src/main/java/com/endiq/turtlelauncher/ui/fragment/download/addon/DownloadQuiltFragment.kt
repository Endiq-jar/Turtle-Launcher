package com.endiq.turtlelauncher.ui.fragment.download.addon

import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.feature.mod.modloader.FabricLikeUtils

class DownloadQuiltFragment : DownloadFabricLikeFragment(FabricLikeUtils.QUILT_UTILS, R.drawable.ic_quilt) {
    companion object {
        const val TAG: String = "DownloadQuiltFragment"
    }
}