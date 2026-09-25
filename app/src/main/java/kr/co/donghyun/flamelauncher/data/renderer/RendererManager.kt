package kr.co.donghyun.flamelauncher.data.renderer

import android.content.Context

enum class Renderer(
    val id: String,
    val displayName: String,
    val description: String,
    val pojavRenderer: String,
    val libglName: String,
    val libglString: String,
    val libglEs: String,
    val emoji: String,
    /** 외부 APK 플러그인이 .so 를 제공하는 렌더러인지(MobileGlues). 내부 번들 렌더러는 false. */
    val extraEnv: Map<String, String> = emptyMap()
) {
    ZINK(
        id = "zink",
        displayName = "Zink (Vulkan)",
        description = "Vulkan을 OpenGL로 변환. 모던 GPU에서 가장 호환성 좋음. 1.17+ 추천. " +
                "내부적으로 Vulkan Zink → Freedreno/Panfrost(벤더별) → 실패 시 GL4ES 순으로 자동 폴백.",
        pojavRenderer = "vulkan_zink",
        libglName = "libOSMesa.so",
        libglString = "VulkanGL",
        libglEs = "3",
        emoji = "🌋",
        extraEnv = mapOf(
            "force_glsl_extensions_warn" to "true",
            "allow_higher_compat_version" to "true",
            "allow_glsl_extension_directive_midshader" to "true",
            "MESA_LOADER_DRIVER_OVERRIDE" to "zink",
            "GALLIUM_DRIVER" to "zink",
//            "POJAV_LOAD_TURNIP" to "1"  // Adreno면 Turnip 시도
            "MESA_VK_WSI_PRESENT_MODE" to "mailbox",
            "VK_ICD_FILENAMES" to "",         // 시스템 ICD 무시
            "ZINK_DEBUG" to "noreorder",      // zink 의 일부 reorder 최적화 비활성 (안정성)
            "LIBGL_KOPPER_DRI2" to "1",       // kopper(=zink WSI) 가 DRI2 없이 동작
            "LIBGL_DRI3_DISABLE" to "1",      // DRI3 도 끔
            "GALLIUM_HUD" to "",
        )
    ),

    /**
     * GL4ES — OpenGL(데스크톱) → OpenGL ES 2.0 변환 레이어.
     * Zink 가 지원하지 못하는 pre-1.13(특히 LWJGL2 시절의 immediate-mode GL)을 돌리기 위한 경로.
     * PojavLauncher 계열이 구버전 구동에 쓰는 기본 방식이며, libgl4es_114.so 로 동작한다.
     * (egl_bridge.c 의 GL4ES 폴백 경로와 동일하게 POJAV_RENDERER=opengles2 / LIBGL_ES=2 사용)
     */
    GL4ES(
        id = "gl4es",
        displayName = "GL4ES",
        description = "OpenGL을 GLES2로 변환. 구버전(1.12 이하) 및 Zink 미지원 기기용.",
        pojavRenderer = "opengles2",
        libglName = "libgl4es_114.so",
        libglString = "GL4ES wrapper",
        libglEs = "2",
        emoji = "🕹️",
        extraEnv = mapOf(
            // pre-1.13 안정화용 GL4ES 플래그 (PojavLauncher 권장값 기반)
            "LIBGL_ES" to "2",                 // GLES 2.0 백엔드
            "LIBGL_GL" to "21",                // 데스크톱 GL 2.1 에뮬레이션 (구버전 호환)
            "LIBGL_MIPMAP" to "3",             // 밉맵 자동 생성
            "LIBGL_NORMALIZE" to "1",          // 법선 정규화 (구버전 라이팅)
            "LIBGL_NOINTOVLHACK" to "1",
            "LIBGL_NOERROR" to "1",            // glGetError 비용 제거 (성능/안정)
            "LIBGL_USEVBO" to "1",             // ★ pre-1.13 의 VBO 미사용 랜덤 크래시 회피 (검색으로 확인된 핵심)
            "LIBGL_SHADERCONVERTER" to "1",
            "LIBGL_FB" to "2",                 // FBO 기반 백버퍼
            "LIBGL_FBONOALPHA" to "1",
        )
    ),

    /**
     * MobileGlues — OpenGL 4.x → OpenGL ES 3.2 변환 레이어. **앱에 내장**(jniLibs).
     * 셰이더(Iris/Sodium) 성능이 가장 좋은 모던 렌더러. 1.17+ / 셰이더팩에 추천이고,
     * 26.3 의 OpenGL 백엔드는 이것만 쓸 수 있다(EGL 진입점까지 내보내는 유일한 번역 계층).
     *
     * 핵심 env:
     *   POJAV_RENDERER = opengles3 / LIBGL_ES = 3
     *   LIBGL_NAME = libmobileglues.so / LIBGL_EGL = libmobileglues.so / POJAVEXEC_EGL = libmobileglues.so
     *   DLOPEN = libmobileglues.so  (정보취득용 libmobileglues_info_getter.so 가 의존성)
     *   MG_DIR_PATH · config.json 은 MinecraftActivity 가 정한다(버전마다 설정이 다르다)
     */
    MOBILEGLUES(
        id = "mobileglues",
        displayName = "MobileGlues",
        description = "OpenGL을 GLES3.2로 변환. 셰이더/모던 버전에 최적.",
        pojavRenderer = "opengles3",
        libglName = "libmobileglues.so",
        libglString = "MobileGlues",
        libglEs = "3",
        emoji = "🚀",
        extraEnv = mapOf(
            // EGL 도 MobileGlues 가 직접 제공 (PojavLauncher/FCL 동일)
            "LIBGL_EGL" to "libmobileglues.so",
            "POJAVEXEC_EGL" to "libmobileglues.so",
        )
    ),

    /**
     * LTW — the GLES/EGL translation layer used by Turtle Launcher for the
     * fast all-version path. It must remain a selectable renderer instead of
     * being silently replaced by GL4ES during the migration.
     */
    LTW(
        id = "ltw",
        displayName = "LTW (All Version Fast)",
        description = "Fast GLES/EGL translation layer for supported Minecraft versions.",
        pojavRenderer = "opengles3_ltw",
        libglName = "libltw.so",
        libglString = "LTW",
        libglEs = "3",
        emoji = "⚡",
        extraEnv = mapOf(
            // LTW owns the EGL entry point; using libEGL.so here only selects
            // Android's GLES provider behind LTW.
            "LIBGL_EGL" to "libEGL.so",
            "POJAVEXEC_EGL" to "libltw.so",
            "force_glsl_extensions_warn" to "true",
            "allow_higher_compat_version" to "true",
            "allow_glsl_extension_directive_midshader" to "true",
            "LIBGL_NOERROR" to "1",
        )
    ),

    /**
     * Krypton Wrapper (aka NG-GL4ES) — gl4es / gl4es-114-extra 의 포크.
     * GL4ES 계열이지만 더 고급 셰이더(Iris/Sodium)와 폭넓은 버전(1.16.5- 및 1.17+)을 지원한다.
     * GLES3 백엔드(POJAV_RENDERER=opengles3)로 동작하며 .so 는 libng_gl4es.so.
     *
     * ⚠️ MobileGlues 와 달리 **외부 앱 플러그인이 아니라 내부 번들 렌더러**다(ZL2 와 동일).
     *    libng_gl4es.so 를 app/src/main/jniLibs/arm64-v8a/ 에 직접 넣고, GL4ES 처럼
     *    런처 자신의 nativeLibraryDir 에서 로드한다. EGL/DLOPEN 은 필요 없다(시스템 libEGL 사용).
     *    (ZL2 NGGL4ESRenderer: rendererId=opengles3, lib=libng_gl4es.so, env 아래와 동일)
     */
    KRYPTON(
        id = "krypton",
        displayName = "Krypton Wrapper",
        description = "GL4ES 기반 고급 래퍼(NG-GL4ES). 셰이더/폭넓은 버전 지원. 내장 렌더러.",
        pojavRenderer = "opengles3",
        libglName = "libng_gl4es.so",
        libglString = "Krypton Wrapper",
        libglEs = "3",
        emoji = "⚛️",
        // 내부 번들이므로 isPlugin=false (GL4ES/Zink 와 동일하게 취급)
        extraEnv = mapOf(
            // ZL2 NGGL4ESRenderer.getRendererEnv() 와 동일
            "LIBGL_USE_MC_COLOR" to "1",
            "LIBGL_GL" to "31",
            "LIBGL_ES" to "3",
            "LIBGL_NORMALIZE" to "1",
            "LIBGL_NOERROR" to "1",
        )
    );

    /**
     * 렌더러 환경변수 맵을 만든다.
     *
     * @param cacheDir 캐시 디렉터리(GLSL 캐시 / TMPDIR).
     * @param nativeDir 런처 본체의 nativeLibraryDir(POJAV_NATIVEDIR).
     */
    fun buildEnv(
        cacheDir: String,
        nativeDir: String,
        preferGl4esOnZinkFallback: Boolean = false,
        customVulkanDriver: Pair<String, String>? = null,   // (드라이버 폴더경로, .so 파일명)
    ): Map<String, String> {
        val glNameResolved = libglName
        val dlopenResolved = libglName

        val base = mutableMapOf(
            "POJAV_RENDERER" to pojavRenderer,
            "LIBGL_NAME" to glNameResolved,
            "LIBGL_STRING" to libglString,
            "LIBGL_ES" to libglEs,
            "DLOPEN" to dlopenResolved,
            "MESA_GLSL_CACHE_DIR" to cacheDir,
            "TMPDIR" to cacheDir,
            "POJAV_NATIVEDIR" to nativeDir,
            "FORCE_VSYNC" to "false",
            "POJAV_VSYNC" to "1"
        )
        base.putAll(extraEnv)

        // Zink 계열 폴백 체인(Zink→Freedreno→Panfrost→GL4ES)에서, 이 버전이
        // GL4ES 로 안전하게 구동 가능한 버전이면 Zink 실패 시 Freedreno/Panfrost 를
        // 건너뛰고 곧바로 GL4ES 로 폴백하도록 네이티브(egl_bridge.c)에 신호를 준다.
        if (id == "vulkan_zink" && preferGl4esOnZinkFallback) {
            base["POJAV_ZINK_FALLBACK_GL4ES_DIRECT"] = "1"
        }

        // 사용자가 커스텀 Vulkan 드라이버(Turnip 등)를 설정에서 선택해뒀으면
        // egl_bridge.c 의 load_custom_vulkan_driver() 가 이 값을 읽어서 시스템
        // 기본 드라이버 대신 이걸 우선 로드한다(Zink 에만 의미 있음).
        if (id == "vulkan_zink" && customVulkanDriver != null) {
            base["POJAV_CUSTOM_VULKAN_DRIVER_DIR"] = customVulkanDriver.first
            base["POJAV_CUSTOM_VULKAN_DRIVER_NAME"] = customVulkanDriver.second
        }

        if (id == "mobileglues") {
            // info_getter 는 libmobileglues.so 의 의존성이라 함께 올린다(둘 다 앱에 내장).
            base["DLOPEN"] = "libmobileglues_info_getter.so,libmobileglues.so"
        }

        return base
    }

    companion object {
        fun fromId(id: String?): Renderer = entries.firstOrNull { it.id == id } ?: ZINK

        /**
         * 현재 기기/설치 상태에서 인스턴스 설정에 노출할 렌더러 목록.
         * 내부 렌더러(Zink/GL4ES)는 항상, MobileGlues 는 APK 가 감지됐을 때만 포함.
         *
         */
        fun selectableRenderers(): List<Renderer> = buildList {
            add(ZINK)
            add(GL4ES)
            add(KRYPTON)      // 내부 번들(libng_gl4es.so)
            add(LTW)          // 내부 번들(libltw.so)
            add(MOBILEGLUES)  // 내부 번들(libmobileglues.so)
        }
    }
}

/**
 * 전역(런처 기본) 렌더러 설정.
 *
 * 인스턴스별 렌더러는 InstanceMeta.rendererId 에 저장한다.
 * 인스턴스에 값이 없을 때(null)의 폴백 기본값으로 이 전역 설정을 사용한다.
 */
object RendererManager {
    private const val PREFS = "renderer_prefs"
    private const val KEY_SELECTED = "selected_renderer"

    fun load(context: Context): Renderer {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED, null)
        return Renderer.fromId(id)
    }

    fun save(context: Context, renderer: Renderer) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SELECTED, renderer.id)
            .apply()
    }
}