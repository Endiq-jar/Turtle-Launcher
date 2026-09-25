/*
 * Terracotta node list — rewritten for FlameLauncher (minimal port).
 *
 * 원본(ZalithLauncher2, GPL-3.0)은 glavo 노드 서버에서 목록을 받아오고
 * 중국 본토에서만 쓰도록 제한했다. FlameLauncher 는 그 인프라에 의존하지 않기로 했으므로
 * 네트워크 fetch 를 제거하고 "공개 퍼블릭 노드(또는 본인 노드)" 를 그대로 반환한다.
 *
 * 기존 호출부 호환을 위해 suspend fun fetchNodes(): List<URI> 시그니처는 유지한다.
 *
 * ⚠️ PUBLIC_NODES 가 비어 있으면 EasyTier 는 P2P 직결만 시도한다.
 *    NAT 뒤에서 연결이 안 되면 여기에 유효한 릴레이(공개 노드 또는 본인 노드)를 채워야 한다.
 *    공개 노드는 타인 인프라라 테스트용이며, 실배포는 본인 EasyTier 노드를 권장한다.
 *    형식: "tcp://host:port" / "udp://host:port" / "wss://host:port"
 */

package kr.co.donghyun.flamelauncher.presentation.util.terracota

import android.util.Log
import java.net.URI

private const val TAG = "TerracottaNodeList"

/**
 * 공개/자체 EasyTier 노드 목록.
 * 본인 노드를 띄웠다면 그 주소로 교체하세요. 예) "tcp://my-easytier.example:11010"
 *
 * ⚠️ 이 목록이 비면 호스트가 룸을 등록/중계할 코디네이션 지점이 없어
 *    방 만들기 후 타임아웃으로 PING_SERVER_RST / SCAFFOLDING_INVALID_RESPONSE 류
 *    Exception 이 자동으로 뜬다. 최소 1개 이상 유효한 노드를 두는 것을 권장.
 */
private val PUBLIC_NODES: List<String> = listOf(
    "tcp://13.125.58.184:11010"
)

/**
 * 호출부에서 "기본 노드" 로 바로 쓰기 위한 동기 접근자(네트워크 호출 없음).
 * 컨트롤러 생성 시 기본값으로 넣어 노드가 빈 채로 호스팅되는 일을 막는다.
 */
fun defaultNodes(): List<String> = PUBLIC_NODES

/**
 * 노드 목록을 반환한다(네트워크 호출 없음).
 * 잘못된 형식의 항목은 건너뛴다.
 */
@Suppress("RedundantSuspendModifier")  // 호출부 호환을 위해 suspend 유지
suspend fun fetchNodes(): List<URI> {
    return PUBLIC_NODES.mapNotNull { raw ->
        runCatching { URI(raw) }
            .onFailure { Log.w(TAG, "Invalid terracotta node URI: $raw", it) }
            .getOrNull()
    }
}