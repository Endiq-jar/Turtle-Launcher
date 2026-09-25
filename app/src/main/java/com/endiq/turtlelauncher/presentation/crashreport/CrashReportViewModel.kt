package com.endiq.turtlelauncher.presentation.crashreport

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.endiq.turtlelauncher.presentation.util.crash.CrashLogParser
import java.io.File
import javax.inject.Inject

/**
 * CrashReportScreen ViewModel.
 * ⚠️ 소규모 진단 화면이라 domain.repository 계층 없이 CrashLogParser 를 직접 사용한다
 * (재사용/테스트 대체 필요성이 낮은 화면에는 Google 가이드도 이 정도 단순화를 허용).
 */
@HiltViewModel
class CrashReportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _logPath = MutableStateFlow("")
    val logPath: StateFlow<String> = _logPath.asStateFlow()

    private val _logContent = MutableStateFlow("")
    val logContent: StateFlow<String> = _logContent.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _suspects = MutableStateFlow<List<CrashLogParser.SuspectMod>>(emptyList())
    val suspects: StateFlow<List<CrashLogParser.SuspectMod>> = _suspects.asStateFlow()

    private lateinit var modsDir: File
    private lateinit var instanceDir: File
    private var initialized = false

    fun initialize(instanceDir: File) {
        if (initialized) return
        initialized = true
        this.instanceDir = instanceDir
        modsDir = File(instanceDir, "mods")
        viewModelScope.launch(Dispatchers.IO) { loadLatestLog(instanceDir) }
    }

    private fun loadLatestLog(instanceDir: File) {
        val crashDir = File(instanceDir, "crash-reports")
        val latestCrash = crashDir.listFiles()
            ?.filter { it.extension == "txt" }
            ?.maxByOrNull { it.lastModified() }

        if (latestCrash == null) {
            _logPath.value = ""
            _logContent.value = ""
            _suspects.value = emptyList()
            _isLoading.value = false
            return
        }

        _logPath.value = latestCrash.absolutePath
        val content = try {
            latestCrash.readText()
        } catch (e: Exception) {
            "로그 파일을 읽는 중 오류가 발생했습니다: ${e.message}"
        }
        _logContent.value = content
        _suspects.value = try {
            CrashLogParser.parseSuspects(content, modsDir)
        } catch (_: Exception) {
            emptyList()
        }
        _isLoading.value = false
    }

    fun toggleMod(jarName: String, enable: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = CrashLogParser.toggleMod(modsDir, jarName, enable)
            if (ok) {
                _suspects.value = try {
                    CrashLogParser.parseSuspects(_logContent.value, modsDir)
                } catch (_: Exception) {
                    _suspects.value
                }
            }
        }
    }
}
