package kr.co.donghyun.turtlelauncher.domain.model

/**
 * 인스턴스(설치된 마인크래프트 실행 단위)의 도메인 모델.
 * data.instance.InstanceMeta 를 그대로 미러링한다 — 필드 이름을 맞춰서
 * data.mapper.InstanceMapper 가 얇은 매핑만 하도록 했다.
 *
 * ⚠️ 이 클래스는 android.* 를 import 하지 않는다(순수 코틀린).
 * domain 레이어의 원칙: presentation 도 data 도 domain 을 의존하지만,
 * domain 은 어느 쪽도 의존하지 않는다.
 */
data class Instance(
    val id: String,
    val name: String,
    val type: InstanceType,
    val mcVersion: String,
    val loaderType: String? = null,
    val loaderVersion: String? = null,
    val mainClass: String = "net.minecraft.client.main.Main",
    val extraJars: List<String> = emptyList(),
    val assetIndexId: String = "",
    val iconEmoji: String = "🌿",
    val iconPath: String? = null,
    val gameJvmArgs: List<String> = emptyList(),
    val gameArgs: List<String> = emptyList(),
    val sourceModId: Int? = null,
    val rendererId: String? = null,
)

enum class InstanceType { VANILLA, MODPACK, FABRIC }
