package kr.co.donghyun.turtlelauncher.domain.repository

import kr.co.donghyun.turtlelauncher.data.mods.ContentItem
import kr.co.donghyun.turtlelauncher.presentation.ui.screen.ContentType

/**
 * 컨텐츠(모드팩/모드/텍스처팩/쉐이더팩) 검색·브라우징 계약.
 *
 * ⚠️ 스코프: 검색/필터/설치여부표시 만 다룬다. 실제 설치(installDirect/
 * installModrinthModpack/installToExistingInstance/installToNewInstance 등, 1000줄+
 * 로 서로 강하게 얽혀있고 실기기 테스트가 불가능한 로직)는 ContentPackBrowserActivity 에
 * 당분간 남겨둔다 — MinecraftActivity 와 동일한 이유로 급하게 가르면 리스크가 더 크다고 판단.
 *
 * ⚠️ ContentItem/ContentType 을 그대로 쓴다 — 둘 다 Android 프레임워크 의존성이 없는
 * 순수 데이터 클래스/enum 이라(ContentType 이 presentation.ui.screen 패키지에 있는 건
 * 레거시 배치일 뿐 실제 UI 의존성은 없음) 복제 비용 대비 실익이 낮다고 판단.
 */
interface ContentRepository {
    suspend fun searchCurseForge(
        query: String,
        classId: Int,
        gameVersion: String,
        modLoaderType: Int?,
        index: Int,
        pageSize: Int,
    ): List<ContentItem>

    suspend fun searchModrinth(
        query: String,
        projectType: String,
        gameVersion: String,
        loader: String,
        offset: Int,
        limit: Int,
    ): List<ContentItem>

    /** MC 버전 필터용 목록 (release 만, 최신순). */
    suspend fun getAvailableMcVersions(): List<String>

    /** 주어진 검색 결과 중 이미 설치된 인스턴스와 매칭되는 항목의 trackKey 집합. */
    fun getInstalledContentKeys(contentPacks: List<ContentItem>): Set<String>
}
