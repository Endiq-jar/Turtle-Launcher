package com.endiq.turtlelauncher.renderer.renderers

import com.endiq.turtlelauncher.renderer.RendererInterface

/**
 * LTW ("Large Thin Wrapper", self-reported as "OpenLTW" in its own version string) -
 * MojoLauncher's own renderer (github.com/MojoLauncher/LTW), actively maintained there
 * (MojoLauncher's changelog references LTW updates as recently as its 20260326 build,
 * e.g. "Updated LTW for improved 1.21.7+ performance"). Not to be confused with a
 * standalone GL4ES-style translator: `nm -D` on the bundled libltw.so shows it exports
 * only ~120 specific desktop-GL entry points (glDrawElementsBaseVertex, glTexBuffer,
 * glBlendFunciSEPARATE, buffer-storage calls, MultiDraw*, etc.) plus eglGetProcAddress -
 * no glClear/glDrawArrays/eglGetDisplay/eglInitialize at all. Its own log strings confirm
 * the design: "LTWInit: failed loading custom libEGL, using default" - it dlopens the
 * real system EGL/GLESv2 itself at init and serves as a thin interposer in front of it,
 * patching in the handful of desktop-GL-3.x-shaped calls a plain mobile GLES driver
 * usually lacks (the "3.3" in its "3.3 OpenLTW" version string is the GL version it makes
 * the game see), forwarding everything else straight through via its own
 * eglGetProcAddress. That's architecturally identical to how Holy GL4ES/Krypton Wrapper
 * are wired here (own translation .so + system libEGL.so), not the ANGLE-paired setup its
 * name suggests from the FCLRendererPlugin release notes ("ANGLE/LTW") - MojoLauncher's
 * own GitHub issue tracker confirms "OpenLTW" is selectable as its own standalone
 * renderer, independent of the ANGLE toggle.
 *
 * No unique identifier to copy from FCL's RendererManager.kt here - current
 * FCL-Team/FoldCraftLauncher upstream doesn't have LTW as a built-in at all (checked
 * directly; it's still plugin-only there too), so getUniqueIdentifier() below is a
 * freshly generated UUID, not lifted from an existing source of truth.
 *
 * Version note: the bundled libltw.so reports "Built on: Jul 13 2026" - newer than the
 * LTW-2025.7.16.apk asset on ShirosakiMio/FCLRendererPlugin's own "Renderer" release
 * (dated Jul 2025, over a year stale), so that release would be a downgrade. LTW has no
 * other versioned distribution channel (MojoLauncher/LTW builds straight to an AAR from
 * source, no tagged releases) - the bundled build is already the newest available.
 */
class LTWRenderer : RendererInterface {
    companion object {
        const val ID = "LTW"
    }

    override fun getRendererId(): String = ID

    // Same reasoning as HolyGL4ES/Krypton Wrapper: LTW doesn't implement eglGetDisplay/
    // eglInitialize itself (confirmed via nm -D - absent), it dlopens the real system EGL
    // internally and only interposes specific GL entry points, so it's still a plain
    // GLESv2/EGL context as far as pojavInitOpenGL's native dispatch is concerned.
    override fun getNativeRendererId(): String = "opengles"

    override fun getUniqueIdentifier(): String = "e7dcb6d0-bf40-44f0-9703-791c7b24c69e"

    override fun getRendererName(): String = "LTW"

    override fun getRendererEnv(): Lazy<Map<String, String>> = lazy { emptyMap() }

    override fun getDlopenLibrary(): Lazy<List<String>> = lazy { emptyList() }

    override fun getRendererLibrary(): String = "libltw.so"

    override fun getRendererEGL(): String = "libEGL.so"
}
