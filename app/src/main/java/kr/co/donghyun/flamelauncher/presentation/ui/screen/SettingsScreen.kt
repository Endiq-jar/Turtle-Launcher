package kr.co.donghyun.flamelauncher.presentation.ui.screen

import kr.co.donghyun.flamelauncher.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.co.donghyun.flamelauncher.data.renderer.Renderer
import kr.co.donghyun.flamelauncher.data.setting.Setting
import kr.co.donghyun.flamelauncher.domain.model.JvmSettings
import kr.co.donghyun.flamelauncher.presentation.ui.theme.BgBorder
import kr.co.donghyun.flamelauncher.presentation.ui.theme.BgDark
import kr.co.donghyun.flamelauncher.presentation.ui.theme.BgSurface
import kr.co.donghyun.flamelauncher.presentation.ui.theme.Flame
import kr.co.donghyun.flamelauncher.presentation.ui.theme.TextMain
import kr.co.donghyun.flamelauncher.presentation.ui.theme.TextSub
import kr.co.donghyun.flamelauncher.presentation.util.window.isTablet
import kr.co.donghyun.flamelauncher.presentation.util.window.isCompact

/**
 * JVM/게임플레이 설정 화면. Clean Architecture 마이그레이션 이후: 상태(settings/saved/
 * globalRenderer)는 SettingsViewModel 이 들고 있고, 이 Composable 은 순수 UI만 담당한다
 * (예전엔 JvmSettingsManager/RendererManager 를 이 파일이 직접 호출했었음).
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    settings: JvmSettings,
    saved: Boolean,
    globalRenderer: Renderer,
    totalRamMb: Int,
    maxHeapCeilingMb: Int,
    onSettingsChange: (JvmSettings) -> Unit,
    onReset: () -> Unit,
    onSave: () -> Unit,
    onGlobalRendererChange: (Renderer) -> Unit,
    launcherSetting: Setting = Setting(),
    onAnimationsEnabledChange: (Boolean) -> Unit = {},
    onPickBackground: () -> Unit = {},
    onClearBackground: () -> Unit = {},
    onPickSkin: () -> Unit = {},
    onClearSkin: () -> Unit = {},
) {
    val context = LocalContext.current
    val tablet = isTablet()
    val compact = isCompact()

    Column(modifier = Modifier.fillMaxSize().background(BgDark).systemBarsPadding()) {
        // 툴바 반응형 조정
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BgSurface)
                .border(1.dp, BgBorder, RoundedCornerShape(0.dp))
                .padding(horizontal = if (tablet) 16.dp else if (compact) 6.dp else 10.dp, vertical = if (tablet) 10.dp else if (compact) 4.dp else 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text(context.getString(R.string.back_button), color = TextSub, fontSize = if (tablet) 14.sp else if (compact) 10.sp else 11.sp)
            }
            Text(
                text = context.getString(R.string.jvm_settings_title),
                color = TextMain,
                fontSize = if (tablet) 18.sp else if (compact) 12.sp else 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).padding(horizontal = 4.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 8.dp)) {
                TextButton(onClick = onReset) {
                    Text(context.getString(R.string.initialize_button), color = Color(0xFFFF6B6B), fontSize = if (tablet) 13.sp else if (compact) 9.sp else 11.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Button(
                    onClick = onSave,
                    colors = ButtonDefaults.buttonColors(containerColor = Flame),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = if (compact) PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                      else ButtonDefaults.ContentPadding,
                ) {
                    Text(context.getString(R.string.save_button), color = Color.White, fontSize = if (tablet) 13.sp else if (compact) 9.sp else 11.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        if (saved) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Flame.copy(alpha = 0.1f))
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(context.getString(R.string.settings_saved_check), color = Flame, fontSize = if (tablet) 13.sp else 11.sp)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(if (tablet) 20.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (tablet) 16.dp else 10.dp)
        ) {
            // 전역 렌더러 섹션
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BgSurface, RoundedCornerShape(12.dp))
                    .border(1.dp, BgBorder, RoundedCornerShape(12.dp))
                    .padding(if (tablet) 16.dp else if (compact) 9.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    context.getString(R.string.default_renderer_label),
                    color = TextMain,
                    fontSize = if (tablet) 15.sp else 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    context.getString(R.string.new_instance_default_renderer_desc) +
                        context.getString(R.string.override_per_instance_hint),
                    color = TextSub,
                    fontSize = if (tablet) 12.sp else 10.sp
                )

                Spacer(Modifier.height(2.dp))

                Renderer.selectableRenderers().forEach { r ->
                    GlobalRendererOption(
                        emoji = r.emoji,
                        title = r.displayName,
                        desc = r.description,
                        selected = globalRenderer.id == r.id,
                        tablet = tablet,
                        onClick = { onGlobalRendererChange(r) },
                    )
                }
            }

            // Turtle의 전역 애니메이션 스위치. 비활성화하면 screen/dialog/list motion과
            // press compression이 모두 즉시 전환되어 저사양 기기와 접근성을 함께 지원한다.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BgSurface, RoundedCornerShape(12.dp))
                    .border(1.dp, BgBorder, RoundedCornerShape(12.dp))
                    .padding(if (tablet) 16.dp else if (compact) 9.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SettingToggleRow(
                    emoji = "✨",
                    title = context.getString(R.string.turtle_animation_settings_title),
                    subtitle = context.getString(R.string.turtle_animation_settings_desc),
                    checked = launcherSetting.animationsEnabled,
                    onCheckedChange = onAnimationsEnabledChange,
                )
            }

            // 실제 SAF 이미지 선택을 통해 Turtle의 사용자 배경을 보존한다.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BgSurface, RoundedCornerShape(12.dp))
                    .border(1.dp, BgBorder, RoundedCornerShape(12.dp))
                    .padding(if (tablet) 16.dp else if (compact) 9.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    context.getString(R.string.turtle_background_settings_title),
                    color = TextMain, fontSize = if (tablet) 15.sp else 12.sp, fontWeight = FontWeight.Bold
                )
                Text(
                    if (launcherSetting.backgroundUri.isNullOrBlank())
                        context.getString(R.string.turtle_background_settings_none)
                    else context.getString(R.string.turtle_background_settings_selected),
                    color = TextSub, fontSize = if (tablet) 12.sp else 10.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onPickBackground,
                        colors = ButtonDefaults.buttonColors(containerColor = Flame),
                        shape = RoundedCornerShape(8.dp),
                    ) { Text(context.getString(R.string.turtle_background_choose), color = Color.White, fontSize = 11.sp) }
                    if (!launcherSetting.backgroundUri.isNullOrBlank()) {
                        TextButton(onClick = onClearBackground) {
                            Text(context.getString(R.string.turtle_background_clear), color = Color(0xFFFF8A8A), fontSize = 11.sp)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    if (launcherSetting.skinUri.isNullOrBlank())
                        context.getString(R.string.turtle_skin_settings_none)
                    else context.getString(R.string.turtle_skin_settings_selected),
                    color = TextSub, fontSize = if (tablet) 12.sp else 10.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onPickSkin,
                        colors = ButtonDefaults.buttonColors(containerColor = Flame),
                        shape = RoundedCornerShape(8.dp),
                    ) { Text(context.getString(R.string.turtle_skin_choose), color = Color.White, fontSize = 11.sp) }
                    if (!launcherSetting.skinUri.isNullOrBlank()) {
                        TextButton(onClick = onClearSkin) {
                            Text(context.getString(R.string.turtle_skin_clear), color = Color(0xFFFF8A8A), fontSize = 11.sp)
                        }
                    }
                }
            }

            // 화면 설정 섹션
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BgSurface, RoundedCornerShape(12.dp))
                    .border(1.dp, BgBorder, RoundedCornerShape(12.dp))
                    .padding(if (tablet) 16.dp else if (compact) 9.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(context.getString(R.string.screen_settings_label), color = TextMain, fontSize = if (tablet) 15.sp else if (compact) 10.sp else 12.sp, fontWeight = FontWeight.Bold)

                SettingToggleRow(
                    emoji = "🖥", title = context.getString(R.string.fullscreen_label),
                    subtitle = context.getString(R.string.hide_system_bars_fullscreen),
                    checked = settings.fullscreen,
                    onCheckedChange = { onSettingsChange(settings.copy(fullscreen = it)) },
                )
                ResolutionScaleRow(
                    percent = settings.resolutionScalePercent,
                    onPercentChange = { onSettingsChange(settings.copy(resolutionScalePercent = it)) },
                )

                Spacer(Modifier.height(10.dp))
                Text(context.getString(R.string.memory_allocation_max_ram, totalRamMb), color = TextMain, fontSize = if (tablet) 15.sp else if (compact) 10.sp else 12.sp, fontWeight = FontWeight.Bold)

                Column {
                    Text(context.getString(R.string.max_heap_memory_label, settings.maxHeapMb), color = TextMain, fontSize = if (tablet) 13.sp else 11.sp)
                    Slider(
                        value = settings.maxHeapMb.toFloat(),
                        onValueChange = { onSettingsChange(settings.copy(maxHeapMb = it.toInt())) },
                        valueRange = 1024f..maxHeapCeilingMb.toFloat(),
                        steps = ((maxHeapCeilingMb - 1024) / 256),
                        colors = SliderDefaults.colors(thumbColor = Flame, activeTrackColor = Flame)
                    )
                }
            }

            // GC 섹션
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BgSurface, RoundedCornerShape(12.dp))
                    .border(1.dp, BgBorder, RoundedCornerShape(12.dp))
                    .padding(if (tablet) 16.dp else if (compact) 9.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(context.getString(R.string.garbage_collector_gc_label), color = TextMain, fontSize = if (tablet) 15.sp else if (compact) 10.sp else 12.sp, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        Text(context.getString(R.string.gc_g1gc_use), color = TextMain, fontSize = if (tablet) 13.sp else 11.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(context.getString(R.string.gc_optimized_for_large_heap), color = TextSub, fontSize = if (tablet) 11.sp else 9.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = settings.useG1GC,
                        onCheckedChange = { onSettingsChange(settings.copy(useG1GC = it)) },
                        colors = SwitchDefaults.colors(checkedTrackColor = Flame)
                    )
                }
            }

            // 사용자 지정 인수 섹션
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BgSurface, RoundedCornerShape(12.dp))
                    .border(1.dp, BgBorder, RoundedCornerShape(12.dp))
                    .padding(if (tablet) 16.dp else if (compact) 9.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(context.getString(R.string.extra_jvm_args_label), color = TextMain, fontSize = if (tablet) 15.sp else if (compact) 10.sp else 12.sp, fontWeight = FontWeight.Bold)
                BasicTextField(
                    value = settings.extraJvmArgs,
                    onValueChange = { onSettingsChange(settings.copy(extraJvmArgs = it)) },
                    textStyle = TextStyle(color = TextMain, fontSize = if (tablet) 13.sp else 11.sp),
                    cursorBrush = SolidColor(Flame),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = if (tablet) 100.dp else if (compact) 50.dp else 70.dp)
                        .background(BgDark, RoundedCornerShape(8.dp))
                        .border(1.dp, BgBorder, RoundedCornerShape(8.dp))
                        .padding(10.dp)
                )
            }
        }
    }
}
/**
 * 전역 렌더러 선택용 라디오 한 줄.
 * InstanceSettingsScreen 의 RendererOption 과 동일한 시각 언어를 쓰되,
 * SettingsScreen 의 폰트 스케일(tablet) 규칙에 맞춰 크기만 조정한다.
 */
@Composable
private fun GlobalRendererOption(
    emoji: String,
    title: String,
    desc: String,
    selected: Boolean,
    tablet: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (selected) Flame else BgBorder
    val compact = isCompact()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Flame.copy(alpha = 0.12f) else BgDark)
            .border(if (selected) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = if (tablet) 12.dp else if (compact) 8.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(emoji, fontSize = if (tablet) 20.sp else if (compact) 15.sp else 18.sp)
        Spacer(Modifier.width(if (compact) 8.dp else 12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (selected) Flame else TextMain,
                fontSize = if (tablet) 14.sp else if (compact) 10.sp else 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(desc, color = TextSub, fontSize = if (tablet) 11.sp else if (compact) 8.sp else 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (selected) {
            Spacer(Modifier.width(if (compact) 4.dp else 8.dp))
            Text("✓", color = Flame, fontSize = if (tablet) 18.sp else if (compact) 13.sp else 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}
