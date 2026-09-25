package kr.co.donghyun.flamelauncher.presentation.util.crash

import java.io.File

/**
 * 크래시 로그에서 "문제 가능성 있는 모드"를 추출한다.
 *
 * 전략 3 (주축): 스택트레이스의 `~[xxx.jar%23..!/:..]` 패턴에서 jar 파일명을 뽑는다.
 *   - 실제 코드가 실행된 jar 라 진범에 가깝다. (mixin 나열 줄의 `from mod X` 와 다름)
 *   - 코어 jar(minecraft/forge/fml/modlauncher 등)는 제외한다.
 * 전략 1 (보조): `Suspected Mods:` 필드에 Forge 가 명시한 모드가 있으면 함께 표시.
 *
 * 추출한 jar 를 instanceDir/mods 의 실제 파일과 매칭해, 토글 가능 여부를 판단한다.
 */
object CrashLogParser {

    /** 토글/표시 대상에서 제외할 코어 jar 의 파일명 prefix (소문자). */
    private val CORE_JAR_PREFIXES = listOf(
        "minecraft", "client-", "server-", "forge-", "fmlcore", "fmlloader",
        "fmlearlydisplay", "javafmllanguage", "lowcodelanguage", "mclanguage",
        "modlauncher", "bootstraplauncher", "securejarhandler", "asm",
        "accesstransformers", "eventbus", "mixin-", "mixinextras",
        "coremods", "spi-", "jarjar", "nashorn", "lwjgl", "processor-launcher",
    )

    /** `~[name.jar%23...]` / `~[name.jar:...]` / `[name.jar]` 에서 jar 파일명을 뽑는 정규식. */
    private val JAR_IN_BRACKET = Regex("""[\[~]\[?([\w.+\-]+\.jar)""")
    // 좀 더 일반적으로 모든 *.jar 토큰도 백업으로
    private val ANY_JAR = Regex("""([\w.+\-]+\.jar)""")

    data class SuspectMod(
        val jarName: String,        // 예: "controllable-sdl-2.30.12-1.1.0.jar"
        val displayName: String,    // 보기 좋은 이름 (jar 에서 버전 추정 제거)
        val existsInMods: Boolean,  // mods 폴더에 .jar 로 존재 → 끌 수 있음
        val currentlyDisabled: Boolean, // 이미 .jar.pingdisabled 로 꺼져 있음
        val occurrences: Int,       // 스택에서 등장 횟수(높을수록 의심도↑)
    )

    /**
     * @param logContent 크래시 로그 전문
     * @param modsDir instanceDir/mods
     * @return 의심 모드 목록 (등장 횟수 내림차순)
     */
    fun parseSuspects(logContent: String, modsDir: File): List<SuspectMod> {
        if (logContent.isBlank()) return emptyList()

        // ── 전략 3: 스택의 jar 파일명 추출 + 등장 횟수 집계 ──
        val jarCounts = LinkedHashMap<String, Int>()

        // 우선 bracket 패턴(신뢰도 높음)
        JAR_IN_BRACKET.findAll(logContent).forEach { m ->
            val jar = m.groupValues[1]
            if (!isCoreJar(jar)) jarCounts[jar] = (jarCounts[jar] ?: 0) + 1
        }
        // bracket 으로 하나도 못 잡았으면 백업으로 모든 .jar 토큰
        if (jarCounts.isEmpty()) {
            ANY_JAR.findAll(logContent).forEach { m ->
                val jar = m.groupValues[1]
                if (!isCoreJar(jar)) jarCounts[jar] = (jarCounts[jar] ?: 0) + 1
            }
        }

        // ── 전략 1: Suspected Mods 필드 (보조) ──
        // "Suspected Mods: foo, bar" 또는 "Suspected Mods: NONE"
        val suspectedModIds = Regex("""Suspected Mods:\s*(.+)""")
            .find(logContent)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals("NONE", ignoreCase = true) }
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        // mods 폴더 인덱스: 활성(.jar) / 비활성(.jar.pingdisabled)
        val activeJars = mutableSetOf<String>()
        val disabledJars = mutableSetOf<String>()  // 원래 jar 이름(.pingdisabled 떼고)
        if (modsDir.isDirectory) {
            modsDir.listFiles()?.forEach { f ->
                val n = f.name
                when {
                    n.endsWith(".jar", ignoreCase = true) -> activeJars.add(n)
                    n.endsWith(".jar.pingdisabled", ignoreCase = true) ->
                        disabledJars.add(n.removeSuffix(".pingdisabled"))
                }
            }
        }

        val result = mutableListOf<SuspectMod>()
        val seen = mutableSetOf<String>()

        // jar 등장 횟수 내림차순
        jarCounts.entries.sortedByDescending { it.value }.forEach { (jar, count) ->
            if (!seen.add(jar)) return@forEach
            val active = activeJars.contains(jar)
            val disabled = disabledJars.contains(jar)
            result.add(
                SuspectMod(
                    jarName = jar,
                    displayName = prettyName(jar),
                    existsInMods = active,           // 지금 켜져 있고 mods 에 있음 → 끌 수 있음
                    currentlyDisabled = disabled,    // 이미 꺼져 있음 → 켤 수 있음
                    occurrences = count,
                )
            )
        }

        // Suspected Mods 로만 언급되고 jar 매칭이 안 된 항목도 표시(끄기는 불가, 정보용)
        suspectedModIds.forEach { modId ->
            // modId 가 jar 이름의 일부로 매칭되면 이미 위에 있을 것
            val alreadyListed = result.any { it.jarName.lowercase().contains(modId.lowercase()) }
            if (!alreadyListed && seen.add("modid:$modId")) {
                result.add(
                    SuspectMod(
                        jarName = "",                // 매칭되는 jar 없음
                        displayName = modId,
                        existsInMods = false,
                        currentlyDisabled = false,
                        occurrences = 0,
                    )
                )
            }
        }

        return result
    }

    private fun isCoreJar(jar: String): Boolean {
        val lower = jar.lowercase()
        return CORE_JAR_PREFIXES.any { lower.startsWith(it) }
    }

    /** "controllable-sdl-2.30.12-1.1.0.jar" → "controllable-sdl" 처럼 버전 꼬리를 줄여 보기 좋게. */
    private fun prettyName(jar: String): String {
        var n = jar.removeSuffix(".jar")
        // 첫 숫자 버전 토큰 이전까지만 취함 (예: foo-bar-1.2.3 → foo-bar)
        val idx = Regex("""-\d""").find(n)?.range?.first
        if (idx != null && idx > 0) n = n.substring(0, idx)
        return n
    }

    /**
     * 모드 토글: 활성(.jar) ↔ 비활성(.jar.pingdisabled).
     * @return 성공 여부
     */
    fun toggleMod(modsDir: File, jarName: String, enable: Boolean): Boolean {
        if (jarName.isBlank() || !modsDir.isDirectory) return false
        return try {
            if (enable) {
                // 켜기: .jar.pingdisabled → .jar
                val disabled = File(modsDir, "$jarName.pingdisabled")
                val target = File(modsDir, jarName)
                if (!disabled.exists()) return target.exists() // 이미 켜져 있으면 성공 취급
                if (target.exists()) target.delete()
                disabled.renameTo(target)
            } else {
                // 끄기: .jar → .jar.pingdisabled
                val active = File(modsDir, jarName)
                val disabled = File(modsDir, "$jarName.pingdisabled")
                if (!active.exists()) return disabled.exists() // 이미 꺼져 있으면 성공 취급
                if (disabled.exists()) disabled.delete()
                active.renameTo(disabled)
            }
        } catch (_: Exception) {
            false
        }
    }
}