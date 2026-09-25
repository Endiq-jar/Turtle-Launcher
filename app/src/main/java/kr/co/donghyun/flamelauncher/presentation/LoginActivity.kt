package kr.co.donghyun.flamelauncher.presentation

import kr.co.donghyun.flamelauncher.R
import android.content.Intent
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dagger.hilt.android.AndroidEntryPoint
import kr.co.donghyun.flamelauncher.presentation.base.BaseActivity
import kr.co.donghyun.flamelauncher.presentation.login.LoginEvent
import kr.co.donghyun.flamelauncher.presentation.login.LoginViewModel
import kr.co.donghyun.flamelauncher.presentation.ui.theme.BgBorder
import kr.co.donghyun.flamelauncher.presentation.ui.theme.BgSurface
import kr.co.donghyun.flamelauncher.presentation.ui.theme.Flame
import kr.co.donghyun.flamelauncher.presentation.ui.theme.FlameLauncherTheme
import kr.co.donghyun.flamelauncher.presentation.ui.theme.TextMain
import kr.co.donghyun.flamelauncher.presentation.ui.theme.TextSub

/**
 * Microsoft 로그인 웹뷰 화면 — Clean Architecture 마이그레이션 완료.
 * 실제 토큰 교환/세션 저장은 LoginViewModel → AuthRepository 로 옮겼다.
 * WebView 자체는 Android API 라 Activity 에 남아있다.
 */
@AndroidEntryPoint
class LoginActivity : BaseActivity() {

    companion object {
        const val RESULT_ERROR = "login_error"
    }

    private val viewModel: LoginViewModel by viewModels()

    override fun onCreated() {
        setContent {
            FlameLauncherTheme {
                val isLoading by viewModel.isLoading.collectAsState()
                val statusMessage by viewModel.statusMessage.collectAsState()
                var offlineDialog by remember { mutableStateOf(false) }
                var offlineName by remember { mutableStateOf("") }

                LaunchedEffect(Unit) {
                    viewModel.events.collect { event ->
                        when (event) {
                            is LoginEvent.Success -> {
                                setResult(RESULT_OK, Intent())
                                finish()
                            }
                            is LoginEvent.Failure -> {
                                setResult(RESULT_CANCELED, Intent().putExtra(RESULT_ERROR, event.message))
                                finish()
                            }
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0D0008))) {
                    // WebView 는 로딩 중에도 화면에서 빼지 않는다(항상 컴포지션에 유지).
                    // redirect 감지 즉시 이 웹페이지 위에 팝업만 오버레이로 띄운다.
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?
                                    ): Boolean {
                                        val url = request?.url?.toString() ?: return false
                                        if (viewModel.isRedirectUri(url)) {
                                            val code = request.url?.getQueryParameter("code")
                                            if (code != null) viewModel.login(code)
                                            return true
                                        }
                                        return false
                                    }
                                }
                                loadUrl(viewModel.getAuthUrl())
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    if (isLoading) {
                        // 마인크래프트 부팅 오버레이(MinecraftBootOverlay)와 동일한 카드형 팝업을
                        // 웹페이지 위에 스크림과 함께 띄운다(다이얼로그처럼).
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.55f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .widthIn(max = 340.dp)
                                    .fillMaxWidth(0.85f)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(BgSurface)
                                    .border(1.dp, BgBorder, RoundedCornerShape(18.dp))
                                    .padding(horizontal = 24.dp, vertical = 22.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    CircularProgressIndicator(color = Flame, modifier = Modifier.size(36.dp))
                                    Column {
                                        Text(
                                            getString(R.string.logging_in),
                                            color = TextMain,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        if (statusMessage.isNotBlank()) {
                                            Spacer(Modifier.height(4.dp))
                                            Text(statusMessage, color = TextSub, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (!isLoading) {
                        TextButton(
                            onClick = { offlineDialog = true },
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
                        ) {
                            Text("Play offline", color = Flame, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (offlineDialog) {
                        AlertDialog(
                            onDismissRequest = { offlineDialog = false },
                            title = { Text("Offline account", color = TextMain) },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Use a local name when Microsoft sign-in is unavailable. This does not create an online account.", color = TextSub, fontSize = 12.sp)
                                    OutlinedTextField(
                                        value = offlineName,
                                        onValueChange = { offlineName = it },
                                        singleLine = true,
                                        label = { Text("Player name") },
                                    )
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { viewModel.loginOffline(offlineName) }) {
                                    Text("Continue", color = Flame)
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { offlineDialog = false }) { Text("Cancel", color = TextSub) }
                            },
                        )
                    }
                }
            }
        }
    }
}
