package kr.co.donghyun.flamelauncher.presentation.network

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kr.co.donghyun.flamelauncher.domain.model.HostEntry
import kr.co.donghyun.flamelauncher.domain.model.Instance
import kr.co.donghyun.flamelauncher.domain.model.ServerRow
import kr.co.donghyun.flamelauncher.domain.repository.NetworkRepository
import javax.inject.Inject

/**
 * NetworkSettingsScreen ViewModel.
 * ⚠️ 단순 CRUD 위임이라 UseCase 계층 생략, Repository 직접 주입(SettingsViewModel과 동일 판단).
 */
@HiltViewModel
class NetworkSettingsViewModel @Inject constructor(
    private val repository: NetworkRepository,
) : ViewModel() {

    private val _hostEntries = MutableStateFlow<List<HostEntry>>(emptyList())
    val hostEntries: StateFlow<List<HostEntry>> = _hostEntries.asStateFlow()

    private val _instances = MutableStateFlow<List<Instance>>(emptyList())
    val instances: StateFlow<List<Instance>> = _instances.asStateFlow()

    private val _serverRows = MutableStateFlow<List<ServerRow>>(emptyList())
    val serverRows: StateFlow<List<ServerRow>> = _serverRows.asStateFlow()

    init {
        _hostEntries.value = repository.getHostEntries()
        _instances.value = repository.getInstancesForServerPicker()
        _serverRows.value = repository.getServerRows()
    }

    fun saveHostEntries(entries: List<HostEntry>) {
        _hostEntries.value = entries
        repository.saveHostEntries(entries)
    }

    fun addHostEntry(entry: HostEntry) = saveHostEntries(_hostEntries.value + entry)

    fun updateHostEntry(old: HostEntry, updated: HostEntry) = saveHostEntries(
        _hostEntries.value.map { if (it.hostname == old.hostname && it.ip == old.ip) updated else it }
    )

    fun toggleHostEntry(entry: HostEntry) = saveHostEntries(
        _hostEntries.value.map {
            if (it.hostname == entry.hostname && it.ip == entry.ip) it.copy(enabled = !it.enabled) else it
        }
    )

    fun deleteHostEntry(entry: HostEntry) = saveHostEntries(
        _hostEntries.value.filterNot { it.hostname == entry.hostname && it.ip == entry.ip }
    )

    fun addServerFavorite(instanceId: String, mcVersion: String, name: String, address: String) {
        repository.addServerFavorite(instanceId, mcVersion, name, address)
        _serverRows.value = repository.getServerRows()
    }

    fun removeServerFavorite(instanceId: String, mcVersion: String, address: String) {
        repository.removeServerFavorite(instanceId, mcVersion, address)
        _serverRows.value = repository.getServerRows()
    }
}
