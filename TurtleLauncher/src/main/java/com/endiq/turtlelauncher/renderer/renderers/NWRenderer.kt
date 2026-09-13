package com.endiq.turtlelauncher.renderer.renderers

import com.endiq.turtlelauncher.utils.path.PathManager
import com.endiq.turtlelauncher.renderer.RendererInterface
import java.io.File

/**
 * NW — a GL4ES-family desktop-GL translation layer (bundled as libnw.so). Binary inspection
 * (`nm -D` on all four ABIs, cross-checked) shows the same shape as Holy GL4ES: it exports
 * the full legacy+modern desktop GL surface (glBegin/glCallList alongside
 * glBindVertexArray/glMultiDrawElementsBaseVertexARB/etc., ~4500 symbols) under a `gl4es_`-
 * prefixed internal namespace, plus glslang/spirv-cross for GLSL->SPIR-V shader conversion.
 * No eglGetDisplay/eglInitialize/eglGetProcAddress among its own exports, but string refs to
 * "libEGL", "libGLESv2", "libGLESv3" confirm - like Holy GL4ES and LTW - it dlopens the
 * device's real system EGL/GLESv2 itself internally rather than shipping its own, so
 * getRendererEGL() below points at the system libEGL.so rather than a bundled one.
 *
 * Its LIBGL_* environment variable surface (LIBGL_SHRINK, LIBGL_NODOWNSAMPLING,
 * LIBGL_VGPU_FORCE, LIBGL_BATCH, LIBGL_NOVAOCACHE, etc.) matches gl4es's own long-standing
 * env var conventions closely enough to place it in the same family/lineage as Holy GL4ES
 * rather than as an unrelated translator. It also reads its own `<NGG_DIR_PATH>/...` config
 * directory (string-confirmed, same pattern as MobileGlues' MG_DIR_PATH) - pointed at a real
 * persistent writable directory the same way that gap is closed for MobileGlues, rather than
 * leaving it unset.
 *
 * ABI note: only 4 loose per-ABI libnw.so files were supplied (no source, no FCL
 * RendererManager.kt entry to copy identifiers from), so getUniqueIdentifier() below is a
 * freshly generated UUID, same as LTW/MobileGlues.
 */
class NWRenderer : RendererInterface {
    companion object {
        const val ID = "NW"
    }

    override fun getRendererId(): String = ID

    // Same reasoning as Holy GL4ES/LTW/MobileGlues: NW sits on top of a plain GLESv2/EGL
    // context as far as pojavInitOpenGL's native dispatch is concerned - none of its own
    // strings match the "custom_gallium"/"vulkan_zink"/"gallium_*" native branches.
    override fun getNativeRendererId(): String = "opengles"

    override fun getUniqueIdentifier(): String = "03543a99-7f9b-47ba-8ae3-1e5162db9fa6"

    override fun getRendererName(): String = "NW"

    override fun getRendererEnv(): Lazy<Map<String, String>> = lazy {
        val dirPath = File(PathManager.DIR_FILE, "nw").apply { mkdirs() }.absolutePath
        mapOf("NGG_DIR_PATH" to dirPath)
    }

    override fun getDlopenLibrary(): Lazy<List<String>> = lazy { emptyList() }

    override fun getRendererLibrary(): String = "libnw.so"

    override fun getRendererEGL(): String = "libEGL.so"
}
