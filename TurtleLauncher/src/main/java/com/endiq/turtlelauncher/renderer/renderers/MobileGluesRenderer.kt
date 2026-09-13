package com.endiq.turtlelauncher.renderer.renderers

import com.endiq.turtlelauncher.utils.path.PathManager
import com.endiq.turtlelauncher.renderer.RendererInterface
import java.io.File

/**
 * MobileGlues - "(on) Mobile, GL uses ES", a real GL implementation running on top of the
 * host's own OpenGL ES 3.x (best on 3.2, minimum 3.0). Real upstream:
 * github.com/MobileGL-Dev/MobileGlues-release. Unlike LTW, `nm -D` on the bundled
 * libmobileglues.so confirms it's fully self-contained - it exports eglGetDisplay,
 * eglInitialize, eglGetProcAddress AND glClear/the rest of GLES itself, so it needs
 * neither a separate system EGL nor a dlopen-adjunct library; both getRendererLibrary()
 * and getRendererEGL() below point at the same file.
 *
 * The .so bundled in this repo was replaced this round: it reported
 * `MG_MOBILEGLUES_VERSION 1340` (V1.3.4), two releases behind. Downloaded the real
 * MobileGlues_2.0.0.apk from MobileGlues-release's GitHub Releases, verified its sha256
 * against the one published on the release page before touching anything
 * (a7e1eb2973...5581a - matched exactly), extracted lib/<abi>/libmobileglues.so for all 4
 * ABIs from inside it, confirmed the extracted binary still reports
 * `MG_MOBILEGLUES_VERSION 2000` and still exports the same eglGetDisplay/eglInitialize/
 * glClear/eglGetProcAddress set with the same 5 NEEDED libs (libandroid/liblog/libm/
 * libdl/libc, nothing new) before swapping all 4 ABIs' copies in.
 *
 * MG_DIR_PATH: MobileGlues reads `<MG_DIR_PATH>/config.json` for its own settings
 * (enableAngle, customGLVersion, multidrawMode, etc. - all internally managed, none of
 * this launcher's concern) - confirmed via the literal "MG_DIR_PATH = %s" / "/config.json"
 * strings in the binary. Left unset, its fallback behavior on Android is unverified (no
 * launch log to confirm against yet, unlike Horizon-Renderer's equivalent PSA-folder gap),
 * so it's pointed at a real persistent writable directory the same way that gap was
 * closed there, rather than leaving it to chance.
 *
 * No FCL RendererManager.kt entry to copy an identifier from either (checked - not a
 * built-in there), so getUniqueIdentifier() is a freshly generated UUID.
 */
class MobileGluesRenderer : RendererInterface {
    companion object {
        const val ID = "MOBILEGLUES"
    }

    override fun getRendererId(): String = ID

    // MobileGlues presents itself as a normal EGL/GLESv2 implementation to the native
    // dispatch layer even though it may translate through ANGLE/Vulkan internally
    // (enableAngle is its own internal setting, dlopen'd by itself if used) - it isn't one
    // of the Mesa gallium/vulkan_zink-specific strings, so "opengles" is correct here too.
    override fun getNativeRendererId(): String = "opengles"

    override fun getUniqueIdentifier(): String = "4b4b8e4b-083d-429c-97e1-5e8239b6dc17"

    override fun getRendererName(): String = "MobileGlues"

    override fun getRendererEnv(): Lazy<Map<String, String>> = lazy {
        val dirPath = File(PathManager.DIR_FILE, "mobileglues").apply { mkdirs() }.absolutePath
        mapOf("MG_DIR_PATH" to dirPath)
    }

    override fun getDlopenLibrary(): Lazy<List<String>> = lazy { emptyList() }

    override fun getRendererLibrary(): String = "libmobileglues.so"

    override fun getRendererEGL(): String = "libmobileglues.so"
}
