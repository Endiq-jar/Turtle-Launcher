package kr.co.donghyun.flamelauncher.presentation.ui.components

import kr.co.donghyun.flamelauncher.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.co.donghyun.flamelauncher.presentation.ui.theme.BgBorder
import kr.co.donghyun.flamelauncher.presentation.ui.theme.BgSurface
import kr.co.donghyun.flamelauncher.presentation.ui.theme.Flame
import kr.co.donghyun.flamelauncher.presentation.ui.theme.TextMain
import kr.co.donghyun.flamelauncher.presentation.ui.theme.TextSub
import kr.co.donghyun.flamelauncher.presentation.util.window.isCompact

/**
 * 마인크래프트 부팅 중 표시되는 풀스크린 로딩 오버레이.
 * 게임 surface 위에 얹혀, 첫 프레임이 렌더링될 때까지 화면을 가린다.
 *
 * 레이아웃: Row( LoadingIndicator | Column(제목 + 안내문) ) + 우상단 닫기(X) 버튼.
 * 인디케이터는 Material3 Expressive 의 LoadingIndicator — 여러 도형 사이를 모핑하며 회전한다.
 *
 * @param modCount mods 폴더의 .jar 개수. 0 이면 바닐라로 간주하고 지연 문구를 숨긴다.
 * @param maxDelayMinutes 모드팩일 때 안내할 "최대 약 N분" 값.
 * @param onClose 사용자가 닫기(X) 버튼을 눌렀을 때.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MinecraftBootOverlay(
    modCount: Int,
    maxDelayMinutes: Int,
    onClose: () -> Unit,
) {
    val context = LocalContext.current

    val isModpack = modCount > 0
    val compact = isCompact()

    Box(
        modifier = Modifier
            .fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth(if (compact) 0.94f else 0.9f)
                .clip(RoundedCornerShape(18.dp))
                .background(BgSurface)
                .border(1.dp, BgBorder, RoundedCornerShape(18.dp))
                .padding(horizontal = if (compact) 14.dp else 24.dp, vertical = if (compact) 14.dp else 22.dp)
        ) {

            // 본문: 인디케이터(왼쪽) + 글자 Column(오른쪽)
            Row(
                modifier = Modifier.padding(end = if (compact) 20.dp else 24.dp),   // 닫기 버튼과 겹치지 않게
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 18.dp)
            ) {
                // 모양이 모핑되며 도는 Material3 Expressive 인디케이터
                LoadingIndicator(
                    modifier = Modifier.size(if (compact) 32.dp else 48.dp),
                    color = Flame
                )

                Column(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp)
                ) {
                    Text(
                        text = context.getString(R.string.booting_minecraft),
                        color = TextMain,
                        fontSize = if (compact) 12.sp else 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (isModpack) {
                        Text(
                            text = context.getString(R.string.loading_mod_count, modCount) +
                                    context.getString(R.string.first_launch_delay_hint, maxDelayMinutes),
                            color = TextSub,
                            fontSize = if (compact) 9.sp else 12.sp
                        )
                    } else {
                        Text(
                            text = context.getString(R.string.please_wait),
                            color = TextSub,
                            fontSize = if (compact) 9.sp else 12.sp
                        )
                    }
                }
            }
        }
    }
}