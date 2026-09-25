package kr.co.donghyun.flamelauncher.presentation.util.forge

import android.content.Context
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kr.co.donghyun.flamelauncher.presentation.util.minecraft.VersionRepository
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * Forge / NeoForge 설치 결과. FabricInstallResult 와 같은 모양으로 맞춰서
 * InstanceMeta 채울 때 동일 흐름을 쓸 수 있게 한다.
 */

data class ForgeInstallResult(
    val success: Boolean,
    val mainClass: String = "",
    val extraJars: List<String> = emptyList(),
    val mcVersion: String = "",
    val forgeVersion: String = "",
    val gameJvmArgs: List<String> = emptyList(),
    val gameArgs: List<String> = emptyList(),
    val isLegacy: Boolean = false,
    val requiresProcessors: Boolean = false,
    // 🆕 빌더(:forgebuilder)가 ProcessorLauncher 를 띄울 때 쓸 전용 classpath.
    //   = install_profile.json processor 라이브러리 + ProcessorLauncher.jar.
    //   게임 cp(extraJars)와 분리해, 게임 JVM 은 ForgeBootstrap 으로 직행하게 한다.
    val processorClasspath: List<String> = emptyList(),
    // 🆕 빌더 진입점(보통 ProcessorLauncher FQN). requiresProcessors=true 일 때만 의미.
    val processorMainClass: String = "",
    val error: String? = null
)

/**
 * Forge installer.jar 를 직접 파싱해서 라이브러리와 launch profile 을 추출한다.
 *
 *  - pre 1.13      : install_profile.json 안에 versionInfo 가 통째로 박혀있는 구버전 포맷.
 *                    universal jar 직접 다운로드해서 classpath 에 얹으면 끝.
 *  - 1.13+         : install_profile.json + 별도 version.json + maven/ 디렉토리 + processors.
 *                    여기서는 라이브러리/maven 까지만 처리. processors 는 후속 작업.
 *
 * 결과의 [ForgeInstallResult.requiresProcessors] 가 true 면 실제 부팅 단계에서
 * 클라이언트 jar 가 패치되어야 함을 호출 측에 알린다.
 */
class ForgeInstaller(
    private val instanceDir: File,
    private val onProgress: (phase: String, current: Int, total: Int) -> Unit = { _, _, _ -> }
) {
    private val client = OkHttpClient()

    fun install(context : Context, mcVersion: String, forgeVersion: String, isNeoForge: Boolean = false): ForgeInstallResult {
        val fullVersion = "$mcVersion-$forgeVersion"
        val installerUrl = if (isNeoForge) {
            // NeoForge 1.20.1 fork: net.neoforged:forge:1.20.1-47.X
            // 1.20.2+ : net.neoforged:neoforge:X.Y.Z
            "https://maven.neoforged.net/releases/net/neoforged/neoforge/$forgeVersion/neoforge-$forgeVersion-installer.jar"
        } else {
            "https://maven.minecraftforge.net/net/minecraftforge/forge/$fullVersion/forge-$fullVersion-installer.jar"
        }

        // ── 1) installer jar 다운로드 ─────────────────────────────
        val tmpDir = File(instanceDir, "tmp").also { it.mkdirs() }
        val installerFile = File(tmpDir, "forge-$fullVersion-installer.jar")
        if (!installerFile.exists() || installerFile.length() == 0L) {
            onProgress("Forge installer 다운로드 중...", 0, 0)
            try {
                val req = Request.Builder().url(installerUrl).build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return ForgeInstallResult(success = false,
                            error = "Installer 다운로드 실패 HTTP ${resp.code} ($installerUrl)")
                    }
                    resp.body?.byteStream()?.use { input ->
                        FileOutputStream(installerFile).use { input.copyTo(it) }
                    }
                }
            } catch (e: Exception) {
                return ForgeInstallResult(success = false, error = "Installer 예외: ${e.message}")
            }
        }

        // ── 2) install_profile.json 파싱 + version 객체 추출 ──────
        val parsed = try {
            ZipFile(installerFile).use { zip ->
                val ipEntry = zip.getEntry("install_profile.json")
                    ?: return ForgeInstallResult(success = false, error = "install_profile.json 없음")
                val ipJson = zip.getInputStream(ipEntry).bufferedReader().readText()
                val ipObj = JsonParser.parseString(ipJson).asJsonObject

                // (a) 구버전: versionInfo 가 install_profile.json 안에 통째로
                // (b) 신버전: 별도 version.json 이 zip 루트에 따로
                val versionObj = ipObj["versionInfo"]?.asJsonObject ?: run {
                    val vEntry = zip.getEntry("version.json")
                        ?: return ForgeInstallResult(success = false,
                            error = "versionInfo / version.json 모두 없음")
                    JsonParser.parseString(zip.getInputStream(vEntry).bufferedReader().readText()).asJsonObject
                }

                val mainClass = versionObj["mainClass"]?.asString
                    ?: return ForgeInstallResult(success = false, error = "mainClass 누락")

                // versionInfo 가 있으면 = 구버전 = launchwrapper = legacy
                val isLegacy = ipObj.has("versionInfo")
                        || mainClass.contains("launchwrapper", ignoreCase = true)

                // arguments / minecraftArguments 양쪽 다 흡수
                val jvmArgs = mutableListOf<String>()
                val gameArgs = mutableListOf<String>()
                versionObj["arguments"]?.asJsonObject?.let { args ->
                    args["jvm"]?.asJsonArray?.forEach { e -> e.takeStringArg(jvmArgs) }
                    args["game"]?.asJsonArray?.forEach { e -> e.takeStringArg(gameArgs) }
                }
                versionObj["minecraftArguments"]?.asString
                    ?.split(" ")?.filter { it.isNotBlank() }
                    ?.forEach { gameArgs.add(it) }

                val versionLibs = versionObj["libraries"]?.asJsonArray
                    ?.mapNotNull { parseLib(it.asJsonObject) }
                    ?: emptyList()
                val installLibs = ipObj["libraries"]?.asJsonArray
                    ?.mapNotNull { parseLib(it.asJsonObject) }
                    ?: emptyList()

                val requiresProcessors = ipObj["processors"]?.asJsonArray
                    ?.takeIf { it.size() > 0 } != null

                Triple(
                    Profile(
                        mainClass = mainClass,
                        isLegacy = isLegacy,
                        requiresProcessors = requiresProcessors,
                        jvmArgs = jvmArgs,
                        gameArgs = gameArgs,
                        libs = versionLibs,            // ★ version.json 만
                        processorLibs = installLibs    // ★ install_profile.json 은 별도
                    ),
                    installerFile,
                    Unit
                )
            }
        } catch (e: Exception) {
            Log.e("FLAME_LAUNCHER", "installer 파싱 실패", e)
            return ForgeInstallResult(success = false, error = "installer 파싱: ${e.message}")
        }

        val profile = parsed.first
        val librariesDir = File(instanceDir, "libraries")

        // ── 3) installer.jar 안의 maven/ 디렉토리 통째로 추출 (modern) ──
        extractBundledMaven(installerFile, librariesDir)

        // ── 4) profile 의 라이브러리들 다운로드 ──────────────────────
        val jarList = mutableListOf<String>()

// 1) version.json libs — 디스크에 받고 classpath 에도 추가
        val totalGame = profile.libs.size
        profile.libs.forEachIndexed { idx, lib ->
            onProgress("Forge libs (game) ${idx + 1}/$totalGame: ${lib.name}", idx + 1, totalGame)
            downloadLibrary(lib, librariesDir)?.let { jarList.add(it.absolutePath) }
        }

// 2) install_profile.json libs — 디스크엔 받되 classpath 에는 추가하지 않는다.
//    ProcessorLauncher 가 자체 URLClassLoader 로 띄울 때만 필요.
//    여기 jar 들 (ForgeAutoRenamingTool, BinaryPatcher, jarsplitter, installertools 등)
//    은 ASM/Netty 등을 통합 포함한 fat jar 가 많아서 게임 classpath 에 두면
//    BootstrapLauncher 의 자동 모듈 등록 단계에서 split package 충돌이 줄줄이 발생.
        val totalProc = profile.processorLibs.size
        profile.processorLibs.forEachIndexed { idx, lib ->
            onProgress("Forge libs (processor) ${idx + 1}/$totalProc: ${lib.name}", idx + 1, totalProc)
            downloadLibrary(lib, librariesDir)   // 반환값 무시 = jarList 에 안 더함
        }

        Log.d("FLAME_LAUNCHER",
            "📦 Forge classpath = ${jarList.size}개 (processor-only=${profile.processorLibs.size}개는 디스크만)")

        // ── 5) legacy forge 면 universal jar 직접 다운로드 후 classpath 앞쪽에 ──
        if (profile.isLegacy) {
            downloadForgeUniversal(mcVersion, forgeVersion, librariesDir, isNeoForge)
                ?.let { jarList.add(0, it.absolutePath) }
        }

        // 게임 진입점은 항상 실제 게임 메인(ForgeBootstrap 등). 가로채지 않는다.
        val gameMainClass = profile.mainClass
        // 빌더 전용 cp/진입점 (requiresProcessors 일 때만 채움)
        val processorCp = mutableListOf<String>()
        var processorMain = ""

        if (profile.requiresProcessors) {
            try {
                onProgress("Mojang mappings 사전 다운로드 중...", 0, 0)
                val resolvedMojmapsSha = preDownloadMojmaps(installerFile, librariesDir, mcVersion)

                onProgress("Processor 메타 준비 중...", 0, 0)
                extractInstallerDataDir(installerFile, instanceDir)
                serializeProcessors(
                    installerFile = installerFile,
                    instanceDir = instanceDir,
                    librariesDir = librariesDir,
                    mcVersion = mcVersion,
                    realMainClass = profile.mainClass,
                    mojmapsShaOverride = resolvedMojmapsSha
                )
                val launcherJar = copyProcessorLauncherJar(context = context, librariesDir = librariesDir)

                // 🆕 빌더 cp = 게임 cp 사본(version.json libs 포함) + processor 라이브러리(디스크에 이미 받아둠) + ProcessorLauncher.jar.
                //   ProcessorLauncher 는 forge-install-data.properties 의 processor.N.classpath 를
                //   자체 ChildFirstClassLoader 로 다시 구성하므로, 여기 빌더 cp 에는 "ProcessorLauncher 자신을
                //   로드할 수 있는 최소 cp + 진입점"만 있으면 된다. 안전하게 게임 cp 전체 + launcher jar 로 둔다.
                processorCp.addAll(jarList)               // 게임 cp 사본
                processorCp.add(0, launcherJar.absolutePath)
                processorMain = "kr.co.donghyun.flamelauncher.forge.ProcessorLauncher"

                Log.i("FLAME_LAUNCHER", "✅ Forge processors 메타 준비 완료 (빌더 cp=${processorCp.size}개, 게임 진입점=$gameMainClass)")
            } catch (e: Exception) {
                Log.e("FLAME_LAUNCHER", "Processor 메타 준비 실패", e)
                return ForgeInstallResult(success = false,
                    error = "Processor 메타 준비 실패: ${e.message}")
            }
        }

        installerFile.delete()

        return ForgeInstallResult(
            success = true,
            mainClass = gameMainClass,            // ← 게임은 항상 ForgeBootstrap 직행
            extraJars = jarList,                  // ← ProcessorLauncher.jar 미포함(깨끗한 게임 cp)
            mcVersion = mcVersion,
            forgeVersion = forgeVersion,
            processorClasspath = processorCp,     // 🆕 빌더 전용
            processorMainClass = processorMain,   // 🆕 빌더 전용
            gameJvmArgs = profile.jvmArgs,
            gameArgs = profile.gameArgs,
            isLegacy = profile.isLegacy,
            requiresProcessors = profile.requiresProcessors
            // ... (나머지 기존 필드 그대로: gameJvmArgs, gameArgs, isLegacy, requiresProcessors=profile.requiresProcessors)
        )
    }

    // ─── helpers ───────────────────────────────────────────────

    private data class Profile(
        val mainClass: String,
        val isLegacy: Boolean,
        val requiresProcessors: Boolean,
        val jvmArgs: List<String>,
        val gameArgs: List<String>,
        val libs: List<LibSpec>,           // version.json libraries — classpath 들어감
        val processorLibs: List<LibSpec>   // install_profile.json libraries — 다운만, classpath 제외
    )

    private data class LibSpec(
        val name: String,
        val url: String?,
        val path: String?
    )

    private fun parseLib(obj: JsonObject): LibSpec? {
        val name = obj["name"]?.asString ?: return null
        val artifact = obj["downloads"]?.asJsonObject?.get("artifact")?.asJsonObject
        val explicitUrl = artifact?.get("url")?.asString ?: obj["url"]?.asString
        val explicitPath = artifact?.get("path")?.asString
        return LibSpec(name, explicitUrl, explicitPath)
    }

    private fun extractBundledMaven(installerJar: File, librariesDir: File) {
        try {
            ZipFile(installerJar).use { zip ->
                zip.entries().asSequence()
                    .filter { it.name.startsWith("maven/") && !it.isDirectory }
                    .forEach { entry ->
                        val rel = entry.name.removePrefix("maven/")
                        val dest = File(librariesDir, rel)
                        if (!dest.exists() || dest.length() == 0L) {
                            dest.parentFile?.mkdirs()
                            zip.getInputStream(entry).use { input ->
                                FileOutputStream(dest).use { input.copyTo(it) }
                            }
                        }
                    }
            }
        } catch (e: Exception) {
            Log.w("FLAME_LAUNCHER", "bundled maven 추출 실패: ${e.message}")
        }
    }

    /**
     * install_profile.json 의 data.MOJMAPS / MOJMAPS_SHA 를 보고
     * Mojang client_mappings.txt 를 Android 쪽 OkHttp 로 미리 받아 둔다.
     *
     * 임베디드 OpenJDK 의 TLS 가 Mojang CDN 과 핸드셰이크 실패하는 케이스가 있어서,
     * 미리 받아둠으로써 ProcessorLauncher 의 outputsValid 체크에 걸려 DOWNLOAD_MOJMAPS 가 skip 된다.
     */
    /**
     * @return 디스크에 확보된 client_mappings 의 실제 SHA-1(소문자 hex). 확보 실패 시 null.
     *   이 값이 install_profile.json 의 stale 한 MOJMAPS_SHA 와 다르면, 호출측이
     *   serializeProcessors 에 넘겨 processor 인자의 {MOJMAPS_SHA} 를 이 값으로 덮어쓴다.
     *   (그러지 않으면 installertools 의 DOWNLOAD_MOJMAPS 가 stale SHA 로 입력 검증하다 throw → 빌드 실패)
     */
    private fun preDownloadMojmaps(
        installerFile: File,
        librariesDir: File,
        mcVersion: String,
    ): String? {
        val ipObj = try {
            ZipFile(installerFile).use { zip ->
                val entry = zip.getEntry("install_profile.json")
                    ?: return@use null
                JsonParser.parseString(zip.getInputStream(entry).bufferedReader().readText())
                    .asJsonObject
            }
        } catch (e: Exception) {
            Log.w("FLAME_LAUNCHER", "🗺 install_profile.json 재파싱 실패: ${e.message}")
            null
        } ?: return null

        val data = ipObj["data"]?.asJsonObject ?: return null
        val mojmapsRaw = data["MOJMAPS"]?.asJsonObject?.get("client")?.asString ?: run {
            Log.d("FLAME_LAUNCHER", "🗺 MOJMAPS data 항목 없음")
            return null
        }
        // ★ install_profile 의 expected SHA — 작은따옴표/큰따옴표 둘 다 제거
        val installProfileExpectedSha = data["MOJMAPS_SHA"]?.asJsonObject
            ?.get("client")?.asString
            ?.trim('\'', '"')

        if (!mojmapsRaw.startsWith("[") || !mojmapsRaw.endsWith("]")) {
            Log.w("FLAME_LAUNCHER", "🗺 MOJMAPS 좌표 형식 이상: $mojmapsRaw")
            return null
        }
        val coord = mojmapsRaw.substring(1, mojmapsRaw.length - 1)
        val target = File(mavenCoordToPath(coord, librariesDir))

        // Mojang version.json 에서 client_mappings 정보 가져오기
        val versionEntry = try {
            VersionRepository()
                .fetchVersionList()
                .firstOrNull { it.id == mcVersion }
        } catch (_: Exception) { null } ?: run {
            Log.w("FLAME_LAUNCHER", "🗺 version manifest 에서 $mcVersion 못 찾음"); return null
        }

        val versionJson = try {
            client.newCall(Request.Builder().url(versionEntry.url).build())
                .execute().use { it.body?.string() }
        } catch (e: Exception) {
            Log.e("FLAME_LAUNCHER", "🗺 version.json 실패: ${e.message}"); return null
        } ?: return null

        val cm = try {
            JsonParser.parseString(versionJson).asJsonObject["downloads"]?.asJsonObject
                ?.get("client_mappings")?.asJsonObject
        } catch (_: Exception) { null }

        if (cm == null) {
            Log.w("FLAME_LAUNCHER", "🗺 client_mappings 없음 (구버전?)")
            return null
        }
        val url = cm["url"]?.asString ?: return null
        val mojangSha = cm["sha1"]?.asString?.trim('\'', '"')
        val mojangSize = cm["size"]?.asLong ?: -1L

        Log.i("FLAME_LAUNCHER",
            "🗺 MOJMAPS expected SHA — install_profile=$installProfileExpectedSha  " +
                    "mojang_version_json=$mojangSha")

        val staleSha = installProfileExpectedSha != null && mojangSha != null
                && !installProfileExpectedSha.equals(mojangSha, ignoreCase = true)
        if (staleSha) {
            Log.w("FLAME_LAUNCHER",
                "🗺 ⚠️ install_profile 의 MOJMAPS_SHA 가 Mojang 현재 client_mappings 와 다름. " +
                        "Forge installer 가 stale 한 SHA 를 박아둔 상황 — Mojang 측이 mappings 재배포한 듯. " +
                        "→ serializeProcessors 에서 processor 인자의 {MOJMAPS_SHA} 를 Mojang 현재값으로 덮어쓴다.")
        }

        // 이미 받아져 있으면 SHA 비교 — Mojang 현재 SHA 와 일치하면 그대로 재사용.
        if (target.exists() && target.length() > 0) {
            val current = sha1Hex(target)
            Log.i("FLAME_LAUNCHER",
                "🗺 현재 디스크 mojmaps: size=${target.length()} (expected=$mojangSize) sha=$current")
            if (mojangSha?.equals(current, ignoreCase = true) == true) {
                Log.i("FLAME_LAUNCHER", "🗺 ✅ mojmaps(=Mojang 현재 SHA) OK — pre-download skip")
                return current.lowercase()
            }
            // Mojang 현재값과 다르면(=stale 한 install_profile SHA 로 받아둔 구버전 등) 재다운로드.
            Log.w("FLAME_LAUNCHER", "🗺 디스크 mojmaps 가 Mojang 현재 SHA 와 불일치 → 재다운로드")
        }

        Log.i("FLAME_LAUNCHER", "🗺 mojmaps pre-download: $url → ${target.absolutePath}")
        target.parentFile?.mkdirs()
        return try {
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.e("FLAME_LAUNCHER", "🗺 HTTP ${resp.code}"); return null
                }
                resp.body?.byteStream()?.use { input ->
                    FileOutputStream(target).use { input.copyTo(it) }
                }
            }
            val actual = sha1Hex(target)
            val actualSize = target.length()
            Log.i("FLAME_LAUNCHER",
                "🗺 다운로드 완료: size=$actualSize sha=$actual")

            when {
                mojangSha?.equals(actual, ignoreCase = true) == true -> {
                    Log.i("FLAME_LAUNCHER", "🗺 ✅ Mojang version.json SHA 와 일치")
                }
                installProfileExpectedSha?.equals(actual, ignoreCase = true) == true -> {
                    Log.i("FLAME_LAUNCHER", "🗺 ✅ install_profile SHA 일치")
                }
                else -> {
                    Log.e("FLAME_LAUNCHER",
                        "🗺 ❌ 양쪽 SHA 모두 불일치 — 네트워크 손상 또는 redirect 문제")
                }
            }
            // 실제로 디스크에 확보된 파일의 SHA 를 반환(= processor 검증에 써야 할 진짜 값).
            actual.lowercase()
        } catch (e: Exception) {
            Log.e("FLAME_LAUNCHER", "🗺 OkHttp 다운로드 예외: ${e.message}", e)
            null
        }
    }

    private fun sha1Hex(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        f.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun downloadLibrary(lib: LibSpec, librariesDir: File): File? {
        val path = lib.path ?: mavenNameToPath(lib.name)
        val destFile = File(librariesDir, path)
        if (destFile.exists() && destFile.length() > 0) return destFile
        destFile.parentFile?.mkdirs()

        // URL 후보 순서: 명시 URL → forge maven → mojang libs → neoforged → fabric
        val candidates = buildList {
            lib.url?.let { u ->
                if (u.endsWith(".jar")) add(u)
                else add(if (u.endsWith("/")) "$u$path" else "$u/$path")
            }
            add("https://maven.minecraftforge.net/$path")
            add("https://libraries.minecraft.net/$path")
            add("https://maven.neoforged.net/releases/$path")
            add("https://maven.fabricmc.net/$path")
        }
        for (url in candidates) {
            try {
                val req = Request.Builder().url(url).build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        resp.body?.byteStream()?.use { input ->
                            FileOutputStream(destFile).use { input.copyTo(it) }
                        }
                        return destFile
                    }
                }
            } catch (_: Exception) {}
        }
        Log.w("FLAME_LAUNCHER", "라이브러리 다운로드 실패: ${lib.name}")
        return null
    }


    private fun downloadForgeUniversal(
        mcVersion: String, forgeVersion: String,
        librariesDir: File, isNeoForge: Boolean
    ): File? {
        val fullVersion = "$mcVersion-$forgeVersion"
        val (path, urls) = if (isNeoForge) {
            "net/neoforged/neoforge/$forgeVersion/neoforge-$forgeVersion-universal.jar" to listOf(
                "https://maven.neoforged.net/releases/net/neoforged/neoforge/$forgeVersion/neoforge-$forgeVersion-universal.jar"
            )
        } else {
            "net/minecraftforge/forge/$fullVersion/forge-$fullVersion-universal.jar" to listOf(
                "https://maven.minecraftforge.net/net/minecraftforge/forge/$fullVersion/forge-$fullVersion-universal.jar",
                "https://maven.minecraftforge.net/net/minecraftforge/forge/$fullVersion/forge-$fullVersion.jar"
            )
        }
        val destFile = File(librariesDir, path)
        if (destFile.exists() && destFile.length() > 0) return destFile
        destFile.parentFile?.mkdirs()
        for (url in urls) {
            try {
                val req = Request.Builder().url(url).build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        resp.body?.byteStream()?.use { input ->
                            FileOutputStream(destFile).use { input.copyTo(it) }
                        }
                        return destFile
                    }
                }
            } catch (_: Exception) {}
        }
        Log.w("FLAME_LAUNCHER", "Forge universal jar 다운로드 실패: $fullVersion")
        return null
    }

    private fun mavenNameToPath(name: String): String {
        val parts = name.split(":")
        if (parts.size < 3) return name
        val group = parts[0].replace('.', '/')
        val artifact = parts[1]
        val versionFull = parts[2]
        val classifier = if (parts.size > 3) "-${parts[3]}" else ""
        return "$group/$artifact/$versionFull/$artifact-$versionFull$classifier.jar"
    }

    private fun JsonElement.takeStringArg(out: MutableList<String>) {
        if (isJsonPrimitive && asJsonPrimitive.isString) out.add(asString)
    }
    // ─── 신규 helpers ──────────────────────────────────────────

    /** installer.jar 의 `data/` 디렉토리를 instance/data/ 로 추출 (client.lzma 등) */
    private fun extractInstallerDataDir(installerJar: File, instanceDir: File) {
        ZipFile(installerJar).use { zip ->
            zip.entries().asSequence()
                .filter { it.name.startsWith("data/") && !it.isDirectory }
                .forEach { entry ->
                    val dest = File(instanceDir, entry.name)  // 그대로 data/X 로
                    if (!dest.exists() || dest.length() == 0L) {
                        dest.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            FileOutputStream(dest).use { input.copyTo(it) }
                        }
                    }
                }
        }
    }

    /** assets/forge-runtime/processor-launcher.jar → instance/libraries/...  복사 */
    private fun copyProcessorLauncherJar(context : Context, librariesDir: File): File {
        val dest = File(librariesDir, "kr/co/donghyun/flamelauncher/processor-launcher/1.0/processor-launcher-1.0.jar")
        dest.parentFile?.mkdirs()
        if (!dest.exists() || dest.length() == 0L) {
            context.assets.open("forge-runtime/processor-launcher.jar").use { input ->
                FileOutputStream(dest).use { input.copyTo(it) }
            }
        }
        return dest
    }

    /**
     * install_profile.json 의 processors 를 모두 해석해 instance/forge-install-data.properties 로 직렬화.
     */
    private fun serializeProcessors(
        installerFile: File,
        instanceDir: File,
        librariesDir: File,
        mcVersion: String,
        realMainClass: String,
        mojmapsShaOverride: String? = null
    ) {
        val ipObj = ZipFile(installerFile).use { zip ->
            val ipEntry = zip.getEntry("install_profile.json")!!
            JsonParser.parseString(zip.getInputStream(ipEntry).bufferedReader().readText()).asJsonObject
        }

        // 1) data 섹션 → varMap
        val varMap = mutableMapOf(
            "SIDE"          to "client",
            "MINECRAFT_JAR" to File(instanceDir, "versions/$mcVersion/$mcVersion.jar").absolutePath,
            "ROOT"          to instanceDir.absolutePath,
            "INSTALLER"     to installerFile.absolutePath
        )
        ipObj["data"]?.asJsonObject?.entrySet()?.forEach { (key, value) ->
            val raw = value.asJsonObject["client"]?.asString ?: return@forEach
            varMap[key] = resolveValue(raw, librariesDir, installerFile, instanceDir, varMap)
        }

        // ★ stale SHA 교정: install_profile.json 의 MOJMAPS_SHA 는 Forge 빌드 시점값이라
        //   Mojang 이 client_mappings 를 재배포하면 현재 파일과 어긋난다. 이 stale 값이
        //   processor 인자({MOJMAPS_SHA})에 그대로 박히면 installertools 의 DOWNLOAD_MOJMAPS 가
        //   '디스크의 (올바른) mappings vs stale expected SHA' 를 비교하다 실패 → patched client jar
        //   미생성 → ForgeBootstrap 이 Minecraft.class 를 못 찾아 무한 로딩.
        //   preDownloadMojmaps 가 확보한 실제 SHA 로 덮어써, processor 가 올바른 값으로 검증하게 한다.
        if (mojmapsShaOverride != null) {
            val before = varMap["MOJMAPS_SHA"]
            if (before == null || !before.equals(mojmapsShaOverride, ignoreCase = true)) {
                varMap["MOJMAPS_SHA"] = mojmapsShaOverride
                Log.i("FLAME_LAUNCHER",
                    "🗺 processor 용 MOJMAPS_SHA 교정: $before → $mojmapsShaOverride " +
                            "(stale install_profile SHA 무력화)")
            }
        }

        // 2) processors 직렬화
        val DELIM = "\u0001"
        val props = StringBuilder()
        props.append("realMainClass=").append(realMainClass).append('\n')

        val procArray = ipObj["processors"]?.asJsonArray ?: JsonArray()
        var outIdx = 0
        for (p in procArray) {
            val po = p.asJsonObject
            val sides = po["sides"]?.asJsonArray?.map { it.asString }
            if (sides != null && !sides.contains("client")) continue

            val jarPath = mavenCoordToPath(po["jar"].asString, librariesDir)
            val cpList = po["classpath"].asJsonArray.map { mavenCoordToPath(it.asString, librariesDir) } +
                    listOf(jarPath)
            val argsList = po["args"].asJsonArray.map { arg ->
                resolveValue(arg.asString, librariesDir, installerFile, instanceDir, varMap)
            }
            val outputs = po["outputs"]?.asJsonObject
            val outKeys = outputs?.entrySet()?.map {
                resolveValue(it.key, librariesDir, installerFile, instanceDir, varMap)
            } ?: emptyList()
            val outVals = outputs?.entrySet()?.map { (_, v) ->
                resolveValue(v.asString, librariesDir, installerFile, instanceDir, varMap)
            } ?: emptyList()

            val prefix = "processor.$outIdx."
            props.append(prefix).append("jar=").append(escape(jarPath)).append('\n')
            props.append(prefix).append("classpath=").append(escape(cpList.joinToString(DELIM))).append('\n')
            props.append(prefix).append("args=").append(escape(argsList.joinToString(DELIM))).append('\n')
            if (outKeys.isNotEmpty()) {
                props.append(prefix).append("outputs.keys=").append(escape(outKeys.joinToString(DELIM))).append('\n')
                props.append(prefix).append("outputs.values=").append(escape(outVals.joinToString(DELIM))).append('\n')
            }
            outIdx++
        }
        props.insert(props.indexOf('\n') + 1, "processorCount=$outIdx\n")

        File(instanceDir, "forge-install-data.properties").writeText(props.toString(), Charsets.UTF_8)
    }

    /** properties 포맷에서 = / : / 공백 / 백슬래시는 이스케이프 */
    private fun escape(s: String): String = s
        .replace("\\", "\\\\")
        .replace("=", "\\=")
        .replace(":", "\\:")
        .replace("\n", "\\n")

    /**
     * install_profile.json 의 값 한 개를 해석.
     *
     * 분기는 반드시 **원본 s 의 prefix** 로 결정한다. 변수 치환 결과가 우연히
     * '/' 로 시작해도 그건 디스크 절대경로지 installer JAR 내부 경로가 아니다.
     * (이전 버그: `{MAPPINGS}` → 치환 후 `/storage/.../mappings.txt` 가 되어
     *  `/` prefix 라고 오판 → installer 안에서 디스크 경로 찾다가 실패)
     *
     *  - `[g:a:v[:c[@ext]]]` → libraries 상의 실제 파일 경로
     *  - `/data/foo.lzma`     → installer.jar 안의 항목을 instance/data/foo.lzma 로 추출
     *  - `'리터럴'`           → 따옴표 제거
     *  - 그 외                → 변수 치환만
     */
    private fun resolveValue(
        s: String,
        librariesDir: File,
        installerFile: File,
        instanceDir: File,
        varMap: Map<String, String>
    ): String = when {
        s.startsWith("[") && s.endsWith("]") -> {
            val expanded = expandVars(s, varMap)
            mavenCoordToPath(expanded.substring(1, expanded.length - 1), librariesDir)
        }
        s.startsWith("/") -> {
            // installer JAR 내부 경로. 변수 치환을 거쳐도 여전히 내부 경로 의미.
            val expanded = expandVars(s, varMap)
            extractFromInstaller(installerFile, expanded.removePrefix("/"), instanceDir)
        }
        s.startsWith("'") && s.endsWith("'") ->
            expandVars(s.substring(1, s.length - 1), varMap)
        else ->
            expandVars(s, varMap)
    }

    /** {VAR_NAME} 토큰을 varMap 값으로 치환. ICU regex 호환을 위해 양쪽 중괄호 모두 escape. */
    private fun expandVars(s: String, varMap: Map<String, String>): String =
        Regex("\\{([A-Z_][A-Z0-9_]*)\\}").replace(s) { m ->
            varMap[m.groupValues[1]] ?: m.value
        }

    private fun mavenCoordToPath(coord: String, librariesDir: File): String {
        // "g:a:v" / "g:a:v:c" / "g:a:v:c@ext" / "g:a:v@ext"
        val atIdx = coord.indexOf('@')
        val (base, ext) = if (atIdx >= 0)
            coord.substring(0, atIdx) to coord.substring(atIdx + 1)
        else coord to "jar"
        val parts = base.split(":")
        require(parts.size >= 3) { "invalid maven coord: $coord" }
        val group = parts[0].replace('.', '/')
        val artifact = parts[1]
        val version = parts[2]
        val classifier = if (parts.size > 3) "-${parts[3]}" else ""
        return File(librariesDir, "$group/$artifact/$version/$artifact-$version$classifier.$ext").absolutePath
    }

    private fun extractFromInstaller(installerFile: File, internalPath: String, instanceDir: File): String {
        val dest = File(instanceDir, "data/${File(internalPath).name}")
        if (dest.exists() && dest.length() > 0) return dest.absolutePath
        dest.parentFile?.mkdirs()
        ZipFile(installerFile).use { zip ->
            val entry = zip.getEntry(internalPath)
                ?: throw IllegalStateException("Installer 안에 $internalPath 없음")
            zip.getInputStream(entry).use { input ->
                FileOutputStream(dest).use { input.copyTo(it) }
            }
        }
        return dest.absolutePath
    }
}