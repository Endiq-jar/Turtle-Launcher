package kr.co.donghyun.flamelauncher.data.mods

import com.google.gson.annotations.SerializedName

// ── 검색 (/v2/search) ────────────────────────────────────────
//   CurseForge 와 JSON 구조가 완전히 다르다. 여기 모델은 Modrinth 전용이며,
//   화면에는 공통 모델(ContentItem)로 변환해서 넘긴다.

data class ModrinthSearchResponse(
    val hits: List<ModrinthSearchHit> = emptyList(),
    val offset: Int = 0,
    val limit: Int = 0,
    @SerializedName("total_hits")
    val totalHits: Int = 0
)

data class ModrinthSearchHit(
    @SerializedName("project_id")
    val projectId: String,                 // 8자리 base62 (장기 식별자)
    val slug: String? = null,
    val title: String = "",
    val description: String = "",          // 짧은 요약
    val downloads: Long = 0,
    @SerializedName("icon_url")
    val iconUrl: String? = null,
    @SerializedName("project_type")
    val projectType: String = "",          // "mod" | "modpack" | "resourcepack" | "shader" | "datapack"
    val versions: List<String> = emptyList(),   // 지원 MC 버전들
    val categories: List<String> = emptyList(), // 로더 + 카테고리가 섞여 들어옴
    @SerializedName("latest_version")
    val latestVersion: String? = null
)

// ── 프로젝트 상세 (/v2/project/{id}) ─────────────────────────

data class ModrinthProject(
    val id: String,
    val slug: String? = null,
    val title: String = "",
    val description: String = "",          // 짧은 요약
    val body: String = "",                 // 긴 설명(마크다운)
    val downloads: Long = 0,
    @SerializedName("icon_url")
    val iconUrl: String? = null,
    @SerializedName("project_type")
    val projectType: String = "",
    @SerializedName("game_versions")
    val gameVersions: List<String> = emptyList(),
    val loaders: List<String> = emptyList(),
    val gallery: List<ModrinthGalleryImage> = emptyList()
)

data class ModrinthGalleryImage(
    val url: String,
    val featured: Boolean = false,
    val title: String? = null,
    val description: String? = null
)

// ── 버전 목록 (/v2/project/{id}/version) ─────────────────────

data class ModrinthVersion(
    val id: String,                        // 버전 id (다운로드 식별)
    @SerializedName("project_id")
    val projectId: String,
    val name: String = "",                 // 버전 표시 이름
    @SerializedName("version_number")
    val versionNumber: String = "",
    @SerializedName("version_type")
    val versionType: String = "release",   // "release" | "beta" | "alpha"
    @SerializedName("game_versions")
    val gameVersions: List<String> = emptyList(),
    val loaders: List<String> = emptyList(),
    @SerializedName("date_published")
    val datePublished: String? = null,     // ISO8601
    val files: List<ModrinthVersionFile> = emptyList(),
    val dependencies: List<ModrinthDependency> = emptyList()
)

data class ModrinthVersionFile(
    val url: String,
    val filename: String,
    val primary: Boolean = false,
    val size: Long = 0,
    val hashes: ModrinthHashes? = null
)

data class ModrinthHashes(
    val sha1: String? = null,
    val sha512: String? = null
)

data class ModrinthDependency(
    @SerializedName("project_id")
    val projectId: String? = null,
    @SerializedName("version_id")
    val versionId: String? = null,
    @SerializedName("dependency_type")
    val dependencyType: String = "required"   // "required" | "optional" | "incompatible" | "embedded"
)

// ── .mrpack 인덱스 (modrinth.index.json) ─────────────────────
//   .mrpack = zip. 내부 modrinth.index.json 이 매니페스트.
//   files[] 는 직접 다운로드 URL 을 가지므로 CurseForge 처럼 파일 메타를 또 조회할 필요가 없다.

data class MrpackIndex(
    val formatVersion: Int = 1,
    val game: String = "minecraft",
    val versionId: String = "",            // 모드팩 자체 버전
    val name: String = "",
    val summary: String? = null,
    val files: List<MrpackFile> = emptyList(),
    val dependencies: Map<String, String> = emptyMap()  // "minecraft","forge","fabric-loader","neoforge","quilt-loader" → 버전
)

data class MrpackFile(
    val path: String,                      // 게임 디렉터리 기준 상대 경로 (예: "mods/sodium.jar")
    val hashes: ModrinthHashes? = null,
    val env: MrpackEnv? = null,
    val downloads: List<String> = emptyList(),  // 직접 다운로드 URL 목록(첫 번째 사용)
    val fileSize: Long = 0
)

data class MrpackEnv(
    val client: String? = null,            // "required" | "optional" | "unsupported"
    val server: String? = null
)