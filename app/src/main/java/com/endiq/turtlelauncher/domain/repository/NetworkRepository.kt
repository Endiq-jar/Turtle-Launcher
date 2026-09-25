package com.endiq.turtlelauncher.domain.repository

import com.endiq.turtlelauncher.domain.model.HostEntry
import com.endiq.turtlelauncher.domain.model.Instance
import com.endiq.turtlelauncher.domain.model.ServerRow

/** 네트워크 설정(호스트 매핑 + 서버 즐겨찾기) 화면 계약. */
interface NetworkRepository {
    fun getHostEntries(): List<HostEntry>
    fun saveHostEntries(entries: List<HostEntry>)

    fun getInstancesForServerPicker(): List<Instance>
    fun getServerRows(): List<ServerRow>
    fun addServerFavorite(instanceId: String, mcVersion: String, name: String, address: String): Boolean
    fun removeServerFavorite(instanceId: String, mcVersion: String, address: String)
}
