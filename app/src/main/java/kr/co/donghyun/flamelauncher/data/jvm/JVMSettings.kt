package kr.co.donghyun.flamelauncher.data.jvm

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonParser
import kr.co.donghyun.flamelauncher.data.renderer.RendererManager
import java.io.File

data class JvmSettings(
    val maxHeapMb: Int = 4096,
    val minHeapMb: Int = 512,
    val useG1GC: Boolean = true,
    val gcPauseMillis: Int = 100,
    val parallelRefProc: Boolean = true,
    val heapRegionSizeMb: Int = 32,
    val disableClouds: Boolean = true,
    val extraJvmArgs: String = "",   // 줄바꿈 구분 커스텀 인자
    val mouseSensitivity: Float = 1.5f,
    val renderDistance: Int = 4,
    val graphicsMode: Int = 0,       // 0=fast, 1=fancy, 2=fabulous
    val cacheDirPath: String = "",
    val unlockFps: Boolean = true,

    // ── 디스플레이 ────────────────────────────────────────────────
    // 전체 화면: 시스템 바(상태/내비)를 숨기고 화면을 꽉 채운다. 기본 켜짐, 끌 수 있음.
    //            실제 적용은 게임 Activity 에서 한다(아래 applyFullscreen 참고).
    val fullscreen: Boolean = true,
    // 렌더 해상도 배율(%). 100 = 네이티브(화면 그대로). 낮출수록 프레임버퍼가 작아져
    //            FPS 가 오르고 화면은 약간 흐려진다. (ZalithLauncher2 의 Resolution 과 동일 개념)
    val resolutionScalePercent: Int = 100,
) {

    /** 0.25 ~ 1.00 범위로 보정된 실제 배율. 잘못된 값이 들어와도 범위를 벗어나지 않게 한다. */
    val resolutionScale: Float
        get() = resolutionScalePercent
            .coerceIn(RES_SCALE_MIN_PERCENT, RES_SCALE_MAX_PERCENT) / 100f

    /**
     * 화면 픽셀(width × height)에 배율을 적용한 실제 렌더 해상도.
     * GL/영상 백엔드 안정성을 위해 짝수로 맞추고 최소 2px 을 보장한다.
     * 모던(1.13+) 버전에서 surface/브리지 윈도우 크기를 정할 때 사용한다.
     */
    fun scaledResolution(width: Int, height: Int): Pair<Int, Int> {
        val s = resolutionScale
        val w = ((width * s).toInt() and 1.inv()).coerceAtLeast(2)
        val h = ((height * s).toInt() and 1.inv()).coerceAtLeast(2)
        return w to h
    }

    fun toJvmArgArray(
        context: Context,
        mcDir : File,
        userDir: String,
        classPath: String,
        libraryPath: String,
        mainClass: String,
        versionId : String,
        // 호출자(MinecraftActivity.resolveRendererForVersion)가 이미 해석한 렌더러 id.
        // null 이면 과거처럼 RendererManager.load 로 폴백하지만, 그 경우 인스턴스별
        // rendererId(InstanceMeta) 를 무시해 MinecraftActivity 와 값이 어긋날 수 있으므로
        // 가능하면 항상 넘긴다. (이 불일치가 opengl.libname 중복/충돌의 원인이었다)
        rendererId: String? = null
    ): Array<String> {
        val args = mutableListOf(
            "-Xmx${maxHeapMb}M",
            "-Xms${minHeapMb}M",
            "-XX:+UnlockExperimentalVMOptions",
            "-Djna.nosys=true",
            "-Doshi.os=android",
            "-Dio.netty.native.workdir=${cacheDirPath}",
            "-Djna.tmpdir=${cacheDirPath}",
        )
        if (useG1GC) {
            args += listOf(
                "-XX:+UseG1GC",
                "-XX:MaxGCPauseMillis=$gcPauseMillis",
                if (parallelRefProc) "-XX:+ParallelRefProcEnabled" else "-XX:-ParallelRefProcEnabled",
                "-XX:G1NewSizePercent=20",
                "-XX:G1ReservePercent=20",
                "-XX:G1HeapRegionSize=${heapRegionSizeMb}m",
            )
        }

        // ── 프레임 일정성 개선용 JVM 인자 ───────────────────────
        // FPS cap 해제 자체는 options.txt 가 하지만, 이 옵션들은 GC pause 가
        // 프레임 사이에 끼어드는 걸 줄여서 unlocked FPS 가 실제로 매끄럽게 나오게 해줌.
        if (unlockFps) {
            args += listOf(
                "-XX:+DisableExplicitGC",            // 모드가 System.gc() 호출해도 무시
                "-XX:+AlwaysPreTouch",               // heap 페이지 미리 다 터치 → 런타임 page fault 제거
                "-XX:+ParallelRefProcEnabled",
                "-XX:G1MixedGCCountTarget=4",
                "-XX:InitiatingHeapOccupancyPercent=15",
                "-XX:G1RSetUpdatingPauseTimePercent=5",
                "-XX:SurvivorRatio=32",
                "-XX:+PerfDisableSharedMem",         // /tmp/hsperfdata 접근으로 stall 방지
                "-XX:MaxTenuringThreshold=1",
            )
        }

        // 렌더러 결정: 호출자가 넘긴 rendererId 를 최우선으로 신뢰한다.
        // (MinecraftActivity.resolveRendererForVersion 이 InstanceMeta.rendererId →
        //  전역 기본 → MobileGlues 미설치 폴백 → pre-1.13 GL4ES 강제까지 모두 반영한 값)
        // 넘어오지 않은 경우에만 과거 동작(RendererManager.load)으로 폴백.
        val resolvedRendererId = rendererId ?: RendererManager.load(context).id

        // pre-1.13(1.12.x 이하) 레거시는 Zink(OSMesa)로 못 돌리므로 GL4ES 로 강제.
        //   rendererId 를 넘겨받았다면 이미 resolveRendererForVersion 에서 동일 처리가
        //   끝났지만, 폴백 경로(rendererId == null)를 위해 여기서도 한 번 더 보정한다.
        val legacyForceGl4es = isLegacyVersion(versionId)
        val effectiveRendererId = if (legacyForceGl4es &&
            resolvedRendererId != "gl4es" && resolvedRendererId != "gl4es_desktop")
            "gl4es" else resolvedRendererId

        args += listOf(
            "-Duser.dir=$userDir",
            "-Djava.class.path=$classPath",
            "-Djava.library.path=$libraryPath",
            // ⚠️ org.lwjgl.opengl.libname / opengles.libname 는 여기서 emit 하지 않는다.
            //    MinecraftActivity.startMinecraft 의 rendererLibArgs 가 단일 소스로 emit하며
            //    여기서 중복 emit 하면 JVM 이 "먼저 정의된 값"을 채택해 충돌한다.
            //    실제로 과거엔 여기 libOSMesa.so 가 앞쪽([25])에 박혀 MobileGlues 절대경로
            //    override([69])를 이겨버려, LWJGL 이 OSMesa 를 로드하고 GL 함수 포인터를
            //    못 잡아 프로세스가 죽었다.
            "-Dorg.lwjgl.librarypath=$libraryPath",
            "-Dping.main.class=$mainClass",
            "-Dorg.lwjgl.system.SharedLibraryExtractPath=$libraryPath",
            "-Dorg.lwjgl.system.SharedLibraryExtractDirectory=$libraryPath",
            "-Dorg.lwjgl.util.NoChecks=true",
            "-Dorg.lwjgl.util.Debug=true",
            "-Dorg.lwjgl.util.DebugLoader=true",
            "-Dfml.earlyprogresswindow=false",
            "-Dorg.lwjgl.opengl.Display.allowSoftwareOpenGL=true",
            "-Djava.io.tmpdir=${cacheDirPath}"
        )

        // ── Cacio (AWT 가상 백엔드) ──────────────────────────────────────────
        // FancyMenu, Dice, JourneyMap 등 일부 모드가 java.awt.* 를 호출하는데,
        // JRE 에 libawt_xawt.so(headful AWT)가 없어서 headless=true 여도 Toolkit.loadLibraries
        // 가 libawt_xawt.so 로드를 시도하다 UnsatisfiedLinkError 로 죽는다.
        // cacio 가 toolkit 을 가로채면 libawt_xawt.so 자체가 불필요해진다.
        //
        // ⚠️ JRE 버전별로 cacio 주입 방식이 완전히 다르다 (둘을 섞으면 안 됨):
        //   - JRE8 : cacio-androidnw-1.10 + -Xbootclasspath/p:(prepend) + net.java.openjdk.cacio.ctc.*
        //   - JRE9+: cacio 1.19.1(shared+tta) + -Xbootclasspath/a:(append)
        //            + -javaagent:cacio-agent.jar (premain 이 Unsafe 로 Toolkit 설치)
        //            + com.github.caciocavallosilano.cacio.ctc.* + -Djdk.module.addExports/addOpens 풀세트.
        //            (JRE9+ 에 -Xbootclasspath/p: 를 주면 HotSpot 이 JNI_CreateJavaVM 단계에서
        //             -6(JNI_EINVAL) 으로 JVM 생성을 거부한다. prepend 옵션이 제거됐기 때문.)
        val javaMajor = resolveJavaMajor(versionId)

        if (javaMajor <= 8) {
            // ── JRE8: 기존 검증된 방식 그대로 ──
            val cacioDir = "${context.filesDir}/caciocavallo"
            val cacioJars = listOf(
                "$cacioDir/ResConfHack.jar",
                "$cacioDir/cacio-androidnw-1.10-SNAPSHOT.jar",
                "$cacioDir/cacio-shared-1.10-SNAPSHOT.jar"
            ).joinToString(":")

            // 레거시: cacio 가상 화면 = 렌더 해상도. resolutionScale 적용해 FPS 조절.
            val dm = context.resources.displayMetrics
            val (cacioW, cacioH) = scaledResolution(dm.widthPixels, dm.heightPixels)

            args += "-Dcacio.managed.screensize=${cacioW}x${cacioH}"
            args += "-Xbootclasspath/p:$cacioJars"
            args += "-Djava.awt.headless=false"
            args += "-Dcacio.font.fontmanager=sun.awt.X11FontManager"
            args += "-Dcacio.font.fontscaler=sun.font.FreetypeFontScaler"
            args += "-Dswing.defaultlaf=javax.swing.plaf.metal.MetalLookAndFeel"
            args += "-Dawt.toolkit=net.java.openjdk.cacio.ctc.CTCToolkit"
            args += "-Djava.awt.graphicsenv=net.java.openjdk.cacio.ctc.CTCGraphicsEnvironment"
            args += "-Duser.home=${mcDir.parentFile.absolutePath}"
        } else {
            // ── JRE9+ (17/21/25): cacio17 정식 구성 ──
            // PojavLauncher/FCL 이 검증한 방식. -Xbootclasspath/p:(prepend) 대신
            //   1) -Xbootclasspath/a:(append) — JRE9+ 에서 살아있는 옵션이라 JNI -6 이 안 난다.
            //   2) -Djava.system.class.loader=...CTCPreloadClassLoader — agent 없이도 toolkit 을
            //      게임 클래스보다 먼저 가로채게 하는 핵심. (FancyMenu/Dice 가 java.awt.Color 를 불러도
            //      cacio 가 이미 toolkit 을 점유 → libawt_xawt.so 로드 시도 자체가 사라진다.)
            //   3) 신버전 클래스명 com.github.caciocavallosilano.cacio.ctc.*
            //   4) java.desktop 내부 패키지 개방용 --add-exports / --add-opens 풀세트.
            val cacio17Dir = "${context.filesDir}/caciocavallo17"
            // ZL2(main) 와 동일한 cacio 1.19.1 세트 + 전용 agent jar.
            //   ⚠️ agent 는 cacio-tta 와 별개의 파일(cacio-agent.jar)이며 매니페스트에
            //      Premain-Class(com.github.caciocavallosilano.cacio.agent.CTCJavaAgent) 를 갖는다.
            //      이 agent 는 premain 에서 sun.misc.Unsafe 로 Toolkit/GraphicsEnvironment 필드를
            //      직접 덮어써 CTCToolkit 을 설치한다(=privateLookupIn 을 쓰지 않음).
            //      그래서 system class loader 트릭이 필요 없고, initPhase3 타이밍 문제도 없다.
            val cacio17Jars = listOf(
                "$cacio17Dir/cacio-shared-1.19.1-SNAPSHOT.jar",
                "$cacio17Dir/cacio-tta-1.19.1-SNAPSHOT.jar"
            ).joinToString(":")
            val cacioAgentJar = "$cacio17Dir/cacio-agent.jar"

            // 모던: 렌더링은 GL 이 담당. cacio 가상 화면은 AWT 호환용이라 실제 픽셀이면 충분.
            val dm = context.resources.displayMetrics

            args += "-Djava.awt.headless=false"
            args += "-Dcacio.managed.screensize=${dm.widthPixels}x${dm.heightPixels}"
            args += "-Dcacio.font.fontmanager=sun.awt.X11FontManager"
            args += "-Dcacio.font.fontscaler=sun.font.FreetypeFontScaler"
            args += "-Dswing.defaultlaf=javax.swing.plaf.nimbus.NimbusLookAndFeel"
            args += "-Dawt.toolkit=com.github.caciocavallosilano.cacio.ctc.CTCToolkit"
            args += "-Djava.awt.graphicsenv=com.github.caciocavallosilano.cacio.ctc.CTCGraphicsEnvironment"
            // ❌ -Djava.system.class.loader=...CTCPreloadClassLoader 는 쓰지 않는다.
            //    그 방식은 initSystemClassLoader(initPhase3)에서 CTCPreloadClassLoader.<clinit>
            //    가 MethodHandles.privateLookupIn(java.lang.reflect) 를 호출하는데, 이 시점엔
            //    addOpens 가 아직 deep-reflection 을 허용할 만큼 안정화되지 않아
            //    "module java.base does not open java.lang.reflect" 로 죽는다.
            // ✅ 대신 agent 를 쓴다. premain 은 모듈/open 안정화 이후 실행되고,
            //    sun.misc.Unsafe 로 Toolkit 필드를 직접 덮어쓰므로 privateLookupIn 이 필요 없다.
            args += "-javaagent:$cacioAgentJar"
            args += "-Xbootclasspath/a:$cacio17Jars"

            // java.desktop / java.base 내부 패키지 개방 (JRE9+ 모듈 캡슐화 우회).
            //   ZL2 와 동일하게 한-토큰 "--add-exports=A/B=ALL-UNNAMED" 형식으로 emit 한다.
            //   JNI_CreateJavaVM 경로에서도 한-토큰 '=' 형식은 정상 인식된다(JDK-8320860).
            //   normalizeJvmArgsForJni 는 이미 '=' 가 붙은 한-토큰을 그대로 통과시킨다.
            args += "--add-exports=java.desktop/java.awt=ALL-UNNAMED"
            args += "--add-exports=java.desktop/java.awt.peer=ALL-UNNAMED"
            args += "--add-exports=java.desktop/sun.awt.image=ALL-UNNAMED"
            args += "--add-exports=java.desktop/sun.java2d=ALL-UNNAMED"
            args += "--add-exports=java.desktop/java.awt.dnd.peer=ALL-UNNAMED"
            args += "--add-exports=java.desktop/sun.awt=ALL-UNNAMED"
            args += "--add-exports=java.desktop/sun.awt.event=ALL-UNNAMED"
            args += "--add-exports=java.desktop/sun.awt.datatransfer=ALL-UNNAMED"
            args += "--add-exports=java.desktop/sun.font=ALL-UNNAMED"
            args += "--add-exports=java.base/sun.security.action=ALL-UNNAMED"
            args += "--add-opens=java.base/java.util=ALL-UNNAMED"
            args += "--add-opens=java.desktop/java.awt=ALL-UNNAMED"
            args += "--add-opens=java.desktop/sun.font=ALL-UNNAMED"
            args += "--add-opens=java.desktop/sun.java2d=ALL-UNNAMED"
            args += "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED"
            args += "--add-opens=java.base/java.net=ALL-UNNAMED"
        }
        // ─────────────────────────────────────────────────────────────────────

        // MobileGlues 사용 시 Sodium 자체 검증 우회 (1.21+ 셰이더 호환)
        if (effectiveRendererId == "mobileglues") {
            args += listOf(
                "-Dnet.caffeinemc.sodium.checks.skip=true",
                "-Dsodium.checks.issue2561=false",
                "-Dorg.lwjgl.opengl.maxVersion=4.6",
                "-Diris.force.support=true"
            )
        }

        // 커스텀 인자
        extraJvmArgs.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { args += it }

        return args.toTypedArray()
    }

    companion object {
        /** 해상도 배율 하한 (%) */
        const val RES_SCALE_MIN_PERCENT = 25
        /**
         * 해상도 배율 상한 (%). 100 = 네이티브.
         * 100 초과로 올리고 싶으면(슈퍼샘플링 = 화질↑·FPS↓) 이 값만 키우면 된다.
         */
        const val RES_SCALE_MAX_PERCENT = 100
    }
}

object JvmSettingsManager {
    private const val FILE_NAME = "jvm_settings.json"
    private val gson = Gson()

    fun load(context: Context): JvmSettings {
        val fallback = JvmSettings(cacheDirPath = context.cacheDir.absolutePath)
        return try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return fallback

            val text = file.readText()
            var settings = gson.fromJson(text, JvmSettings::class.java) ?: return fallback

            // ⚠️ Gson 은 Kotlin 생성자를 거치지 않아서, JSON 에 없는 필드는 data class 의
            //    기본값이 아니라 0/false 로 채워진다. 구버전 jvm_settings.json 에는
            //    fullscreen / resolutionScalePercent 가 없으므로, 키가 빠진 경우 기본값으로 보정한다.
            //    (이게 없으면 기존 사용자는 전체화면 off, 해상도 0% 로 깨진다.)
            val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            if (obj?.has("fullscreen") != true) settings = settings.copy(fullscreen = true)
            if (obj?.has("resolutionScalePercent") != true)
                settings = settings.copy(resolutionScalePercent = 100)

            // 범위 보정 (잘못된 값/0 방지)
            settings = settings.copy(
                resolutionScalePercent = settings.resolutionScalePercent
                    .coerceIn(JvmSettings.RES_SCALE_MIN_PERCENT, JvmSettings.RES_SCALE_MAX_PERCENT)
            )

            // cacheDirPath가 비어있으면 채워주기
            if (settings.cacheDirPath.isEmpty())
                settings.copy(cacheDirPath = context.cacheDir.absolutePath)
            else settings
        } catch (_: Exception) {
            fallback
        }
    }

    fun save(context: Context, settings: JvmSettings) {
        try {
            File(context.filesDir, FILE_NAME).writeText(gson.toJson(settings))
        } catch (_: Exception) {}
    }

    fun reset(context: Context): JvmSettings {
        val default = JvmSettings(cacheDirPath = context.cacheDir.absolutePath)
        save(context, default)
        return default
    }
}

fun isLegacyVersion(versionId: String): Boolean {
    // 1.12.2 이하: legacy (AWT 필요)
    // 1.13+: modern (LWJGL3, AWT 불필요)
    // 베타/알파/클래식/인데브(b1.x, a1.x, a0.x, c0.x, inf-, rd-) 등 "1."로 시작하지 않는
    // 구버전 표기는 기존 파서(removePrefix("1.") 후 파싱)가 못 잡아 modern 으로 오판했다.
    //   → resolveJavaMajor() 가 JRE17 을 골라 cacio 주입 방식이 어긋나면서
    //     b1.7.3 등이 실행 즉시 크래시하던 버그의 근본 원인.
    val id = versionId.trim().lowercase()
    if (id.startsWith("b1.") || id.startsWith("a1.") || id.startsWith("a0.") ||
        id.startsWith("c0.") || id.startsWith("inf-") || id.startsWith("rd-")) {
        return true
    }
    val major = Regex("""^1\.(\d+)""").find(id)?.groupValues?.get(1)?.toIntOrNull() ?: return false
    return major <= 12
}

/**
 * 버전에 따라 사용할 Java major 를 추정한다.
 *
 * ⚠️ cacio 주입 방식 분기에만 쓰는 보수적 추정치다.
 *    실제 JRE 선택은 MinecraftJREPreparer 가 하므로, 여기 값과 미세하게 다를 수 있어도
 *    "8 인가 / 9+ 인가" 경계만 정확하면 -Xbootclasspath/p: 사고를 막는 목적은 달성된다.
 *
 *   - 1.12.2 이하         → 8  (legacy, cacio-androidnw-1.10 + bootclasspath/p)
 *   - 그 외(1.13+/스냅샷)  → 17 (modern, bootclasspath/p 금지)
 *
 * 추후 1.20.5+ → 21, 26w+ 스냅샷 → 25 처럼 세분화해도 cacio 분기에는 영향 없다
 * (모두 9+ 이므로 동일하게 bootclasspath/p 를 안 쓰는 쪽으로 간다).
 */
fun resolveJavaMajor(versionId: String): Int {
    if (isLegacyVersion(versionId)) return 8
    return 17
}