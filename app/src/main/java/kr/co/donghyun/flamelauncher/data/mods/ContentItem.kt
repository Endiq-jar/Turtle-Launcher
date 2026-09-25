package kr.co.donghyun.flamelauncher.data.mods

/**
 * 컨텐츠 소스. 버전 필터 자리를 이 선택으로 대체한다.
 */
enum class ContentSource(val label: String, val prefix: String) {
    CURSEFORGE("CurseForge", "cf"),
    MODRINTH("Modrinth", "mr")
}

/**
 * 소스 무관 공통 컨텐츠 모델.
 *
 * CurseForge(id:Int) 와 Modrinth(id:String) 의 식별자/구조가 다르므로,
 * 화면·리스트는 이 공통 모델만 쓰고, 상세/설치 시 source 로 분기한다.
 *
 * @param id      소스 내 식별자. CurseForge=숫자 문자열, Modrinth=base62 문자열.
 * @param rawId   CurseForge 숫자 id (CF 전용 경로 호환용). Modrinth 면 null.
 */
data class ContentItem(
    val source: ContentSource,
    val id: String,
    val name: String,
    val summary: String,
    val downloads: Long,
    val logoUrl: String?,
    val rawId: Int? = null,
) {
    /** 설치 추적용 안정 키. 소스 접두 + id (예: "cf:12345", "mr:AABBccdd"). */
    val trackKey: String get() = "${source.prefix}:$id"

    companion object {
        /** CurseForge 모델 → 공통 모델 */
        fun from(mod: CurseForgeMod): ContentItem = ContentItem(
            source = ContentSource.CURSEFORGE,
            id = mod.id.toString(),
            name = mod.name,
            summary = mod.summary,
            downloads = mod.downloadCount,
            logoUrl = mod.logo?.url,
            rawId = mod.id,
        )

        /** Modrinth 검색 결과 → 공통 모델 */
        fun from(hit: ModrinthSearchHit): ContentItem = ContentItem(
            source = ContentSource.MODRINTH,
            id = hit.projectId,
            name = hit.title,
            summary = hit.description,
            downloads = hit.downloads,
            logoUrl = hit.iconUrl,
            rawId = null,
        )
    }
}