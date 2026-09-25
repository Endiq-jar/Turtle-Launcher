/*
 * Terracotta wrapper — ported & simplified for FlameLauncher.
 *
 * Original: ZalithLauncher2 (GPL-3.0), referencing FoldCraftLauncher / HMCL.
 *   https://github.com/FCL-Team/FoldCraftLauncher/blob/52f0542/FCL/src/main/java/com/tungsten/fcl/terracotta/Terracotta.java
 *
 * Simplifications for the minimal port:
 *  - EventViewModel(event bus)  -> direct VpnRequestCallback
 *  - MutableTransitionStateFlow -> StateFlow + previous-state tracking
 *  - PathManager / Logger       -> context.filesDir + android.util.Log
 *
 * 수정(버그 픽스):
 *  - 폴링 루프를 try/catch 로 감싸 일시적 파싱/검증 실패가 루프를 죽이지 않도록 함.
 *  - 상태 변경 감지를 index "증가" 가 아니라 "변경"(롤백 포함)으로 바꿔
 *    setWaiting() 이후 native 가 index 를 되돌려도 Waiting 이 publish 되도록 함 → reset 동작.
 *  - ★ 폴링 루프를 외부(Composable rememberCoroutineScope)가 아니라 싱글톤이 소유한
 *    앱 수명 스코프(pollScope)에서 돌린다. 다이얼로그를 닫으면 외부 스코프가 취소되어
 *    루프가 죽고, initialized=true 라 재오픈 시 루프가 다시 시작되지 않아 상태 갱신이
 *    영구히 멈추던 버그(재오픈 후 무반응)를 해결한다. ensurePolling() 으로 항상 보장.
 */

package kr.co.donghyun.flamelauncher.presentation.util.terracota

import android.util.Log
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kr.co.donghyun.flamelauncher.presentation.util.terracota.TerracottaState
import kr.co.donghyun.terracota.TerracottaAndroidAPI
import java.io.File
import java.io.IOException
import java.io.StringWriter
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "Terracotta"

/**
 * Terracotta 통합 진입점(싱글톤).
 *
 * 상태 폴링 루프가 핵심: 일정 간격으로 native getState() 를 읽어 index 가 바뀌면
 * 새 상태로 간주하고 StateFlow 에 publish. 호스트/게스트 진행이 이 흐름으로 드러난다.
 * 루프는 싱글톤이 소유한 pollScope 에서 돌아 앱 수명 동안 유지된다.
 */
object Terracotta {

    enum class Mode { Host, Guest }

    /**
     * native 가 VpnService 를 요구할 때(EasyTier 가 tun 을 획득할 때) 호출되는 콜백.
     * UI/Activity 레이어에서 VpnService.prepare() → 동의 → TerracottaVPNService 시작을 처리해야 한다.
     */
    fun interface VpnRequestCallback {
        fun onRequestVpn()
    }

    var initialized = false
        private set

    private var vpnCallback: VpnRequestCallback? = null
    private var metadata: TerracottaAndroidAPI.Metadata? = null

    var mode: Mode? = null
        private set

    // 현재 상태
    private val _state = MutableStateFlow<TerracottaState.Ready?>(null)
    val state = _state.asStateFlow()

    // (이전, 새) 상태 전이 — ZL2 의 MutableTransitionStateFlow.changes 대체
    private val _stateChanges = MutableSharedFlow<Pair<TerracottaState.Ready?, TerracottaState.Ready>>(
        extraBufferCapacity = 64
    )
    val stateChanges = _stateChanges.asSharedFlow()

    private val stateRef = AtomicReference<TerracottaState.Ready?>(null)

    // ★ 폴링 루프 전용 스코프(앱 수명). Composable 스코프에 의존하지 않는다.
    private val pollScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pollJob: Job? = null

    /** 폴링 간격. 1ms 는 과도해 앱 수명 내내 돌면 부담이 크므로 합리적 값으로 둔다. */
    private const val POLL_INTERVAL_MS = 50L

    /**
     * @param rootDir     Terracotta 작업 디렉터리 (예: context.filesDir/terracotta)
     * @param logFile     로그 파일 경로
     * @param vpnCallback native 의 VpnService 요청을 받을 콜백
     *
     * 폴링 루프는 내부 pollScope 에서 돌리므로 외부 스코프를 받지 않는다.
     */
    fun initialize(
        rootDir: File,
        logFile: File,
        vpnCallback: VpnRequestCallback
    ) {
        if (initialized) {
            // 이미 초기화됨 — 어떤 이유로든 루프가 멈춰 있으면 되살린다(재오픈 안전).
            this.vpnCallback = vpnCallback
            ensurePolling()
            return
        }
        this.vpnCallback = vpnCallback

        rootDir.mkdirs()
        logFile.parentFile?.mkdirs()

        metadata = TerracottaAndroidAPI.initialize(
            rootDir.absolutePath,
            logFile.absolutePath
        ) {
            // native VpnService 요청 → 콜백 위임
            this.vpnCallback?.onRequestVpn()
        }

        ensurePolling()
        initialized = true
    }

    /** 폴링 루프가 살아있지 않으면 (재)시작한다. 중복 시작은 막는다. */
    private fun ensurePolling() {
        if (pollJob?.isActive == true) return
        pollJob = pollScope.launch {
            while (true) {
                try {
                    val current = stateRef.get()
                    val index = current?.index ?: -1

                    val stateJson = TerracottaAndroidAPI.getState()
                    val obj = TerracottaState.TerracottaStateGson.fromJson<TerracottaState.Ready>(
                        stateJson,
                        object : TypeToken<TerracottaState.Ready>() {}.type
                    )

                    // index 가 "증가" 뿐 아니라 "변경"(리셋/롤백 포함)되면 새 상태로 본다.
                    // setWaiting() 이후 native 가 index 를 0 등으로 되돌려도 Waiting 이 publish 된다.
                    if (obj != null && obj.index != index) {
                        if (stateRef.compareAndSet(current, obj)) {
                            _state.value = obj
                            _stateChanges.tryEmit(current to obj)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e // 코루틴 취소는 그대로 전파
                } catch (e: Throwable) {
                    // 일시적 파싱/검증 실패가 폴링 루프 전체를 죽이지 않도록 한다.
                    Log.w(TAG, "state poll failed: ${e.message}")
                }

                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun setWaiting() {
        if (!initialized) return
        ensurePolling() // 상태 갱신이 멈춰 있지 않도록 보장
        TerracottaAndroidAPI.setWaiting()
    }

    /**
     * 폴링 루프가 죽어있으면 되살리기만 한다(상태는 건드리지 않음).
     * 다이얼로그 재진입 시 "이미 열린 방이 있는데 강제로 Waiting 으로 리셋되는" 문제를
     * 피하려고 setWaiting() 대신 이 함수로 안전하게 살아있는지만 확인한다.
     */
    fun ensureAlive() {
        if (!initialized) return
        ensurePolling()
    }

    /** 호스트 모드: LAN 을 스캔해 EasyTier 가상망에 광고. room=null 이면 새 룸 코드 발급. */
    fun setScanning(room: String?, player: String?, extraNodes: List<String>?) {
        checkInitialized()
        ensurePolling()
        if (_state.value !is TerracottaState.Waiting)
            throw IllegalStateException("reset state to waiting first!")
        mode = Mode.Host
        TerracottaAndroidAPI.setScanning(room, player, extraNodes)
    }

    /** 게스트 모드: 룸 코드로 가상망 합류. 코드가 유효하지 않으면 false. */
    fun setGuesting(room: String, player: String?, extraNodes: List<String>?): Boolean {
        checkInitialized()
        ensurePolling()
        if (_state.value !is TerracottaState.Waiting)
            throw IllegalStateException("reset state to waiting first!")
        mode = Mode.Guest
        return TerracottaAndroidAPI.setGuesting(room, player, extraNodes)
    }

    fun parseRoomCode(room: String?): TerracottaAndroidAPI.RoomType? {
        if (!initialized || room == null) return null
        return TerracottaAndroidAPI.parseRoomCode(room)
    }

    fun getMetadata(): TerracottaAndroidAPI.Metadata =
        metadata ?: TerracottaAndroidAPI.Metadata("unknown", 0, "unknown")

    fun collectLogs(): String? {
        if (!initialized) return null
        return try {
            TerracottaAndroidAPI.collectLogs().use { reader ->
                val writer = StringWriter()
                val buf = CharArray(4096)
                var n: Int
                while (reader.read(buf).also { n = it } != -1) writer.write(buf, 0, n)
                writer.toString()
            }
        } catch (e: IOException) {
            Log.w(TAG, "Failed to collect logs: ${e.message}")
            "Failed to collect logs: ${e.message}"
        }
    }

    private fun checkInitialized() {
        if (!initialized) throw IllegalStateException("initialize Terracotta first!")
    }
}