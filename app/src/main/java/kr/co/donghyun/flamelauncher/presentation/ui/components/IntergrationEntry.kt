/*
 * Terracotta 통합 예시 — Activity(Compose) 에서 VPN 동의 런처를 컨트롤러에 연결하는 법.
 * 이 파일은 "어떻게 붙이는지" 보여주는 예시이며, 실제로는 FlameLauncher 의
 * 게임 화면(MinecraftActivity) 또는 별도 화면에 맞춰 넣으면 된다.
 */
package kr.co.donghyun.flamelauncher.presentation.ui.components

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kr.co.donghyun.flamelauncher.presentation.ui.screen.TerracottaDialog
import kr.co.donghyun.flamelauncher.presentation.util.terracota.TerracottaController

/**
 * Terracotta 진입 Composable.
 *
 * @param activity 현재 Activity (VpnService.prepare 에 필요)
 * @param userName 플레이어 이름(없으면 null)
 * @param onClose  다이얼로그 닫기
 *
 * 사용:
 *   var show by remember { mutableStateOf(false) }
 *   if (show) TerracottaEntry(activity, userName) { show = false }
 */
@Composable
fun TerracottaEntry(
    activity: Activity,
    userName: String?,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val controller = remember { TerracottaController(activity, scope) }

    // VPN 동의 결과 런처 — 결과를 컨트롤러로 전달
    val vpnLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        controller.onVpnConsentResult(result.resultCode == Activity.RESULT_OK)
    }

    DisposableEffect(controller) {
        controller.launchVpnConsent = { intent -> vpnLauncher.launch(intent) }
        controller.start()   // 초기화 + waiting
        onDispose {
            controller.launchVpnConsent = null
            // 주: 여기서 stopVpn 하지 않음 — 백그라운드에서도 연결 유지하려면.
            //     완전 종료를 원하면 controller.stopVpn(activity) 호출.
        }
    }

    TerracottaDialog(
        controller = controller,
        userName = userName,
        onClose = onClose
    )
}