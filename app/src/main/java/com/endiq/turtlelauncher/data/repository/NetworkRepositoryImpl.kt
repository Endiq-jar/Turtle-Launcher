package com.endiq.turtlelauncher.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.endiq.turtlelauncher.data.instance.InstanceManager
import com.endiq.turtlelauncher.data.mapper.toDomain
import com.endiq.turtlelauncher.domain.model.HostEntry
import com.endiq.turtlelauncher.domain.model.Instance
import com.endiq.turtlelauncher.domain.model.ServerRow
import com.endiq.turtlelauncher.domain.repository.NetworkRepository
import com.endiq.turtlelauncher.presentation.util.hosts.HostsManager
import com.endiq.turtlelauncher.presentation.util.hosts.ServerFavorites
import com.endiq.turtlelauncher.data.instance.InstanceMeta
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : NetworkRepository {

    override fun getHostEntries(): List<HostEntry> =
        HostsManager.load(context).map { HostEntry(it.hostname, it.ip, it.note, it.enabled) }

    override fun saveHostEntries(entries: List<HostEntry>) {
        HostsManager.save(context, entries.map {
            com.endiq.turtlelauncher.presentation.util.hosts.HostEntry(it.hostname, it.ip, it.note, it.enabled)
        })
    }

    override fun getInstancesForServerPicker(): List<Instance> =
        InstanceManager.listInstances(context).map { it.toDomain() }

    override fun getServerRows(): List<ServerRow> =
        InstanceManager.listInstances(context).flatMap { meta: InstanceMeta ->
            ServerFavorites.list(context, meta.id).map { fav ->
                ServerRow(
                    instanceId = meta.id,
                    instanceName = meta.name,
                    mcVersion = meta.mcVersion,
                    name = fav.name,
                    address = fav.address,
                )
            }
        }

    override fun addServerFavorite(instanceId: String, mcVersion: String, name: String, address: String): Boolean =
        ServerFavorites.add(context, instanceId, mcVersion, name, address)

    override fun removeServerFavorite(instanceId: String, mcVersion: String, address: String) {
        ServerFavorites.remove(context, instanceId, mcVersion, address)
    }
}
