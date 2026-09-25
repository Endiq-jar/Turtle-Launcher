package kr.co.donghyun.turtlelauncher.presentation.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.co.donghyun.turtlelauncher.data.renderer.CustomVulkanDriverManager
import kr.co.donghyun.turtlelauncher.presentation.ui.theme.BgBorder
import kr.co.donghyun.turtlelauncher.presentation.ui.theme.BgDark
import kr.co.donghyun.turtlelauncher.presentation.ui.theme.BgSurface
import kr.co.donghyun.turtlelauncher.presentation.ui.theme.Turtle
import kr.co.donghyun.turtlelauncher.presentation.ui.theme.TextPrimary
import kr.co.donghyun.turtlelauncher.presentation.ui.theme.TextSecondary

/**
 * 커스텀 Vulkan 드라이버(Turnip 등) 가져오기/선택/삭제 바텀시트.
 *
 * ⚠️ 드라이버 파일 자체는 여기서 제공하지 않는다 — 신뢰할 수 있는 출처(Mesa 공식,
 * K11MCH1/AdrenoToolsDrivers 등)에서 사용자가 직접 zip 을 받아와야 한다. 이 화면은
 * "가져오기 → 목록 → 활성 선택"만 담당한다. 잘못된/기기와 안 맞는 드라이버를 선택하면
 * 게임이 크래시할 수 있고, 그 경우 여기서 다시 "시스템 기본"으로 되돌리면 된다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomVulkanDriverSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var drivers by remember { mutableStateOf(CustomVulkanDriverManager.listDrivers(context)) }
    var activeId by remember { mutableStateOf(CustomVulkanDriverManager.getActiveDriverId(context)) }
    var importing by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        errorMsg = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                CustomVulkanDriverManager.importDriverZip(context, uri)
            }
            importing = false
            if (result == null) {
                errorMsg = "가져오기 실패 — zip 안에 meta.json 이 없거나 형식이 안 맞아요."
            } else {
                drivers = CustomVulkanDriverManager.listDrivers(context)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = BgSurface,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(
                "🧩 커스텀 Vulkan 드라이버",
                color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                "Turnip 등 표준 포맷(zip 안에 .so + meta.json) 드라이버를 가져와서 Zink 렌더러에 적용해요. " +
                    "신뢰할 수 있는 곳(Mesa 공식, K11MCH1/AdrenoToolsDrivers 등)에서 받은 파일만 사용하세요. " +
                    "안 맞는 드라이버는 크래시를 일으킬 수 있어요 — 그럴 땐 아래에서 \"시스템 기본\"으로 되돌리면 돼요.",
                color = TextSecondary, fontSize = 11.sp, lineHeight = 15.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
            )

            // 시스템 기본(커스텀 드라이버 안 씀)
            DriverRow(
                title = "시스템 기본 드라이버",
                subtitle = "이 기기의 기본 Vulkan 드라이버를 그대로 사용",
                selected = activeId == null,
                onClick = {
                    CustomVulkanDriverManager.setActiveDriverId(context, null)
                    activeId = null
                },
                onDelete = null,
            )

            drivers.forEach { driver ->
                Spacer(Modifier.height(8.dp))
                DriverRow(
                    title = driver.displayName,
                    subtitle = driver.libraryName,
                    selected = activeId == driver.id,
                    onClick = {
                        CustomVulkanDriverManager.setActiveDriverId(context, driver.id)
                        activeId = driver.id
                    },
                    onDelete = {
                        CustomVulkanDriverManager.deleteDriver(context, driver.id)
                        drivers = CustomVulkanDriverManager.listDrivers(context)
                        if (activeId == driver.id) activeId = null
                    },
                )
            }

            errorMsg?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = Turtle, fontSize = 12.sp)
            }

            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (importing) {
                    CircularProgressIndicator(color = Turtle, modifier = Modifier.height(24.dp))
                } else {
                    TextButton(onClick = { zipPicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }) {
                        Text("📦 드라이버 zip 가져오기", color = Turtle, fontWeight = FontWeight.Bold)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("닫기", color = TextSecondary)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun DriverRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) Turtle.copy(alpha = 0.12f) else BgDark, RoundedCornerShape(10.dp))
            .border(if (selected) 1.5.dp else 1.dp, if (selected) Turtle else BgBorder, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = TextSecondary, fontSize = 11.sp)
        }
        if (onDelete != null) {
            TextButton(onClick = onDelete) {
                Text("삭제", color = Color(0xFFE5484D))
            }
        }
    }
}
