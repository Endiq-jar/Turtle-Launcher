package com.endiq.turtlelauncher.presentation.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.endiq.turtlelauncher.presentation.ui.components.HtmlWebView
import com.endiq.turtlelauncher.presentation.ui.theme.*
import java.net.HttpURLConnection
import java.net.URL

/**
 * 저장소 README 를 그대로 띄우는 업데이트 노트 창. iOS 판과 같은 내용을 본다.
 *
 * ⚠️ 마크다운을 직접 렌더링하지 않는다. GitHub 이 `Accept: application/vnd.github.html`
 *    로 요청하면 **렌더링된 HTML** 을 주므로, 표·체크박스·코드블록까지 GitHub 과 같은
 *    모양으로 나온다. 앱에서 마크다운 파서를 들고 다닐 이유가 없다.
 */
private const val README_API = "https://api.github.com/repos/Endiq-jar/TurtleLauncher/readme"

/** 앱이 살아있는 동안 한 번만 받는다. 화면을 오갈 때마다 다시 받을 이유가 없다. */
private var cachedHtml: String? = null

@Composable
fun ReleaseNotesPane(modifier: Modifier = Modifier) {
    var html by remember { mutableStateOf(cachedHtml) }
    var failed by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(reloadKey) {
        if (html != null) return@LaunchedEffect
        failed = false
        val loaded = withContext(Dispatchers.IO) { fetchReadmeHtml() }
        if (loaded != null) {
            cachedHtml = loaded
            html = loaded
        } else {
            failed = true
        }
    }

    Box(modifier = modifier.fillMaxSize().background(BgDark)) {
        when {
            html != null -> Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            ) {
                HtmlWebView(
                    html = html!!,
                    // 상대 경로 이미지(README 의 배지·스크린샷)를 풀어줄 기준.
                    baseUrl = "https://raw.githubusercontent.com/Endiq-jar/TurtleLauncher/main/",
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                )
            }

            failed -> Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("📡", fontSize = 40.sp)
                Spacer(Modifier.height(10.dp))
                Text(
                    "업데이트 노트를 불러오지 못했어요",
                    color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "네트워크를 확인하고 다시 시도해 주세요.",
                    color = TextSecondary, fontSize = 11.sp,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    "다시 시도",
                    color = TurtlePrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                        .background(BgItem)
                        .clickable { reloadKey++ }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }

            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = TurtlePrimary)
            }
        }
    }
}

private fun fetchReadmeHtml(): String? = try {
    (URL(README_API).openConnection() as HttpURLConnection).run {
        // ⚠️ 이 헤더가 핵심이다. 없으면 JSON 메타데이터(base64 본문)가 오고,
        //    있으면 GitHub 이 렌더링한 HTML 을 그대로 준다.
        setRequestProperty("Accept", "application/vnd.github.html")
        setRequestProperty("User-Agent", "TurtleLauncher")
        connectTimeout = 10_000
        readTimeout = 10_000
        if (responseCode == 200) inputStream.bufferedReader().use { it.readText() } else null
    }
} catch (_: Exception) {
    null
}
