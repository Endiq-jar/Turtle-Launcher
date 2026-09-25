package com.endiq.turtlelauncher.presentation

import com.endiq.turtlelauncher.R
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.input.InputManager.InputDeviceListener
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.WindowManager
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.graphics.Insets
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.endiq.turtlelauncher.BuildConfig
import com.endiq.turtlelauncher.data.auth.LocalAccountManager
import com.endiq.turtlelauncher.data.auth.LocalSkinManager
import com.endiq.turtlelauncher.data.auth.LocalSkinServer
import com.endiq.turtlelauncher.data.auth.MicrosoftAuthManager
import com.endiq.turtlelauncher.data.auth.ThirdPartyAuthManager
import com.endiq.turtlelauncher.data.auth.ThirdPartyAccount
import com.endiq.turtlelauncher.data.instance.InstanceManager
import com.endiq.turtlelauncher.data.instance.InstanceType
import com.endiq.turtlelauncher.data.jvm.JvmSettings
import com.endiq.turtlelauncher.data.jvm.JvmSettingsManager
import com.endiq.turtlelauncher.data.jvm.isLegacyVersion
import com.endiq.turtlelauncher.data.renderer.Renderer
import com.endiq.turtlelauncher.domain.model.MinecraftSupport
import com.endiq.turtlelauncher.data.renderer.RendererManager
import com.endiq.turtlelauncher.presentation.input.GamepadHandler
import com.endiq.turtlelauncher.presentation.input.GlfwKeys
import com.endiq.turtlelauncher.presentation.input.glfwToSdlScancode
import com.endiq.turtlelauncher.presentation.util.usesSdl
import com.endiq.turtlelauncher.presentation.util.needsLwjgl34
import com.endiq.turtlelauncher.presentation.ui.components.GameControllerView
import com.endiq.turtlelauncher.presentation.ui.components.InGameMenuOverlay
import com.endiq.turtlelauncher.presentation.ui.components.DisabledModsOverlay
import com.endiq.turtlelauncher.presentation.ui.components.DisabledModInfo
import com.endiq.turtlelauncher.presentation.ui.components.MinecraftBootOverlay
import com.endiq.turtlelauncher.presentation.ui.components.MinecraftSurface
import com.endiq.turtlelauncher.presentation.ui.theme.TurtleLauncherTheme
import com.endiq.turtlelauncher.presentation.util.MinecraftActivityBridge
import com.endiq.turtlelauncher.presentation.util.dns.DnsHookNative
import com.endiq.turtlelauncher.presentation.util.forge.startBuilderAndWaitExitBlocking
import com.endiq.turtlelauncher.presentation.util.jni.JavaNativeLauncher
import com.endiq.turtlelauncher.presentation.util.minecraft.MinecraftJREPreparer
import com.endiq.turtlelauncher.presentation.util.resources.ResourcePackImporter
import com.endiq.turtlelauncher.feature.turtle.DailyPlaytimeStats
import com.endiq.turtlelauncher.feature.mod.NanoVGNativesFix
import com.endiq.turtlelauncher.launch.AutoSettingsOptimizer
import org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
import org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE
import org.lwjgl.glfw.GLFW.GLFW_KEY_SLASH
import org.lwjgl.glfw.GLFW.GLFW_KEY_T
import org.lwjgl.glfw.GLFW.GLFW_PRESS
import org.lwjgl.glfw.GLFW.GLFW_RELEASE
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream


@dagger.hilt.android.AndroidEntryPoint
class MinecraftActivity : org.libsdl.app.SDLActivity() {

    // ── 26.3+ (SDL3) 경로 ────────────────────────────────────────────────────
    //
    // 26.3 부터 마인크래프트는 GLFW 를 버리고 SDL3 로 간다. SDL3 의 안드로이드 백엔드는
    // 창·입력의 자바 쪽을 [org.libsdl.app.SDLActivity] 로 받으므로, 이 액티비티가 그
    // 클래스를 **상속**한다. 그 전 버전에서는 [usesSDL] 이 false 라 SDL 이 초기화되지
    // 않고(업스트림 onCreate 가 통째로 비켜간다) 기존 PojavLauncher 스택 그대로 돈다.
    //
    // ⚠️ BaseActivity 를 상속하지 못하게 됐다(자바는 단일 상속). 그쪽이 하던 일은
    //    가로 고정·창 플래그·onCreated() 호출 셋뿐이라 아래 onCreate 로 옮겼다.
    private val sdlMode: Boolean by lazy {
        usesSdl(intent.getStringExtra(EXTRA_VERSION_ID) ?: "")
    }

    override fun usesSDL(): Boolean = sdlMode

    /** SDL 모드에서 C main 스레드는 띄우지 않는다 — main 은 마인크래프트를 돌리는 JVM 이다. */
    override fun startsSDLMainThread(): Boolean = false

    /** SDL 의 JNI_OnLoad 가 **달빅 VM** 을 잡게 여기서 올린다. JVM 의 LWJGL 은 같은 걸 재사용한다. */
    override fun getLibraries(): Array<String> = arrayOf("SDL3")

    /**
     * SDL 이 게임 화면(SurfaceView)을 만들 때 우리 터치 처리를 심는다.
     *
     * ⚠️ 만든 뒤에 setOnTouchListener 로 붙이면 안 된다. SDLSurface.handleResume() 이
     *    재개될 때마다 `setOnTouchListener(this)` 로 자기 리스너를 다시 등록해서, 붙여 둔
     *    드래그(시점 회전) 처리가 덮어써졌다(실측: 인게임 드래그가 안 먹음).
     *    onTouch 자체를 재정의하면 SDL 이 다시 등록해도 결국 여기로 온다.
     */
    override fun createSDLSurface(context: Context): org.libsdl.app.SDLSurface =
        object : org.libsdl.app.SDLSurface(context) {
            private val turtleTouch =
                com.endiq.turtlelauncher.presentation.ui.components.minecraftTouchListener(
                    this@MinecraftActivity
                ) { intArrayOf(width, height) }

            override fun onTouch(v: View, event: MotionEvent): Boolean = turtleTouch.onTouch(v, event)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // super 보다 먼저 고정한다 — 뒤에 하면 SDL 이 만든 창이 한 번 세로로 잡혔다 돈다.
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        super.onCreate(savedInstanceState)
        if (sdlMode) {
            // SDL3 는 SDLActivity.loadLibraries 가 이미 올렸다. 입력 주입 쪽만 더 올린다.
            runCatching {
                System.loadLibrary("turtlesdl")
                // 게임이 SDL_Init 을 부르기 전에 알려야 한다 — 늦으면 SDL 이 초기화를 거절한다.
                Log.i("TURTLE_LAUNCHER", "SDL_SetMainReady: ${nativeSdlSetMainReady()}")
            }.onFailure { Log.e("TURTLE_LAUNCHER", "libturtlesdl.so 준비 실패", it) }
        }
        applyWindowFlags()
        onCreated()
    }

    private var hideNavigation = false

    private fun hideNavigation() {
        hideNavigation = true
        applyWindowFlags()
    }

    private fun applyWindowFlags() {
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (hideNavigation) controller.hide(WindowInsetsCompat.Type.navigationBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }


    // ⚠️ 이 클래스의 external fun 들은 JNI 네이티브 코드에 클래스 이름이 하드코딩되어 있어서
    //   (Java_com_endiq_turtlelauncher_presentation_MinecraftActivity_...) 절대 다른
    //   클래스로 옮기면 안 된다 — 컴파일은 되지만 런타임에 UnsatisfiedLinkError.
    @javax.inject.Inject
    lateinit var bytecodePatchRepo: com.endiq.turtlelauncher.data.repository.MinecraftBytecodePatchRepositoryImpl

    private external fun nativeIsGrabbing(): Boolean
    private external fun nativeSetupBridgeWindow(surface: Surface)
    private external fun nativeTrySetupShowingWindow(): Boolean
    private external fun nativeDumpInputState()

    private external fun nativeSendKey(key: Int, scancode: Int, action: Int, mods: Int)

    // ── 26.3+ (SDL3) 입력 주입 — libturtlesdl.so ──────────────────────────────
    // 화면 버튼·게임패드는 SDL 의 SurfaceView 밖에서 오므로 이벤트를 직접 넣는다.
    // (SurfaceView 자체의 터치는 SDL 이 알아서 받는다)
    private external fun nativeSdlSendKey(scancode: Int, action: Int): Boolean
    private external fun nativeSdlSendChar(codepoint: Int): Boolean
    private external fun nativeSdlSendMouseButton(glfwButton: Int, action: Int): Boolean
    private external fun nativeSdlSendCursorPos(mode: Int, x: Float, y: Float): Boolean
    private external fun nativeSdlSendScroll(dx: Float, dy: Float): Boolean
    private external fun nativeSdlSetFramebufferSize(w: Int, h: Int)
    private external fun nativeSdlIsGrabbing(): Int
    private external fun nativeSdlSetMainReady(): Boolean
    private external fun nativeMgSwapCount(): Int

    private external fun nativeSendMouseButton(button: Int, action: Int, mods: Int)
    private external fun nativeSendCursorPos(x: Float, y: Float)


    // Intent로 전달받은 버전 정보
    private lateinit var versionId: String
    private lateinit var assetIndex: String
    private val sessionStartedAtMs = System.currentTimeMillis()
    private lateinit var extraJars: List<String>
    private lateinit var mainClass: String
    internal var instanceDir: String? = null
    private var customGameDir: String? = null
    private var currentSurface: Surface? = null
    @Volatile var combatMode: Boolean = false

    // 화면 컨트롤러 표시 토글은 GameControllerView 가 자체적으로(좌상단 🎮 버튼) 관리한다.
    //   여기서는 별도 상태/플러밍을 두지 않는다.

    private val PROCESSOR_ONLY_JAR_PREFIXES = listOf(
        "ForgeAutoRenamingTool",
        "BinaryPatcher", "binarypatcher",
        "jarsplitter",
        "installertools",
        "vignette",
        "DiffPatch", "diffpatch",
        "mergetool"   // ※ 부팅에 필요한 mergetool-*-api.jar 는 보존 필요 — 아래 헬퍼에서 별도 처리
    )

    private var gameControllerView: GameControllerView? = null
    private var inputDeviceListener: InputDeviceListener? = null

    internal val isGrabbing: Boolean
        get() {
            if (!jvmStarted) return false
            // 26.3 은 grab 을 SDL_SetWindowRelativeMouseMode 로 건다. 창이 아직 없으면 -1.
            if (sdlMode) return try { nativeSdlIsGrabbing() == 1 } catch (_: Throwable) { false }
            return try { nativeIsGrabbing() } catch (_: Throwable) { false }
        }

    private var jvmStarted = false
    private var javaMajor: Int = 21

    // ── 디스플레이 설정(전체화면 / 해상도 배율) — onCreated 에서 1회 로드 ──
    private var fullscreenEnabled = true
    private var renderScalePercent = 100
    private var renderScaleApplied = false

    // ── 부팅 로딩 오버레이 상태 ──
    // showBootOverlay 가 true 인 동안 게임 surface 위에 "부팅 중" 다이얼로그를 표시한다.
    // 첫 프레임 콜백(MinecraftActivityBridge.onFirstFrameRendered) 또는 타임아웃에 false 가 된다.
    private var showBootOverlay by mutableStateOf(true)

    // ☰ 인게임 메뉴(설정/온라인 LAN) 표시 여부. GameControllerView 의 ☰ 버튼이 true 로 만든다.
    private var showInGameMenu by mutableStateOf(false)

    // 기기 비호환으로 자동 비활성화된 모드 목록 + 첫 프레임 후 알림 팝업 표시 여부.
    //   disableUnsupportedMods(실행 전) 가 채우고, 첫 프레임 콜백에서 비어있지 않으면 팝업을 띄운다.
    private val disabledModsList = mutableStateListOf<DisabledModInfo>()
    private var showDisabledModsPopup by mutableStateOf(false)
    private var bootModCount by mutableIntStateOf(0)
    private var bootMaxDelayMin by mutableIntStateOf(2)
    @Volatile private var bootOverlayDismissed = false


    companion object {
        private const val EXTRA_VERSION_ID = "version_id"
        // ELF e_machine 값: arm64. jar 내 .so 가 이 기기에서 로드 가능한지 판정에 사용.
        private const val ELF_EM_AARCH64 = 183
        private const val EXTRA_ASSET_INDEX = "asset_index"
        private const val EXTRA_EXTRA_JARS = "extra_jars"
        private const val EXTRA_MAIN_CLASS = "main_class"
        private const val EXTRA_GAME_DIR = "game_dir"
        private const val EXTRA_INSTANCE_DIR = "instance_dir"


        /** 안내 팝업에서 쓸, 사용자가 고른 렌더러 표시명(선택). */
        const val EXTRA_RESULT_RENDERER_ID = "result_renderer_id"

        @JvmStatic
        var currentInstance: MinecraftActivity? = null

        fun start(
            context: Context,
            versionId: String,
            assetIndex: String,
            extraJars: List<String> = emptyList(),
            mainClass: String = "net.minecraft.client.main.Main",
            customGameDir: String? = null,
            instanceDir: String? = null
        ) {
            context.startActivity(
                buildIntent(context, versionId, assetIndex, extraJars, mainClass, customGameDir, instanceDir)
            )
        }

        /**
         * Activity Result 방식 실행. MobileGlues 미설치 등으로 게임이 안 뜨고 종료될 때
         * 호출 측(MainActivity)이 결과(RESULT_MOBILEGLUES_MISSING)를 받아 안내 팝업을 띄울 수 있다.
         * 동작은 [start] 와 동일하되 startActivity 대신 전달받은 런처로 실행한다.
         */
        fun startForResult(
            context: Context,
            launcher: androidx.activity.result.ActivityResultLauncher<Intent>,
            versionId: String,
            assetIndex: String,
            extraJars: List<String> = emptyList(),
            mainClass: String = "net.minecraft.client.main.Main",
            customGameDir: String? = null,
            instanceDir: String? = null
        ) {
            launcher.launch(
                buildIntent(context, versionId, assetIndex, extraJars, mainClass, customGameDir, instanceDir)
            )
        }

        private fun buildIntent(
            context: Context,
            versionId: String,
            assetIndex: String,
            extraJars: List<String>,
            mainClass: String,
            customGameDir: String?,
            instanceDir: String?
        ): Intent {
            Log.d("TURTLE_LAUNCHER", "MC 시작: mainClass=$mainClass, extraJars=${extraJars.size}개")
            Log.d("TURTLE_LAUNCHER", "instanceDir 전달: $instanceDir")
            Log.d("TURTLE_LAUNCHER", "customGameDir 전달: $customGameDir")
            return Intent(context, MinecraftActivity::class.java).apply {
                instanceDir?.let { putExtra(EXTRA_INSTANCE_DIR, it) }
                putExtra(EXTRA_VERSION_ID, versionId)
                putExtra(EXTRA_ASSET_INDEX, assetIndex)
                putStringArrayListExtra(EXTRA_EXTRA_JARS, ArrayList(extraJars))
                putExtra(EXTRA_MAIN_CLASS, mainClass)
                customGameDir?.let { putExtra(EXTRA_GAME_DIR, it) }
            }
        }
    }


    // ── 게임 내 "리소스팩 폴더 열기" → SAF 로 .zip 선택해 resourcepacks/ 에 복사 ──
    //   MainActivity(stub).openLink 가 resourcepacks 경로를 감지하면 이 Activity 의
    //   openResourcePackPicker(dir) 를 호출한다. 피커 결과는 아래 런처가 받는다.
    //   (런처 등록은 필드 초기화 시점 = RESUMED 이전이라 게임 중에도 launch 가능)
    private var pendingResourcePacksDir: File? = null

    private val resourcePackPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val dir = pendingResourcePacksDir
        pendingResourcePacksDir = null
        if (uri == null || dir == null) return@registerForActivityResult
        Toast.makeText(this, getString(R.string.importing_resource_pack), Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            val result = ResourcePackImporter.importZip(
                context = applicationContext,
                zipUri = uri,
                resourcePacksDir = dir,
            )
            withContext(Dispatchers.Main) {
                val msg = when (result) {
                    is ResourcePackImporter.Result.Success ->
                        getString(R.string.resource_pack_added_activate, result.packName)
                    is ResourcePackImporter.Result.Failure ->
                        result.reason
                }
                Toast.makeText(this@MinecraftActivity, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    // 🎙 음성채팅 모드(Simple Voice Chat 등)를 위한 마이크 권한 — 게임 실행 전에 미리
    //   요청해둔다. 거부해도 게임 자체는 정상 실행되고, 그 모드의 마이크 기능만 안 될 뿐이다.
    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 결과와 무관하게 게임은 이미 계속 진행 중 — 별도 처리 불필요 */ }

    private fun requestMicPermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    /**
     * 게임 내 "리소스팩 폴더 열기"에서 호출됨(MainActivity stub 경유).
     * 폴더 선택 대신 .zip 파일 피커를 띄워, 고른 팩을 해당 resourcepacks 폴더로 복사한다.
     */
    fun openResourcePackPicker(resourcePacksDir: File) {
        pendingResourcePacksDir = resourcePacksDir.apply { mkdirs() }
        val mimeTypes = arrayOf(
            "application/zip",
            "application/x-zip-compressed",
            "application/octet-stream",
        )
        try {
            resourcePackPickerLauncher.launch(mimeTypes)
            Toast.makeText(this, getString(R.string.select_resource_pack_zip_to_add), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("TURTLE_LAUNCHER", "리소스팩 피커 실행 실패: ${e.message}", e)
            pendingResourcePacksDir = null
        }
    }

    private fun onCreated() {
        hideNavigation()
        currentInstance = this
        requestMicPermissionIfNeeded()

        versionId = intent.getStringExtra(EXTRA_VERSION_ID) ?: MinecraftSupport.SUPPORTED_VERSION
        if (!MinecraftSupport.isSupported(versionId)) {
            Toast.makeText(
                this,
                "Minecraft $versionId is not supported. Only ${MinecraftSupport.SUPPORTED_VERSION} can be launched.",
                Toast.LENGTH_LONG,
            ).show()
            finish()
            return
        }
        assetIndex = intent.getStringExtra(EXTRA_ASSET_INDEX) ?: "26"
        // Content-pack launches can bypass MainActivity; tune the game path as well.
        AutoSettingsOptimizer.apply(this, versionId)
        extraJars = intent.getStringArrayListExtra(EXTRA_EXTRA_JARS) ?: emptyList()
        mainClass = intent.getStringExtra(EXTRA_MAIN_CLASS) ?: "net.minecraft.client.main.Main"
        instanceDir = intent.getStringExtra(EXTRA_INSTANCE_DIR)
        Log.d("TURTLE_LAUNCHER", "instanceDir 수신: $instanceDir")  // ← 추가

        // MobileGlues 는 앱에 내장돼 있다(jniLibs). 설치 여부를 확인할 것도, 안내할 것도 없다.
        customGameDir = intent.getStringExtra(EXTRA_GAME_DIR)
        Log.d("TURTLE_LAUNCHER", "customGameDir 수신: $customGameDir")  // ← 추가

        // 엣지투엣지로 직접 제어 — 시스템이 IME 에 맞춰 윈도우를 리사이즈/팬 하지 않게 한다.
        // (adjustNothing 만으로는 Compose/surface 로 IME insets 가 전파되어 화면이 밀리고
        //  번쩍이므로, 아래 리스너에서 IME insets 를 소비해 하위 전파를 끊는다.)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setOnApplyWindowInsetsListener(window.decorView) { _, insets ->
            val ime = insets.isVisible(WindowInsetsCompat.Type.ime())
            gameControllerView?.setImeVisibleExternal(ime)
            if (ime != imeVisible) {
                imeVisible = ime
                updateGameControllerVisibility()
            }
            // IME insets 를 0 으로 덮어써서 하위(Compose/Surface)로의 전파를 차단.
            //   → 게임 화면이 IME 에 밀리지 않고(다 해결), surface 재레이아웃 번쩍임(가)도 사라진다.
            //   다른 insets(상태바/제스처 등)는 그대로 유지.
            WindowInsetsCompat.Builder(insets)
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.NONE)
                .build()
        }

        // ── 전체 화면 토글 ──
        // hideNavigation() 으로 기본은 몰입형(전체화면)이지만, 사용자가 전체화면을 끄면
        // 시스템 바를 다시 보여준다. 해상도 배율 값도 여기서 함께 읽어 둔다.
        val displaySettings = JvmSettingsManager.load(this)
        fullscreenEnabled = displaySettings.fullscreen
        renderScalePercent = displaySettings.resolutionScalePercent
        applyFullscreen(fullscreenEnabled)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { return }
        })

        // ⚠️ 26.3+(SDL) 에서는 **SDL 이 만든 화면을 건드리지 않는다** — iOS 와 같은 구조다.
        //    SDL 의 SurfaceView 를 떼어 Compose 로 옮겼더니 SDL 자바 쪽의 표면 통지가
        //    꼬여서, 게임은 창을 만들었는데 한 프레임도 안 나왔다(실측). SDL 레이아웃은
        //    SDLActivity.onCreate 가 이미 setContentView 해 두었으니, 그 위에 오버레이만 얹는다.
        val gameContent: @androidx.compose.runtime.Composable () -> Unit = {
            TurtleLauncherTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (!sdlMode) MinecraftSurface(
                        onSurfaceCreated = { surface, _ ->
                            currentSurface = surface
                            applyRenderResolutionScale()   // 렌더 해상도 배율(축소 시 FPS↑)
                            if (!jvmStarted) {
                                jvmStarted = true
                                setupAndLaunch(surface)
                            } else {
                                try {
                                    System.loadLibrary("turtlejvm")
                                    nativeSetupBridgeWindow(surface)
                                    Log.d("TURTLE_LAUNCHER", "✅ Surface 재바인딩 완료 (resume 후)")
                                } catch (e: Exception) {
                                    Log.e("TURTLE_LAUNCHER", "Surface 재바인딩 실패: ${e.message}", e)
                                }
                            }
                        },
                        onSurfaceChanged = { w, h -> sendScreenSize(w, h) },
                        onSurfaceDestroyed = {
                            Log.d("TURTLE_LAUNCHER", "Surface destroyed — JVM 유지")
                            currentSurface = null
                        },
                    )
                    // GameControllerOverlay() ← 제거

                    // 부팅 로딩 오버레이 (첫 프레임 렌더링 전까지 표시)
                    if (showBootOverlay) {
                        MinecraftBootOverlay(
                            modCount = bootModCount,
                            maxDelayMinutes = bootMaxDelayMin,
                            onClose = { dismissBootOverlay("user-close") },
                        )
                    }

                    // ☰ 인게임 메뉴(설정 + 온라인 LAN). GameControllerView 의 ☰ 버튼으로 열림.
                    if (showInGameMenu) {
                        InGameMenuOverlay(
                            activity = this@MinecraftActivity,
                            userName = currentPlayerName(),
                            controllerVisible = gameControllerView?.isControllerVisible ?: true,
                            onToggleController = { gameControllerView?.toggleControllerVisible() },
                            onControlPresetChange = {
                                val preset = gameControllerView?.cycleControlPreset()
                                if (preset != null) {
                                    Toast.makeText(this@MinecraftActivity, "Control preset: $preset", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onClose = { showInGameMenu = false },
                        )
                    }

                    // ⚠️ 기기 비호환으로 비활성화된 모드 안내(첫 프레임 후 1회).
                    if (showDisabledModsPopup && disabledModsList.isNotEmpty()) {
                        DisabledModsOverlay(
                            disabled = disabledModsList.toList(),
                            onClose = { showDisabledModsPopup = false },
                        )
                    }
                    // 🎮 컨트롤러 표시 토글은 GameControllerView 내부(좌상단 버튼)에서 자체 처리.
                }
            }
        }
        if (sdlMode) {
            addContentView(
                androidx.compose.ui.platform.ComposeView(this).apply { setContent { gameContent() } },
                android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        } else {
            setContent { gameContent() }
        }


        // GameControllerView 는 부팅 오버레이가 닫힌 뒤(첫 프레임 이후) 추가한다.
        // 부팅 중에는 오버레이가 컨트롤러 위가 아니라, 컨트롤러 자체가 아직 없도록 한다.

        // 초기 상태 반영 + 디바이스 변화 감지
        setupInputDeviceWatching()
        installPhysicalKeyboardInterceptor()

        if (sdlMode) {
            launchWhenSdlSurfaceReady()
            watchSdlWindowForBootOverlay()
        }

        setupBootOverlay()
    }

    /**
     * SDL 모드에서 부팅 오버레이를 닫는 신호.
     *
     * ⚠️ 기존 신호(egl_bridge.c 의 첫 swap → onFirstFrameRendered)는 PojavLauncher 스택에서
     *    오는 것이라 26.3 경로에는 오지 않는다. 대신 SDL 창이 생겼는지를 본다 — 창이 서면
     *    게임이 그리기 시작한 것이다.
     */
    private fun watchSdlWindowForBootOverlay() {
        // 첫 프레임이 화면에 나가면 닫는다. 신호는 MobileGlues 의 스왑 수다.
        // ⚠️ UI 스레드에서 SDL 을 조회하지 않는다 — 게임이 SDL 락을 쥐고 창을 만드는 순간
        //    그 조회가 걸려 메인 스레드가 통째로 멈췄다(실측). 백그라운드에서 센 값만 본다.
        lifecycleScope.launch(Dispatchers.Default) {
            repeat(1200) {   // 최대 5분
                if (bootOverlayDismissed) return@launch
                if (runCatching { nativeMgSwapCount() }.getOrDefault(0) > 0) {
                    withContext(Dispatchers.Main) { dismissBootOverlay("first-frame") }
                    return@launch
                }
                kotlinx.coroutines.delay(250)
            }
            withContext(Dispatchers.Main) { dismissBootOverlay("timeout") }
        }
    }

    /**
     * SDL 모드의 JVM 시작 시점 — SDL 이 만든 Surface 가 크기까지 확정된 다음이다.
     *
     * ⚠️ surfaceCreated 가 아니라 surfaceChanged 를 기다린다. SDL 의 안드로이드 백엔드는
     *    창 크기를 자바 쪽 surfaceChanged 로 받으므로, 그 전에 SDL_CreateWindow 가 불리면
     *    0x0 짜리 창이 잡힌다.
     */
    private fun launchWhenSdlSurfaceReady() {
        val surfaceView = mSurface as SurfaceView
        // 기존 코드가 게임 화면을 이 태그로 찾는다(소프트키보드 포커스·해상도 배율 등).
        surfaceView.tag = "minecraft_surface"
        // ⚠️ SDL 자기 콜백만 믿으면 안 된다. 이 뷰는 SDL 레이아웃에서 떼어 Compose 트리로
        //    옮겨지는데, 그 과정에서 SDL 쪽 surfaceCreated/Changed 가 오지 않는 경우가 있다
        //    (실측: destroyed 만 찍히고 created 가 없었다). 그러면 SDL 창 크기가 0 으로 남아
        //    게임이 한 프레임도 내놓지 않는다. 우리가 받은 것을 그대로 넘겨 준다.
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
                currentSurface = holder.surface
                if (jvmStarted) return
                jvmStarted = true
                Log.d("TURTLE_LAUNCHER", "🪟 SDL surface 준비됨 (${w}x$h) — JVM 시작")
                setupAndLaunch(holder.surface)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                currentSurface = null
            }
        })
    }

    /**
     * 26.3 용 LWJGL 3.4.3 네이티브를 앱 전용 폴더로 꺼내고 그 경로를 돌려준다.
     *
     * ⚠️ jniLibs 에 둘 수 없다. 이름이 기존 3.3.3 판(liblwjgl.so 등)과 똑같아서 한 폴더에
     *    공존하지 못한다. 그래서 assets 에 넣고 버전별 폴더로 꺼낸 뒤 org.lwjgl.librarypath
     *    맨 앞에 둔다.
     */
    /**
     * 26.3 GL 백엔드가 쓸 GL 라이브러리(MobileGlues)의 절대경로. 없으면 null.
     *
     * ⚠️ 절대경로여야 한다 — LWJGL 과 SDL 이 **같은 파일**을 열어야 주소 비교를 통과한다.
     */
    private fun sdlGlLibPath(): String? {
        // 앱에 넣은 것을 먼저 쓴다 — 버전을 우리가 고정할 수 있다(26.3 은 최신판이 필요하다).
        val bundled = File(applicationInfo.nativeLibraryDir, "libmobileglues.so")
        return if (bundled.exists()) bundled.absolutePath else null
    }

    /**
     * 26.3 용 MobileGlues 설정. **매 실행 덮어쓴다.**
     *
     * ⚠️ `enableNoError` 를 Level2(=3) 로 둬야 한다. 26.3 의 렌더 엔진은 셰이더/프로그램을
     *    무더기로 만들어 보면서 실패를 감수하는데, MobileGlues 가 그 오류를 그대로 올리면
     *    "Failed to load required shader programs" 로 부팅이 끊긴다(업스트림도 26.3 부터는
     *    이 옵션을 켜라고 안내한다).
     * ⚠️ customGLVersion=46 은 실험값이다. 기본(4.0)에서는 마인크래프트가 만든 셰이더를
     *    MobileGlues 가 다시 쓰다가 깨뜨린다(_uniform 미선언 등). 높은 버전으로 보고하면
     *    다시 쓰는 양이 줄어드는지 보려는 것이다.
     */
    /**
     * MobileGlues 설정·로그 폴더. **외부 저장소**에 둔다 — 릴리스 빌드는 run-as 가 막혀서
     * 내부 cacheDir 의 latest.log 를 꺼내 볼 수 없다(26.3 셰이더 문제를 쫓다 겪었다).
     */
    private fun mobileGluesDir(): File =
        File(getExternalFilesDir(null) ?: cacheDir, "MobileGlues").apply { mkdirs() }

    /** MobileGlues 가 열리기 전에 설정 폴더를 알려준다. */
    private fun applyMobileGluesDirEnv() {
        runCatching { JavaNativeLauncher().nativeSetEnv("MG_DIR_PATH", mobileGluesDir().absolutePath) }
            .onFailure { Log.w("TURTLE_LAUNCHER", "MG_DIR_PATH 설정 실패: ${it.message}") }
    }

    /**
     * MobileGlues 설정을 **실행할 때마다** 이 버전에 맞게 다시 쓴다(열리는 순간 읽는다).
     *
     * ⚠️ 폴더가 하나라서, 26.3 설정을 한 번 쓰면 다른 버전에도 그대로 남았다. 실측(1.21.1
     *    코블버스): Iris 셰이더팩이 Mali 에서 컴파일에 실패했는데(noperspective 미지원) 오류
     *    숨김(enableNoError 3) 때문에 Iris 가 실패를 모르고 깨진 프로그램으로 그려서 월드
     *    텍스처가 하나도 안 나왔다. 셰이더팩 전체를 헛컴파일하느라 부팅·입장도 1분씩 늘었다.
     *    오류를 알려주면(1) Iris 가 첫 실패에서 셰이더를 끄고 바닐라로 그린다.
     */
    private fun writeMobileGluesConfig() {
        // 26.3 은 이 값(오류 숨김 · GL 4.6)으로 부팅·렌더링을 확인했다.
        val (noError, glVersion) = if (sdlMode) 3 to 46 else 1 to 0
        File(mobileGluesDir(), "config.json").writeText(
            """
            {
              "enableANGLE": 0,
              "enableNoError": $noError,
              "enableExtTimerQuery": 0,
              "enableExtComputeShader": 1,
              "enableExtDirectStateAccess": 0,
              "maxGlslCacheSize": 256,
              "multidrawMode": 0,
              "angleDepthClearFixMode": 0,
              "customGLVersion": $glVersion,
              "fsr1Setting": 0
            }
            """.trimIndent()
        )
    }

    private fun prepareLwjgl34Natives(): File {
        val outDir = File(filesDir, "lwjgl341")
        outDir.mkdirs()
        val abi = Build.SUPPORTED_ABIS.firstOrNull { supported ->
            supported == "arm64-v8a" || supported == "armeabi-v7a" ||
                supported == "x86" || supported == "x86_64"
        } ?: "arm64-v8a"
        val assetDir = "lwjgl341/$abi"
        // The current 3.4.3 payload is arm64-only. For the legacy ABIs, use the matching
        // ABI's packaged LWJGL/native set instead of returning an empty directory and then
        // passing nonexistent absolute paths to the game JVM.
        if (assets.list(assetDir).isNullOrEmpty() && abi != "arm64-v8a") {
            Log.w("TURTLE_LAUNCHER", "No 3.4.3 native asset for $abi; falling back to $applicationInfo.nativeLibraryDir")
            return File(applicationInfo.nativeLibraryDir)
        }
        // ⚠️ 기준은 **설치 시각**이다. 버전코드로 했더니 개발 중 재설치에서 그대로 스킵돼,
        //    새로 넣은 liblwjgl_opengl.so 가 안 풀리고 옛 3.3.3 판이 먼저 잡혔다(실측).
        //    (assets 는 압축돼 있을 수 있어 크기 비교는 못 믿는다)
        val stamp = File(outDir, ".stamp")
        val want = runCatching {
            packageManager.getPackageInfo(packageName, 0).lastUpdateTime.toString()
        }.getOrDefault(BuildConfig.VERSION_CODE.toString())
        if (runCatching { stamp.readText() }.getOrNull() == want) return outDir

        assets.list(assetDir)?.forEach { name ->
            val out = File(outDir, name)
            assets.open("$assetDir/$name").use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
            Log.d("TURTLE_LAUNCHER", "📦 LWJGL 3.4.3 네이티브 추출: $name (${out.length()} B)")
        }
        stamp.writeText(want)
        return outDir
    }

    /** Terracotta 등에서 쓸 현재 플레이어 이름. 저장된 세션이 없으면 null(→ 익명). */
    private fun currentPlayerName(): String? =
        try {
            MicrosoftAuthManager.loadSession(this)?.username
                ?: ThirdPartyAuthManager.load(this)?.profileName
                ?: LocalAccountManager.load(this)?.username
        } catch (_: Exception) { null }

    private fun extractAuthlibInjector(): File? {
        val target = File(filesDir, "runtime/authlib-injector.jar")
        if (target.isFile && target.length() > 0) return target
        return runCatching {
            target.parentFile?.mkdirs()
            assets.open("authlib-injector.jar").use { input ->
                target.outputStream().use { output -> input.copyTo(output, 16 * 1024) }
            }
            target.takeIf { it.isFile && it.length() > 0 }
        }.getOrNull()
    }

    /** 게임 컨트롤러 오버레이 뷰를 화면에 추가한다. 첫 프레임 이후 1회 호출. */
    private fun attachGameControllerView() {
        if (gameControllerView != null) return   // 중복 추가 방지
        gameControllerView = GameControllerView(this).also { view ->
            view.onMenuClick = { showInGameMenu = true }
            addContentView(
                view,
                android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        // 현재 입력 상태 즉시 반영(컨트롤러가 늦게 붙으므로).
        // setupInputDeviceWatching() 을 다시 부르면 리스너/폴링이 중복 등록되므로,
        // 가시성 갱신만 호출한다. (리스너·폴링은 onCreate 에서 이미 살아있음)
        try { updateGameControllerVisibility() } catch (_: Throwable) {}
    }


    /**
     * 부팅 로딩 오버레이 준비:
     *  - mods 폴더의 .jar 개수를 세어 모드팩 여부/지연 안내 시간 결정
     *  - 첫 프레임 콜백 등록 (네이티브가 첫 swap 시 호출 → 오버레이 닫기)
     *  - 콜백이 오지 않을 경우를 대비한 타임아웃 안전망
     */
    private fun setupBootOverlay() {
        // 모드 개수 계산 (mcDir/mods 의 .jar). 0 이면 바닐라로 간주.
        val count = try {
            val base = instanceDir?.let { File(it) }
                ?: customGameDir?.let { File(it) }
                ?: File(getExternalFilesDir(null), "instances/vanilla_$versionId")
            File(base, "mods").listFiles()
                ?.count { it.isFile && it.extension.equals("jar", ignoreCase = true) }
                ?: 0
        } catch (_: Exception) { 0 }

        bootModCount = count
        // 대략적 안내값: 기본 2분 + 모드 50개당 약 1분, 최대 10분으로 캡.
        bootMaxDelayMin = (2 + count / 50).coerceIn(2, 10)

        // 첫 프레임 콜백 등록 (렌더 스레드에서 불리므로 UI 스레드로 전환)
        MinecraftActivityBridge.setFirstFrameListener {
            runOnUiThread {
                dismissBootOverlay("first-frame")
                // 기기 비호환으로 비활성화된 모드가 있으면, 게임 진입 직후 1회 안내.
                if (disabledModsList.isNotEmpty()) {
                    showDisabledModsPopup = true
                }
            }
        }

        MinecraftActivityBridge.setFpsListener { fps ->
            runOnUiThread { gameControllerView?.updateFps(fps) }
        }

        // 타임아웃 안전망: 콜백이 어떤 이유로 안 와도 무한 로딩이 되지 않게.
        // 모드 수에 비례해 넉넉히 잡되(분→ms), 최소 30초.
        val timeoutMs = (bootMaxDelayMin.toLong() * 60_000L).coerceAtLeast(30_000L)
        window.decorView.postDelayed({
            if (!bootOverlayDismissed) {
                Log.w("TURTLE_LAUNCHER", "부팅 오버레이 타임아웃(${timeoutMs}ms) — 강제 닫기")
                dismissBootOverlay("timeout")
            }
        }, timeoutMs)
    }

    private fun dismissBootOverlay(reason: String) {
        if (bootOverlayDismissed) return
        bootOverlayDismissed = true
        showBootOverlay = false
        Log.d("TURTLE_LAUNCHER", "부팅 오버레이 닫음 ($reason)")
        // 오버레이가 사라진 뒤에야 게임 컨트롤러를 화면에 올린다.
        attachGameControllerView()
    }

    private fun setupInputDeviceWatching() {
        val im = getSystemService(INPUT_SERVICE)
                as android.hardware.input.InputManager

        // 초기 상태 반영
        updateGameControllerVisibility()

        // 디바이스 add/remove/change 감지
        inputDeviceListener = object : InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) {
                Log.d("TURTLE_LAUNCHER", "🎮 입력 디바이스 추가: id=$deviceId")
                updateGameControllerVisibility()
            }
            override fun onInputDeviceRemoved(deviceId: Int) {
                Log.d("TURTLE_LAUNCHER", "🎮 입력 디바이스 제거: id=$deviceId")
                updateGameControllerVisibility()
            }
            override fun onInputDeviceChanged(deviceId: Int) {
                updateGameControllerVisibility()
            }
        }
        im.registerInputDeviceListener(inputDeviceListener, null)

        // ── grab 상태 폴링(가벼움, 500ms) ──
        //   마인크래프트 onGrabStateChanged JNI 콜백은 모듈 레이어 불일치로 NoSuchMethodError 라
        //   신뢰 불가. nativeIsGrabbing()(=pojav_environ->isGrabbing, 정상 갱신)을 폴링해
        //   '첫 grab=true' 를 월드 진입으로 보고, 이후 컨트롤러를 계속 표시(A-1).
        startGrabStatePolling()
    }

    private var lastGrabState: Boolean? = null
    private var imeVisible = false
    private var hasEnteredWorld = false

    private fun startGrabStatePolling() {
        lifecycleScope.launch {
            var shownOnce = false
            while (true) {
                if (jvmStarted) {
                    // JVM 시작 직후 1회: grab 변화가 없어도(타이틀은 grab=false 유지) 컨트롤러를 표시.
                    if (!shownOnce) {
                        shownOnce = true
                        updateGameControllerVisibility()
                    }
                    val grab = isGrabbing
                    if (grab != lastGrabState) {
                        lastGrabState = grab
                        if (grab) hasEnteredWorld = true   // 첫 grab = 월드 진입
                        updateGameControllerVisibility()
                    }
                }
                kotlinx.coroutines.delay(300)
            }
        }
    }

    /**
     * 진짜 외장 물리 키보드 또는 마우스/터치패드가 연결돼 있는지.
     *
     * 휴대폰은 내장 입력장치(gpio-keys, 헤드셋 버튼, PMIC 키, 가상 네비게이션 키 등)를
     * SOURCE_KEYBOARD + KEYBOARD_TYPE_ALPHABETIC 로 잘못 보고하는 경우가 많아,
     * 단순 비트 검사만으로는 오탐이 잦다. 그래서 다음을 모두 만족할 때만 true:
     *   1) 가상 디바이스가 아님(isVirtual == false)
     *   2) 외장 디바이스(isExternal == true) — 내장 컨트롤은 대부분 false 라 걸러짐
     *   3) 알려진 내장 디바이스 이름 패턴이 아님(gpio-keys, headset, pmic, mtk-kpd 등)
     *   4) 실제 알파벳 키보드(전체 SOURCE_KEYBOARD 비트) 또는 마우스/터치패드 포인터
     *
     * isExternal 이 일부 기기에서 신뢰되지 않을 수 있으나, 이 함수는 더 이상 컨트롤러
     * 가시성을 좌우하지 않고(우측 상단 토글이 그 역할) 소프트키보드 표시 판단에만 쓰이므로
     * 보수적으로 "확실한 외장만" 인정해도 안전하다.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun hasHardwareKeyboardOrMouse(): Boolean {
        val im = getSystemService(INPUT_SERVICE)
                as android.hardware.input.InputManager

        // 내장/가짜 입력장치로 흔히 보고되는 이름 패턴(소문자 비교)
        val internalNameHints = listOf(
            "gpio", "headset", "headphone", "pmic", "kpd", "keypad",
            "mtk-kpd", "qpnp", "pm8", "lid", "hall", "accelerometer",
            "gsensor", "virtual", "uinput", "input device", "back phone"
        )

        for (id in im.inputDeviceIds) {
            val dev = im.getInputDevice(id) ?: continue
            if (dev.isVirtual) continue

            // 외장 디바이스만 인정(API 16+ 의 isExternal; 일부 기기 미신뢰 가능 → try)
            val external = try { dev.isExternal } catch (_: Throwable) { false }
            if (!external) continue

            val name = (dev.name ?: "").lowercase()
            if (internalNameHints.any { name.contains(it) }) continue

            val src = dev.sources

            // 진짜 알파벳 키보드: 전체 SOURCE_KEYBOARD 비트가 켜져 있어야 함
            val isRealKeyboard = (src and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD
                    && dev.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC

            // 진짜 포인터: 마우스 / 상대좌표 마우스 / 터치패드
            val isPointer = (src and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
                    || (src and InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE
                    || (src and InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD

            if (isRealKeyboard || isPointer) {
                Log.d("TURTLE_LAUNCHER",
                    getString(R.string.external_input_detected_log, dev.name, src.toString(16), dev.keyboardType))
                return true
            }
        }
        return false
    }


    /**
     * GameControllerView 상태 갱신.
     *
     * 컨트롤러 표시 ON/OFF 는 이제 GameControllerView 안의 좌상단 🎮 토글이 자체적으로
     * 관리하므로, 여기서는 뷰를 항상 VISIBLE 로 두고(토글 버튼이 늘 보여야 하므로)
     * escOnly(버튼 종류) 판정만 전달한다.
     *  - grab + IME 닫힘 → 전체 버튼 (WASD 등 플레이 조작)
     *  - 그 외(타이틀/인벤토리/ESC메뉴/채팅) → ESC + 키보드 버튼만
     *
     * (이전엔 물리 키보드/마우스 자동 감지로 뷰 전체를 숨겼으나, 일부 휴대폰이 내장
     *  입력장치를 물리 키보드로 오탐해 컨트롤러가 안 뜨는 문제가 있어 명시적 토글로 전환.
     *  감지 함수 hasHardwareKeyboardOrMouse 는 소프트키보드 표시 판단 등 다른 용도로만 사용.)
     */
    internal fun updateGameControllerVisibility() {
        val fullControl = isGrabbing && !imeVisible
        runOnUiThread {
            val view = gameControllerView ?: return@runOnUiThread
            view.setEscOnlyMode(!fullControl)   // grab/IME 아니면 ESC + 키보드만
            // 뷰 자체는 항상 표시 — 내부 🎮 토글이 실제 버튼 노출을 제어.
            if (view.visibility != View.VISIBLE) {
                view.visibility = View.VISIBLE
            }
            // 물리 마우스용 OS 포인터 표시 제어:
            //   - 인게임(grab): 마인크래프트가 자체 십자선을 그리므로 OS 화살표는 숨김
            //     (둘이 겹쳐 보이는 것 방지). 또한 grab 진입 시 마우스 델타 기준점 리셋.
            //   - 메뉴(비 grab): OS 화살표 보이게 두어 메뉴 클릭이 자연스럽게.
            updatePointerIconForGrab(isGrabbing)
        }
    }

    /** grab 상태에 맞춰 SurfaceView 의 OS 포인터 아이콘을 숨기거나(NULL) 기본(ARROW)으로. */
    private fun updatePointerIconForGrab(grabbing: Boolean) {
        // grab 전환 시 마우스 델타 기준점 리셋(시점이 확 튀지 않도록).
        lastMouseX = -1f
        lastMouseY = -1f
        if (pointerHidden == grabbing) return
        pointerHidden = grabbing
        val sv = window.decorView.findViewWithTag<View>("minecraft_surface") ?: return
        sv.pointerIcon = if (grabbing) {
            // 빈(NULL) 아이콘 = 커서 숨김
            android.view.PointerIcon.getSystemIcon(this, android.view.PointerIcon.TYPE_NULL)
        } else {
            android.view.PointerIcon.getSystemIcon(this, android.view.PointerIcon.TYPE_ARROW)
        }
        applyPointerCapture(grabbing)
    }

    // ── 물리 마우스: 포인터 캡처(무한 상대이동) ───────────────────────────
    //
    // grab 중에 onGenericMotionEvent 의 event.x/y 델타로 시점을 돌리면, OS 커서가
    // 화면 가장자리에 닿는 순간 좌표가 더 안 변해서 **델타가 0 이 되고 시점 회전이 멈춘다**.
    // 데스크톱에서 마우스를 계속 같은 방향으로 돌릴 수 있는 것과 완전히 다른 동작이라,
    // 물리 마우스로 플레이가 사실상 불가능했다.
    //
    // 안드로이드의 정답은 Pointer Capture(API 26+, minSdk 26 이라 항상 사용 가능):
    // 캡처하면 커서가 사라지고 AXIS_RELATIVE_X/Y 로 **화면 경계와 무관한 상대 델타**가
    // 들어온다. grab 이 풀리면 캡처를 해제해 메뉴에서 절대좌표 커서로 되돌린다.
    private var capturedListenerInstalled = false

    private fun applyPointerCapture(capture: Boolean) {
        val sv = window.decorView.findViewWithTag<View>("minecraft_surface") ?: return
        if (capture) {
            if (!capturedListenerInstalled) {
                capturedListenerInstalled = true
                sv.setOnCapturedPointerListener { _, event -> handleCapturedPointerEvent(event) }
            }
            // 캡처는 뷰가 포커스를 가지고 있어야 성립한다.
            sv.isFocusableInTouchMode = true
            sv.requestFocus()
            sv.requestPointerCapture()
        } else {
            sv.releasePointerCapture()
        }
    }

    /** 캡처 모드에서 들어오는 마우스 이벤트(상대좌표). */
    private fun handleCapturedPointerEvent(event: MotionEvent): Boolean {
        if (!jvmStarted) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                // 캡처 중엔 절대좌표가 무의미하고 RELATIVE 축만 유효하다.
                val dx = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
                val dy = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
                if (dx != 0f || dy != 0f) moveLookBy(dx, dy)
                return true
            }
            MotionEvent.ACTION_BUTTON_PRESS -> {
                glfwButtonForActionButton(event.actionButton)
                    ?.let { sendMouseButton(it, GLFW_PRESS) }
                return true
            }
            MotionEvent.ACTION_BUTTON_RELEASE -> {
                glfwButtonForActionButton(event.actionButton)
                    ?.let { sendMouseButton(it, GLFW_RELEASE) }
                return true
            }
            MotionEvent.ACTION_SCROLL -> {
                val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if (v != 0f) sendMouseScroll(0f, v)
                return true
            }
        }
        return false
    }

    /**
     * 시점을 상대 델타만큼 움직인다(캡처된 마우스 · 게임패드 오른쪽 스틱 공용).
     * grab 이 아니면 메뉴 커서를 화면 안에서 움직이는 용도로 쓴다.
     */
    internal fun moveLookBy(dx: Float, dy: Float) {
        // 26.3 은 grab 중에 절대 좌표를 보지 않는다 — MouseHandler.onMove 가 xrel/yrel 만 쓴다.
        // 커서를 워프시키면 시점이 튀므로 상대 델타로 넣는다.
        if (sdlMode && isGrabbing) {
            sendCursorDelta(dx * MOUSE_SENSITIVITY, dy * MOUSE_SENSITIVITY)
            return
        }
        currentCursorX += dx * MOUSE_SENSITIVITY
        currentCursorY += dy * MOUSE_SENSITIVITY
        if (!isGrabbing) {
            // 메뉴에서는 커서가 화면 밖으로 나가면 다시 못 들어오므로 가둔다.
            val sv = window.decorView.findViewWithTag<View>("minecraft_surface")
            val w = (sv?.width ?: 0).toFloat()
            val h = (sv?.height ?: 0).toFloat()
            if (w > 0f) currentCursorX = currentCursorX.coerceIn(0f, w)
            if (h > 0f) currentCursorY = currentCursorY.coerceIn(0f, h)
        }
        sendCursorPos(currentCursorX, currentCursorY)
    }

    private fun setupAndLaunch(surface: Surface) {
        val nativesDir = File(applicationContext.filesDir, "natives")

        // ★ mcVersion 기반으로 Java major 결정
        javaMajor = MinecraftJREPreparer.pickJavaMajor(versionId)
        Log.d("TURTLE_LAUNCHER", "선택된 Java major: $javaMajor (mc=$versionId)")

        // turtlejvm 은 반드시 떠야 하므로 별도 처리
        try {
            System.loadLibrary("turtlejvm")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("TURTLE_LAUNCHER", "❌ libturtlejvm.so 로드 실패 — 진행 불가: ${e.message}", e)
            return
        }


        var renderer = resolveRendererForVersion()

        // ⚠️ 26.3 은 Vulkan 을 먼저 시도하고, 기기가 못 받으면 **GL 백엔드로 폴백**한다.
        //    (Mali-G57 실측: Vulkan 1.2·dynamicRendering·synchronization2 미지원 → Vulkan 탈락)
        //    그 GL 백엔드는 "LWJGL 이 연 GL 라이브러리"와 "SDL 이 연 GL 라이브러리"가 같은
        //    파일이어야 통과한다(GlBackend.loadLibrary 가 glGetError 주소를 비교한다).
        //    그래서 EGL 진입점까지 직접 내보내는 MobileGlues 만 쓸 수 있다 — GL4ES 계열은
        //    egl* 를 안 내보내서 SDL 이 그 파일로 컨텍스트를 만들지 못한다.
        if (sdlMode) {
            writeMobileGluesConfig()
            // ⚠️ **로드보다 먼저** 심어야 한다. MobileGlues 는 열리는 순간 설정을 읽는데,
            //    그때 MG_DIR_PATH 가 없으면 기본값(/sdcard/MG)의 남의 앱 설정을 집어간다
            //    — 실측: enableNoError=0 인 옛 config 를 읽어 우리 설정이 통째로 무시됐다.
            applyMobileGluesDirEnv()
            val mgPath = sdlGlLibPath()
            if (mgPath != null) {
                // info_getter 가 libmobileglues.so 의 의존성이다 — 먼저 올린다.
                runCatching { System.load(File(File(mgPath).parent, "libmobileglues_info_getter.so").absolutePath) }
                    .onFailure { Log.d("TURTLE_LAUNCHER", "info_getter 로드 스킵: ${it.message}") }
                runCatching { System.load(mgPath) }
                    .onSuccess { Log.d("TURTLE_LAUNCHER", "✅ 26.3 GL 백엔드용 MobileGlues 로드: $mgPath") }
                    .onFailure { Log.w("TURTLE_LAUNCHER", "⚠️ MobileGlues 로드 실패: ${it.message}") }
            } else {
                Log.w("TURTLE_LAUNCHER", "⚠️ MobileGlues 를 찾지 못했다 — Vulkan 이 안 되는 기기면 화면이 안 뜬다")
            }
        }
        else when (renderer.id) {
            "mobileglues" -> {
                writeMobileGluesConfig()
                applyMobileGluesDirEnv()
                // info_getter 가 libmobileglues.so 의 의존성이라 먼저 올린다.
                loadSoSafely(File(applicationInfo.nativeLibraryDir, "libmobileglues_info_getter.so"), required = false)
                if (loadSoSafely(File(applicationInfo.nativeLibraryDir, "libmobileglues.so"), required = true)) {
                    Log.d("TURTLE_LAUNCHER", "✅ 렌더러: MobileGlues")
                }
            }
            "zink" -> {
                try { System.loadLibrary("vulkan") } catch (_: Throwable) {}
                if (loadSoSafely(File(nativesDir, "libOSMesa.so"), required = true)) {
                    Log.d("TURTLE_LAUNCHER", "✅ 렌더러: Zink")
                }
            }
            "ltw" -> {
                // LTW is a bundled renderer. Load it before the game JVM so
                // its EGL entry points are available to the native bridge.
                if (loadSoSafely(File(nativesDir, "libltw.so"), required = true)) {
                    Log.d("TURTLE_LAUNCHER", "✅ 렌더러: LTW (libltw.so)")
                }
            }
            "krypton" -> {
                // Krypton(NG-GL4ES) 은 내부 번들 렌더러 — 런처 자신의 nativeLibraryDir 에서 로드.
                // (GL4ES 와 동일 방식. 외부 플러그인 아님. libng_gl4es.so 를 jniLibs 에 번들해 둔다.)
                if (loadSoSafely(File(nativesDir, "libng_gl4es.so"), required = true)) {
                    Log.d("TURTLE_LAUNCHER", "✅ 렌더러: Krypton Wrapper (libng_gl4es.so)")
                }
            }
            else -> {
                // gl4es / gl4es_desktop / holy_gl4es
                if (loadSoSafely(File(nativesDir, "libgl4es_114.so"), required = true)) {
                    Log.d("TURTLE_LAUNCHER", "✅ 렌더러: GL4ES")
                }
            }
        }

        // 공통 .so — 하나가 실패해도 다음 것은 계속 시도
        loadSoSafely(File(nativesDir, "libopenal.so"), required = false)
        if (!sdlMode) {
            // ⚠️ GLFW 스택은 26.3 미만 전용이다. libglfw.so / libpojavexec.so 는 자기들이
            //    창과 입력을 소유한다고 가정하므로(pojavInit 라우팅) SDL 경로에서 같이 올리면
            //    같은 화면을 두 주인이 다투게 된다. liblwjgl.so 도 3.3.3 판이라 3.4.3 jar 와
            //    섞이면 클래스 초기화에서 죽는다.
            loadSoSafely(File(nativesDir, "libglfw.so"), required = true)
            loadSoSafely(File(nativesDir, "libpojavexec.so"), required = true)
            loadSoSafely(File(nativesDir, "liblwjgl.so"), required = false)
            loadSoSafely(File(nativesDir, "liblwjgl_opengl.so"), required = false)
        }

        DnsHookNative.setup(this)

        // ── AWT stub preload (실패해도 무시 — JNI 바인딩 불일치여도 핵심 .so 는 이미 떠 있음) ──
        try {
            JavaNativeLauncher.preloadAwtStubs(applicationInfo.nativeLibraryDir)
        } catch (e: UnsatisfiedLinkError) {
            Log.w("TURTLE_LAUNCHER", "⚠️ preloadAwtStubs 바인딩 실패 (무시 가능): ${e.message}")
        } catch (e: Throwable) {
            Log.w("TURTLE_LAUNCHER", "⚠️ preloadAwtStubs 예외 (무시 가능): ${e.message}")
        }

        // SDL 모드에서는 창을 SDL 이 갖는다 — 브릿지 창(GLFW 대역)을 만들면 안 된다.
        if (!sdlMode) {
            try {
                nativeSetupBridgeWindow(surface)
                Log.d("TURTLE_LAUNCHER", "✅ setupBridgeWindow 완료")
            } catch (e: Exception) {
                Log.e("TURTLE_LAUNCHER", "setupBridgeWindow 실패: ${e.message}", e)
            }
        }

        startCrashWatcher()
        startMinecraft()
    }

    /**
     * .so 한 개를 안전하게 로드. 이미 로드되어 있거나 파일이 없으면 false 반환.
     * required=true 인데 실패하면 ERROR 로그, 아니면 WARN 로그만 남기고 계속 진행.
     */
    private fun loadSoSafely(soFile: File, required: Boolean): Boolean {
        if (!soFile.exists()) {
            if (required) Log.e("TURTLE_LAUNCHER", "❌ 필수 .so 파일 없음: ${soFile.name}")
            else Log.w("TURTLE_LAUNCHER", "⚠️ .so 파일 없음 (스킵): ${soFile.name}")
            return false
        }
        return try {
            System.load(soFile.absolutePath)
            Log.d("TURTLE_LAUNCHER", "📦 .so 로드: ${soFile.name}")
            true
        } catch (e: UnsatisfiedLinkError) {
            // 이미 로드된 경우도 여기로 옴 — 무해
            val msg = e.message ?: ""
            if (msg.contains("already loaded", ignoreCase = true)) {
                Log.d("TURTLE_LAUNCHER", "ℹ️ 이미 로드됨: ${soFile.name}")
                true
            } else {
                if (required) Log.e("TURTLE_LAUNCHER", "❌ ${soFile.name} 로드 실패: $msg", e)
                else Log.w("TURTLE_LAUNCHER", "⚠️ ${soFile.name} 로드 실패 (무시): $msg")
                false
            }
        }
    }


    private fun startCrashWatcher() {
        val instanceBase = instanceDir?.let { File(it) }
            ?: customGameDir?.let { File(it) }  // instanceDir 없을 때만 fallback
            ?: File(getExternalFilesDir(null), "instances/vanilla_$versionId")

        Thread {
            val crashDir = File(instanceBase, "crash-reports")
            val existingFiles = crashDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()

            // JVM이 실행되는 동안 새 크래시 파일 감시
            while (!isFinishing) {
                Thread.sleep(1000)
                val newCrash = crashDir.listFiles()
                    ?.any { it.extension == "txt" && !existingFiles.contains(it.name) } == true
                if (newCrash) {
                    Log.d("TURTLE_LAUNCHER", "새 크래시 감지!")
                    val intent = Intent(this, CrashReportActivity::class.java).apply {
                        putExtra("instance_dir", instanceBase.absolutePath)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    startActivity(intent)
                    break
                }
            }
        }.start()
    }

    private fun sendScreenSize(width: Int, height: Int) {
        if (sdlMode) { nativeSdlSetFramebufferSize(width, height); return }
        try {
            Class.forName("org.lwjgl.glfw.CallbackBridge")
                .getMethod("nativeSendScreenSize", Int::class.java, Int::class.java)
                .invoke(null, width, height)
        } catch (_: Exception) {}
    }

    /**
     * 전체 화면(몰입형) on/off.
     *  - on  : 상태바·내비게이션바 숨김(가장자리 스와이프하면 잠깐 나타남). hideNavigation() 과 동일 상태.
     *  - off : 시스템 바를 다시 표시. (게임은 edge-to-edge 라 바 뒤로 그려짐)
     */
    private fun applyFullscreen(enable: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (enable) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    /**
     * 렌더 해상도 배율 적용. SurfaceView 버퍼를 줄이면 GPU 가 더 적은 픽셀을 그려 FPS 가 오르고,
     * SurfaceView 가 화면 크기로 다시 늘려서 보여준다. (ZalithLauncher2 의 Resolution 과 동일 방식)
     * 버퍼가 작아지면 onSurfaceChanged 로 축소된 크기가 전달되어 sendScreenSize 도 자동으로 맞춰진다.
     * 100% 면 네이티브 그대로라 손대지 않는다.
     *
     * ⚠️ 버퍼 크기는 **뷰 크기를 따라가게** 한다. 예전에는 표면이 처음 생길 때의 화면 크기
     *    (displayMetrics)로 한 번만 정했는데, 자동 회전이 꺼진 세로 기본 태블릿에서는 표면이
     *    세로로 먼저 생긴 뒤 가로로 돈다 — 버퍼가 세로 모양(660x1002)으로 굳은 채 가로 화면
     *    (1920x1200)에 늘어나 게임 화면이 위아래로 찌그러졌다(실측, 1.21.1 · 배율 55%).
     */
    private fun applyRenderResolutionScale() {
        if (renderScaleApplied) return
        if (renderScalePercent >= 100) { renderScaleApplied = true; return }

        val sv = window.decorView.findViewWithTag<View>("minecraft_surface") as? SurfaceView
        if (sv == null) {
            Log.w("TURTLE_LAUNCHER", "⚠️ 해상도 배율: SurfaceView(minecraft_surface) 를 못 찾음 — 스킵")
            return
        }
        val settings = JvmSettings(resolutionScalePercent = renderScalePercent)
        fun fitTo(viewW: Int, viewH: Int) {
            if (viewW <= 0 || viewH <= 0) return
            val (w, h) = settings.scaledResolution(viewW, viewH)
            sv.holder.setFixedSize(w, h)   // → surfaceChanged → sendScreenSize 로 게임도 따라온다
            Log.i("TURTLE_LAUNCHER",
                getString(R.string.render_resolution_debug_log, renderScalePercent, w, h, viewW, viewH))
        }
        fitTo(sv.width, sv.height)
        sv.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (r - l != oldR - oldL || b - t != oldB - oldT) fitTo(r - l, b - t)
        }
        renderScaleApplied = true
    }

    internal var currentCursorX = 1280f  // 화면 중앙 근처
    internal var currentCursorY = 720f
    internal val MOUSE_SENSITIVITY = 1.5f

    // ── 물리 마우스 상태 ──
    //   onGenericMotionEvent 로 들어오는 외장 마우스 hover/move 의 직전 위치.
    //   인게임(grab)에서는 절대 좌표가 아니라 이 값과의 "델타"로 시점을 돌린다.
    //   -1f = 아직 기준점 없음(다음 이벤트에서 기준만 잡고 델타는 보내지 않음 → 첫 진입 시 획 돎 방지).
    private var lastMouseX = -1f
    private var lastMouseY = -1f
    // 직전에 우리가 마우스를 위해 OS 포인터를 숨겼는지(grab 상태에 따라 토글).
    private var pointerHidden = false

    /**
     * 게임패드 → 키보드/마우스 변환기.
     * 마인크래프트 자바판은 컨트롤러를 네이티브로 지원하지 않으므로 변환이 유일한 방법이다.
     */
    private val gamepad by lazy {
        GamepadHandler(
            sendKey = { key, action -> sendKey(key, action) },
            sendMouseButton = { button, action -> sendMouseButton(button, action) },
            sendScroll = { x, y -> sendMouseScroll(x, y) },
            moveCursorBy = { dx, dy -> moveLookBy(dx, dy) },
        )
    }

    // 마인크래프트 GUI 스케일(options.txt). 핫바 영역 계산에 사용. 게임 실행 시 갱신.
    @Volatile internal var mcGuiScale: Int = 0   // 0 = 자동(auto)

    // 사용자가 인게임 오버레이에서 직접 맞춘 핫바 "터치 영역" 스케일.
    //   0 = 미설정(자동: options.txt guiScale → 없으면 해상도 auto 사용)
    //   1~4 = 그 스케일로 핫바 터치 사각형 강제(게임 GUI Scale 단위와 동일)
    //   화면에 그려지는 핫바 크기는 마인크래프트 옵션에서 바꾸고, 여기선 터치 인식 영역만 맞춘다.
    @Volatile internal var hotbarTouchScaleOverride: Int = 0

    private val hotbarPrefs by lazy {
        getSharedPreferences("ping_ingame", MODE_PRIVATE)
    }

    /** 저장된 핫바 터치 스케일 오버라이드를 읽어 적용(게임 시작 시 1회 호출). */
    internal fun loadHotbarTouchScale() {
        hotbarTouchScaleOverride = hotbarPrefs.getInt("hotbar_touch_scale", 0)
    }

    /** 핫바 터치 스케일 오버라이드 설정(0=자동, 1~4). 즉시 반영 + 저장. */
    internal fun setHotbarTouchScale(scale: Int) {
        val v = scale.coerceIn(0, 4)
        hotbarTouchScaleOverride = v
        hotbarPrefs.edit().putInt("hotbar_touch_scale", v).apply()
    }

    /**
     * 핫바 슬롯 선택 (index 0~8). 화면 하단 핫바 영역 터치 시 호출.
     * 마인크래프트는 1~9 키로 슬롯을 직접 선택하므로 GLFW_KEY_1(49)+index 를 전송.
     */
    internal fun selectHotbarSlot(index: Int) {
        if (index !in 0..8) return
        val key = 49 + index   // GLFW_KEY_1 = 49
        sendKey(key, GLFW_PRESS)
        sendKey(key, GLFW_RELEASE)
    }

    /**
     * 현재 화면 크기 기준 핫바의 화면상 사각형(left,right,top,bottom px)을 계산한다.
     * ZL2 와 동일한 규칙: slotSize = guiScale*20, 핫바너비 = slotSize*9, 화면 하단 중앙.
     *
     * 스케일 우선순위:
     *   1) 사용자 오버라이드(hotbarTouchScaleOverride, 1~4) — 인게임 오버레이에서 맞춘 값
     *   2) options.txt 의 guiScale(mcGuiScale, 1~auto)
     *   3) 해상도 기반 auto
     * 핫바가 화면에 없으면 null.
     */
    internal fun computeHotbarRect(viewW: Int, viewH: Int): android.graphics.RectF? {
        if (viewW <= 0 || viewH <= 0) return null
        val auto = minOf(viewW / 320, viewH / 240).coerceAtLeast(1)
        val scale = when {
            hotbarTouchScaleOverride in 1..4 -> hotbarTouchScaleOverride   // 사용자 지정 우선
            mcGuiScale in 1..auto -> mcGuiScale                            // options.txt
            else -> auto                                                   // 자동
        }
        val slot = scale * 20f
        val total = slot * 9f
        if (total <= 0f || total > viewW) return null
        val left = (viewW - total) / 2f
        val right = left + total
        // 핫바는 화면 맨 아래에서 살짝 위. MC 기본은 바닥에서 약 (slot/ ... ) 위지만,
        //   터치 편의상 바닥 ~ slotHeight*1.0 영역으로 잡는다.
        val bottom = viewH.toFloat()
        val top = bottom - slot
        return android.graphics.RectF(left, top, right, bottom)
    }


    /**
     * PojavLauncher patched lwjgl-glfw-classes.jar 에 누락된 GLFW 3.4 API 를
     * ASM 으로 노옵 스텁 주입.
     *
     * 1.21.5+ (특히 26w14a) 가 부팅 단계에서 호출하는 API:
     *   - glfwPlatformSupported(int)Z   ← 26w14a 가 NoSuchMethodError 로 죽는 지점
     *   - glfwGetPlatform()I
     *   - glfwFocusWindow / glfwHideWindow / glfwMaximizeWindow / glfwRestoreWindow (J)V
     *
     * 실제 GLFW 백엔드는 어차피 libglfw.so/libpojavexec.so 가 자체 구현이라,
     * 노옵 스텁이 있어도 MC 가 부팅 단계는 통과한다. HiDPI / IME 같은 부가 기능은
     * 동작 안 할 수 있지만 게임 자체는 켜짐.
     */
    /**
     * lwjgl-glfw-classes.jar 안에 GLFW 3.4 신규 API 스텁이 들어있는지
     * 실제 클래스 바이트를 검사해서 보장한다. 마커 파일에 의존하지 않음 —
     * 런처 업데이트로 필요한 스텁 목록이 늘어나도 자동 재패치되도록.
     */
    internal fun sendMouseButton(button: Int, action: Int) {
        Log.d("TURTLE_LAUNCHER", "sendMouseButton: btn=$button action=$action")
        if (sdlMode) { nativeSdlSendMouseButton(button, action); return }
        nativeSendMouseButton(button, action, 0)
    }

    internal fun sendCursorPos(x: Float, y: Float) {
        Log.d("TURTLE_LAUNCHER", "sendCursorPos: x=$x y=$y")
        if (sdlMode) { nativeSdlSendCursorPos(0, x, y); return }
        nativeSendCursorPos(x, y)
    }

    /** 시점 회전용 상대 이동. grab 중에는 26.3 이 xrel/yrel 만 읽는다. */
    private fun sendCursorDelta(dx: Float, dy: Float) {
        nativeSdlSendCursorPos(1, dx, dy)
    }

    internal fun sendKey(glfwKeyCode: Int, action: Int) {

        Log.d("TURTLE_LAUNCHER", "sendKey: $glfwKeyCode action=$action")

        val scancode = getScancode(glfwKeyCode)

        if (sdlMode) nativeSdlSendKey(glfwToSdlScancode(glfwKeyCode), action)
        else nativeSendKey(glfwKeyCode, scancode, action, 0)

        // 채팅/명령어 키 → 소프트 키보드 자동 표시 (T=84, /=47).
        //   엔터(257)/ESC(256) → 채팅 닫힘이므로 키보드 숨김.
        if (action == GLFW_PRESS) {
            when (glfwKeyCode) {
                GLFW_KEY_T, GLFW_KEY_SLASH -> showGameSoftKeyboard()   // 채팅 / 명령어
                GLFW_KEY_ENTER, GLFW_KEY_ESCAPE -> hideGameSoftKeyboard()  // 전송/취소
            }
        }
    }

    private fun showGameSoftKeyboard() {
        // 물리 키보드가 있으면 surface 의 onCreateInputConnection 이 null 을 반환하므로
        //   소프트 키보드를 띄우지 않는다(물리 키로 입력).
        if (hasHardwareKeyboardOrMouse()) return
        runOnUiThread {
            val surface = window.decorView.findViewWithTag<View>("minecraft_surface") ?: return@runOnUiThread
            surface.isFocusableInTouchMode = true
            surface.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(surface, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideGameSoftKeyboard() {
        runOnUiThread {
            val surface = window.decorView.findViewWithTag<View>("minecraft_surface")
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.hideSoftInputFromWindow(
                (surface ?: window.decorView).windowToken, 0
            )
        }
    }


    internal fun glfwKeyToChar(glfwKey: Int): Char = when (glfwKey) {
        in 65..90 -> ('a' + (glfwKey - 65))  // a-z
        in 48..57 -> ('0' + (glfwKey - 48))  // 0-9
        32 -> ' '
        else -> '\u0000'
    }

    internal fun getScancode(glfwKey: Int): Int = when (glfwKey) {
        // 알파벳 A=65 → scancode 30, B=66 → 48, C=67 → 46 ...
        65 -> 30   // A
        66 -> 48   // B
        67 -> 46   // C
        68 -> 32   // D
        69 -> 18   // E
        70 -> 33   // F
        71 -> 34   // G
        72 -> 35   // H
        73 -> 23   // I
        74 -> 36   // J
        75 -> 37   // K
        76 -> 38   // L
        77 -> 50   // M
        78 -> 49   // N
        79 -> 24   // O
        80 -> 25   // P
        81 -> 16   // Q
        82 -> 19   // R
        83 -> 31   // S
        84 -> 20   // T
        85 -> 22   // U
        86 -> 47   // V
        87 -> 17   // W
        88 -> 45   // X
        89 -> 21   // Y
        90 -> 44   // Z
        // 숫자
        48 -> 11   // 0
        49 -> 2    // 1
        50 -> 3    // 2
        51 -> 4    // 3
        52 -> 5    // 4
        53 -> 6    // 5
        54 -> 7    // 6
        55 -> 8    // 7
        56 -> 9    // 8
        57 -> 10   // 9
        // 특수키
        32 -> 57   // Space
        256 -> 1   // ESC
        257 -> 28  // Enter
        258 -> 15  // Tab
        259 -> 14  // Backspace
        261 -> 211 // Delete
        265 -> 103 // Up
        264 -> 108 // Down
        263 -> 105 // Left
        262 -> 106 // Right
        340 -> 42  // Left Shift
        341 -> 29  // Left Ctrl
        342 -> 56  // Left Alt
        344 -> 54  // Right Shift
        345 -> 97  // Right Ctrl
        346 -> 100 // Right Alt
        else -> 0
    }

    fun gaKey(jarPath: String, librariesRoot: String): String? {
        if (!jarPath.startsWith("$librariesRoot/")) return null
        val rel = jarPath.removePrefix("$librariesRoot/")
        val parts = rel.split("/")
        if (parts.size < 4) return null
        // [group..., artifact, version, filename]
        val artifactIdx = parts.size - 3
        val artifact = parts[artifactIdx]
        val group = parts.subList(0, artifactIdx).joinToString(".")
        // ⚠️ modern Forge 예외: net.minecraftforge:forge 는 같은 GA 로 -client.jar 와
        //   -universal.jar 두 개가 존재한다. 둘은 역할이 다른 별개 아티팩트로,
        //   -client.jar 에는 패치된 net/minecraft/client/Minecraft.class 가 들어있고
        //   (ForgeProdLaunchHandler.getMinecraftPaths 가 이걸 클래스패스에서 찾음)
        //   -universal.jar 에는 Forge 자체 코드가 들어있다.
        //   classifier 를 무시하고 같은 키로 묶으면 둘 중 하나(보통 universal)만 남아
        //   Minecraft.class 를 못 찾는 크래시가 난다. → classifier 를 키에 포함해 둘 다 보존.
        val fileName = parts.last()
        if (group == "net.minecraftforge" && artifact == "forge") {
            val classifier = when {
                fileName.endsWith("-client.jar")    -> "client"
                fileName.endsWith("-universal.jar") -> "universal"
                fileName.endsWith("-shim.jar")      -> "shim"
                else -> ""
            }
            if (classifier.isNotEmpty()) return "$group:$artifact:$classifier"
        }
        return "$group:$artifact"
    }

    /**
     * forge-install-data.properties 에서 patched client jar(=binarypatcher 출력,
     * net/minecraft/client/Minecraft.class 포함) 의 경로를 읽어 반환한다.
     *
     * 이 파일은 Forge 설치 단계에서 생성되어 "첫 게임 실행" 시점에도 항상 존재하므로, 아직 디스크에
     * 만들어지지 않은 patched client jar 의 정확한 "미래 경로" 를 추측 없이 알 수 있다.
     * ProcessorLauncher 가 이 파일을 user.dir(=mcDir)에서 읽으므로 여기서도 mcDir/instanceBase 순으로 찾는다.
     *
     * outputs.keys 는 \u0001(SOH) 로 구분된 경로 목록이며, 그 중 net/minecraftforge/forge/ 아래의
     * -client.jar 만 고른다(= 최종 patched 게임 jar). client-<ver>-official.jar 같은 패치 전 중간
     * 산출물은 /net/minecraft/client/ 아래라 자연히 제외된다.
     */
    private fun forgeClientJarsFromInstallData(vararg dirs: File): List<String> {
        val file = dirs.asSequence()
            .map { File(it, "forge-install-data.properties") }
            .firstOrNull { it.isFile } ?: return emptyList()
        return try {
            val props = java.util.Properties()
            file.inputStream().use { props.load(it.reader(Charsets.UTF_8)) }
            val count = props.getProperty("processorCount", "0").toIntOrNull() ?: 0
            val result = LinkedHashSet<String>()

            // 1순위: binarypatcher 프로세서의 최종 출력 = patched client jar(Minecraft.class 포함).
            //   ⚠️ NeoForge installer 는 install_profile 에 processor outputs 를 선언하지 않는다
            //      (Forge 와 다름) → outputs.keys 가 비어 탐지에 실패했었다. binarypatcher 의 출력은
            //      args 의 "--output <jar>" 에 항상 있으므로, outputs 가 없으면 그걸 직접 읽는다.
            //      Forge:    --output .../net/minecraftforge/forge/<ver>/forge-<ver>-client.jar
            //      NeoForge: --output .../net/neoforged/neoforge/<ver>/neoforge-<ver>-client.jar
            //   '도구(binarypatcher)'로 식별하므로 버전/로더 무관하고 패치 전 중간물을 집을 위험이 없다.
            for (i in 0 until count) {
                val tool = props.getProperty("processor.$i.jar") ?: ""
                if (!tool.contains("binarypatcher", ignoreCase = true)) continue
                val outs = (props.getProperty("processor.$i.outputs.keys") ?: "")
                    .split('\u0001').map { it.trim() }.filter { it.endsWith(".jar") }
                if (outs.isNotEmpty()) {
                    outs.forEach { result.add(File(it).absolutePath) }
                } else {
                    // NeoForge: outputs 선언 없음 → args 의 --output 값에서 출력 jar 추출
                    val pargs = (props.getProperty("processor.$i.args") ?: "").split('\u0001')
                    val oi = pargs.indexOf("--output")
                    if (oi >= 0 && oi + 1 < pargs.size) {
                        val out = pargs[oi + 1].trim()
                        if (out.endsWith(".jar")) result.add(File(out).absolutePath)
                    }
                }
            }

            // 1.5순위(NeoForge): NeoForge 는 binarypatcher 대신 런타임 transformer 를 쓴다.
            //   최종 게임 jar = net/neoforged/minecraft-client-patched/<ver>/minecraft-client-patched-<ver>.jar.
            //   생성 도구 이름이 버전마다 달라(jarsplitter/AutoRenamingTool 등) 도구로 못 잡으므로 출력 '경로'로 식별.
            //   (이 분기 없으면 NeoForge 는 항상 빈 리스트 → ensureForgePatchedJar 가 nowOk=false → '구성 실패')
            if (result.isEmpty()) {
                for (i in 0 until count) {
                    (props.getProperty("processor.$i.outputs.keys") ?: "")
                        .split('\u0001').forEach { raw ->
                            val path = raw.trim()
                            val norm = path.replace('\\', '/')
                            if (path.endsWith(".jar")
                                && (norm.contains("/net/neoforged/minecraft-client-patched/")
                                        || File(path).name.startsWith("minecraft-client-patched"))
                            ) result.add(File(path).absolutePath)
                        }
                }
            }

            // 2순위(보강): binarypatcher 식별 실패 시에만, forge/ 아래 -client.jar 출력을 잡는다.
            if (result.isEmpty()) {
                for (i in 0 until count) {
                    (props.getProperty("processor.$i.outputs.keys") ?: "")
                        .split('\u0001').forEach { raw ->
                            val path = raw.trim()
                            if (path.endsWith("-client.jar")
                                && path.replace('\\', '/').contains("/net/minecraftforge/forge/")) {
                                result.add(File(path).absolutePath)
                            }
                        }
                }
            }

            if (result.isNotEmpty())
                Log.d("TURTLE_LAUNCHER", "📄 install-data patched client jar(${result.size}): $result")
            result.toList()
        } catch (e: Exception) {
            Log.w("TURTLE_LAUNCHER", "forge-install-data.properties 파싱 실패(무시): ${e.message}")
            emptyList()
        }
    }

    /**
     * Forge/NeoForge 의 patched client jar(forge-<ver>-client.jar 등, net/minecraft/client/Minecraft.class 포함)가
     * 디스크에 있는지 확인하고, 없으면 :forgebuilder 별도 프로세스로 ProcessorLauncher 를 돌려 생성한다. (ZL2 방식)
     *
     * 게임 JVM(이 프로세스)이 부팅되기 전에 호출해야 한다. 빌더 JVM 은 별도 프로세스에서 JNI_CreateJavaVM 을
     * 쓰므로, 이 프로세스의 게임 JVM 부팅과 충돌하지 않는다. ZL2 runJvmRetryRuntimes 처럼 JRE 8→17→21 재시도.
     *
     * @return jar 가 (이미 있거나, 빌더로) 준비되면 true. 모든 JRE 로 실패하면 false.
     */
    private fun ensureForgePatchedJar(
        mcDir: File,
        instanceBase: File?,
        dedupedJars: List<String>
    ): Boolean {
        // 이미 존재하면 빌더 불필요
        val clientJars = forgeClientJarsFromInstallData(mcDir, instanceBase ?: mcDir)
        val alreadyOk = clientJars.isNotEmpty() &&
                clientJars.all { File(it).let { f -> f.exists() && f.length() > 0 } }
        if (alreadyOk) {
            Log.i("TURTLE_LAUNCHER", "ℹ️ Forge patched jar 이미 존재 — 빌더 생략")
            return true
        }
        Log.i("TURTLE_LAUNCHER", "🔨 Forge patched jar 없음 → 빌더 프로세스로 생성 시작")

        // 빌더 cp: ProcessorLauncher.jar + 게임 cp(dedupedJars).
        //   ProcessorLauncher 는 forge-install-data.properties 의 processor.N.classpath 를 자체
        //   ChildFirstClassLoader 로 다시 들기 때문에, 빌더 cp 에는 ProcessorLauncher 자신을 로드할 수
        //   있는 cp 만 있으면 된다. 게임 cp + launcher jar 로 충분.
        val launcherJar = findProcessorLauncherJar(instanceBase, dedupedJars)
        if (launcherJar == null) {
            Log.e("TURTLE_LAUNCHER", "❌ ProcessorLauncher.jar 를 찾지 못함 — 빌더 불가")
            return false
        }
        val builderCp = buildList {
            add(launcherJar)
            addAll(dedupedJars)
        }.distinct()

        val builderProcMain = "com.endiq.turtlelauncher.forge.ProcessorLauncher"
        fun buildBuilderArgs(jreMajor: Int): Array<String> {
            val modular = jreMajor >= 9
            return buildList {
                if (!modular) add("-XX:+IgnoreUnrecognizedVMOptions")
                add("-Djava.awt.headless=true")
                // ⚠️ -Xmx 는 작게(512m) — 처음엔 1536m 로 넉넉하게 잡았었는데, 빌더는
                //   :forgebuilder 라는 별개 프로세스라 이게 끝나고 프로세스가 완전히
                //   정리되기 전에 게임 프로세스(수 GB 힙 요청)가 뜨면 기기 전체 메모리가
                //   부족해져서 게임 JVM 이 극도로 느려지는(GC 스래싱) 원인이 될 수 있다.
                //   빌더는 게임 본체와 달리 디옵퓨스케이션/패치만 하면 되니 512m 로도 충분.
                add("-Xmx512m")
                add("-Dping.main.class=$builderProcMain")
                add("-Duser.dir=${mcDir.absolutePath}")
                add("-Djava.class.path=" + builderCp.joinToString(File.pathSeparator))
            }.toTypedArray()
        }

        // ZL2 runJvmRetryRuntimes 와 동일: JRE 8 → 17 → 21 순서로 재시도.
        val retryMajors = listOf(javaMajor, 21, 17, 8).distinct()

        for ((idx, major) in retryMajors.withIndex()) {
            val builderLibJvm = try {
                MinecraftJREPreparer.prepareJreMajorAndGetPath(this, major, "forge-builder")
            } catch (e: Exception) {
                Log.w("TURTLE_LAUNCHER", "빌더 JRE $major 준비 실패: ${e.message}")
                continue
            }
            Log.i("TURTLE_LAUNCHER", "🔨 빌더 시도 ${idx + 1}/${retryMajors.size} (JRE $major)")
            val code = startBuilderAndWaitExitBlocking(
                context = this,
                libJvmPath = builderLibJvm,
                jvmArgs = buildBuilderArgs(major),
                userDir = mcDir.absolutePath,
                rendererEnv = emptyMap(),   // processor 는 렌더러 불필요
                postSummary = getString(R.string.forge_component_generating, major)
            )
            val nowOk = forgeClientJarsFromInstallData(mcDir, instanceBase ?: mcDir)
                .let { it.isNotEmpty() && it.all { p -> File(p).let { f -> f.exists() && f.length() > 0 } } }
            if (code == 0 && nowOk) {
                Log.i("TURTLE_LAUNCHER", "✅ 빌더 성공 (JRE $major). patched jar 생성 완료")
                return true
            }
            Log.w("TURTLE_LAUNCHER", "빌더 실패 (JRE $major, code=$code, jarExists=$nowOk) → 다음 JRE 시도")
        }
        Log.e("TURTLE_LAUNCHER", "❌ 모든 JRE 로 빌더 실패")
        return false
    }

    /**
     * ProcessorLauncher(빌더 진입점) jar 의 절대경로를 찾는다.
     *   1순위: dedupedJars 중 launcher jar 파일명과 일치하는 항목
     *   2순위: instanceBase/libraries 아래를 재귀 탐색
     *
     * ⚠️ 실제 산출물명 주의: ForgeInstaller.copyProcessorLauncherJar 는 에셋
     *   forge-runtime/processor-launcher.jar 를 Maven 경로
     *   libraries/com/endiq/turtlelauncher/processor-launcher/1.0/processor-launcher-1.0.jar
     *   로 복사한다. 과거 하드코딩 "ProcessorLauncher.jar" 와 이름이 달라(하이픈·버전) 매칭에
     *   실패했고, 그 결과 빌더가 launcher jar 를 못 찾아 ensureForgePatchedJar 가 항상 false 를
     *   반환 → "Forge 구성 요소 생성 실패" 로 부팅이 중단됐다.
     *   → 실제 산출물명(processor-launcher*.jar)과 구(舊) 이름(ProcessorLauncher.jar)을 모두 허용한다.
     */
    private fun findProcessorLauncherJar(instanceBase: File?, dedupedJars: List<String>): String? {
        fun isLauncherJar(name: String): Boolean {
            val n = name.lowercase()
            return n == "processorlauncher.jar" ||
                    (n.startsWith("processor-launcher") && n.endsWith(".jar"))
        }
        dedupedJars.firstOrNull { isLauncherJar(File(it).name) }
            ?.let { return it }
        val libRoot = instanceBase?.let { File(it, "libraries") } ?: return null
        if (!libRoot.exists()) return null
        return libRoot.walkTopDown()
            .firstOrNull { it.isFile && isLauncherJar(it.name) }
            ?.absolutePath
    }

    /**
     * JLI(java 커맨드 파서)는 "--add-opens X" 같은 두-토큰 형식을 "--add-opens=X" 로
     * 합쳐 JVM에 넘기지만, JNI_CreateJavaVM 직접 호출 경로엔 JLI 가 없다.
     * 두 토큰 그대로 들어가면 JVM 은 "--add-opens" 만 보고 값을 못 찾아 그냥 무시한다
     * (ignoreUnrecognized=JNI_TRUE 라 에러도 안 남). 그래서 여기서 미리 합쳐준다.
     *
     * 추가로 "-p" 는 JLI 전용 짧은 형이라 hotspot 자체는 못 알아먹음 → "--module-path" 로 정규화.
     */
    private val JLI_TWO_TOKEN_OPTS = setOf(
        "--add-opens", "--add-exports", "--add-reads", "--add-modules",
        "--patch-module", "--module-path", "-p", "--upgrade-module-path",
        "--limit-modules", "--module", "-m"
    )

    /**
     * Android(bionic)에서 동작 불가능한 모드를 로드에서 제외한다.
     *
     * 현재 대상: controllable (controllable-forge / controllable-sdl)
     *   - SDL2 네이티브를 jar 에 번들하는데, 그게 데스크톱 glibc 빌드라
     *     "libm.so.6 not found" 로 dlopen 실패 → onClientSetup 단계에서 크래시.
     *   - ZL2 포함 어떤 PojavLauncher 계열도 못 쓰는 데스크톱 전용 네이티브라,
     *     모바일에선 영구 제외가 맞다(복원 불필요).
     *
     * Forge 의 mods 스캐너는 .jar 확장자만 로드하므로, .jar → .jar.pingdisabled
     * 로 rename 해서 제외한다. (파일 삭제 아님 — 모드팩 무결성 보존)
     *
     * 주의: "controlling"(옵션 화면 검색 모드)은 이름이 비슷하지만 전혀 다른 모드이고
     *       네이티브가 없어 정상 동작하므로 제외 대상이 아니다.
     */
    private fun disableUnsupportedMods(modsDir: File) {
        if (!modsDir.isDirectory) return

        // 비활성화 목록 초기화(재실행 대비)
        disabledModsList.clear()

        // ── (1) 파일명 prefix 블랙리스트 (소문자, startsWith) ──
        //   네이티브 스캔으로 못 잡는 케이스를 보강한다.
        //   - controllable-*: SDL/Forge 컨트롤러 네이티브 (안드로이드 미지원)
        //   - crashassistant: 데스크탑 전용 크래시 UI
        //   - axiom: imgui 네이티브를 System.loadLibrary 로 직접 로드(jar 밖) → 스캔 회피하므로 명시.
        //     (x86_64 .so 동봉 또는 imgui-javaarm64 런타임 의존, 둘 다 이 기기에서 불가)
        //   주의: "controlling"(옵션 검색 모드)은 네이티브 없이 정상 동작 → 제외 안 함. '-' 포함으로 구분.
        val blockedPrefixes = listOf(
            "controllable-forge", "controllable-sdl", "crashassistant",
            "axiom", "flashback"
        )
        // 사유 라벨 매핑(접두사 → 설명). 매칭 안 되면 기본 문구.
        fun prefixReason(lower: String): String = when {
            lower.startsWith("axiom") -> getString(R.string.desktop_only_imgui_unsupported)
            lower.startsWith("controllable") -> getString(R.string.sdl_controller_native_unsupported)
            lower.startsWith("crashassistant") -> getString(R.string.desktop_only_mod)
            else -> getString(R.string.android_unsupported_mod)
        }

        modsDir.listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            val name = f.name
            if (!name.endsWith(".jar", ignoreCase = true)) return@forEach
            val lower = name.lowercase()

            // (1) prefix 블랙리스트
            val prefixHit = blockedPrefixes.any { lower.startsWith(it) }
            // (2) jar 내부 네이티브 ABI 스캔: .so 가 있는데 arm64 가 하나도 없으면 데스크탑 전용으로 판정
            val nativeVerdict = if (prefixHit) null else scanJarNativeAbi(f)

            val reason: String? = when {
                prefixHit -> prefixReason(lower)
                nativeVerdict != null -> nativeVerdict
                else -> null
            }
            if (reason == null) return@forEach  // 호환 → 그대로 둠

            val disabled = File(f.parentFile, "$name.pingdisabled")
            try {
                if (disabled.exists()) disabled.delete()
                if (f.renameTo(disabled)) {
                    Log.d("TURTLE_LAUNCHER", "🚫 모드 로드 제외 ($reason): $name")
                    disabledModsList.add(DisabledModInfo(displayName = name, reason = reason))
                } else {
                    Log.w("TURTLE_LAUNCHER", "⚠️ 모드 제외 실패 (rename): $name")
                }
            } catch (e: Exception) {
                Log.w("TURTLE_LAUNCHER", "⚠️ 모드 제외 중 오류: $name — ${e.message}")
            }
        }

        if (disabledModsList.isNotEmpty()) {
            Log.i("TURTLE_LAUNCHER", "🚫 기기 비호환 모드 ${disabledModsList.size}개 비활성화됨")
        }
    }

    /**
     * jar 안의 네이티브 라이브러리(.so) ABI 를 스캔해 "데스크탑 전용" 여부를 판정한다.
     *
     * 판정 규칙:
     *  - .so 엔트리가 하나도 없으면 → null (네이티브 없는 순수 자바 모드. 호환)
     *  - .so 가 있는데 그중 arm64(EM_AARCH64) 가 하나라도 있으면 → null (이 기기에서 로드 가능)
     *  - .so 가 있는데 arm64 가 전혀 없으면(x86_64/x86/arm32 등만) → 사유 문자열 반환(비호환)
     *
     * ELF 헤더만 읽으므로 가볍다(엔트리당 20바이트). 큰 jar 도 .so 만 골라 헤더만 본다.
     * .dll/.dylib 만 있는 경우도 데스크탑 전용으로 본다.
     */
    private fun scanJarNativeAbi(jar: File): String? {
        var sawSo = false
        var sawArm64 = false
        var sawForeignNative = false  // .dll/.dylib 또는 비-arm64 .so
        try {
            java.util.zip.ZipFile(jar).use { zf ->
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (e.isDirectory) continue
                    val en = e.name.lowercase()
                    when {
                        en.endsWith(".so") -> {
                            sawSo = true
                            val machine = readElfMachine(zf, e)
                            if (machine == ELF_EM_AARCH64) sawArm64 = true
                            else if (machine != null) sawForeignNative = true
                            // machine == null(헤더 못읽음)은 판단 보류
                        }
                        en.endsWith(".dll") || en.endsWith(".dylib") || en.endsWith(".jnilib") -> {
                            sawForeignNative = true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("TURTLE_LAUNCHER", "⚠️ jar 네이티브 스캔 실패(무시): ${jar.name} — ${e.message}")
            return null  // 스캔 실패 시 보수적으로 비활성화하지 않음
        }

        // arm64 .so 가 있으면 무조건 호환(다른 ABI 가 섞여 있어도 이 기기는 arm64 를 씀)
        if (sawArm64) return null
        // arm64 는 없는데 데스크탑/타 ABI 네이티브가 있으면 비호환
        if (sawSo && sawForeignNative) return getString(R.string.desktop_only_native_no_arm64)
        if (!sawSo && sawForeignNative) return getString(R.string.desktop_only_native_dll_dylib)
        // .so 가 있지만 전부 헤더를 못 읽은 애매한 경우 → 호환으로 둔다(오탐 방지)
        return null
    }

    /**
     * ZipEntry(=.so)의 ELF 헤더에서 e_machine(아키텍처) 값을 읽는다.
     * ELF: 0~3 매직(0x7F 'E' 'L' 'F'), 18~19 e_machine(LE, 2바이트).
     * @return e_machine 값, 또는 ELF 가 아니거나 읽기 실패 시 null
     */
    private fun readElfMachine(zf: java.util.zip.ZipFile, entry: java.util.zip.ZipEntry): Int? {
        return try {
            zf.getInputStream(entry).use { ins ->
                val head = ByteArray(20)
                var read = 0
                while (read < 20) {
                    val r = ins.read(head, read, 20 - read)
                    if (r < 0) break
                    read += r
                }
                if (read < 20) return null
                // ELF 매직 확인
                if (head[0].toInt() != 0x7F || head[1].toInt().toChar() != 'E' ||
                    head[2].toInt().toChar() != 'L' || head[3].toInt().toChar() != 'F'
                ) return null
                // e_machine: offset 18, little-endian u16
                val lo = head[18].toInt() and 0xFF
                val hi = head[19].toInt() and 0xFF
                (hi shl 8) or lo
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun normalizeJvmArgsForJni(
        args: Array<String>,
        freetypeLibPath: String? = null,
        jnaBootPath: String? = null,
        jnaTmpDir: String? = null,
        sdlLibraryPath: String? = null,
    ): Array<String> {
        val out = ArrayList<String>(args.size)
        var i = 0
        while (i < args.size) {
            val a = args[i]
            // freetype libname 은 별도로 강제하므로, 기존에 들어온 것들은 모두 제거(ZL2 purgeArg 방식).
            // version.json / 사용자 설정이 데스크톱용 경로를 넣었을 수 있어 충돌을 막는다.
            if (freetypeLibPath != null && a.startsWith("-Dorg.lwjgl.freetype.libname")) {
                i++
                continue
            }
            // jna.boot.library.path 도 우리가 강제하므로 기존 것은 제거.
            // (JNA 는 이 경로에서 libjnidispatch.so 를 직접 로드 → 추출 실패로 인한
            //  "Could not initialize class com.sun.jna.NativeLibrary" 방지)
            if (jnaBootPath != null && a.startsWith("-Djna.boot.library.path")) {
                i++
                continue
            }
            // ⚠️ 26.3 의 version.json 은 네이티브 경로를 자기 것으로 못박는다
            //    (-Djava.library.path=<natives>/java, SharedLibraryExtractPath 등).
            //    그 폴더는 비어 있다 — 우리는 natives jar 을 안 푼다. 데스크톱용이라 쓸 수도 없다.
            //    전부 걷어내고 아래에서 우리 경로를 단 하나만 다시 넣는다.
            if (sdlLibraryPath != null &&
                (a.startsWith("-Djava.library.path") ||
                 a.startsWith("-Dorg.lwjgl.librarypath") ||
                 a.startsWith("-Dorg.lwjgl.system.SharedLibraryExtractPath"))) {
                i++
                continue
            }
            // jna.tmpdir 는 안드로이드에서 쓰기 가능한 캐시 경로로 치환 (ZL2 방식).
            // 폴백 추출이 필요할 때 읽기 전용 경로라 실패하는 것을 막는다.
            if (jnaTmpDir != null && a.startsWith("-Djna.tmpdir")) {
                out.add("-Djna.tmpdir=$jnaTmpDir")
                i++
                continue
            }
            // 이미 "--add-opens=..." 처럼 = 가 붙어있으면 그대로
            val isBare = a in JLI_TWO_TOKEN_OPTS
            if (isBare && i + 1 < args.size) {
                val v = args[i + 1]
                // JNI_CreateJavaVM 경로(=JAVA_TOOL_OPTIONS 와 동일, JLI launcher 파싱 없음)에서는
                // launcher 전용 옵션을 반드시 한-토큰 "옵션=값" 형식으로 줘야 인식된다.
                //   • 두-토큰 "--add-opens" "A=B"  → JNI 에서 "Unrecognized option: --add-opens"
                //   • 한-토큰 "--add-opens=A=B"     → 정상 (named 타겟 =cpw.mods.securejarhandler 포함)
                // (ref: JDK-8320860 — JAVA_TOOL_OPTIONS 는 '=' 형식 필수)
                // 따라서 -Djdk.module.* 로 변환하지 않고 단순히 '=' 로 결합만 한다.
                //   (-Djdk.module.addOpens 프로퍼티는 named 타겟을 제대로 적용하지 못해 부적합)
                val canonical = if (a == "-p") "--module-path" else a
                out.add("$canonical=$v")
                i += 2
            } else {
                // 이미 "--add-opens=..." 처럼 '=' 가 붙은 한-토큰은 그대로 둔다(정상 형식).
                out.add(a)
                i++
            }
        }
        if (sdlLibraryPath != null) {
            out.add("-Djava.library.path=$sdlLibraryPath")
            out.add("-Dorg.lwjgl.librarypath=$sdlLibraryPath")
        }
        // 제거 후 우리가 계산한 freetype 경로를 단 하나만 추가
        if (freetypeLibPath != null) {
            out.add("-Dorg.lwjgl.freetype.libname=$freetypeLibPath")
        }
        // JNA 부트 경로 강제 (단 하나만)
        if (jnaBootPath != null) {
            out.add("-Djna.boot.library.path=$jnaBootPath")
            // JNA 가 시스템 경로의 jnidispatch 를 먼저 찾도록(추출 회피). 실패 시 boot path 폴백.
            out.add("-Djna.nosys=false")
        }
        return out.toTypedArray()
    }

    /**
     * Pojav 의 patched `lwjgl-glfw-classes*.jar` 는 lwjgl 의 모든 서브패키지
     * (glfw, opengl, openal, stb, system 등) 를 한 jar 에 통합한 fat jar.
     * vanilla `lwjgl-openal-*.jar`, `lwjgl-opengl-*.jar` 등이 같이 classpath 에 있으면
     * BootstrapLauncher 가 자동 모듈로 등록하다가 split package 폭발.
     * → patched fat jar 만 남기고 나머지 vanilla lwjgl-* jar 들은 classpath 에서 제거.
     */
    private fun isRedundantLwjglJar(file: File): Boolean =
        LwjglClasspathPolicy.isRedundant(file.name, sdlMode)

    private fun startMinecraft() {
        val base = applicationContext.filesDir
        val nativesDir = File(base, "natives")
        val jarList = mutableListOf<String>()
        val seenGA = mutableSetOf<String>()

        val instanceBase = instanceDir?.let { File(it) }
            ?: customGameDir?.let { File(it) }
            ?: File(getExternalFilesDir(null), "instances/vanilla_$versionId")

        val isLegacy = isLegacyVersion(versionId)

        val mcDir = if (isLegacy) {
            // 레거시 MC 는 user.home/.minecraft 를 강제로 사용.
            // 인스턴스 베이스 안에 .minecraft 폴더를 만들고 거기로 user.home 을 가리키게 함.
            val legacyRoot = File(instanceBase, ".minecraft")
            legacyRoot.mkdirs()
            File(legacyRoot, "logs").mkdirs()
            File(legacyRoot, "mods").mkdirs()
            legacyRoot
        } else {
            instanceBase.also {
                it.mkdirs()
                File(it, "logs").mkdirs()
                File(it, "mods").mkdirs()
            }
        }


        Log.d("TURTLE_LAUNCHER", "instanceBase: ${instanceBase.absolutePath}")

        // 인스턴스 메타 로드 — Fabric의 gameJvmArgs/gameArgs 가져오기
        val instanceMeta = InstanceManager.loadMeta(instanceBase)
        val isFabric = mainClass.contains("knot", ignoreCase = true)
                || mainClass.contains("fabric", ignoreCase = true)
                || instanceMeta?.loaderType == "fabric"
        Log.d("TURTLE_LAUNCHER", "isFabric=$isFabric, loaderType=${instanceMeta?.loaderType}, mainClass=$mainClass")

        // Original Turtle compatibility payload: Fabric/Quilt NanoVG natives must be present
        // before the classpath is assembled. It is a no-op for vanilla and Forge/NeoForge.
        NanoVGNativesFix.ensureInstalled(
            context = this,
            loaderType = instanceMeta?.loaderType,
            mainClass = mainClass,
            modsDir = File(mcDir, "mods"),
        )

        // ★ 추가 — Forge/NeoForge 의 BootstrapLauncher 경유 부팅 감지
        //   libraries 워커 / versionJar 분기에서 동시에 쓰기 위해 여기서 한 번만 계산
        //   ⚠️ Forge 1.20.x+ 는 게임 진입점을 cpw.mods.bootstraplauncher.BootstrapLauncher 에서
        //      자체 포크인 net.minecraftforge.bootstrap.ForgeBootstrap 으로 교체했다. 이 클래스명이
        //      아래 패턴(cpw.mods / BootstrapLauncher / ProcessorLauncher / net.neoforged) 어디에도
        //      안 걸려 isModernLoader=false 가 되면, Forge 전용 처리(중간 산출물 제외 · 바닐라 jar 제외 ·
        //      forge-client.jar 선등록 · :forgebuilder 빌더 실행)가 통째로 스킵된다. 그러면 바닐라/중간
        //      산출물 jar 가 net.minecraft 패키지를 선점하고 패치된 client jar 가 cp 에서 빠져,
        //      SECURE-BOOTSTRAP 에서 Minecraft.class 를 못 찾고 죽는다.
        //      (NeoForge 는 여전히 cpw.mods.bootstraplauncher 라 정상 → 'NeoForge 는 되는데 Forge 만
        //       막힘' 증상으로 나타났다.)
        val isModernLoader = (instanceMeta?.loaderType == "forge"
                || instanceMeta?.loaderType == "neoforge")
                && (mainClass.startsWith("cpw.mods")
                || mainClass.startsWith("net.minecraftforge.bootstrap")   // ★ Forge 1.20.x+ 자체 포크
                || mainClass.contains("BootstrapLauncher", ignoreCase = true)
                || mainClass.contains("ForgeBootstrap", ignoreCase = true) // ★ 보강(패키지 외 클래스명도 매칭)
                || mainClass.contains("ProcessorLauncher", ignoreCase = true)
                || mainClass.contains("net.neoforged", ignoreCase = true))

        // Forge 와 NeoForge 는 게임 jar 처리 방식이 다르다:
        //   - NeoForge: -DlibraryDirectory + 좌표로 게임 jar 를 transformer module 로 직접 찾음
        //               → classpath 에 넣으면 중복 모듈 충돌. 제외해야 함.
        //   - Forge   : ForgeProdLaunchHandler.getMinecraftPaths 가 classpath/모듈 레이어에서
        //               net/minecraft/client/Minecraft.class(SRG) 를 직접 찾음
        //               → srg 게임 jar 를 classpath 에 넣어야 함(빼면 BOOTSTRAP 로더가 못 찾고 죽음).
        val isNeoForge = instanceMeta?.loaderType == "neoforge"
                || mainClass.contains("net.neoforged", ignoreCase = true)
        // PojavLauncher 패치 LWJGL은 26.3 **미만**에 필요 (libglfw.so 가 pojavInit 라우팅을 가정함).
        // 26.3+ 는 GLFW 를 아예 쓰지 않으므로 이 fat jar 를 넣으면 3.4.3 클래스와 겹쳐 깨진다.
        copyMicBridgeJar(base)
        if (!sdlMode) {
            copyLwjglJars(base)
            bytecodePatchRepo.patchLwjglGlfwIfNeeded(File(base, "lwjgl3"))
        }
        val lwjgl3Dir = File(base, "lwjgl3")
        // 수정 — patched GLFW를 무조건 0번 인덱스에
        val lwjglJars = if (sdlMode) mutableListOf() else lwjgl3Dir.listFiles()
            ?.filter { it.extension == "jar" }
            ?.toMutableList() ?: mutableListOf()

        // patched glfw를 분리해서 맨 앞으로
        val patchedGlfw = lwjglJars.find { it.name.contains("glfw-classes") }
        lwjglJars.remove(patchedGlfw)
        lwjglJars.sortBy { it.name }

        if (patchedGlfw != null) {
            jarList.add(patchedGlfw.absolutePath)  // 0번 인덱스
            Log.d("TURTLE_LAUNCHER", "🔧 patched GLFW 우선 주입: ${patchedGlfw.name}")
        }

        lwjglJars.forEach { jar ->
            jarList.add(jar.absolutePath)
            Log.d("TURTLE_LAUNCHER", "🔧 LWJGL jar 주입: ${jar.name}")
        }
        val cleanedExtraJars = extraJars.filter { p ->
            val f = File(p)
            when {
                isProcessorOnlyJar(f) -> { Log.d("TURTLE_LAUNCHER", "🚫 extraJars processor-only 제거: ${f.name}"); false }
                isRedundantLwjglJar(f) -> { Log.d("TURTLE_LAUNCHER", "🚫 extraJars vanilla lwjgl 제거: ${f.name}"); false }
                else -> true
            }
        }
        jarList.addAll(0, cleanedExtraJars)

        val searchDirs = listOfNotNull(
            instanceBase,
            getExternalFilesDir(null),
            base
        ).distinct()

        // 이미 jarList에 들어있는 extraJars(Fabric 라이브러리)의 GA를 먼저 점유
        jarList.toList().forEach { jp ->
            for (rootCandidate in searchDirs) {
                val libRoot = File(rootCandidate, "libraries").absolutePath
                val ga = gaKey(jp, libRoot)
                if (ga != null) { seenGA.add(ga); break }
            }
        }
        searchDirs.forEach { dir ->
            val librariesDir = File(dir, "libraries")
            // ── ZL2 방식(절충): Forge/NeoForge 에서도 libraries/ 는 walkTopDown 으로 수집하되,
            //   net.minecraft 패키지 소유권을 꼬이게 하는 "게임 jar 류"만 정밀 제외한다.
            //   (log4j-core 등 일반 라이브러리는 Forge version.json libs 에 없고 바닐라 쪽에만
            //    있을 수 있으므로, 디스크 수집을 끊으면 'Module log4j.core not found' 가 난다.
            //    그래서 전체 스킵이 아니라 게임 jar 만 제외하는 게 맞다 = ZL2 filterLibrary 와 동일)
            if (librariesDir.exists()) {
                librariesDir.walkTopDown().forEach { f ->
                    if (!f.isFile || f.extension != "jar") return@forEach
                    if (f.name.contains("natives-linux")) return@forEach

                    // NeoForge 게임 jar 는 classpath 에서 제외(좌표로 자동 로드 → 중복 모듈 충돌 회피).
                    //   Forge 는 반대로 srg 게임 jar 를 classpath 에 넣어야 getMinecraftPaths 가
                    //   net/minecraft/client/Minecraft.class 를 찾는다 → Forge 는 제외하지 않음.
                    if (isNeoForge) {
                        val n = f.name
                        val ap = f.absolutePath
                        val isGameJar =
                            // net/minecraft/client/<ver>/ 아래 srg/slim/extra
                            (ap.contains("/net/minecraft/")
                                    && (n.contains("-srg.") || n.contains("-slim.")
                                    || n.contains("-srg-and-extra.") || n.contains("-extra.")))
                                    // net/neoforged/neoforge/<ver>/ 아래 client/universal (게임 클래스 포함)
                                    || (ap.contains("/net/neoforged/neoforge/")
                                    && (n.endsWith("-client.jar") || n.endsWith("-universal.jar")))
                        if (isGameJar) {
                            Log.d("TURTLE_LAUNCHER", "🚫 NeoForge 게임 jar classpath 제외 (좌표로 자동 로드, ZL2 방식): ${f.name}")
                            return@forEach
                        }
                    }

                    // Forge: net/minecraft/client/<ver>/ 아래의 "중간 산출물"을 classpath 에서 제외.
                    //   client-<ver>-official.jar 는 processor.1(deobf) 산출물로, Minecraft.class 를
                    //   담고 있지만 Forge 패치(binarypatcher) 적용 "전" 버전이다. 이게 cp 에 있으면
                    //   BootstrapLauncher 의 "첫 jar 가 패키지 소유" 규칙에 따라 net.minecraft 패키지를
                    //   선점해, 정작 실행용인 forge-<ver>-client.jar(패치 후)의 클래스가 가려진다.
                    //   → official/mappings/slim/srg 등 중간 산출물은 빼고, 위에서 명시 등록한
                    //     forge-<ver>-client.jar 만 게임 jar 로 남긴다.
                    if (isModernLoader && !isNeoForge
                        && f.absolutePath.contains("/net/minecraft/client/")) {
                        Log.d("TURTLE_LAUNCHER", "🚫 Forge 중간 산출물 classpath 제외 (forge-client.jar 만 사용): ${f.name}")
                        return@forEach
                    }

                    if (isProcessorOnlyJar(f)) {
                        Log.d("TURTLE_LAUNCHER", "🚫 processor-only jar 제외: ${f.name}")
                        return@forEach
                    }

                    if (jarList.contains(f.absolutePath)) return@forEach
                    if (isProcessorOnlyJar(f)) {
                        Log.d("TURTLE_LAUNCHER", "🚫 processor-only jar 제외: ${f.name}")
                        return@forEach
                    }
                    if (isRedundantLwjglJar(f)) {                                  // ★ 추가
                        Log.d("TURTLE_LAUNCHER", "🚫 vanilla lwjgl jar 제외 (patched fat 만 keep): ${f.name}")
                        return@forEach
                    }

                    // 마인크래프트 번들 LWJGL은 PojavLauncher 패치 버전과 충돌하므로 제외
                    // PojavLauncher 패치 GLFW만 제외. core/opengl/openal 등 다른 LWJGL 모듈은
                    // 1.14 번들 그대로 쓰는 게 호환성 안전.
                    val lowerName = f.name.lowercase()

                    // 변경 → glfw-classes 동명 클래스 충돌 방지를 위해 lwjgl-glfw-*만 제외
                    val lwjglGlfwPattern = Regex("^lwjgl-glfw-\\d.*\\.jar$")
                    if (lwjglGlfwPattern.matches(lowerName)) {
                        Log.d("TURTLE_LAUNCHER", "번들 lwjgl-glfw 제외 (PojavLauncher patched 사용): ${f.name}")
                        return@forEach
                    }

                    val ga = gaKey(f.absolutePath, librariesDir.absolutePath)
                    if (ga != null && seenGA.contains(ga)) {
                        Log.d("TURTLE_LAUNCHER", "중복 라이브러리 스킵: $ga (${f.name})")
                        return@forEach
                    }
                    if (ga != null) seenGA.add(ga)
                    jarList.add(f.absolutePath)
                }
            }

            val legacyDir = File(dir, "libraries_$versionId")
            if (legacyDir.exists()) {
                legacyDir.walkTopDown().forEach { f ->
                    if (!f.isFile || f.extension != "jar") return@forEach
                    if (f.name.contains("natives-linux")) return@forEach
                    if (jarList.contains(f.absolutePath)) return@forEach

                    val lowerName = f.name.lowercase()
                    // 변경
                    val lwjglGlfwPattern = Regex("^lwjgl-glfw-\\d.*\\.jar$")
                    if (lwjglGlfwPattern.matches(lowerName)) {
                        Log.d("TURTLE_LAUNCHER", "번들 lwjgl-glfw 제외 (PojavLauncher patched 사용): ${f.name}")
                        return@forEach
                    }

                    val ga = gaKey(f.absolutePath, legacyDir.absolutePath)
                    if (ga != null && seenGA.contains(ga)) {
                        Log.d("TURTLE_LAUNCHER", "중복 라이브러리 스킵: $ga (${f.name})")
                        return@forEach
                    }
                    if (ga != null) seenGA.add(ga)
                    jarList.add(f.absolutePath)
                }
            }
        }

        if (mainClass.contains("launchwrapper")) {
            bytecodePatchRepo.patchLaunchwrapperIfNeeded(searchDirs)
        }

        // 게임 client jar 를 classpath 에 반드시 포함한다.
        //   pre-1.6(1.2.5 등)은 lang/en_US.lang 같은 리소스를 getResourceAsStream 으로
        //   클래스패스에서 읽는다. 게임 jar 가 classpath 에 없으면 그 스트림이 null 이 되어
        //   StringTranslate(adn) 초기화에서 NullPointerException 으로 죽는다.
        //   여러 후보 경로(instanceBase/versions, .minecraft/versions, base/versions)를 모두 본다.
        val versionJarCandidates = buildList {
            for (d in searchDirs) {
                add(File(d, "versions/$versionId/$versionId.jar"))
                add(File(d, ".minecraft/versions/$versionId/$versionId.jar"))
            }
            // mcDir 기준도 추가 (pre-1.6 은 mcDir = .minecraft)
            add(File(mcDir, "versions/$versionId/$versionId.jar"))
            add(File(mcDir.parentFile ?: mcDir, "versions/$versionId/$versionId.jar"))
        }.distinct()

        versionJarCandidates.forEach {
            Log.d("TURTLE_LAUNCHER", "🔎 client jar 후보: ${it.absolutePath} exists=${it.exists()}")
        }

        val versionJar = versionJarCandidates.firstOrNull { it.exists() && it.length() > 0 }

        if (versionJar == null) {
            Log.e("TURTLE_LAUNCHER", "❌ 게임 client jar 를 찾지 못함! ($versionId.jar) — pre-1.6 리소스 로딩 실패 위험")
        }

        versionJar?.let {
            // ZL2 방식: 바닐라 client jar 를 classpath 에 포함한다(pre-1.6 리소스 로딩 등).
            //   ⚠️ 단, modern Forge 는 예외다. 바닐라 <ver>.jar 는 net/minecraft/ 패키지를
            //   다수 포함(약 29개)하는데, 이게 forge-<ver>-client.jar 보다 먼저 classpath 에
            //   들어가면 BootstrapLauncher 의 "첫 jar 가 패키지 소유" 규칙에 따라 net.minecraft
            //   패키지를 선점한다. 그런데 바닐라엔 deobf 된 net/minecraft/client/Minecraft.class 가
            //   없으므로(official/SRG 이름 불일치), 결국 BOOTSTRAP 에서 Minecraft.class 를 못 찾고
            //   "Could not find net/minecraft/client/Minecraft.class" 로 죽는다.
            //   → modern Forge 는 바닐라 jar 를 넣지 않는다. 게임 클래스는 forge-<ver>-client.jar
            //     (binarypatcher 산출물, official 매핑 + Forge 코드 포함)가 제공한다.
            //   (NeoForge 는 게임 jar 를 좌표로 로드하므로 기존 동작 유지)
            val skipVanillaForForge = isModernLoader && !isNeoForge
            if (skipVanillaForForge) {
                Log.d("TURTLE_LAUNCHER", "🚫 modern Forge: 바닐라 client jar 제외 (forge-client.jar 가 net.minecraft 소유): ${it.name}")
            } else if (!jarList.contains(it.absolutePath)) {
                jarList.add(it.absolutePath)
                Log.d("TURTLE_LAUNCHER", "✅ 바닐라 client jar classpath 포함 (ZL2 방식): ${it.absolutePath}")
            } else {
                Log.d("TURTLE_LAUNCHER", "ℹ️ client jar 이미 classpath 에 있음: ${it.name}")
            }
        }

        // ── Forge: processor 가 만드는 최종 게임 jar 를 classpath 에 "미리" 등록 ──
        //   Forge prod 의 ForgeProdLaunchHandler.getMinecraftPaths 는 BOOTSTRAP 클래스로더에서
        //   net/minecraft/client/Minecraft.class 를 getResource 로 찾는다. 그 클래스를 담은 jar 는
        //   binarypatcher(processor.2)가 만드는 forge-<ver>-client.jar 다.
        //
        //   문제: classpath 구성(이 walkTopDown)은 ProcessorLauncher 가 jar 를 만들기 "전"에
        //   끝나므로, 첫 실행 땐 forge-<ver>-client.jar 가 디스크에 없어 cp 에서 빠진다.
        //   그러면 BOOTSTRAP 이 Minecraft.class 를 못 찾고
        //   "Could not find net/minecraft/client/Minecraft.class" 로 죽는다.
        //   (두 번째 실행에서도 cp 는 이 시점에 다시 구성되므로 동일하게 빠진다)
        //
        //   해결: 파일이 아직 없어도 그 경로를 cp 에 넣어둔다. processor 가 launch 도중 그 경로에
        //   생성하고, BootstrapLauncher 가 java.class.path 를 읽는 시점(processor 실행 후)엔
        //   이미 존재한다. NeoForge 는 좌표 자동로드라 해당 없음 → Forge 에만 적용.
        if (isModernLoader && !isNeoForge) {
            val forgeRoot = File(File(instanceBase, "libraries"), "net/minecraftforge/forge")

            // 🔧 FIX(첫 실행 크래시): 패치된 게임 jar(forge-<ver>-client.jar, Minecraft.class 포함)는
            //   binarypatcher 가 JVM 안에서 만들기 때문에 "첫 실행" 시점엔 디스크에 아직 없다.
            //   기존엔 instanceMeta.loaderVersion 으로 경로를 합성했는데, 그게 null/형식불일치면 합성이
            //   실패해 첫 실행 cp 에서 이 jar 가 통째로 빠졌다(=첫 실행만 크래시, 2번째부터는 파일이 생겨
            //   walk 가 주워서 정상). → 추측 대신, 설치 단계에서 생성되어 첫 실행에도 항상 존재하는
            //   forge-install-data.properties 의 binarypatcher 출력 경로를 직접 읽어 선등록한다.
            val installDataClientJars = forgeClientJarsFromInstallData(mcDir, instanceBase)
            installDataClientJars.forEach { path ->
                if (!jarList.contains(path)) {
                    jarList.add(path)
                    Log.d("TURTLE_LAUNCHER", "✅ Forge client jar 선등록(install-data 기준): $path")
                } else {
                    Log.d("TURTLE_LAUNCHER", "ℹ️ Forge client jar 이미 classpath 에 있음(install-data): ${File(path).name}")
                }
            }

            // 디스크에 이미 생성돼 있으면 그것도 포함(2번째 이후 실행/재설치 등).
            val forgeClientJars: List<File> = (forgeRoot.listFiles()?.toList() ?: emptyList())
                .filter { it.isDirectory }
                .flatMap { verDir ->
                    (verDir.listFiles()?.toList() ?: emptyList())
                        .filter { it.name.endsWith("-client.jar") }
                }
            forgeClientJars.forEach { cj ->
                if (!jarList.contains(cj.absolutePath)) {
                    jarList.add(cj.absolutePath)
                    Log.d("TURTLE_LAUNCHER", "✅ Forge client jar classpath 포함: ${cj.absolutePath}")
                } else {
                    Log.d("TURTLE_LAUNCHER", "ℹ️ Forge client jar 이미 classpath 에 있음: ${cj.name}")
                }
            }

            // 최후 보루: install-data 도 못 읽고 디스크에도 없을 때만 loaderVersion 으로 합성.
            if (installDataClientJars.isEmpty() && forgeClientJars.isEmpty()) {
                val lv = instanceMeta?.loaderVersion
                if (lv != null) {
                    // loaderVersion 이 "54.1.14" 또는 "1.21.4-54.1.14" 둘 다 올 수 있으므로 정규화.
                    val full = if (lv.startsWith("$versionId-")) lv else "$versionId-$lv"
                    val synth = File(forgeRoot, "$full/forge-$full-client.jar")
                    if (!jarList.contains(synth.absolutePath)) {
                        jarList.add(synth.absolutePath)
                        Log.d("TURTLE_LAUNCHER", "✅ Forge client jar 선등록(합성 경로, 최후 보루): ${synth.absolutePath}")
                    }
                } else {
                    Log.w("TURTLE_LAUNCHER", "⚠️ Forge client jar 경로를 확정할 수 없음(install-data·디스크·loaderVersion 모두 없음) — getMinecraftPaths 실패 위험")
                }
            }
        }

        val assetsDir = searchDirs
            .map { File(it, "assets") }
            .firstOrNull { File(it, "indexes").exists() && File(it, "indexes").listFiles()?.isNotEmpty() == true }
            ?: File(getExternalFilesDir(null) ?: base, "assets")

        // pre-1.6 (map_to_resources/virtual) 에셋이면 objects/<hash> 를 평문 경로로 펼친다.
        //   1.2.5/1.5.2 등은 이 펼친 디렉터리를 ${game_assets}/${assets_root} 로 받아야
        //   lang/en_US.lang 같은 리소스를 읽을 수 있다(안 하면 Display.create 직후 NPE 로 멈춤).
        //   null 이면 1.7+ 표준 에셋이므로 기존 assetsDir 를 그대로 쓴다.
        val legacyAssetsRoot: File? = bytecodePatchRepo.prepareLegacyResources(assetsDir, mcDir, assetIndex)
        val gameAssetsPath = (legacyAssetsRoot ?: assetsDir).absolutePath

        val irisConfig = File(mcDir, "config/iris.properties")
        if (!irisConfig.exists()) {                       // ← 이미 존재하면 손대지 않음 (지금도 이 조건은 있음)
            irisConfig.parentFile?.mkdirs()
            irisConfig.writeText("shaders.enabled=false\n")
        }

        // ── 1.12.x Forge SplashProgress 비활성화 ──
        //   1.12.x Forge 의 SplashProgress 는 별도 스레드에서 두 번째 OpenGL 컨텍스트
        //   (org.lwjgl.opengl.SharedDrawable)를 만들어 로딩 스플래시를 그린다. 그러나 모바일
        //   (PojavLauncher/OSMesa/Zink)은 단일 GL 컨텍스트만 지원하므로 SharedDrawable 생성이
        //   실패하고, 그 스레드의 Display.update()→glfwPollEvents() 가 StackOverflowError 로
        //   터지며 게임이 부팅조차 못 한다. (PojavLauncher 계열 공통 이슈; 표준 해결책은 스플래시
        //   비활성화) config/splash.properties 의 enabled=false 로 이 스레드 자체를 끈다.
        //   1.13+ Forge 는 SplashProgress 구조가 없어 해당 없음 → 1.12.x 에만 적용.
        //   ※ 파일이 이미 있어도(이전 실행에서 Forge 가 enabled=true 로 생성했을 수 있음)
        //     enabled 키만 false 로 강제 보정한다. 나머지 키는 보존.
        if (isForgeSplashProblematicVersion(versionId)) {
            try {
                val splashCfg = File(mcDir, "config/splash.properties")
                splashCfg.parentFile?.mkdirs()

                // 기존 내용 읽어 키맵 구성(있으면).
                val props = LinkedHashMap<String, String>()
                if (splashCfg.exists()) {
                    splashCfg.readLines().forEach { line ->
                        val t = line.trim()
                        if (t.isEmpty() || t.startsWith("#")) return@forEach
                        val eq = t.indexOf('=')
                        if (eq > 0) props[t.substring(0, eq).trim()] = t.substring(eq + 1).trim()
                    }
                }
                val before = props["enabled"]
                // 핵심: enabled 강제 false. 없으면 기본 키도 채워 둠.
                props["enabled"] = "false"
                if (!props.containsKey("rotate")) props["rotate"] = "false"
                if (!props.containsKey("logoOffset")) props["logoOffset"] = "0"

                if (before != "false") {   // 이미 false 면 다시 쓰지 않음
                    splashCfg.writeText(props.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n")
                    Log.d("TURTLE_LAUNCHER", "🩹 1.12.x SplashProgress 비활성화 적용(enabled=${before ?: "none"}→false) @ ${splashCfg.absolutePath}")
                } else {
                    Log.d("TURTLE_LAUNCHER", "🩹 1.12.x SplashProgress 이미 비활성화됨")
                }
            } catch (e: Exception) {
                Log.w("TURTLE_LAUNCHER", "splash.properties 쓰기 실패", e)
            }
        }


        // ★ versionId 전달
        val libJvmPath = MinecraftJREPreparer.prepareJreAndGetPath(this, versionId)
        val jvmSettings = JvmSettingsManager.load(this)

        // 모드 개수 — 첫 실행 시 모드팩이 무거우면 렌더 설정을 강제로 낮추기 위함.
        // disableUnsupportedMods 로 .pingdisabled 처리된 것도 실제 로드되진 않지만,
        // 메모리/로딩 부담은 "설치된 모드 수" 기준으로 보는 게 안전하므로 .jar / .jar.pingdisabled 둘 다 센다.
        val installedModCount = countInstalledMods(File(mcDir, "mods"))
        val optionsFile = File(mcDir, "options.txt")
        ensureBundledTurtleOptions(optionsFile)
        syncOptionsTxt(optionsFile, jvmSettings, installedModCount, versionId)
        writeTurtlePerformanceProfile(File(mcDir, "turtle_performance.properties"), jvmSettings)
        // Iris 셰이더 그림자 렌더 거리 최소(최초 1회) — config/iris.properties 의 maxShadowRenderDistance.
        syncIrisProperties(File(mcDir, "config/iris.properties"))
        // Forge/NeoForge early loading window(망치/여우 로딩 화면) 설정.
        // ZL2 와 동일하게 켜는 게 기본. 단, 모드팩(특히 Sinytra Connector 포함)은 early window 의
        //   acceptGameLayer → DisplayWindow.updateModuleReads 단계에서 게임 클래스
        //   (net.minecraft.client.gui.screens.LoadingOverlay)를 읽지 못해 NoClassDefFoundError 로
        //   크래시한다. 복잡한 모듈 구성 때문이므로 모드팩 인스턴스는 early window 를 끈다.
        //   (일반 Forge/NeoForge 는 켜서 망치/여우 애니메이션 유지)
        // (앱이 자기 외부 디렉토리 파일을 쓰는 것이라 권한 문제 없음)
        val enableEarlyWindow = !isPre117Version(versionId)
        if (isPre117Version(versionId)) {
            Log.d("TURTLE_LAUNCHER", "🪟 1.17 미만 — early window 비활성화")
        }
        syncFmlConfig(File(mcDir, "config/fml.toml"), enableEarlyWindow)

// ★ JDK 9+ 전용 플래그는 javaMajor>=9 일 때만 부착
        val isModularJre = javaMajor >= 9

// ★ JDK 8 에서는 미지원 옵션을 무시하도록 (G1NewSizePercent 등이 문제)
        val jvm8CompatArgs: Array<String> =
            if (!isModularJre) arrayOf("-XX:+IgnoreUnrecognizedVMOptions") else emptyArray()

        val launchWrapperArgs = if (isModularJre && mainClass.contains("launchwrapper")) {
            arrayOf(
                "--add-opens", "java.base/java.lang=ALL-UNNAMED",
                "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED",
                "--add-opens", "java.base/java.io=ALL-UNNAMED",
                "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
                "--add-exports", "java.base/jdk.internal.loader=ALL-UNNAMED",
                "--add-opens", "java.base/java.net=ALL-UNNAMED",
                "--add-opens", "java.base/java.util=ALL-UNNAMED",
                "--add-opens", "java.base/java.util.jar=ALL-UNNAMED",
                "--add-opens", "java.base/java.util.zip=ALL-UNNAMED",
            )
        } else emptyArray()

        val fabricJvmArgs = if (isModularJre && isFabric) {
            arrayOf(
                "--add-opens", "java.base/java.lang=ALL-UNNAMED",
                "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED",
                "--add-opens", "java.base/java.io=ALL-UNNAMED",
                "--add-opens", "java.base/java.net=ALL-UNNAMED",
                "--add-opens", "java.base/java.util=ALL-UNNAMED",
                "--add-opens", "java.base/java.util.jar=ALL-UNNAMED",
                "--add-opens", "java.base/java.util.zip=ALL-UNNAMED",
                "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
                "--add-exports", "java.base/jdk.internal.loader=ALL-UNNAMED",
            )
        } else emptyArray()



// ── ${library_directory} 등 placeholder 해석 ──
        val forgeLibrariesDir = File(instanceBase, "libraries")
        val metaJvmArgsRaw = (instanceMeta?.gameJvmArgs ?: emptyList())
            .map { raw ->
                raw
                    .replace("\${library_directory}",   forgeLibrariesDir.absolutePath)
                    .replace("\${libraries_directory}", forgeLibrariesDir.absolutePath)
                    .replace("\${classpath_separator}", File.pathSeparator)
                    .replace("\${version_name}",        versionId)
                    .replace("\${natives_directory}",   nativesDir.absolutePath)
            }

// ── BootstrapLauncher 의 -DignoreList 에 LWJGL 들 추가 ──
//   PojavLauncher patched lwjgl-glfw-classes.jar 는 다른 LWJGL 서브모듈(openal, opengl 등)
//   클래스도 통합 포함되어 있어서 자동 모듈로 잡히면 split package 충돌 발생.
//   ignoreList 에 prefix 매칭시키면 classpath unnamed module 로 남아 충돌 회피.
        val metaJvmArgs: Array<String> = metaJvmArgsRaw.map { arg ->
            if (isModernLoader && arg.startsWith("-DignoreList=")) {
                // 이미 들어있는지 확인 후 없으면 추가.
                //   ⚠️ ZL2 방식: 게임 jar(-srg/-slim/-srg-and-extra)는 ignoreList 에 넣지 않는다.
                //     게임 jar 를 classpath 에 포함시켜 NeoForge 가 minecraft 모듈로 만들어야 하는데,
                //     ignoreList 에 넣으면 module 변환에서 빠져 net.minecraft.* 가 다시 누락된다.
                //     프로세서 전용 jar(installertools/ForgeAutoRenamingTool 등)만 제외 유지.
                val needed = listOf(
                    "ForgeAutoRenamingTool", "BinaryPatcher", "binarypatcher",
                    "jarsplitter", "installertools", "vignette", "DiffPatch", "diffpatch",
                    "commons-collections4",
                    // 모드가 shade 한 라이브러리와 classpath 라이브러리의 split-package 충돌 회피:
                    //   KryptonReforged / AgeOfWeapons 등이 org.checkerframework.framework.qual 를
                    //   자기 jar 에 포함(shade)하는데, classpath 의 checker-qual.jar(=checker.qual 모듈)도
                    //   같은 패키지를 export 해 "Modules krypton and checker.qual export package
                    //   org.checkerframework.framework.qual" module resolution 충돌이 난다.
                    //   ignoreList 는 classpath jar 에만 적용되므로 checker-qual 을 넣으면 named module
                    //   이 안 되고(unnamed classpath 로 남음) 모드 shade 본만 모듈로 남아 충돌 해소.
                    "checker-qual"
                ).filterNot { arg.contains(",$it") || arg.endsWith("=$it") }

                if (needed.isNotEmpty()) {
                    val patched = arg + "," + needed.joinToString(",")
                    Log.d("TURTLE_LAUNCHER", "🩹 ignoreList 보강: +${needed.joinToString(",")}")
                    patched
                } else arg
            } else arg
        }.toTypedArray()

// ── Modern Forge fallback: 모듈 안 로드돼도 reflection 통과시키는 ALL-UNNAMED opens ──
        val modernForgeArgs: Array<String> = if (isModularJre && isModernLoader) {
            arrayOf(
                // ── Forge/NeoForge EarlyWindow(망치/여우 로딩 창) 비활성화 ──
                //   ZL2 와 동일하게 -Dfml.earlyprogresswindow=false 로 EarlyWindow 자체를 끈다.
                //   EarlyWindow(net.neoforged.fml.earlydisplay.DisplayWindow)는 자기 전용 스레드에서
                //   glfwMakeContextCurrent 없이 GL.createCapabilities() 를 호출하는데, PojavLauncher/
                //   OSMesa/MobileGlues 의 단일 EGL 컨텍스트 모델에선 그 스레드에 컨텍스트가 없어
                //   "no OpenGL context current in the current thread" 로 죽는다(확인됨).
                //   fml.toml 의 earlyWindowControl 이 아니라 이 시스템 프로퍼티가 실제 차단 스위치다.
                "-Dfml.earlyprogresswindow=false",
                "-Dfml.ignoreInvalidMinecraftCertificates=true",
                "-Dfml.ignorePatchDiscrepancies=true",
                "-Dloader.disable_forked_guis=true",
                "-Djava.awt.headless=true",
                // ── 진단: BootstrapLauncher 가 어떤 jar 를 모듈로 만들고 어떤 걸 제외하는지
                //   stdout 으로 출력하게 한다(bsl.debug). forge-client.jar 가 BOOTSTRAP 모듈
                //   레이어에 안 올라가는 이유(ignoreList/패키지소유/모듈명충돌)를 직접 확인용.
                "-Dbsl.debug=true",
                "--add-opens", "java.base/java.util.jar=ALL-UNNAMED",
                "--add-opens", "java.base/java.lang.invoke=ALL-UNNAMED",
                "--add-opens", "java.base/java.lang=ALL-UNNAMED",
                "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED",
                "--add-opens", "java.base/java.net=ALL-UNNAMED",
                "--add-opens", "java.base/java.io=ALL-UNNAMED",
                "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
                "--add-exports", "java.base/sun.security.util=ALL-UNNAMED",
            )
        } else emptyArray()

        Log.d("TURTLE_LAUNCHER",
            "isModernForge=$isModernLoader metaJvmArgs(resolved)=${metaJvmArgs.toList()}")

        var renderer = resolveRendererForVersion()

//        if (renderer.id == "zink" && !RendererProbe.nativeZinkCompatible()) {
//            Log.w("TURTLE_LAUNCHER", "⚠️ 이 기기는 Zink 미호환 — Holy GL4ES로 자동 폴백")
//            runOnUiThread {
//                Toast.makeText(
//                    this,
//                    "이 기기의 GPU는 Zink 미지원 — GL4ES로 전환합니다",
//                    Toast.LENGTH_LONG
//                ).show()
//            }
//            renderer = Renderer.fromId("gl4es")  // 또는 "gl4es" — 둘 중 가용한 것
//        }


        val glLibName = when (renderer.id) {
            // ⚠️ 절대경로로 준다. 이름만 주면 LWJGL 이 librarypath 를 뒤져 files/natives/ 의
            //    옛 복사본을 열고, SDL 은 APK 안의 것을 연다 — 26.3 은 두 라이브러리의
            //    glGetError 주소를 비교하므로 그 순간 "glGetError mismatch" 로 거절한다.
            "mobileglues" -> File(applicationInfo.nativeLibraryDir, "libmobileglues.so").absolutePath
            "krypton"     -> "libng_gl4es.so"   // 내부 번들 (jniLibs)
            "ltw"          -> File(applicationInfo.nativeLibraryDir, "libltw.so").absolutePath
            "gl4es", "gl4es_desktop" -> "libgl4es_114.so"
            "zink" -> "libOSMesa.so"
            else   -> "libgl4es_114.so"
        }

        Log.i("TurtleLauncherJVM", "🎨 Selected glLibName=$glLibName (renderer=${renderer.id})")

        // ── LWJGL 렌더러 라이브러리 지정 (ZL2 GameLauncher.progressFinalUserArgs 와 동일) ──
        //   ZL2 는 렌더러 종류와 무관하게 항상 -Dorg.lwjgl.opengl.libname 을 emit 한다
        //   (zink 포함; VulkanZinkRenderer.getRendererLibrary() = libOSMesa_8.so).
        //   이 프로퍼티가 없으면 LWJGL 의 GL.create() 가 시스템 기본(데스크탑 GLX) 경로로
        //   폴백해 libGLX.so.0 을 찾다가 UnsatisfiedLinkError 로 죽는다(안드로이드엔 GLX 없음).
        //   → zink 도 반드시 libOSMesa.so 를 명시해야 한다. (과거 zink 를 emptyArray 로
        //     비웠던 것이 libGLX 크래시의 원인이었다.)
        //   MobileGlues 는 GLES3 백엔드라 opengles 경로로도 GL 심볼을 찾을 수 있어 opengles 도 함께 준다
        //   (zink/gl4es 에는 불필요하지만 무해).
        val rendererLibArgs: Array<String> = arrayOf(
            "-Dorg.lwjgl.opengl.libname=$glLibName",
            "-Dorg.lwjgl.opengles.libname=$glLibName"
        )
        Log.i("TurtleLauncherJVM", "🎨 lwjgl libname args=${rendererLibArgs.joinToString()}")

        // ── classpath 중복 제거 ─────────────────────────────────────
        val seenAbs = HashSet<String>()
        val seenFileName = HashSet<String>()
        val originalSize = jarList.size
        var dedupedJars = jarList.filter { abs ->
            if (!seenAbs.add(abs)) {
                Log.d("TURTLE_LAUNCHER", "🗑 절대경로 중복 jar 제거: $abs")
                return@filter false
            }
            val fname = File(abs).name
            if (!seenFileName.add(fname)) {
                Log.d("TURTLE_LAUNCHER", "🗑 동일 파일명 jar 중복 제거: $fname")
                return@filter false
            }
            true
        }

        if (isModernLoader) {
            val moduleJarsFromMp = mutableSetOf<String>()
            var i = 0
            while (i < metaJvmArgs.size) {
                val a = metaJvmArgs[i]
                val mpValue: String? = when {
                    a.startsWith("--module-path=") -> a.removePrefix("--module-path=")
                    a == "--module-path" || a == "-p" -> metaJvmArgs.getOrNull(i + 1)
                    else -> null
                }
                if (mpValue != null) {
                    mpValue.split(File.pathSeparator)
                        .map { File(it).name }
                        .filter { it.isNotBlank() }
                        .forEach { moduleJarsFromMp.add(it) }
                }
                i++
            }
            Log.d("TURTLE_LAUNCHER", "🎯 module-path jars (${moduleJarsFromMp.size}): $moduleJarsFromMp")

            val before = dedupedJars.size
            dedupedJars = dedupedJars.filter { abs ->
                val name = File(abs).name
                when {
                    name in moduleJarsFromMp -> {
                        Log.d("TURTLE_LAUNCHER", "🚫 module-path 에 있어 classpath 제외: $name")
                        false
                    }
                    // processor-launcher 분기 자체를 삭제 — 이 jar 는 mainClass 일 때 classpath 필수
                    else -> true
                }
            }
            Log.d("TURTLE_LAUNCHER", "📦 modern Forge classpath 정리: $before → ${dedupedJars.size}")
        }

        if (dedupedJars.size != originalSize) {
            Log.d("TURTLE_LAUNCHER", "📦 classpath dedupe total: $originalSize → ${dedupedJars.size}")
        }

        // 🎙 마이크 브릿지(javax.sound.sampled.spi.MixerProvider 구현) — 모든 인스턴스에
        //   공통으로 추가. 음성채팅 모드가 없으면 그냥 안 쓰이고 넘어갈 뿐이라 안전하다.
        val micBridgeJar = File(base, "turtle-mic-bridge.jar")
        if (micBridgeJar.exists()) {
            dedupedJars = dedupedJars + micBridgeJar.absolutePath
        }

        val classPathStr = dedupedJars.joinToString(File.pathSeparator)


        val dnsArgs = arrayOf(
            "-Djava.net.preferIPv4Stack=true",          // ★ 추가 — IPv6 시도 자체를 막음
            "-Djava.net.preferIPv4Addresses=true",      // ★ 추가 (보강)
            // JNDI DNS 공급자 강제 설정 (SRV 조회 해결의 핵심)
            "-Djava.naming.provider.url=${DnsHookNative.getActiveDnsServers().joinToString(" ") { "dns://$it" }}",
            "-Dnetworkaddress.cache.ttl=0",
            "-Dnetworkaddress.cache.negative.ttl=0",
            "-Dsun.net.inetaddr.ttl=0",
            "-Dsun.net.inetaddr.negative.ttl=0"
        )

        // ── JNI 디버그 인자 (간헐적 Scudo 힙 손상 원인 추적용) ──
        // -Xcheck:jni 로 추적한 결과 JNI 는 무죄였고, 원인은 String Deduplication(GC)로 확인됨.
        // 추적이 끝났으므로 false. 다시 JNI 디버깅이 필요하면 true 로.
        val ENABLE_JNI_CHECK = false
        val jniDebugArgs = if (ENABLE_JNI_CHECK)
            arrayOf("-Xcheck:jni")
        else
            emptyArray()

        // ── LWJGL FreeType 네이티브 강제 지정 (ZL2 방식) ──
        // 게임이 LWJGL 3.3.6 으로 도는데 .lwjgl/3.3.6/ 추출 디렉터리에 네이티브가
        // prepopulate 되지 않으면 libfreetype.so 를 못 찾아 "Default font failed to load" 로 크래시한다.
        // 버전별 추출 디렉터리에 의존하지 않도록, 앱에 포함된 arm64 libfreetype.so 의
        // 절대경로를 LWJGL 에 직접 지정한다. (실제 인자 주입/중복 제거는 normalizeJvmArgsForJni 에서 수행)
        val freetypeSo = File(applicationInfo.nativeLibraryDir, "libfreetype.so")
        if (freetypeSo.exists()) {
            Log.d("TURTLE_LAUNCHER", "🔤 freetype 강제 지정: ${freetypeSo.absolutePath}")
        } else {
            Log.w("TURTLE_LAUNCHER", "⚠️ libfreetype.so 가 nativeLibraryDir 에 없음 — 폰트 로딩 실패 가능")
        }

        // ── 26.3+ (SDL3 · LWJGL 3.4.3) 전용 인자 ──────────────────────────────
        //
        // ⚠️ 라이브러리 이름은 **절대경로**로 준다. 26.3 의 NativeLibrariesBootstrap 이
        //    추출 경로를 `<extract>/<LWJGL 버전>/<아키텍처>` 로 바꿔치기해서 우리 폴더를
        //    더 이상 보지 않기 때문이다(iOS 에서 실측). LWJGL 의 loadNative 는 이름이
        //    절대경로면 가장 먼저 그걸 그대로 연다.
        val lwjgl34Dir = if (sdlMode) prepareLwjgl34Natives().absolutePath else null
        val sdlStackArgs: Array<String> = if (!sdlMode) emptyArray() else {
            val libDir = applicationInfo.nativeLibraryDir
            arrayOf(
                // ⚠️ 할당자를 시스템 것으로 못박는다. classpath 에 lwjgl-jemalloc jar 이 있으면
                //    LWJGL 이 그걸 고르는데, 안드로이드용 libjemalloc.so 는 없다. 그러면
                //    MemoryUtil 초기화가 죽고 STB·Vulkan 로딩까지 연쇄로 무너진다(실측 크래시).
                "-Dorg.lwjgl.system.allocator=system",
                // ⚠️ 절대경로로 준다. 이름으로 주면 JVM 쪽 로더가 이미 열린 것을 재사용하는데,
                //    그 인스턴스는 게임이 기대하는 초기화 상태가 아니어서 SDL_Init 이 거절한다
                //    (실측: "did you include SDL_main.h ..."). 게임은 자기 인스턴스를 연다.
                "-Dorg.lwjgl.sdl.libname=$libDir/libSDL3.so",
                // ⚠️ shaderc 는 26.3 전용 판을 쓴다. 앱에 번들된 것은 PojavLauncher 판이라
                //    LWJGL 3.4.3 이 요구하는 shaderc_compile_options_set_max_id_bound 가 없고,
                //    진입점이 하나만 없어도 클래스 초기화가 통째로 실패한다(기기 실측).
                "-Dorg.lwjgl.shaderc.libname=$lwjgl34Dir/libshaderc.so",
                "-Dorg.lwjgl.spvc.libname=$libDir/libspirv-cross.so",
                "-Dorg.lwjgl.openal.libname=$libDir/libopenal.so",
                // 안드로이드는 Vulkan 이 시스템 라이브러리다 — 이름만 주면 로더가 찾는다.
                "-Dorg.lwjgl.vulkan.libname=libvulkan.so",
            )
        }
        // 3.4.3 네이티브를 **앞에** 둔다. 뒤쪽 natives/ 에는 3.3.3 판 liblwjgl.so 가 있어서
        // 순서가 뒤집히면 jar 와 버전이 어긋난 쪽이 먼저 잡힌다.
        val effectiveLibraryPath = lwjgl34Dir?.let { "$it${File.pathSeparator}${nativesDir.absolutePath}" }
            ?: nativesDir.absolutePath

        // Prepare the optional local skin agent before the JVM argument array is built.
        // The game JVM is started from the thread below, so all agent files/server state
        // must already exist here.
        val localAccountForSkin = if (MicrosoftAuthManager.loadSession(this) == null) {
            LocalAccountManager.load(this)
        } else null
        val localSkinArgs: Array<String> = if (localAccountForSkin != null && LocalSkinManager.hasSkin(this)) {
            val skinPort = LocalSkinServer.ensureStarted(this, localAccountForSkin)
            val injector = extractAuthlibInjector()
            if (skinPort > 0 && injector != null) {
                arrayOf("-javaagent:${injector.absolutePath}=http://127.0.0.1:$skinPort/api/yggdrasil")
            } else emptyArray()
        } else emptyArray()

        // Third-party auth is resolved on the game thread below. Keep the saved
        // record here so preparing JVM arguments never performs network IO on the
        // activity thread (Battly's agent may need a manifest download).
        val thirdPartyForLaunch: ThirdPartyAccount? = if (
            MicrosoftAuthManager.loadSession(this) == null && localAccountForSkin == null
        ) ThirdPartyAuthManager.load(this) else null

        val jvmArgs = jvm8CompatArgs +
                localSkinArgs +
                jniDebugArgs +
                sdlStackArgs +
                jvmSettings.toJvmArgArray(
                    context = this,
                    mcDir = mcDir,
                    userDir = mcDir.absolutePath,
                    classPath = classPathStr,
                    libraryPath = effectiveLibraryPath,
                    mainClass = mainClass,
                    versionId = versionId,
                    // resolveRendererForVersion() 으로 이미 해석한 렌더러를 그대로 넘겨
                    // JVMSettings 내부 RendererManager.load 와의 불일치를 없앤다.
                    // (이 불일치가 opengl.libname 중복/충돌의 근본 원인이었다)
                    rendererId = renderer.id
                ) +
                dnsArgs +
                rendererLibArgs +     // ★ -Dorg.lwjgl.opengl.libname (MobileGlues GL 함수 로딩에 필수)
                launchWrapperArgs +
                fabricJvmArgs +
                modernForgeArgs +     // ★ 추가 — metaJvmArgs 보다 앞에 둬서 version.json 인자가 덮어쓰도록
                metaJvmArgs

        Log.d("TURTLE_LAUNCHER", "버전: $versionId, mcDir: ${mcDir.absolutePath}, isFabric=$isFabric, javaMajor=$javaMajor")

        Log.d("TURTLE_LAUNCHER", "═══ classpath 항목 ${dedupedJars.size}개 ═══")
        dedupedJars.forEachIndexed { i, p -> Log.d("TURTLE_LAUNCHER", "  [$i] ${File(p).name}") }

        // SDL2 기반 모드(controllable)는 데스크톱 glibc 네이티브(libm.so.6 의존)를 번들해
        // Android(bionic)에서 로드 불가 → 부팅 직전 .jar 확장자를 바꿔 로드에서 제외한다.
        disableUnsupportedMods(File(mcDir, "mods"))

        // Sodium 전체화면 해상도 슬라이더가 Android 가상 디스플레이(getModeCount()==0)에서
        // ESC → 비디오 설정 진입 시 IllegalArgumentException 으로 죽는 것을 방지.
        // SliderControl.<init> 의 max 를 보정해 어떤 0-범위 슬라이더도 안전하게 만든다.
        bytecodePatchRepo.patchSodiumSliderIfNeeded(File(mcDir, "mods"))

        Log.d("TURTLE_LAUNCHER", "═══ mods/ 폴더 ═══")
        File(mcDir, "mods").listFiles()?.forEach {
            Log.d("TURTLE_LAUNCHER", "  ${it.name} (${it.length()}B)")
        }

        Thread {
            try {
                // ── Forge/NeoForge: patched client jar 가 없으면 게임 JVM 부팅 "전"에 빌더로 생성 (ZL2 방식) ──
                //   turtlejvm 의 JNI_CreateJavaVM 은 프로세스당 1회뿐이라, 게임 JVM 안에서 processor 를 돌려
                //   forge-<ver>-client.jar 를 만들고 같은 JVM 에서 ForgeBootstrap 을 부르면, 부팅 시점에 그 jar 가
                //   없어 모듈 인덱스가 죽고 getMinecraftPaths 가 Minecraft.class 를 못 찾는다(첫 실행만 크래시).
                //   → processor 실행을 :forgebuilder 별도 프로세스에서 먼저 끝내 jar 를 디스크에 만든 뒤,
                //     이 프로세스의 게임 JVM 은 ForgeBootstrap 진입점으로 깨끗하게 부팅한다.
                if (isModernLoader && !mainClass.contains("ProcessorLauncher", ignoreCase = true)) {
                    val ensured = ensureForgePatchedJar(
                        mcDir = mcDir,
                        instanceBase = instanceBase,
                        dedupedJars = dedupedJars
                    )
                    if (!ensured) {
                        Log.e("TURTLE_LAUNCHER", "❌ Forge 구성 요소 생성 실패 — 게임 부팅 중단")
                        writeSyntheticCrashReport(
                            instanceBase,
                            "Forge 구성 요소 생성 실패",
                            Exception("빌더(:forgebuilder) 프로세스가 patched jar 생성에 실패했습니다. " +
                                    "메모리 부족, 타임아웃, 또는 프로세서 실행 오류일 수 있습니다.")
                        )
                        runOnUiThread {
                            finish()
                            CrashReportActivity.start(this, instanceBase.absolutePath)
                        }
                        return@Thread
                    }
                }

                val microsoftSession = MicrosoftAuthManager.loadSession(this)
                val validMicrosoftSession = if (microsoftSession != null && !MicrosoftAuthManager.isSessionValid(microsoftSession)) {
                    try {
                        MicrosoftAuthManager.refreshSession(microsoftSession.refreshToken)
                            .also { MicrosoftAuthManager.saveSession(this, it) }
                    } catch (_: Exception) { microsoftSession }
                } else microsoftSession
                val localAccount = if (validMicrosoftSession == null) LocalAccountManager.load(this) else null
                val thirdPartyAccount = if (validMicrosoftSession == null && localAccount == null) {
                    thirdPartyForLaunch?.let { ThirdPartyAuthManager.refreshIfPossible(this, it) }
                } else null
                val thirdPartyJvmArgs: Array<String> = thirdPartyAccount?.let {
                    runCatching {
                        val bundled = extractAuthlibInjector()
                        if (bundled == null) emptyArray<String>()
                        else ThirdPartyAuthManager.jvmArgs(this, it, bundled)
                    }.onFailure { error ->
                        Log.w("TURTLE_LAUNCHER", "Third-party authlib could not be prepared", error)
                    }.getOrDefault(emptyArray())
                } ?: emptyArray()

                // Offline accounts are explicit: they use a stable UUID and zero token, never
                // a fabricated Microsoft credential. Third-party accounts use their own
                // Yggdrasil token/profile and authlib-injector agent.
                val username = validMicrosoftSession?.username
                    ?: thirdPartyAccount?.profileName
                    ?: localAccount?.username
                    ?: "Player"
                val uuid = validMicrosoftSession?.uuid
                    ?: thirdPartyAccount?.profileId
                    ?: localAccount?.uuid
                    ?: "00000000-0000-0000-0000-000000000000"
                val accessToken = validMicrosoftSession?.accessToken
                    ?: thirdPartyAccount?.accessToken
                    ?: "0"
                val userType = when {
                    validMicrosoftSession != null -> "msa"
                    thirdPartyAccount != null -> "mojang"
                    else -> "legacy"
                }
                val launchJvmArgs = jvmArgs + thirdPartyJvmArgs

                // 매니페스트가 minecraftArguments(=공백 구분 단일 문자열)를 줬다면 그게 1.12 이하 레거시 포맷이다.
                // gameArgs 안에 ${...} placeholder가 있다는 사실 자체가 그 시그널.
                val legacyArgs = instanceMeta?.gameArgs.orEmpty()
                val isLegacyArgs = legacyArgs.any { it.contains("\${") }

                val mcArgs: Array<String> = if (isLegacyArgs) {
                    // ── 1.12 이하: placeholder 치환만 해서 그대로 사용 ──
                    val placeholders = mapOf(
                        "\${auth_player_name}"  to username,
                        "\${auth_session}"      to "token:$accessToken:$uuid", // 1.5.x 시절 단일 토큰 포맷
                        "\${auth_uuid}"         to uuid,
                        "\${auth_access_token}" to accessToken,
                        "\${version_name}"      to versionId,
                        "\${game_directory}"    to mcDir.absolutePath,
                        "\${game_assets}"       to gameAssetsPath,           // pre-1.6: 펼친 resources 경로
                        "\${assets_root}"       to gameAssetsPath,
                        "\${assets_index_name}" to assetIndex,
                        "\${user_type}"         to userType,
                        "\${version_type}"      to if (isFabric) "Fabric" else "release",
                        "\${user_properties}"   to "{}",
                        "\${profile_name}"      to username,
                        "\${launcher_name}"     to "TurtleLauncher",
                        "\${launcher_version}"  to "1.0"
                    )
                    val resolved = legacyArgs.map { arg ->
                        placeholders.entries.fold(arg) { acc, (k, v) -> acc.replace(k, v) }
                    }
                    Log.d("TURTLE_LAUNCHER", "legacy mcArgs (${resolved.size}): $resolved")
                    resolved.toTypedArray()
                } else {
                    // ── 1.13+ (또는 Fabric/모드팩): 기존 하드코딩 + 메타 추가 인자 ──
                    val baseMcArgs = arrayOf(
                        "--username",   username,
                        "--version",    versionId,
                        "--gameDir",    mcDir.absolutePath,
                        "--assetsDir",  assetsDir.absolutePath,
                        "--assetIndex", assetIndex,
                        "--uuid",       uuid,
                        "--accessToken",accessToken,
                        "--userType",   userType,
                        "--versionType",if (isFabric) "Fabric" else "release"
                    )
                    val metaGameArgs = legacyArgs.toTypedArray() // placeholder 없는 추가 인자만 들어옴
                    baseMcArgs + metaGameArgs
                }

                val launcher = JavaNativeLauncher()
                // ⚠️ 26.3(SDL) 에서는 **MobileGlues 기준**으로 env 를 만든다.
                //    인스턴스 렌더러가 GL4ES/Zink 로 잡혀 있으면 MG_DIR_PATH·config.json 이
                //    안 만들어지고, 설정 없이 뜬 MobileGlues 는 GL 버전을 낮게 보고한다
                //    (마인크래프트는 GL 3.3 이상을 요구한다).
                val envRenderer = if (sdlMode) Renderer.MOBILEGLUES else renderer
                val rendererEnv = envRenderer.buildEnv(
                    cacheDir = applicationContext.cacheDir.absolutePath,
                    nativeDir = applicationInfo.nativeLibraryDir,
                    // GL4ES 로 안전하게 구동 가능한 버전(1.13 미만)이면 Zink 실패 시
                    // Freedreno/Panfrost 를 건너뛰고 곧바로 GL4ES 로 폴백시킨다.
                    preferGl4esOnZinkFallback = isPre113Version(versionId),
                    // 사용자가 설정에서 커스텀 Vulkan 드라이버(Turnip 등)를 선택해뒀으면 전달.
                    customVulkanDriver = com.endiq.turtlelauncher.data.renderer.CustomVulkanDriverManager
                        .getActiveDriverPathAndName(applicationContext),
                ).toMutableMap().apply {
                    if (sdlMode) {
                        // SDL 은 GL 라이브러리를 이 힌트(환경변수)로 고른다. 26.3 의 GL 백엔드가
                        // LWJGL 쪽 주소와 비교하므로 **같은 파일**을 가리켜야 한다.
                        sdlGlLibPath()?.let { this["SDL_OPENGL_LIBRARY"] = it }
                    }
                    // 설정·로그를 읽을 수 있는 자리로 통일한다(buildEnv 가 넣은 내부 경로를 덮어쓴다).
                    this["MG_DIR_PATH"] = mobileGluesDir().absolutePath
                    // Keep the EGL/native swap interval in lockstep with options.txt. The
                    // old code only disabled this for unlockFps, which made the advertised
                    // FPS setting ineffective on some renderers.
                    this["FORCE_VSYNC"] = jvmSettings.enableVsync.toString()
                    this["POJAV_VSYNC"] = if (jvmSettings.enableVsync) "1" else "0"
                    this["LIBGL_VSYNC"] = if (jvmSettings.enableVsync) "1" else "0"
                    this["POJAV_VSYNC_IN_ZINK"] = if (jvmSettings.enableVsync) "1" else "0"
                    this["TURTLE_FRAME_PACING"] = jvmSettings.framePacing.toString()
                    this["TURTLE_FRAME_SKIPPING"] = jvmSettings.frameSkipping.toString()
                    this["TURTLE_ADAPTIVE_FRAME_TIMING"] = jvmSettings.adaptiveFrameTiming.toString()
                    this["TURTLE_LOW_LATENCY_RENDERING"] = jvmSettings.lowLatencyRendering.toString()
                }

                Log.d("TURTLE_LAUNCHER", "🎨 적용된 렌더러: ${envRenderer.displayName}" +
                        if (sdlMode) " (26.3 GL 백엔드 — 인스턴스 설정 ${renderer.displayName} 대신)" else "")
                rendererEnv.forEach { (k, v) -> Log.d("TURTLE_LAUNCHER", "  env $k=$v") }
                launcher.applyEnv(rendererEnv)

                // JNA 네이티브(libjnidispatch.so) 강제 지정 (ZL2 방식).
                // jniLibs 에 번들된 arm64 libjnidispatch.so 가 nativeLibraryDir 에 있으므로,
                // 그 경로를 jna.boot.library.path 로 지정해 추출 없이 직접 로드하게 한다.
                val jnaDispatch = File(applicationInfo.nativeLibraryDir, "libjnidispatch.so")
                val jnaBootPath = if (jnaDispatch.exists()) {
                    Log.d("TURTLE_LAUNCHER", "🧩 JNA 부트 경로: ${applicationInfo.nativeLibraryDir}")
                    applicationInfo.nativeLibraryDir
                } else {
                    Log.w("TURTLE_LAUNCHER", "⚠️ libjnidispatch.so 가 nativeLibraryDir 에 없음 — JNA 모드 크래시 가능")
                    null
                }

                val normalizedJvmArgs = normalizeJvmArgsForJni(
                    launchJvmArgs,
                    freetypeLibPath = if (freetypeSo.exists()) freetypeSo.absolutePath else null,
                    jnaBootPath = jnaBootPath,
                    jnaTmpDir = cacheDir.absolutePath,
                    sdlLibraryPath = if (sdlMode) effectiveLibraryPath else null,
                )

                Log.d("TURTLE_LAUNCHER", "정규화 후 JVM 인자 ${normalizedJvmArgs.size}개")
                normalizedJvmArgs.forEachIndexed { idx, a ->
                    if (a.startsWith("--add-") || a.startsWith("--module-path") || a.startsWith("--patch-module")) {
                        Log.d("TURTLE_LAUNCHER", "  [$idx] $a")
                    }
                }

                startShowingWindowWatchdog()   // 빌드 끝난 "뒤"부터 120s 카운트
                launcher.bootMinecraftJVM(libJvmPath, normalizedJvmArgs, mcArgs)
            } catch (e: Exception) {
                Log.e("TURTLE_LAUNCHER", "MC 실행 예외: ${e.message}", e)
                // ⚠️ 예전엔 로그만 찍고 끝 — Minecraft 자체가 crash-reports/*.txt 를 쓸 만큼도
                //   진행 못 한 초반 단계(빌더 실패, JVM 부팅 실패 등)에서 예외가 나면 사용자는
                //   화면에 아무 피드백도 못 받고 그냥 멈춘 것처럼 보였다. 이제는 이런 "런처 레벨"
                //   예외도 합성 크래시 리포트를 써서 CrashReportActivity 로 넘긴다.
                writeSyntheticCrashReport(instanceBase, "Minecraft 실행 중 치명적 예외", e)
            } finally {
                val crashDir = File(instanceBase, "crash-reports")
                val files = crashDir.listFiles()
                val hasCrash = files
                    ?.any { it.extension == "txt" &&
                            System.currentTimeMillis() - it.lastModified() < 60_000 } == true
                if (!hasCrash) {
                    // ⚠️ 26.x 는 종료할 때 System.exit 을 부르지 않고 main 을 그냥 반환한다
                    //    (26.2 도 26.3 과 똑같이 종료 시 멈췄다 — 실측).
                    //    그 뒤 아무것도 안 하면 마지막 화면이 멈춘 채로 남고, 몇 초 뒤
                    //    마인크래프트의 종료 감시기가 "shutdown hang" 으로 강제로 끊는다(실측).
                    //    main 이 돌아왔다는 건 게임이 끝났다는 뜻이다 — 바로 닫는다.
                    //    (_exit 는 안드로이드가 강제 종료로 보고 팝업을 띄운다. System.exit 는
                    //     ART 의 정상 종료 경로라 그렇지 않다. 이 프로세스는 게임 전용이다.)
                    Log.i("TURTLE_LAUNCHER", "🏁 main 반환 — 게임 종료")
                    runOnUiThread {
                        finishAndRemoveTask()
                        android.os.Handler(mainLooper).postDelayed({ System.exit(0) }, 300)
                    }
                }
                if (hasCrash) {
                    runOnUiThread {
                        finish()
                        CrashReportActivity.start(this, instanceBase.absolutePath)
                    }
                }
            }
        }.start()
    }

    /**
     * Minecraft/Forge 자체가 crash-reports 폴더에 txt 파일을 쓸 만큼도 진행하지 못한
     * "런처 레벨" 실패(Forge 빌더 실패, JVM 부팅 예외 등)를 CrashReportActivity 에서
     * 볼 수 있는 형태의 합성 크래시 리포트로 기록한다. CrashReportViewModel 은
     * crash-reports 안에서 가장 최근 수정된 txt 파일을 그대로 읽어 보여주므로,
     * 이 파일도 동일하게 인식된다.
     */
    private fun writeSyntheticCrashReport(instanceBase: File, title: String, cause: Throwable) {
        try {
            val crashDir = File(instanceBase, "crash-reports")
            crashDir.mkdirs()
            val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd_HH.mm.ss", java.util.Locale.US)
                .format(java.util.Date())
            val file = File(crashDir, "turtle-launcher-$timestamp.txt")
            val stackTrace = java.io.StringWriter().also { sw ->
                cause.printStackTrace(java.io.PrintWriter(sw))
            }.toString()
            file.writeText(
                "---- TurtleLauncher (런처 레벨) 오류 ----\n" +
                "Time: ${java.util.Date()}\n" +
                "Description: $title\n\n" +
                "$stackTrace\n\n" +
                "-- 참고 --\n" +
                "이 리포트는 Minecraft/Forge 자체가 만든 크래시 로그가 아니라,\n" +
                "런처가 게임을 준비/실행하는 과정에서 잡은 예외를 기록한 것입니다.\n"
            )
            Log.d("TURTLE_LAUNCHER", "📝 합성 크래시 리포트 작성: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e("TURTLE_LAUNCHER", "합성 크래시 리포트 작성 실패: ${e.message}", e)
        }
    }


    private fun startShowingWindowWatchdog() {
        Thread {
            Log.d("TURTLE_LAUNCHER", "🔵 showingWindow 워치독 시작")
            val deadline = System.currentTimeMillis() + 120_000
            var attempts = 0
            var success = false
            // 초기에는 50ms 간격, 한 번 잡으면 5초 간격으로 재확인
            var interval = 50L

            while (System.currentTimeMillis() < deadline) {
                attempts++
                try {
                    if (nativeTrySetupShowingWindow()) {
                        if (!success) {
                            Log.d("TURTLE_LAUNCHER", "✅ showingWindow 첫 세팅 (시도 $attempts, 경과 ${attempts * 50}ms 이내)")
                            success = true
                            interval = 5000L   // 잡힌 뒤엔 느슨하게
                        }
                    } else if (attempts % 40 == 0 && !success) {
                        Log.d("TURTLE_LAUNCHER", "🔵 대기중... (시도 $attempts)")
                    }
                } catch (e: Throwable) {
                    Log.w("TURTLE_LAUNCHER", "워치독 예외: ${e.message}")
                }
                Thread.sleep(interval)
            }
            Log.d("TURTLE_LAUNCHER", "🔵 워치독 종료 (success=$success)")
        }.apply { isDaemon = true; start() }
    }

    /**
     * 물리 마우스(외장) 입력 처리.
     *
     * 터치(SurfaceView.setOnTouchListener)와 별개로, 마우스의 hover/move/scroll/버튼은
     * generic motion 으로 들어온다. 이를 마인크래프트로 전달해 커서가 보이고 움직이게 한다.
     *
     *  - 인게임(grab): 절대좌표가 아닌 "직전 위치와의 델타 × 감도"로 시점 회전
     *    (마인크래프트가 자체 십자선을 그리므로 OS 포인터는 숨긴다)
     *  - 메뉴(비 grab): 절대좌표를 그대로 커서 위치로 (OS 포인터도 자연히 보임)
     *  - 마우스 버튼: 좌/우/휠클릭 → GLFW 0/1/2
     *  - 스크롤: 가능하면 네이티브 스크롤로 전달(핫바 변경/줌). 바인딩 없으면 무시.
     */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        // 게임패드 스틱/트리거/HAT — 마우스보다 먼저 본다(패드는 SOURCE_JOYSTICK 로 온다).
        if (jvmStarted && gamepad.isGamepadEvent(event.source)) {
            if (gamepad.handleMotionEvent(event)) return true
        }

        // 마우스 계열 소스가 아니면 기본 처리.
        val isMouse = (event.source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE ||
                (event.source and InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE
        if (!isMouse || !jvmStarted) return super.onGenericMotionEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_MOVE -> {
                handleMouseMove(event)
                return true
            }
            MotionEvent.ACTION_BUTTON_PRESS -> {
                glfwButtonForActionButton(event.actionButton)?.let { sendMouseButton(it, GLFW_PRESS) }
                return true
            }
            MotionEvent.ACTION_BUTTON_RELEASE -> {
                glfwButtonForActionButton(event.actionButton)?.let { sendMouseButton(it, GLFW_RELEASE) }
                return true
            }
            MotionEvent.ACTION_SCROLL -> {
                val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                if (vScroll != 0f) sendMouseScroll(0f, vScroll)
                return true
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                // 마우스가 화면 밖으로 — 기준점 리셋(다음 진입 시 델타 튐 방지)
                lastMouseX = -1f
                lastMouseY = -1f
            }
        }
        return super.onGenericMotionEvent(event)
    }

    /** 마우스 이동 처리: grab 이면 델타 회전, 아니면 절대좌표. */
    private fun handleMouseMove(event: MotionEvent) {
        val x = event.x
        val y = event.y
        if (isGrabbing) {
            // 기준점이 없으면(첫 이벤트/재진입) 이번엔 기준만 잡고 델타는 보내지 않는다.
            if (lastMouseX < 0f) {
                lastMouseX = x; lastMouseY = y
                return
            }
            val dx = x - lastMouseX
            val dy = y - lastMouseY
            currentCursorX += dx * MOUSE_SENSITIVITY
            currentCursorY += dy * MOUSE_SENSITIVITY
            sendCursorPos(currentCursorX, currentCursorY)
        } else {
            // 메뉴/인벤토리: 절대좌표 그대로
            currentCursorX = x
            currentCursorY = y
            sendCursorPos(currentCursorX, currentCursorY)
        }
        lastMouseX = x
        lastMouseY = y
    }

    /** Android actionButton → GLFW 마우스 버튼(0=좌,1=우,2=휠). 미지원이면 null. */
    private fun glfwButtonForActionButton(actionButton: Int): Int? = when (actionButton) {
        MotionEvent.BUTTON_PRIMARY -> 0
        MotionEvent.BUTTON_SECONDARY -> 1
        MotionEvent.BUTTON_TERTIARY -> 2
        else -> null
    }

    /**
     * 마우스 휠 스크롤을 마인크래프트로 전달.
     * 네이티브 스크롤 바인딩(nativeSendScroll)이 있으면 사용하고, 없으면 조용히 무시.
     * (런처 빌드마다 JNI 이름이 다를 수 있어 reflection 으로 안전 호출)
     */
    private fun sendMouseScroll(xOffset: Float, yOffset: Float) {
        if (sdlMode) { nativeSdlSendScroll(xOffset, yOffset); return }
        try {
            val cb = Class.forName("org.lwjgl.glfw.CallbackBridge")
            // 흔한 시그니처: nativeSendScroll(double, double)
            val m = cb.getMethod("nativeSendScroll", Double::class.java, Double::class.java)
            m.invoke(null, xOffset.toDouble(), yOffset.toDouble())
        } catch (_: Throwable) {
            // 바인딩 없음 — 스크롤만 미동작(나머지 마우스 기능은 정상).
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // 마우스 소스면 우클릭, 아니면 ESC
            if (event.source and InputDevice.SOURCE_MOUSE != 0) {
                sendMouseButton(1, GLFW_PRESS)
            } else {
                sendKey(256, GLFW_PRESS) // ESC
            }
            return true
        }
        val glfwKey = androidKeyToGlfw(keyCode) ?: return false
        sendKey(glfwKey, GLFW_PRESS)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            sendKey(256, GLFW_RELEASE)
            return true
        }
        val glfwKey = androidKeyToGlfw(keyCode) ?: return false
        sendKey(glfwKey, GLFW_RELEASE)
        return true
    }


    private fun installPhysicalKeyboardInterceptor() {
        val originalCallback = window.callback
        window.callback = object : android.view.Window.Callback by originalCallback {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (handlePhysicalKey(event)) return true
                return originalCallback.dispatchKeyEvent(event)
            }
        }
    }

    private fun handlePhysicalKey(event: KeyEvent): Boolean {
        Log.d("TURTLE_LAUNCHER",
            "🔑 handlePhysicalKey: keyCode=${event.keyCode} " +
                    "action=${event.action} src=0x${event.source.toString(16)}")

        // JVM 부팅 전엔 무시
        if (!jvmStarted) return false

        // ★ ACTION_MULTIPLE 은 IME 가 합성한 이벤트 — 절대 가로채지 말 것
        if (event.action == KeyEvent.ACTION_MULTIPLE) return false

        // ★ 게임패드 버튼은 키보드 처리보다 먼저 가로챈다.
        //   상당수 패드가 SOURCE_GAMEPAD|SOURCE_KEYBOARD 로 보고해서, 아래 키보드 경로에
        //   먼저 걸리면 BUTTON_A 같은 게 엉뚱한 키로 나가거나 그냥 삼켜진다.
        if (gamepad.handleKeyEvent(event)) return true

        // ★ IME 가 처리해야 하는 키 — 한글 전환, 한자 등
        when (event.keyCode) {
            KeyEvent.KEYCODE_LANGUAGE_SWITCH,   // 한영 키
            KeyEvent.KEYCODE_KANA,
            KeyEvent.KEYCODE_HENKAN,
            KeyEvent.KEYCODE_MUHENKAN,
            KeyEvent.KEYCODE_EISU -> return false
        }

        val hasKeyboardSource =
            (event.source and InputDevice.SOURCE_KEYBOARD) ==
                    InputDevice.SOURCE_KEYBOARD

        if (!hasKeyboardSource) return false

        // 물리 키보드 / 외장 키보드만 가로채기
        // (소프트 IME 는 deviceId == -1 또는 KeyCharacterMap.VIRTUAL_KEYBOARD)
        val isPhysical =
            (event.source and InputDevice.SOURCE_KEYBOARD) != 0 &&
                    event.deviceId != android.view.KeyCharacterMap.VIRTUAL_KEYBOARD

        if (!isPhysical) return false

        // 시스템 키는 패스 (볼륨, 전원, 홈 등)
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_HOME -> return false
        }

        val isDown = event.action == KeyEvent.ACTION_DOWN
        val glfwAction = if (isDown) 1 else 0

        // 1) 특수키 우선 매핑 (androidKeyToGlfw 가 못 잡는 것들)
        val specialKey = when (event.keyCode) {
            KeyEvent.KEYCODE_DEL          -> 259  // Backspace
            KeyEvent.KEYCODE_FORWARD_DEL  -> 261  // Delete
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> 257
            KeyEvent.KEYCODE_DPAD_LEFT    -> 263
            KeyEvent.KEYCODE_DPAD_RIGHT   -> 262
            KeyEvent.KEYCODE_DPAD_UP      -> 265
            KeyEvent.KEYCODE_DPAD_DOWN    -> 264
            else -> null
        }

        if (specialKey != null) {
            sendKey(specialKey, glfwAction)
            return true
        }

        // 2) 일반 키 → GLFW 매핑
        // 일반 키 → GLFW 매핑
        val glfwKey = androidKeyToGlfw(event.keyCode)
        if (glfwKey != null) {
            sendKey(glfwKey, glfwAction)

            if (isDown) {
                var unicodeChar = event.getUnicodeChar(event.metaState)

                // ★ fallback: unicodeChar 가 0 이지만 우리가 아는 키면 직접 매핑
                if (unicodeChar == 0) {
                    unicodeChar = when (event.keyCode) {
                        KeyEvent.KEYCODE_SPACE -> ' '.code
                        KeyEvent.KEYCODE_TAB   -> '\t'.code
                        else -> 0
                    }
                }

                if (unicodeChar != 0) {
                    val glfwMods = (
                            (if (event.isShiftPressed) 0x0001 else 0) or
                                    (if (event.isCtrlPressed)  0x0002 else 0) or
                                    (if (event.isAltPressed)   0x0004 else 0)
                            )
                    sendCharToMc(unicodeChar.toChar(), glfwMods)
                }
            }
            return true
        }

        // 4) GLFW 매핑은 없지만 unicodeChar 가 있으면 (예: 한글, 특수문자)
        //    채팅창용으로 문자만 송신
        if (isDown) {
            val unicodeChar = event.getUnicodeChar(event.metaState)
            if (unicodeChar != 0) {
                sendCharToMc(unicodeChar.toChar())
                return true
            }
        }

        return false
    }

    private fun sendCharToMc(c: Char, mods: Int = 0) {
        Log.d("TURTLE_LAUNCHER", "📝 sendCharToMc: '$c' (0x${c.code.toString(16)}) mods=$mods")
        if (sdlMode) { nativeSdlSendChar(c.code); return }
        try {
            val cb = Class.forName("org.lwjgl.glfw.CallbackBridge")

            // 1) Char 콜백 (1.12 이하 + 일부 모드용)
            cb.getMethod("nativeSendChar", Char::class.java).invoke(null, c)

            // 2) CharMods 콜백 (1.13+ MC 본체용)
            cb.getMethod("nativeSendCharMods", Char::class.java, Int::class.java)
                .invoke(null, c, mods)
        } catch (e: Exception) {
            Log.e("TURTLE_LAUNCHER", "📝 sendChar 예외", e)
        }
    }


    private fun isProcessorOnlyJar(file: File): Boolean {
        val name = file.name
        // mergetool 은 두 종류 —
        //   • distmarker(Dist/OnlyIn) 등 "게임 부팅 필수" 클래스를 담은 api jar → 보존(false)
        //   • 설치 단계에서만 쓰는 processor jar → 제외(true)
        // 파일명 규칙이 로더/버전마다 다르다:
        //   Forge:    mergetool-api-1.0.jar     (api 가 앞)
        //   NeoForge: mergetool-2.0.0-api.jar   (api 가 뒤, 버전이 중간)
        // 따라서 "mergetool" 로 시작하면서 이름에 'api' 가 들어가면 부팅 필수 jar 로 보고 보존한다.
        if (name.startsWith("mergetool", ignoreCase = true)
            && name.contains("api", ignoreCase = true)) return false
        return PROCESSOR_ONLY_JAR_PREFIXES.any { name.startsWith(it, ignoreCase = true) }
    }

    /**
     * 이 버전이 Forge SplashProgress(SharedDrawable 멀티스레드 GL) 문제를 일으키는지.
     * 현재까지 확인된 대상은 1.12.x Forge. (1.13+ 는 SplashProgress 구조 자체가 다름)
     * versionId 예: "1.12.2", "1.12.2-forge-14.23.5.2860", "1.12.2 Forge 14.23.5.2860".
     */
    private fun isForgeSplashProblematicVersion(versionId: String): Boolean {
        // 버전 문자열에서 "1.12" 또는 "1.12.x" 를 추출.
        val m = Regex("""\b1\.12(?:\.\d+)?\b""").find(versionId)
        return m != null
    }

    /**
     * pre-1.13 (즉 1.12.x 이하) 레거시 버전인지.
     * 이 버전들은 LWJGL2 시절의 구형 immediate-mode OpenGL 에 의존해서 Zink(OSMesa)로는
     * libOSMesa 로드 직후 SIGSEGV 로 죽는다(확인됨). PojavLauncher 계열도 pre-1.13 은
     * GL4ES 로 돌리므로, 이런 버전은 렌더러를 GL4ES 로 강제 폴백한다.
     *
     * 판정: "1.<minor>" 의 minor 를 보고 1.12 이하 + 1.x(1.0~1.12) + 베타/알파/구표기를
     *      모두 레거시로 본다. 1.13 이상이면 false.
     *   versionId 예: "1.12.2", "1.7.10", "1.5.2", "b1.7.3", "1.16.5"(→false)
     */
    /**
     * FML early window(fml.toml) 켜고 끄는 기준 — 1.17 미만이면 꺼짐, 1.17 이상이면 켜짐.
     * versionId 예: "1.16.5"(→true, 꺼짐), "1.17.1"(→false, 켜짐), "1.7.10"(→true, 꺼짐)
     */
    private fun isPre117Version(versionId: String): Boolean {
        val v = versionId.trim()
        val low = v.lowercase()

        // 아주 옛 표기(베타/알파/인데브/클래식) → 무조건 1.17 미만 취급
        if (low.startsWith("b1.") || low.startsWith("a1.") ||
            low.startsWith("c0.") || low.startsWith("inf-") || low.startsWith("rd-")) {
            return true
        }

        // 주간 스냅샷(YYwWWn) — 1.17 첫 스냅샷은 20w45a 근방이라, year 기준으로 단순 비교
        Regex("""^(\d{2})w(\d{2})[a-z~]""").find(low)?.let { mw ->
            val year = mw.groupValues[1].toInt()
            return year < 20   // 20w.. 미만=1.17 미만, 20w45a 이상은 별도 처리 없이 모던 취급
        }

        // 일반 릴리즈 표기 "1.MINOR[.PATCH]"
        val m = Regex("""^1\.(\d+)""").find(low) ?: return true   // 파싱 안 되면 안전하게 구버전 취급
        val minor = m.groupValues[1].toIntOrNull() ?: return true
        return minor < 17
    }

    private fun isPre113Version(versionId: String): Boolean {
        val v = versionId.trim()
        val low = v.lowercase()

        // ── 1) 아주 옛 표기 (베타/알파/인데브/클래식) → 무조건 레거시 ──
        if (low.startsWith("b1.") || low.startsWith("a1.") ||
            low.startsWith("c0.") || low.startsWith("inf-") || low.startsWith("rd-")) {
            return true
        }

        // ── 2) 주간 스냅샷 (YYwWWn, 예: 17w43a / 26w08b) ──
        // 1.13 첫 스냅샷 = 17w43a. (year, week) < (17, 43) 이면 pre-1.13.
        Regex("""^(\d{2})w(\d{2})[a-z~]""").find(low)?.let { mw ->
            val year = mw.groupValues[1].toInt()
            val week = mw.groupValues[2].toInt()
            return when {
                year != 17 -> year < 17       // 16w.. 이하=레거시, 18w.. 이상=모던
                else        -> week < 43       // 17년이면 43주차가 경계
            }
        }

        // ── 3) 정식 릴리스 / pre-release (예: 1.12.2, 1.13-pre7, 26.1.2) ──
        // 맨 앞 major.minor 만 정수로 비교. major≠1 이면(2,21,26...) 전부 모던.
        val m = Regex("""^(\d+)\.(\d+)""").find(v) ?: return false
        val major = m.groupValues[1].toIntOrNull() ?: return false
        val minor = m.groupValues[2].toIntOrNull() ?: return false
        if (major != 1) return false
        return minor <= 12
    }

    /**
     * 이 버전에 사용할 렌더러를 결정. 기본은 사용자가 고른 RendererManager 설정이지만,
     * pre-1.13 레거시는 Zink 가 불가능하므로 GL4ES 로 강제 오버라이드한다.
     */
    private fun resolveRendererForVersion(): Renderer {
        // 1) 인스턴스별 렌더러가 있으면 그것을, 없으면 전역 기본.
        val instanceRendererId = instanceDir
            ?.let { runCatching { InstanceManager.loadMeta(File(it))?.rendererId }.getOrNull() }
        var base = instanceRendererId?.let { Renderer.fromId(it) } ?: RendererManager.load(this)
        Log.d("TURTLE_LAUNCHER",
            "렌더러 해석: instance=${instanceRendererId ?: "-"}, 결정=${base.id}")

        // 3) pre-1.13 레거시는 Zink(OSMesa)로 못 돌리므로 GL4ES 로 강제.
        //    단, Krypton(NG-GL4ES) 도 GL4ES 포크라 구버전(1.16.5- 포함)을 자체 지원하므로 그대로 둔다.
        if (isPre113Version(versionId) &&
            base.id != "gl4es" && base.id != "gl4es_desktop" && base.id != "krypton") {
            Log.w("TURTLE_LAUNCHER",
                "🕹️ pre-1.13 레거시($versionId) 감지 → 렌더러 GL4ES 강제 폴백 (기존: ${base.id})")
            return Renderer.fromId("gl4es")
        }
        return base
    }

    /**
     * mods 폴더에 설치된 모드 개수.
     * Forge 스캐너가 로드하는 .jar 뿐 아니라, 우리가 호환성 때문에 비활성화한
     * .jar.pingdisabled 도 함께 센다(설치된 총량 기준으로 무게를 판단).
     * 하위 폴더(예: 1.16 이하의 일부 구조)는 보지 않는다 — 표준 mods/ 평면 구조만.
     */
    private fun countInstalledMods(modsDir: File): Int {
        if (!modsDir.isDirectory) return 0
        return modsDir.listFiles()?.count { f ->
            f.isFile && (
                    f.name.endsWith(".jar", ignoreCase = true) ||
                            f.name.endsWith(".jar.turtledisabled", ignoreCase = true)
                    )
        } ?: 0
    }

    /**
     * 모드 개수에 따른 성능 등급. 첫 실행 시 options.txt 강제 하향에 사용.
     * 임계값은 arm64 모바일(Mali + Zink/OSMesa) 기준 보수적으로 잡음.
     */
    private enum class PerfTier { VANILLA, LIGHT, MEDIUM, HEAVY, EXTREME }

    private fun computePerfTier(modCount: Int): PerfTier = when {
        modCount <= 0   -> PerfTier.VANILLA   // 바닐라/데이터팩 — 강제 하향 안 함
        modCount < 30   -> PerfTier.LIGHT     // 가벼운 모드 몇 개
        modCount < 80   -> PerfTier.MEDIUM    // 중형 모드팩
        modCount < 150  -> PerfTier.HEAVY     // 대형 모드팩
        else            -> PerfTier.EXTREME   // 초대형(150+) — 최소 옵션
    }

    /**
     * 성능 등급별 강제 옵션값.
     * 첫 실행에서만 적용하며(아래 syncOptionsTxt 참고), 이후엔 사용자가 게임 내에서
     * 올린 값을 보존한다. VANILLA 는 null 을 반환해 강제 하향을 건너뛴다.
     *
     * 키 의미:
     *  - renderDistance     : 청크 렌더 거리 (가장 무거운 항목)
     *  - simulationDistance  : 시뮬레이션 거리 (엔티티/틱 부하) — 1.18+
     *  - graphicsMode        : 0=Fast, 1=Fancy, 2=Fabulous
     *  - entityDistanceScaling: 엔티티 렌더 비율(0.5~5.0)
     *  - particles           : 0=All, 1=Decreased, 2=Minimal
     *  - ao                  : 0=Off, 1=Min, 2=Max (Ambient Occlusion)
     *  - biomeBlendRadius     : 바이옴 경계 블렌딩 반경(0=꺼짐)
     */
    private fun perfFloorOptions(tier: PerfTier): Map<String, String>? = when (tier) {
        PerfTier.VANILLA -> null
        PerfTier.LIGHT -> mapOf(
            "renderDistance" to "8",
            "simulationDistance" to "6",
            "graphicsMode" to "1",
            "entityDistanceScaling" to "0.75",
            "particles" to "1",
            "biomeBlendRadius" to "1",
        )
        PerfTier.MEDIUM -> mapOf(
            "renderDistance" to "6",
            "simulationDistance" to "5",
            "graphicsMode" to "0",
            "entityDistanceScaling" to "0.75",
            "particles" to "1",
            "ao" to "1",
            "biomeBlendRadius" to "1",
            "renderClouds" to "false",
        )
        PerfTier.HEAVY -> mapOf(
            "renderDistance" to "5",
            "simulationDistance" to "4",
            "graphicsMode" to "0",
            "entityDistanceScaling" to "0.5",
            "particles" to "2",
            "ao" to "0",
            "biomeBlendRadius" to "0",
            "renderClouds" to "false",
            "mobSpawning" to "true",
        )
        PerfTier.EXTREME -> mapOf(
            "renderDistance" to "4",
            "simulationDistance" to "4",
            "graphicsMode" to "0",
            "entityDistanceScaling" to "0.5",
            "particles" to "2",
            "ao" to "0",
            "biomeBlendRadius" to "0",
            "renderClouds" to "false",
            "fancyGraphics" to "false",   // 일부 레거시 버전은 graphicsMode 대신 이 키 사용
        )
    }

    /**
     * options.txt 동기화.
     *
     *  1) 항상 강제: maxFps / enableVsync / mipmapLevels(=0, Mali+Zink 크래시 회피).
     *  2) 첫 실행 + 모드팩이 무거우면(PerfTier ≥ LIGHT) 렌더/시뮬레이션/기타 렌더 옵션을
     *     성능 등급에 맞춰 강제로 낮춘다.
     *     - "첫 실행" 판정: options.txt 와 같은 폴더의 마커 파일(.ping_perf_applied) 부재.
     *       한 번 적용하면 마커를 남겨, 이후엔 사용자가 게임 내에서 올린 값을 덮어쓰지 않는다.
     *     - 단, 사용자가 직접 정한 기본값(settings.renderDistance/graphicsMode)이 성능 등급
     *       상한보다 낮다면 그 낮은 값을 존중한다(= min 으로 합침). 절대 사용자보다 높이지 않음.
     *  3) 그 외(키 바인딩/볼륨 등)는 사용자 변경을 보존.
     */
    /**
     * 기기의 현재 시스템 언어를 마인크래프트 options.txt 의 lang 코드("ll_cc", 소문자)로 변환.
     *
     * 마인크래프트 언어 파일명은 소문자 "언어_국가" (예: ko_kr, en_us, ja_jp, zh_cn).
     * Android Locale 의 language/country 를 합쳐 만들되, 국가 코드가 없거나 모호한 주요
     * 언어는 마인크래프트에서 실제 제공하는 대표 변형으로 매핑한다.
     * 매칭 실패 시 en_us 로 폴백(마인크래프트가 모르는 코드를 만나도 기본 영어로 동작).
     */
    private fun systemMinecraftLang(): String {
        val locale = resources.configuration.locales.takeIf { it.size() > 0 }?.get(0)
            ?: java.util.Locale.getDefault()
        val lang = locale.language.lowercase()        // e.g. "ko", "en", "pt", "zh"
        val country = locale.country.lowercase()       // e.g. "kr", "us", "br", "cn" (없을 수 있음)

        // 국가 코드까지 있으면 우선 그대로 시도할 수 있게 구성해두고,
        // 마인크래프트가 실제 제공하는 대표 코드로 보정.
        return when (lang) {
            "ko" -> "ko_kr"
            "ja" -> "ja_jp"
            "en" -> if (country == "gb") "en_gb" else "en_us"
            "zh" -> when (country) {
                "tw", "hk", "mo" -> "zh_tw"   // 번체
                else             -> "zh_cn"   // 간체(기본)
            }
            "pt" -> if (country == "pt") "pt_pt" else "pt_br"  // 브라질이 더 일반적
            "es" -> when (country) {
                "es" -> "es_es"
                "mx" -> "es_mx"
                "ar" -> "es_ar"
                else -> "es_es"
            }
            "de" -> "de_de"
            "fr" -> if (country == "ca") "fr_ca" else "fr_fr"
            "ru" -> "ru_ru"
            "it" -> "it_it"
            "nl" -> "nl_nl"
            "pl" -> "pl_pl"
            "tr" -> "tr_tr"
            "uk" -> "uk_ua"
            "vi" -> "vi_vn"
            "th" -> "th_th"
            "id" -> "id_id"
            "cs" -> "cs_cz"
            "sv" -> "sv_se"
            "fi" -> "fi_fi"
            "da" -> "da_dk"
            "nb", "no" -> "nb_no"
            "hu" -> "hu_hu"
            "el" -> "el_gr"
            "ar" -> "ar_sa"
            "he", "iw" -> "he_il"
            "ro" -> "ro_ro"
            else -> {
                // 알 수 없는 언어: "lang_country" 형태로라도 시도하되, 국가 없으면 en_us.
                if (country.isNotEmpty()) "${lang}_$country" else "en_us"
            }
        }
    }

    /**
     * pre-1.6 판정: 1.5.x 이하( = pre-1.6 legacy assets/언어 시스템 ).
     * 이 버전들은 options.txt lang 주입 시 StringTranslate(adn) NPE 가 나므로 언어를 건드리지 않는다.
     * 1.6+ 는 현대식 lang 로딩이라 시스템 언어 주입이 정상 동작한다.
     * 형식: "1.5.2" → major=5 → true, "1.6.4" → major=6 → false, "1.2.5" → true.
     * 스냅샷/비표준 버전명은 안전하게 false(주입 허용; 1.6+ 가정).
     */
    private fun isPre16Version(versionId: String): Boolean {
        val m = Regex("""^1\.(\d+)""").find(versionId) ?: return false
        val major = m.groupValues[1].toIntOrNull() ?: return false
        return major <= 5
    }

    /** simulationDistance 는 1.18+ 키. 그 이하 버전엔 주입하지 않는다. */
    private fun isModernSimDistance(versionId: String): Boolean {
        val m = Regex("""^1\.(\d+)""").find(versionId) ?: return true  // 스냅샷 등은 최신 가정
        val major = m.groupValues[1].toIntOrNull() ?: return true
        return major >= 18
    }

    /** Seed a new instance with the original Turtle options before applying runtime-safe patches. */
    private fun ensureBundledTurtleOptions(optionsFile: File) {
        if (optionsFile.exists()) return
        runCatching {
            optionsFile.parentFile?.mkdirs()
            assets.open("options.txt").use { input ->
                optionsFile.outputStream().use { output -> input.copyTo(output) }
            }
            Log.d("TURTLE_LAUNCHER", "📝 original Turtle options.txt installed: ${optionsFile.absolutePath}")
        }.onFailure { Log.w("TURTLE_LAUNCHER", "Bundled options.txt unavailable; using generated defaults", it) }
    }

    private fun syncOptionsTxt(optionsFile: File, settings: JvmSettings, modCount: Int = 0, versionId: String = "") {
        val supportsSimDistance = isModernSimDistance(versionId)

        val existing: MutableList<String> = if (optionsFile.exists())
            optionsFile.readLines().toMutableList()
        else
            mutableListOf()

        fun upsert(key: String, value: String) {
            val idx = existing.indexOfFirst { it.startsWith("$key:") }
            val line = "$key:$value"
            if (idx >= 0) existing[idx] = line else existing.add(line)
        }

        fun currentInt(key: String): Int? =
            existing.firstOrNull { it.startsWith("$key:") }
                ?.substringAfter("$key:")?.trim()?.toIntOrNull()

        // ── "최초 1회" 판정: options.txt 와 같은 폴더의 마커 파일 부재 ──
        //   마커가 있으면 이 인스턴스는 이미 1회 초기화됨 → 사용자가 게임 안에서 바꾼 설정을
        //   절대 덮어쓰지 않는다(아래 mipmapLevels 만 예외).
        val marker = File(optionsFile.parentFile, ".turtle_perf_applied")
        val isFirstLaunch = !marker.exists()

        if (isFirstLaunch) {
            // ── 최초 실행에만 적용하는 기본값 (이후 사용자 설정 보존) ──
            val targetMaxFps = if (settings.unlockFps) {
                settings.maxFps.coerceIn(30, 1000)
            } else {
                // The legacy toggle remains a real safety cap for low-memory/power-save tiers.
                minOf(settings.maxFps, 120).coerceIn(30, 120)
            }
            val targetVsync  = settings.enableVsync.toString()
            val targetRenderD = if (modCount > 0) minOf(settings.renderDistance, 6) else settings.renderDistance
            val targetGfxMode = settings.graphicsMode

            upsert("maxFps",         targetMaxFps.toString())
            upsert("enableVsync",    targetVsync)
            upsert("renderDistance", targetRenderD.coerceIn(2, 32).toString())
            upsert("graphicsMode",   targetGfxMode.toString())
            upsert("graphicsPreset", "\"${when (targetGfxMode) { 2 -> "fabulous"; 1 -> "fancy"; else -> "fast" }}\"")
            upsert("renderClouds",   "\"${if (settings.disableClouds) "false" else "true"}\"")
            upsert("entityDistanceScaling", settings.entityDistanceScaling.coerceIn(0.25f, 1f).toString())
            upsert("particles", settings.particles.coerceIn(0, 2).toString())
            upsert("ao", if (settings.ambientOcclusion) "true" else "false")
            upsert("biomeBlendRadius", settings.biomeBlendRadius.coerceIn(0, 7).toString())
            if (supportsSimDistance) {
                upsert("simulationDistance", settings.simulationDistance.coerceIn(2, 32).toString())
            }

            // ── 최초 실행 시 시스템 언어를 마인크래프트 언어로 설정 ──
            //   이미 lang 키가 있으면(이전 실행/사용자 설정) 절대 덮어쓰지 않는다.
            //   ⚠️ pre-1.6(1.5.x 이하)은 StringTranslate NPE 때문에 lang 을 건드리지 않는다.
            val isPre16 = isPre16Version(versionId)
            if (existing.none { it.startsWith("lang:") } && !isPre16) {
                val mcLang = systemMinecraftLang()
                upsert("lang", mcLang)
                Log.d("TURTLE_LAUNCHER", "🌐 최초 실행 — 시스템 언어로 lang=$mcLang 설정")
            } else if (isPre16) {
                val before = existing.size
                existing.removeAll { it.startsWith("lang:") }
                if (existing.size != before) {
                    Log.d("TURTLE_LAUNCHER", "🌐 pre-1.6($versionId) — 기존 lang 줄 제거(StringTranslate NPE 회피)")
                }
            }

            // ── 첫 실행 + 무거운 모드팩 → 렌더 설정 강제 하향 ──
            val tier = computePerfTier(modCount)
            if (tier != PerfTier.VANILLA) {
                perfFloorOptions(tier)?.forEach { (key, value) ->
                    // 정수형 렌더 옵션은 "기존 값이 더 낮으면 그 값 유지"(절대 높이지 않음).
                    val flInt = value.toIntOrNull()
                    if (flInt != null) {
                        val cur = currentInt(key)
                        val applied = if (cur != null) minOf(cur, flInt) else flInt
                        upsert(key, applied.toString())
                    } else {
                        upsert(key, value)
                    }
                }
                Log.d("TURTLE_LAUNCHER", "⚙️ 첫 실행 성능 하향 적용: tier=$tier modCount=$modCount")
            }

            // 한 번만 적용되도록 마커 남김 (이후 실행은 사용자 설정 보존).
            runCatching {
                marker.parentFile?.mkdirs()
                marker.writeText("tier=$tier\nmodCount=$modCount\n")
            }
            Log.d("TURTLE_LAUNCHER", "📝 options.txt 최초 1회 초기화 완료")
        } else {
            Log.d("TURTLE_LAUNCHER", "📝 options.txt 이미 초기화됨 — 사용자 그래픽 보존(FPS/V-Sync/profile controls applied)")
        }

        // A locked performance profile is intentionally applied on every launch. This makes the
        // new settings real for existing instances too; disabling the lock restores Minecraft's
        // own options after the first-run seed.
        // FPS limit and V-Sync are launcher launch controls, not hidden profile-only values.
        // Apply them even when the user chooses to let Minecraft own the other graphics options.
        val effectiveMaxFps = if (settings.unlockFps) settings.maxFps.coerceIn(30, 1000)
        else minOf(settings.maxFps, 120).coerceIn(30, 120)
        upsert("maxFps", effectiveMaxFps.toString())
        upsert("enableVsync", settings.enableVsync.toString())

        if (settings.performanceProfileLocked) {
            upsert("renderDistance", settings.renderDistance.coerceIn(2, 32).toString())
            upsert("graphicsMode", settings.graphicsMode.coerceIn(0, 2).toString())
            upsert("graphicsPreset", "\"${when (settings.graphicsMode.coerceIn(0, 2)) { 2 -> "fabulous"; 1 -> "fancy"; else -> "fast" }}\"")
            upsert("renderClouds", "\"${if (settings.disableClouds) "false" else "true"}\"")
            upsert("entityDistanceScaling", settings.entityDistanceScaling.coerceIn(0.25f, 1f).toString())
            upsert("particles", settings.particles.coerceIn(0, 2).toString())
            upsert("ao", settings.ambientOcclusion.toString())
            upsert("biomeBlendRadius", settings.biomeBlendRadius.coerceIn(0, 7).toString())
            if (supportsSimDistance) upsert("simulationDistance", settings.simulationDistance.coerceIn(2, 32).toString())
        }

        // ── locked profile mipmap setting ─────────────────────────────────────
        // Zero remains the safe default on Mali/Zink, while the explicit UI setting lets a
        // user with a known-good renderer opt into texture filtering.
        val desiredMipmap = settings.mipmapLevels.coerceIn(0, 4)
        if (settings.performanceProfileLocked && currentInt("mipmapLevels") != desiredMipmap) {
            upsert("mipmapLevels", desiredMipmap.toString())
            Log.d("TURTLE_LAUNCHER", "🛡️ mipmapLevels=$desiredMipmap performance profile")
        }

        // 핫바 영역 계산용 — options.txt 의 guiScale 을 읽어둔다(없으면 0=자동).
        mcGuiScale = existing.firstOrNull { it.startsWith("guiScale:") }
            ?.substringAfter("guiScale:")?.trim()?.toIntOrNull() ?: 0

        // 사용자가 이전에 인게임에서 맞춘 핫바 터치 영역 스케일 복원(없으면 0=자동).
        loadHotbarTouchScale()

        optionsFile.writeText(existing.joinToString("\n"))
    }

    /** Persist launcher-owned tuning knobs for renderer/mod integrations and diagnostics. */
    private fun writeTurtlePerformanceProfile(file: File, settings: JvmSettings) {
        val effectiveMaxFps = if (settings.unlockFps) settings.maxFps.coerceIn(30, 1000)
        else minOf(settings.maxFps, 120).coerceIn(30, 120)
        val values = linkedMapOf(
            "maxFps" to effectiveMaxFps,
            "enableVsync" to settings.enableVsync,
            "framePacing" to settings.framePacing,
            "frameSkipping" to settings.frameSkipping,
            "adaptiveFrameTiming" to settings.adaptiveFrameTiming,
            "lowLatencyRendering" to settings.lowLatencyRendering,
            "threadedChunkBuild" to settings.threadedChunkBuild,
            "fastMath" to settings.fastMath,
            "shaderCache" to settings.shaderCache,
            "mipmapLevels" to settings.mipmapLevels.coerceIn(0, 4),
            "entityDistanceScaling" to settings.entityDistanceScaling.coerceIn(0.25f, 1f),
            "simulationDistance" to settings.simulationDistance.coerceIn(2, 32),
            "particles" to settings.particles.coerceIn(0, 2),
            "ambientOcclusion" to settings.ambientOcclusion,
            "biomeBlendRadius" to settings.biomeBlendRadius.coerceIn(0, 7),
            "rendererDebugLogging" to settings.rendererDebugLogging,
        )
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText("# TurtleLauncher performance profile\n" + values.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n")
        }.onFailure { Log.w("TURTLE_LAUNCHER", "performance profile write failed", it) }
    }

    /**
     * Iris 셰이더 전역 설정(config/iris.properties)에 그림자 렌더 거리를 최소로 주입한다.
     *
     * Iris 의 IrisConfig 는 iris.properties 의 `maxShadowRenderDistance`(기본 32)를 읽어
     * 그림자 패스 렌더 반경을 결정한다. 이 값이 클수록 그림자 패스에서 렌더하는 청크가 많아져
     * 모바일/Zink·MobileGlues 환경에서 큰 부하가 된다. 최소(=1)로 낮춰 부하를 줄인다.
     *
     * options.txt 와 동일하게 **최초 1회만** 적용한다(.turtle_iris_applied 마커).
     * 이후엔 사용자가 게임 내 Iris 설정에서 바꾼 값을 보존한다.
     * 셰이더를 한 번도 안 켰으면 iris.properties 가 없을 수 있는데, 그 경우에도 미리 만들어 둔다
     * (Iris 가 로드되며 자기 키들을 추가하고, 우리가 심은 maxShadowRenderDistance 는 그대로 존중됨).
     */
    private fun syncIrisProperties(irisFile: File) {
        // Iris(셰이더)는 Fabric/NeoForge 양쪽에서 동작하므로 로더로 거르지 않는다.
        // 셰이더 미사용 인스턴스면 단지 iris.properties 가 미리 생길 뿐 부작용은 없다.
        val marker = File(irisFile.parentFile, ".turtle_iris_applied")
        if (marker.exists()) {
            Log.d("TURTLE_LAUNCHER", "🌑 iris.properties 이미 초기화됨 — 사용자 그림자 설정 보존")
            return
        }

        val lines: MutableList<String> = if (irisFile.exists())
            irisFile.readLines().toMutableList()
        else
            mutableListOf()

        val key = "maxShadowRenderDistance"
        val idx = lines.indexOfFirst { it.startsWith("$key=") }
        val line = "$key=1"   // 최소 — 그림자 렌더 거리 최소(부하 최소화)
        if (idx >= 0) lines[idx] = line else lines.add(line)

        runCatching {
            irisFile.parentFile?.mkdirs()
            irisFile.writeText(lines.joinToString("\n"))
            marker.writeText("maxShadowRenderDistance=1\n")
            Log.d("TURTLE_LAUNCHER", "🌑 iris.properties 최초 1회 — maxShadowRenderDistance=1(그림자 거리 최소)")
        }.onFailure {
            Log.w("TURTLE_LAUNCHER", "🌑 iris.properties 쓰기 실패: ${it.message}")
        }
    }

    /**
     * Forge/NeoForge 의 config/fml.toml 에서 early loading window(망치/여우 애니메이션) 설정.
     *
     * ZL2 는 fml.toml 을 건드리지 않고 기본값(earlyWindowProvider=fmlearlywindow,
     * earlyWindowControl=true)으로 early window 를 켠 채 구동한다. 그 비결은 GLFW stub
     * (lwjgl-glfw-classes.jar)이 early window 가 요청하는 GL 컨텍스트 버전을 렌더러에 맞게
     * 고정(vulkan_zink→4.6, 기본→3.3)해 주는 것. 우리도 같은 PojavLauncher 계열 stub +
     * Zink(MESA_GL_VERSION_OVERRIDE=4.6) 설정이라 동일하게 켤 수 있다.
     *
     * ENABLE_EARLY_WINDOW = true  → early window 켬 (ZL2 와 동일, 망치/여우 표시)
     *                       false → 끔(provider=none). Mali+OSMesa 에서 별도 스레드 GL
     *                               컨텍스트가 SIGSEGV/Scudo 로 죽으면 이걸로 즉시 되돌린다.
     *
     * 파일이 없으면(첫 실행 등) 해당 줄만 가진 fml.toml 을 새로 만든다. Forge 가 이후
     * 나머지 기본값을 자기 형식으로 채워 넣는다. 앱이 자기 파일을 쓰는 것이라
     * adb push 때와 달리 권한(AccessDenied) 문제가 없다.
     */
    private fun syncFmlConfig(fmlToml: File, enableEarlyWindow: Boolean = true) {
        // ★ early window 토글. 호출부에서 인스턴스 종류에 따라 결정해 넘긴다.
        //   (모드팩/Connector 인스턴스는 early window 의 acceptGameLayer 단계에서
        //    게임 클래스(LoadingOverlay 등) 로드에 실패하므로 OFF 로 넘어온다)
        val ENABLE_EARLY_WINDOW = enableEarlyWindow
        try {
            fmlToml.parentFile?.mkdirs()

            val lines: MutableList<String> = if (fmlToml.exists())
                fmlToml.readLines().toMutableList()
            else
                mutableListOf()

            // TOML 은 "key = value" 형식. 기존 줄(주석/공백 들여쓰기 포함)을 키로 찾아 교체.
            fun upsertToml(key: String, valueLiteral: String) {
                val idx = lines.indexOfFirst { it.trimStart().startsWith("$key ") || it.trimStart().startsWith("$key=") }
                val newLine = "$key = $valueLiteral"
                if (idx >= 0) lines[idx] = newLine else lines.add(newLine)
            }

            if (ENABLE_EARLY_WINDOW) {
                upsertToml("earlyWindowProvider", "\"fmlearlywindow\"")
                upsertToml("earlyWindowControl", "true")
            } else {
                upsertToml("earlyWindowProvider", "\"none\"")
                upsertToml("earlyWindowControl", "false")
            }

            fmlToml.writeText(lines.joinToString("\n"))
            Log.d("TURTLE_LAUNCHER",
                "🪟 fml.toml sync: earlyWindow=${if (ENABLE_EARLY_WINDOW) "ON(fmlearlywindow)" else "OFF(none)"} (${fmlToml.absolutePath})")
        } catch (e: Exception) {
            Log.e("TURTLE_LAUNCHER", "fml.toml 수정 실패: ${e.message}", e)
        }
    }

    /**
     * Android keyCode → GLFW 키코드.
     *
     * 예전엔 여기에 부분 테이블(알파벳 26개 중 9개 + 숫자 1~9 + 기호 일부)이 있었다.
     * 그래서 채팅 글자는 문자 콜백으로 들어가도 **키 바인딩이 대부분 안 먹었다**
     * (F3 디버그 · F5 시점 · F11 전체화면 · 미니맵 M · JEI U · 인벤토리 정렬 R 등).
     * 전체 매핑은 [GlfwKeys.fromAndroid] 로 옮겼다.
     */
    fun androidKeyToGlfw(keyCode: Int): Int? = GlfwKeys.fromAndroid(keyCode)

    private fun copyLwjglJars(base: File) {
        val targetDir = File(base, "lwjgl3").apply { mkdirs() }
        try {
            val jarNames = assets.list("lwjgl3") ?: return
            for (jarName in jarNames) {
                if (!jarName.endsWith(".jar")) continue
                val target = File(targetDir, jarName)
                if (target.exists() && target.length() > 0) continue
                assets.open("lwjgl3/$jarName").use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                Log.d("TURTLE_LAUNCHER", "📦 LWJGL jar 추출: $jarName (${target.length()} bytes)")
            }
        } catch (e: Exception) {
            Log.e("TURTLE_LAUNCHER", "LWJGL jar 추출 실패", e)
        }
    }

    /**
     * turtle-mic-bridge.jar(순수 자바, javax.sound.sampled.spi.MixerProvider 구현체) 를
     * 인스턴스 폴더로 복사해서 게임 classpath 에 넣는다 — Simple Voice Chat 같은 음성채팅
     * 모드가 표준 자바 오디오 API 로 마이크를 열려고 할 때, 이 브릿지가 AAudio(네이티브,
     * mic_bridge.c)로 실제 캡처를 연결해준다.
     */
    private fun copyMicBridgeJar(base: File) {
        try {
            val dest = File(base, "turtle-mic-bridge.jar")
            if (dest.exists() && dest.length() > 0) return
            assets.open("turtle-mic-bridge.jar").use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            Log.d("TURTLE_LAUNCHER", "🎙 마이크 브릿지 jar 추출: ${dest.length()} bytes")
        } catch (e: Exception) {
            Log.e("TURTLE_LAUNCHER", "마이크 브릿지 jar 추출 실패", e)
        }
    }


    override fun onResume() {
        super.onResume()
        window.decorView.findViewWithTag<View>("minecraft_surface")
            ?.let { it.requestFocus() }
            ?: window.decorView.requestFocus()

        // 키보드/다이얼로그 등으로 포커스가 돌아온 뒤에도 전체화면 상태를 유지
        applyFullscreen(fullscreenEnabled)

        // 게임패드 시점 펌프 재개(오른쪽 스틱 → 마우스 델타).
        gamepad.start()

        Log.d("TURTLE_LAUNCHER", "onResume — surface 재바인딩 대기")
    }

    override fun onPause() {
        super.onPause()
        // 펌프 정지 + 눌린 채 남은 패드 키 해제(백그라운드에서 계속 걸어가는 것 방지).
        gamepad.stop()
        if (jvmStarted && isGrabbing) {
            sendKey(256, GLFW_PRESS)    // ESC 누름
            sendKey(256, GLFW_RELEASE)
        }
    }

    /**
     * 안드로이드는 창이 포커스를 잃으면 포인터 캡처를 자동으로 해제한다.
     * 다이얼로그·알림·소프트키보드가 떴다 사라진 뒤 grab 상태면 다시 잡아준다.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && jvmStarted && isGrabbing) {
            applyPointerCapture(true)
        }
    }

    override fun onDestroy() {
        DailyPlaytimeStats.recordSession(this, System.currentTimeMillis() - sessionStartedAtMs)
        currentInstance = null
        // ★ 첫 프레임 리스너 해제 (Activity 누수 방지)
        MinecraftActivityBridge.setFirstFrameListener(null)
        MinecraftActivityBridge.setFpsListener(null)
        // ★ 리스너 해제
        inputDeviceListener?.let { listener ->
            try {
                val im = getSystemService(INPUT_SERVICE)
                        as android.hardware.input.InputManager
                im.unregisterInputDeviceListener(listener)
            } catch (_: Throwable) {}
        }
        inputDeviceListener = null
        gameControllerView = null

        currentInstance = null
        super.onDestroy()
    }
}