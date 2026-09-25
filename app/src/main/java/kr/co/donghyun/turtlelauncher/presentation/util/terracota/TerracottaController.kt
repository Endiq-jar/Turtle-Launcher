package kr.co.donghyun.turtlelauncher.presentation.util.terracota

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kr.co.donghyun.terracota.TerracottaAndroidAPI
import java.io.File

private const val TAG = "TerracottaController"

/**
 * 최소 동작 컨트롤러.
 *
 * 사용 순서:
 *   1) val ctrl = TerracottaController(activity, scope)
 *   2) activity 에서 VPN 동의 런처를 만들고 ctrl.onVpnConsentResult 로 결과를 넘김
 *      (registerForActivityResult(StartActivityForResult))
 *   3) ctrl.start()  → Terracotta 초기화 + waiting
 *   4) 호스트: ctrl.hostRoom(userName)  → state 가 HostOK 되면 .code 가 룸 코드
 *      게스트: ctrl.joinRoom(code, userName)
 *
 * 추가된 동작:
 *   - ① 랜 미오픈: 호스팅 후 HOST_SCAN_TIMEOUT_MS 안에 스캔을 벗어나지 못하면
 *        "LAN 을 먼저 여세요" 오류를 띄우고 대기로 복귀.
 *   - ② 랜 닫힘: 호스트 세션 중 PING_HOST_FAIL/RST·HOST_ET_CRASH 예외가 뜨면
 *        강제 teardown(setWaiting) 으로 연결된 게스트를 끊는다.
 *   - 두 경우 모두 [notices] 로 UI 가 구독할 수 있고, Toast 도 함께 띄운다(UI 수정 없이도 동작).
 */
class TerracottaController(
    private val activity: Activity,
    private val scope: CoroutineScope,
    // 비워두면 EasyTier 가 P2P 직결만 시도하고, NAT/룸 등록 실패로 방 만들기 후
    // n초 뒤 자동 Exception 이 뜬다. 기본값으로 공개/자체 노드를 넣어준다.
    // (다른 노드를 쓰려면 생성 시 명시적으로 전달.)
    private val nodes: List<String> = defaultNodes(),
) {
    /** VPN 동의 다이얼로그를 띄우는 람다. activity 의 ActivityResultLauncher.launch 를 연결한다. */
    var launchVpnConsent: ((Intent) -> Unit)? = null

    // ★ native 의 VpnService 요청 콜백이 짧은 시간 안에 중복 호출되면(예: 호스트 스캔 단계와
    //   실제 EasyTier tun 획득 단계에서 각각 한 번씩) 시스템 VPN 동의 다이얼로그가 두 번 뜨는
    //   문제가 있었다. 응답이 오기 전까지는 재요청을 무시해 한 번만 뜨도록 가드한다.
    @Volatile
    private var vpnRequestPending = false

    val state get() = Terracotta.state
    val stateChanges get() = Terracotta.stateChanges

    /** 호스트 LAN 관련 알림. UI 가 선택적으로 collect 해서 메시지를 보여줄 수 있다. */
    enum class HostNotice { NO_LAN_OPEN, LAN_CLOSED }

    private val _notices = MutableSharedFlow<HostNotice>(extraBufferCapacity = 8)
    val notices = _notices.asSharedFlow()

    private var hostScanTimeoutJob: Job? = null
    private var stateObserverJob: Job? = null

    companion object {
        /** 이 시간 안에 HostScanning 을 벗어나지 못하면 "LAN 미오픈"으로 간주. */
        private const val HOST_SCAN_TIMEOUT_MS = 8_000L
    }

    fun start() {
        // ② 를 위한 상태 관찰자 — 초기화 여부와 무관하게 한 번만 띄운다.
        ensureStateObserver()

        if (Terracotta.initialized) {
            // ★ 이미 방이 열려있거나(HostOK) 참여 중(GuestOK)이면 그대로 유지한다.
            //   예전엔 여기서 무조건 setWaiting() 을 호출해 살아있는 방을 강제로
            //   대기 상태로 리셋시켰는데, 그 상태에서 사용자가 "방 만들기"를 다시 누르면
            //   기존 방과 별개로 새 방이 하나 더 생기는 버그가 있었다(온라인 플레이어 있을 때
            //   재현). Waiting/미확정 상태일 때만 setWaiting 으로 정리한다.
            Terracotta.ensureAlive()
            val cur = Terracotta.state.value
            if (cur == null || cur is TerracottaState.Waiting) {
                Terracotta.setWaiting()
            }
            return
        }
        val root = File(activity.filesDir, "terracotta")
        val logFile = File(root, "terracotta.log")

        Terracotta.initialize(
            rootDir = root,
            logFile = logFile
        ) {
            // native 가 VpnService 를 요구 → 메인 스레드에서 동의 흐름
            activity.runOnUiThread { requestVpn() }
        }
        Terracotta.setWaiting()
    }

    private fun requestVpn() {
        // 이미 사용자 응답을 기다리는 요청이 있으면 무시 — 시스템 동의 다이얼로그 중복 방지.
        if (vpnRequestPending) {
            Log.d(TAG, "requestVpn: 이미 대기 중인 VPN 동의 요청이 있어 무시함")
            return
        }
        val intent = runCatching { VpnService.prepare(activity) }.getOrElse {
            Log.w(TAG, "VpnService.prepare 실패: ${it.message}")
            null
        }
        if (intent != null) {
            // 아직 동의 전 → 시스템 다이얼로그
            val launcher = launchVpnConsent
            if (launcher != null) {
                vpnRequestPending = true
                launcher(intent)
            } else {
                Log.e(TAG, "launchVpnConsent 가 설정되지 않음 — VPN 동의를 띄울 수 없음")
                runCatching { TerracottaAndroidAPI.getPendingVpnServiceRequest().reject() }
                Terracotta.setWaiting()
            }
        } else {
            // 이미 동의됨 → 바로 서비스 시작
            startVpnService()
        }
    }

    /** activity 의 ActivityResult 콜백에서 호출 */
    fun onVpnConsentResult(granted: Boolean) {
        vpnRequestPending = false
        if (granted) {
            startVpnService()
        } else {
            runCatching { TerracottaAndroidAPI.getPendingVpnServiceRequest().reject() }
            Terracotta.setWaiting()
            Log.w(TAG, "VPN 권한이 거부됨")
        }
    }

    private fun startVpnService() {
        runCatching {
            val vpnIntent = Intent(activity, TerracottaVPNService::class.java)
                .setAction(TerracottaVPNService.ACTION_START)
            ContextCompat.startForegroundService(activity, vpnIntent)
        }.onFailure { Log.w(TAG, "startVpnService 실패: ${it.message}") }
    }

    /** 호스트: 새 룸을 열고 LAN 을 광고. 진행은 state(HostScanning→HostStarting→HostOK)로 드러남. */
    fun hostRoom(userName: String?) {
        val started = runCatching {
            Terracotta.setScanning(room = null, player = userName, extraNodes = nodes)
            true
        }.getOrElse {
            Log.w(TAG, "hostRoom 실패: ${it.message}")
            false
        }
        if (started) startHostScanTimeout()
    }

    /**
     * ① 랜 미오픈 감지.
     * 네이티브가 LAN 브로드캐스트를 못 찾아 HostScanning 에서 정체되면,
     * HOST_SCAN_TIMEOUT_MS 후 "LAN 을 먼저 여세요" 오류를 띄우고 대기로 복귀한다.
     * (LAN 이 열려 있으면 곧 HostStarting/HostOK 로 진행되어 타임아웃이 발동하지 않는다.)
     */
    private fun startHostScanTimeout() {
        hostScanTimeoutJob?.cancel()
        hostScanTimeoutJob = scope.launch {
            val progressed = withTimeoutOrNull(HOST_SCAN_TIMEOUT_MS) {
                // 스캔을 벗어나는(또는 외부에서 리셋되는) 첫 상태까지 대기
                Terracotta.state.first { s ->
                    s is TerracottaState.HostStarting ||
                            s is TerracottaState.HostOK ||
                            s is TerracottaState.Exception ||
                            s is TerracottaState.Waiting
                }
            }
            if (progressed == null) {
                // 제한 시간 내내 HostScanning → LAN 미오픈으로 간주
                Log.w(TAG, "host scan timed out; assuming Minecraft LAN is not open")
                _notices.tryEmit(HostNotice.NO_LAN_OPEN)
                toast("LAN 월드를 찾지 못했어요. 먼저 마인크래프트에서 'Open to LAN'으로 월드를 연 뒤 다시 시도하세요.")
                Terracotta.setWaiting()
            }
            // progressed != null → 정상 진행/예외/리셋. 예외 처리는 상태 관찰자(②)가 담당.
        }
    }

    /**
     * ② 랜 닫힘 → 게스트 강제 끊기.
     * 호스트 세션 중 호스트 측 LAN/서버가 사라졌음을 뜻하는 예외가 뜨면,
     * setWaiting() 으로 가상망을 내려 연결된 게스트를 끊는다.
     */
    private fun ensureStateObserver() {
        if (stateObserverJob?.isActive == true) return
        stateObserverJob = scope.launch {
            Terracotta.stateChanges.collect { (_, cur) ->
                runCatching { handleHostLanLoss(cur) }
                    .onFailure { Log.w(TAG, "state observe error: ${it.message}") }
            }
        }
    }

    private fun handleHostLanLoss(cur: TerracottaState.Ready) {
        if (Terracotta.mode != Terracotta.Mode.Host) return
        if (cur !is TerracottaState.Exception) return

        val type = runCatching { cur.getEnumType() }.getOrNull()
        val hostLanLost = type == TerracottaState.Exception.Type.PING_HOST_FAIL ||
                type == TerracottaState.Exception.Type.PING_HOST_RST ||
                type == TerracottaState.Exception.Type.HOST_ET_CRASH
        if (!hostLanLost) return

        Log.w(TAG, "host LAN/server lost ($type); tearing down to disconnect guests")
        hostScanTimeoutJob?.cancel()
        _notices.tryEmit(HostNotice.LAN_CLOSED)
        toast("호스트의 LAN 월드 연결이 끊겨 룸을 종료했어요. 연결된 기기의 접속이 끊깁니다.")
        Terracotta.setWaiting()
    }

    /** 게스트: 룸 코드로 합류. 코드가 유효하지 않으면 false. */
    fun joinRoom(code: String, userName: String?): Boolean {
        return runCatching {
            Terracotta.setGuesting(room = code, player = userName, extraNodes = nodes)
        }.getOrElse {
            Log.w(TAG, "joinRoom 실패: ${it.message}")
            false
        }
    }

    /** 룸 코드 형식 검증 (UI 입력 즉시 체크용) */
    fun isValidRoomCode(code: String): Boolean =
        Terracotta.parseRoomCode(code) != null

    /** 대기 상태로 복귀 (룸 나가기) */
    fun reset() {
        hostScanTimeoutJob?.cancel()
        Terracotta.setWaiting()
    }

    /** VPN 서비스 중지 */
    fun stopVpn(context: Context) {
        hostScanTimeoutJob?.cancel()
        if (TerracottaVPNService.isRunning()) {
            runCatching {
                val vpnIntent = Intent(context, TerracottaVPNService::class.java)
                    .setAction(TerracottaVPNService.ACTION_STOP)
                ContextCompat.startForegroundService(context, vpnIntent)
            }.onFailure { Log.w(TAG, "stopVpn 실패: ${it.message}") }
        }
    }

    private fun toast(msg: String) {
        runCatching {
            activity.runOnUiThread {
                Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
            }
        }
    }
}