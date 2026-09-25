package com.endiq.turtlelauncher.domain.model

data class HostEntry(
    val hostname: String,
    val ip: String,
    val note: String = "",
    val enabled: Boolean = true,
)

data class ServerFavorite(
    val name: String,
    val address: String,
)

/** 화면 표시용 — 즐겨찾기 한 줄 + 어느 인스턴스 소속인지. */
data class ServerRow(
    val instanceId: String,
    val instanceName: String,
    val mcVersion: String,
    val name: String,
    val address: String,
)
