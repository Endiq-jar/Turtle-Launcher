package com.endiq.turtlelauncher.feature.notice

data class NoticeInfo(
    @JvmField val title: String,
    @JvmField val content: String,
    @JvmField val date: String,
    @JvmField val numbering: Int
)
