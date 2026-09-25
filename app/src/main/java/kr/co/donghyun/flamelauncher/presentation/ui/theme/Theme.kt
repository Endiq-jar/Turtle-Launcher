package kr.co.donghyun.flamelauncher.presentation.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// ⚠️ **다크 전용이다.** 라이트 스킴도, 다이내믹 컬러도 두지 않는다.
//
//    예전에는 `isSystemInDarkTheme()` + `dynamicColor = true` 였는데, 그러면 안드로이드 12+
//    에서 **사용자 배경화면 색이 우리 팔레트를 덮어쓴다.** 게다가 스킴에 들어 있던 건
//    프로젝트 템플릿의 Purple80/Pink80 이라, 플레임 팔레트가 MaterialTheme 에 반영된 적이
//    아예 없었다 — 화면들이 FlamePrimary 같은 값을 직접 갖다 쓰고 있어서 드러나지 않았을 뿐이다.
//
//    런처는 게임 화면(항상 어둡다) 옆에 붙어 있는 UI라 밝은 테마가 의미가 없고,
//    두 플랫폼의 화면이 같아 보여야 하므로 iOS 와 같이 다크 하나로 고정한다.
private val FlameDarkScheme = darkColorScheme(
    primary = FlamePrimary,
    onPrimary = BgDark,
    primaryContainer = FlameDark,
    onPrimaryContainer = TextPrimary,

    secondary = FlameLight,
    onSecondary = BgDark,

    tertiary = FlameAccent,
    onTertiary = BgDark,

    background = BgDark,
    onBackground = TextPrimary,

    surface = BgSurface,
    onSurface = TextPrimary,
    surfaceVariant = BgItem,
    onSurfaceVariant = TextSecondary,

    outline = BgBorder,
    outlineVariant = BgBorder,

    error = Red,
    onError = BgDark,
    errorContainer = WarnBg,
    onErrorContainer = TextPrimary,
)

@Composable
fun FlameLauncherTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FlameDarkScheme,
        typography = Typography,
        content = content
    )
}
