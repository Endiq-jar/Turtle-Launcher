package com.endiq.turtlelauncher.feature.notice

import com.google.gson.annotations.SerializedName

class NoticeJsonObject(
    val title: Text,
    val content: Text,
    val date: String,
    val numbering: Int
) {
    class Text(
        @SerializedName("zh_cn") val zhCN: String,
        @SerializedName("zh_tw") val zhTW: String,
        @SerializedName("en_us") val enUS: String
    )
}