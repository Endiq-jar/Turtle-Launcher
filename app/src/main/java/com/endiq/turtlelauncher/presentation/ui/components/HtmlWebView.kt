package com.endiq.turtlelauncher.presentation.ui.components

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import com.endiq.turtlelauncher.presentation.ui.theme.Turtle
import com.endiq.turtlelauncher.presentation.ui.theme.TextMain

/**
 * HTML(모드/모드팩 설명, 릴리스 노트 등)을 이미지까지 포함해 실제로 렌더링하는 뷰어.
 *
 * 기존에는 서버가 준 HTML 태그를 정규식으로 벗겨 평문만 보여줬는데, 그 과정에서
 * Modrinth 처럼 본문에 원시 HTML(`<img>`, `<details>` 등)이 섞여 있으면 태그가 그대로
 * 노출됐다. 여기서는 WebView 로 HTML 을 그대로 렌더링해 이미지·표·코드블록까지 보여준다.
 *
 * 배치 주의:
 *  - 부모가 세로 스크롤(Compose verticalScroll) 컨테이너인 곳에 넣는다는 전제로,
 *    WebView 자체 스크롤을 끄고 내용 높이만큼 자연스럽게 커지게 한다(WRAP_CONTENT).
 *
 * @param html      body 안에 들어갈 HTML 조각(또는 완전한 HTML). 이미지 태그 포함 가능.
 * @param baseUrl   상대 경로 이미지/링크 해석용 기준 URL(대부분 절대경로라 없어도 무방).
 * @param textColor 본문 글자색(다크 테마 기본값).
 * @param linkColor 링크/포인트 색.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlWebView(
    html: String,
    modifier: Modifier = Modifier,
    baseUrl: String? = "https://www.curseforge.com/",
    textColor: Color = TextMain,
    linkColor: Color = Turtle,
) {
    val document = wrapHtmlDocument(html, textColor.toArgb(), linkColor.toArgb())

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                // 부모(Compose)가 스크롤을 담당하므로 WebView 내부 스크롤/오버스크롤은 끈다.
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                isNestedScrollingEnabled = false
                overScrollMode = WebView.OVER_SCROLL_NEVER
                setBackgroundColor(AndroidColor.TRANSPARENT)

                settings.apply {
                    javaScriptEnabled = false               // 설명 렌더에 JS 불필요 — 보안상 끔
                    loadsImagesAutomatically = true
                    blockNetworkImage = false
                    @Suppress("DEPRECATION")
                    mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    domStorageEnabled = false
                    builtInZoomControls = false
                    displayZoomControls = false
                    setSupportZoom(false)
                    textZoom = 100
                }

                // 문서 내 링크는 앱이 아니라 외부 브라우저로 연다.
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        return try {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                request.url,
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            view.context.startActivity(intent)
                            true
                        } catch (_: Exception) {
                            false
                        }
                    }
                }
            }
        },
        update = { web ->
            // 매 recomposition 마다 reload 되지 않도록 마지막 로드 내용을 tag 에 저장해 비교.
            if (web.tag != document) {
                web.tag = document
                web.loadDataWithBaseURL(baseUrl, document, "text/html", "UTF-8", null)
            }
        },
    )
}

/** body 조각을 다크 테마 CSS 로 감싼 완전한 HTML 문서로 만든다. */
private fun wrapHtmlDocument(body: String, textArgb: Int, linkArgb: Int): String {
    val text = hex(textArgb)
    val link = hex(linkArgb)
    return """
        <!DOCTYPE html>
        <html>
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
        <style>
          html,body{margin:0;padding:0;background:transparent;}
          body{
            color:$text;
            font-family:-apple-system,Roboto,'Noto Sans KR',sans-serif;
            font-size:14px;line-height:1.65;
            word-wrap:break-word;overflow-wrap:break-word;
          }
          img{max-width:100%!important;height:auto;border-radius:6px;margin:6px 0;}
          a{color:$link;text-decoration:none;}
          h1,h2,h3,h4,h5,h6{color:$text;line-height:1.3;margin:14px 0 8px;}
          h1{font-size:20px;} h2{font-size:18px;} h3{font-size:16px;}
          p{margin:8px 0;}
          ul,ol{padding-left:22px;margin:8px 0;}
          code{background:#ffffff1a;border-radius:4px;padding:1px 5px;
               font-family:monospace;font-size:13px;}
          pre{background:#ffffff14;border-radius:8px;padding:12px;overflow-x:auto;}
          pre code{background:transparent;padding:0;}
          blockquote{border-left:3px solid $link;margin:8px 0;padding:2px 0 2px 12px;
                     color:#ffffffb0;}
          hr{border:none;border-top:1px solid #ffffff2a;margin:14px 0;}
          table{border-collapse:collapse;display:block;overflow-x:auto;max-width:100%;}
          th,td{border:1px solid #ffffff2a;padding:5px 9px;}
          iframe,video{max-width:100%;}
        </style>
        </head>
        <body>$body</body>
        </html>
    """.trimIndent()
}

/** ARGB int → CSS #rrggbb (알파는 무시). */
private fun hex(argb: Int): String = String.format("#%06X", 0xFFFFFF and argb)
