package com.endiq.turtlelauncher.ui.fragment.download.addon

import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.feature.mod.modloader.FabricLikeUtils

class DownloadFabricFragment : DownloadFabricLikeFragment(FabricLikeUtils.FABRIC_UTILS, R.drawable.ic_fabric) {
    companion object {
        const val TAG: String = "DownloadFabricFragment"
    }
}