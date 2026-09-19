package com.endiq.turtlelauncher.launch

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.endiq.turtlelauncher.feature.version.Version
import com.endiq.turtlelauncher.renderer.Renderers
import com.endiq.turtlelauncher.renderer.renderers.AngleRenderer
import com.endiq.turtlelauncher.renderer.renderers.FreedrenoRenderer
import com.endiq.turtlelauncher.renderer.renderers.HolyGL4ESRenderer
import com.endiq.turtlelauncher.renderer.renderers.LTWRenderer
import com.endiq.turtlelauncher.renderer.renderers.MobileGluesRenderer
import com.endiq.turtlelauncher.renderer.renderers.NWRenderer
import com.endiq.turtlelauncher.renderer.renderers.VGPURenderer
import com.endiq.turtlelauncher.renderer.renderers.VirGLRenderer
import com.endiq.turtlelauncher.renderer.renderers.ZinkRenderer
import net.kdt.pojavlaunch.JMinecraftVersionList
import net.kdt.pojavlaunch.PojavProfile
import net.kdt.pojavlaunch.Tools
import net.kdt.pojavlaunch.prefs.LauncherPreferences
import net.kdt.pojavlaunch.utils.JREUtils
import net.kdt.pojavlaunch.value.MinecraftAccount
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile

/**
 * The seam between the Turtle Launcher instance/account model and the Amethyst launch core.
 *
 * Everything here is additive: the actual game launch always goes through
 * [Tools.launchMinecraft] — the Amethyst pipeline with its Minecraft 26.3+ metadata
 * handling, LWJGL 3.4.1/SDL3 native selection and runtime picking. This bridge only
 * translates the Turtle-side selections (instance, renderer, runtime, JVM args,
 * account) into the Amethyst profile model beforehand.
 */
object TurtleLaunchBridge {
    private const val TAG = "TurtleLaunchBridge"

    /**
     * Turtle renderer unique-identifier → Amethyst LOCAL_RENDERER id.
     *
     * The Amethyst ids route through its own env/natives setup (see
     * JREUtils.loadGraphicsLibrary + egl_bridge.c). The Turtle-only renderers
     * (VirGL, Freedreno, RNW, VGPU, Angle, legacy GL4ES builds) get Amethyst-style
     * ids added to the core switch in the same commit, backed by the Turtle renderer
     * libraries shipped in jniLibs.
     */
    private val RENDERER_ID_MAP: Map<String, String> = mapOf(
        MobileGluesRenderer().getUniqueIdentifier() to "opengles_mobileglues",
        LTWRenderer().getUniqueIdentifier() to "opengles3_ltw",
        ZinkRenderer().getUniqueIdentifier() to "opengles3_desktopgl_zink_kopper",
        HolyGL4ESRenderer().getUniqueIdentifier() to "opengles3_gl4es114",
        NWRenderer().getUniqueIdentifier() to "opengles3_nw",
        VGPURenderer().getUniqueIdentifier() to "opengles3_vgpu",
        AngleRenderer().getUniqueIdentifier() to "opengles3_angle",
        VirGLRenderer().getUniqueIdentifier() to "vulkan_virgl",
        FreedrenoRenderer().getUniqueIdentifier() to "gallium_freedreno"
    )

    /** Fallback when a Turtle renderer id (e.g. a plugin renderer) has no mapping. */
    private const val DEFAULT_RENDERER_ID = "opengles_mobileglues"

    @JvmStatic
    fun toAmethystRendererId(turtleRendererUniqueId: String?): String {
        if (turtleRendererUniqueId == null) return DEFAULT_RENDERER_ID
        // Renderer plugins already declare their own native id - if the id is not a
        // known Turtle built-in unique id, treat it as an Amethyst-native id.
        return RENDERER_ID_MAP[turtleRendererUniqueId]
            ?: run {
                // Plugin / unknown renderer: keep Turtle's declared native id when it
                // looks like an Amethyst-style renderer string, else fall back.
                if (turtleRendererUniqueId.startsWith("opengles") || turtleRendererUniqueId.startsWith("vulkan") || turtleRendererUniqueId.startsWith("gallium")) {
                    turtleRendererUniqueId
                } else {
                    Log.w(TAG, "Unknown renderer id '$turtleRendererUniqueId', falling back to $DEFAULT_RENDERER_ID")
                    DEFAULT_RENDERER_ID
                }
            }
    }

    /**
     * Sync the selected Turtle account into the Amethyst account slot so the Amethyst
     * core (MainActivity, PojavProfile readers) sees the same login.
     */
    @JvmStatic
    fun syncAccount(context: Context, account: MinecraftAccount?) {
        if (account == null) return
        runCatching {
            PojavProfile.setCurrentProfile(context, account)
        }.onFailure {
            Log.w(TAG, "Failed to sync account into PojavProfile", it)
        }
    }

    /**
     * Sync a Turtle instance (Version) into the Amethyst LauncherProfiles "current
     * profile" so Tools.launchMinecraft launches exactly this instance: game dir,
     * runtime, renderer and JVM arguments.
     */
    @JvmStatic
    fun syncCurrentProfile(
        context: Context,
        version: Version,
        runtimeName: String?,
        customArgs: String?
    ): MinecraftProfile {
        LauncherProfiles.load()
        val profileKey = "turtle-" + (version.getVersionName().replace(Regex("[^A-Za-z0-9._-]"), "_"))
        val profilesJson = LauncherProfiles.mainProfileJson
        val profile = MinecraftProfile().apply {
            name = version.getVersionName()
            lastVersionId = version.getVersionName()
            gameDir = version.getGameDir().absolutePath // absolute path, see Tools.getGameDirPath
            if (!runtimeName.isNullOrBlank()) {
                javaDir = Tools.LAUNCHERPROFILES_RTPREFIX + runtimeName
            }
            javaArgs = customArgs?.takeIf { it.isNotBlank() }
            pojavRendererName = toAmethystRendererId(version.getRenderer())
        }
        profilesJson.profiles.put(profileKey, profile)
        LauncherPreferences.DEFAULT_PREF.edit()
            .putString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, profileKey)
            .apply()
        LauncherProfiles.write()
        return profile
    }

    /**
     * Mirror the mapped renderer into the Amethyst-side selection and apply the env
     * extras the Turtle-only renderers need (POJAVEXEC_EGL override etc.).
     */
    @JvmStatic
    fun applyRendererSelection(context: Context, version: Version) {
        val amethystId = toAmethystRendererId(version.getRenderer())
        Tools.LOCAL_RENDERER = amethystId

        // Renderer env extras for the Turtle-only renderer families. The base env for
        // the Amethyst-native ids (mobileglues/ltw/zink/kopper) is set by JREUtils.
        when (amethystId) {
            "opengles3_gl4es114" -> setEnv("POJAVEXEC_EGL", "libgl4es_114.so")
            "opengles3_gl4es115" -> setEnv("POJAVEXEC_EGL", "libgl4es_115.so")
            "opengles3_nw" -> setEnv("POJAVEXEC_EGL", "libnw.so")
            "opengles3_vgpu" -> setEnv("POJAVEXEC_EGL", "libvgpu.so")
            "opengles3_angle" -> {
                setEnv("POJAVEXEC_EGL", "libEGL_angle.so")
                setEnv("LIBGL_GLES", "libGLESv2_angle.so")
                setEnv("LIBGL_EGL", "libEGL_angle.so")
            }
            "vulkan_virgl" -> {
                setEnv("POJAVEXEC_EGL", "libOSMesa_81.so")
                setEnv("GALLIUM_DRIVER", "virgl")
            }
            "gallium_freedreno" -> {
                setEnv("POJAVEXEC_EGL", "libOSMesa_8.so")
                setEnv("GALLIUM_DRIVER", "freedreno")
            }
        }

        // Keep the Turtle renderer catalog in sync for the launcher UI.
        runCatching { Renderers.setCurrentRenderer(context, version.getRenderer(), false) }
    }

    private fun setEnv(key: String, value: String) {
        runCatching {
            android.system.Os.setenv(key, value, true)
        }.onFailure {
            Log.w(TAG, "Failed to set env $key for renderer", it)
        }
    }

    /**
     * Amethyst pre-launch steps that used to live in the Amethyst MainActivity right
     * before Tools.launchMinecraft. Ported here so the Turtle MainActivity keeps the
     * same behavior on the Amethyst core.
     */
    @JvmStatic
    fun runAmethystPreLaunch(activity: Activity, versionInfo: JMinecraftVersionList.Version, versionId: String) {
        // Renderer null-check + fallback to the first compatible renderer.
        if (Tools.LOCAL_RENDERER == null) Tools.LOCAL_RENDERER = DEFAULT_RENDERER_ID
        if (!Tools.checkRendererCompatible(activity, Tools.LOCAL_RENDERER)) {
            val renderersList = Tools.getCompatibleRenderers(activity)
            val firstCompatible = renderersList.rendererIds.firstOrNull() ?: DEFAULT_RENDERER_ID
            Log.i(TAG, "Missing renderer " + Tools.LOCAL_RENDERER + " will be replaced with " + firstCompatible)
            Tools.LOCAL_RENDERER = firstCompatible
            Tools.releaseRenderersCache()
        }

        // MCL-3732 mitigation: ensure server-resource-pack folder for older versions.
        runCatching {
            val gamedir = Tools.getGameDirPath(LauncherProfiles.getCurrentProfile())
            val folder = java.io.File(gamedir, "server-resource-pack")
            // Match the Amethyst logic: versions with a numbered asset index <= 12
            // (pre-1.20.3) need the folder to exist (MCL-3732).
            val assetVersion = (versionInfo.assets ?: "legacy").replace(Regex("\\D"), "")
            try {
                if (assetVersion.isNotEmpty() && assetVersion.toInt() <= 12) folder.mkdir()
            } catch (e: NumberFormatException) {
                folder.mkdir()
            }
        }.onFailure { Log.w(TAG, "MCL-3732 mitigation failed", it) }

        if (Tools.LOCAL_RENDERER == "opengles_mobileglues") {
            runCatching { LauncherPreferences.writeMGRendererSettings() }
        }
    }

    /**
     * Full Amethyst-pipeline launch of a Turtle instance. Called from the Turtle
     * MainActivity once the surface is ready (replaces the legacy Zalith
     * JREUtils.launchWithUtils path).
     */
    @JvmStatic
    fun launchOnAmethystCore(
        activity: AppCompatActivity,
        account: MinecraftAccount,
        version: Version,
        versionInfo: JMinecraftVersionList.Version,
        runtimeName: String,
        customArgs: String
    ) {
        val profile = syncCurrentProfile(activity, version, runtimeName, customArgs)
        syncAccount(activity, account)
        applyRendererSelection(activity, version)
        runAmethystPreLaunch(activity, versionInfo, version.getVersionName())

        // Resolve the required Java version the Turtle way (26.x needs Java 25 even
        // when Mojang's JSON still reports 21).
        val jsonJavaVersion = versionInfo.javaVersion?.majorVersion ?: 8
        val requiredJava = LaunchArgs.resolveRequiredJava(version.getVersionName(), jsonJavaVersion)

        JREUtils.redirectAndPrintJRELog()
        LauncherProfiles.load()
        Tools.launchMinecraft(activity, account, profile, version.getVersionName(), requiredJava)
    }
}
