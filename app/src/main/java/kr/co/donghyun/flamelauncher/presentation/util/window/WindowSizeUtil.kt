package kr.co.donghyun.flamelauncher.presentation.util.window

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class DeviceClass { Compact, Phone, Tablet }

/**
 * smallestScreenWidthDp 기준. 회전과 무관해서 안정적이고, 폴더블 접힘/펼침 시에도
 * Configuration 이 갱신되면서 자동으로 재계산된다(폴드 이벤트 = Configuration 변경).
 *  - Compact (Galaxy Z Flip 커버 화면 등, sw 대략 260~359dp) -> Compact
 *  - 폰 (일반 폰 portrait, sw 360~599dp)                      -> Phone
 *  - 태블릿(Z Fold 펼친 화면 포함, sw 600dp~)                  -> Tablet
 *
 * Z Flip 은 "isTablet() == false" 인 것만으로는 부족하다 — 커버 화면(sw 약 300~320dp)은
 * 일반 폰(sw 360~420dp)보다도 훨씬 좁아서 같은 Phone 디멘션을 쓰면 버튼/텍스트가
 * 화면 밖으로 밀려날 수 있다. 그래서 Phone 을 Compact/Phone 으로 한 번 더 나눈다.
 */
@Composable
@ReadOnlyComposable
fun rememberDeviceClass(): DeviceClass {
    val sw = LocalConfiguration.current.smallestScreenWidthDp
    return when {
        sw >= 600 -> DeviceClass.Tablet
        sw >= 360 -> DeviceClass.Phone
        else      -> DeviceClass.Compact
    }
}

@Composable
@ReadOnlyComposable
fun isTablet(): Boolean = rememberDeviceClass() == DeviceClass.Tablet

/** Z Flip 커버 화면처럼 아주 좁은 화면인지. 폰 전용 UI 안에서 한 번 더 줄여야 할 때 사용. */
@Composable
@ReadOnlyComposable
fun isCompact(): Boolean = rememberDeviceClass() == DeviceClass.Compact

/** 화면 공통 디멘션 — Compact/폰/태블릿 3단 분기 */
object ResponsiveDimens {
    @Composable fun contentMaxWidth(): Dp = if (isTablet()) 1200.dp else Dp.Unspecified
    @Composable fun horizontalPadding(): Dp = when (rememberDeviceClass()) {
        DeviceClass.Tablet -> 24.dp
        DeviceClass.Phone -> 16.dp
        DeviceClass.Compact -> 10.dp
    }
    @Composable fun listGap(): Dp = when (rememberDeviceClass()) {
        DeviceClass.Tablet -> 10.dp
        DeviceClass.Phone -> 6.dp
        DeviceClass.Compact -> 4.dp
    }
    @Composable fun sectionGap(): Dp = when (rememberDeviceClass()) {
        DeviceClass.Tablet -> 20.dp
        DeviceClass.Phone -> 16.dp
        DeviceClass.Compact -> 10.dp
    }
    @Composable fun titleSizeSp(): Int = when (rememberDeviceClass()) {
        DeviceClass.Tablet -> 20
        DeviceClass.Phone -> 16
        DeviceClass.Compact -> 14
    }
    @Composable fun bodySizeSp(): Int = when (rememberDeviceClass()) {
        DeviceClass.Tablet -> 15
        DeviceClass.Phone -> 13
        DeviceClass.Compact -> 11
    }
    @Composable fun listColumns(): Int = if (isTablet()) 2 else 1
    @Composable fun screenPadding(): PaddingValues = PaddingValues(
        horizontal = horizontalPadding(),
        vertical = when (rememberDeviceClass()) {
            DeviceClass.Tablet -> 16.dp
            DeviceClass.Phone -> 12.dp
            DeviceClass.Compact -> 8.dp
        }
    )
    /** 아이콘/터치 타깃 최소 크기 — Compact 에서도 접근성 최소치(40dp) 밑으로는 안 내려간다. */
    @Composable fun touchTargetSize(): Dp = when (rememberDeviceClass()) {
        DeviceClass.Tablet -> 48.dp
        DeviceClass.Phone -> 44.dp
        DeviceClass.Compact -> 40.dp
    }
}
