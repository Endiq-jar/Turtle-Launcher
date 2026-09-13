package com.endiq.turtlelauncher.feature.preset

import com.google.gson.annotations.SerializedName

data class PresetConfig(
    @SerializedName("name") val name: String = "",
    @SerializedName("description") val description: String = "",
    @SerializedName("javaArgs") val javaArgs: String = "",
    @SerializedName("ramAllocation") val ramAllocation: Int = 0,
    @SerializedName("unlimitedFps") val unlimitedFps: Boolean = false,
    @SerializedName("lowLatencyRendering") val lowLatencyRendering: Boolean = false,
    @SerializedName("framePacing") val framePacing: Boolean = false,
    @SerializedName("frameSkipping") val frameSkipping: Boolean = false,
    @SerializedName("adaptiveFrameTiming") val adaptiveFrameTiming: Boolean = false
)
