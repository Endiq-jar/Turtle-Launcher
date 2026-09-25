package com.endiq.turtlelauncher.presentation.ui.theme

import androidx.compose.ui.graphics.Color

// ── 푸른 불꽃 팔레트 ─────────────────────────────────────────────────────────
// 불꽃은 뜨거울수록 푸르다 — 심지 쪽이 시안, 바깥이 짙은 청색이다.
// 그 온도 그라데이션을 그대로 역할에 대응시킨다:
//   TurtleAccent(가장 뜨거움) → TurtlePrimary → TurtleLight → TurtleDark(바깥 불꽃)
// 배경은 검정이 아니라 **푸른기 도는 검정**이라 불꽃색이 겉돌지 않는다.
//
// ⚠️ 값은 iOS 의 TurtleColor 와 1:1 로 맞춘다 — 두 플랫폼이 같은 색으로 보여야 한다.

val Orange = Color(0xFF9CCF93)     // (이름은 유지) 중간 톤 블루
val BgDim = Color(0x88000000)
val Turtle = Color(0xFFC7E8B0)      // 포인트 컬러 (블루 플레임)
val BgDark = Color(0xFF0D0D0D)     // 가장 어두운 배경 (푸른 검정)
val BgSurface = Color(0xFF171717)  // 카드 / 서피스 배경
val BgBorder = Color(0xFF202020)   // 테두리
val TextMain = Color(0xFFF2F5F0)   // 기본 텍스트 (쿨 화이트)
val TextSub = Color(0xFFA8B0A6)    // 보조 텍스트 (쿨 그레이)
val BgItem   = Color(0xFF171717)   // 리스트 아이템
val Green    = Color(0xFF9CCF93)   // 성공
val Red      = Color(0xFFFF8A8A)   // 오류
val WarnBg = Color(0xFF55202020)     // 경고 배경

internal val TurtlePrimary   = Color(0xFFC7E8B0)   // 메인 플레임 (핫 블루)
internal val TurtleLight     = Color(0xFFC7E8B0)   // 라이트 플레임
internal val TurtleDark      = Color(0xFF9CCF93)   // 다크 플레임 (딥 블루)
internal val TurtleAccent    = Color(0xFF9CCF93)   // 액센트 (시안 — 불꽃 심지)
internal val TextPrimary    = Color(0xFFF2F5F0)   // 쿨 화이트
internal val TextSecondary  = Color(0xFFA8B0A6)   // 쿨 그레이
internal val TagRelease     = Color(0xFFC7E8B0)   // 릴리즈 뱃지
internal val TagSnapshot    = Color(0xFF9CCF93)   // 스냅샷 뱃지 (바이올렛)
internal val TagOld         = Color(0xFFA8B0A6)   // 구버전 뱃지
