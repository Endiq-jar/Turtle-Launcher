package com.endiq.turtlelauncher.renderer.renderers

import com.endiq.turtlelauncher.renderer.RendererInterface

/**
 * ANGLE ("Almost Native Graphics Layer Engine", Google/Chromium) - translates the GLESv2/EGL
 * calls Minecraft's LWJGL makes into the device's native Vulkan (or GLES, depending on the
 * build) driver. `libEGL_angle.so`/`libGLESv2_angle.so` were already bundled in this repo's
 * jniLibs for all 4 ABIs (FoldCraftLauncher credits ANGLE as one of its dependencies) but had
 * no RendererInterface class wiring them up - sitting unused until now.
 *
 * Binary inspection (`nm -D` on arm64-v8a, the ABI checked): `libEGL_angle.so` exports
 * eglGetDisplay/eglInitialize/eglGetProcAddress; `libGLESv2_angle.so` exports glClear (and
 * the rest of the GLES surface) but no EGL entry points of its own - the same
 * renderer-lib/EGL-lib split as VirGL (libOSMesa_81.so + libEGL.so) and Zink (libglxshim.so +
 * libEGL_mesa.so), not the fully-self-contained shape MobileGlues has. `readelf -d` on both
 * shows only NEEDED entries for libc/libdl/libm/liblog/libnativewindow - no other bundled .so
 * required. All 4 ABIs carry both files, unlike VirGL (missing on x86) or the Turnip Vulkan
 * driver (arm64-v8a only) - so ANGLE has no ABI gaps for Renderers.hasRequiredLibrary to filter.
 *
 * getNativeRendererId(): ANGLE presents a standard GLESv2/EGL context to the app regardless of
 * which backend (Vulkan/GL/Metal/D3D) it translates to internally - none of its own exported
 * symbols match the "custom_gallium"/"vulkan_zink"/"gallium_*" strings pojavInitOpenGL's native
 * dispatch checks for (see RendererInterface.getNativeRendererId's doc comment), so it belongs
 * in the same "opengles" bucket as Holy GL4ES/LTW/MobileGlues/NW.
 *
 * No FCL RendererManager.kt entry to copy an identifier from (FCL bundles ANGLE as a
 * dependency/asset, not as one of its own named built-in renderers), so getUniqueIdentifier()
 * below is a freshly generated UUID, same as LTW/MobileGlues/NW.
 */
class AngleRenderer : RendererInterface {
    companion object {
        const val ID = "ANGLE"
    }

    override fun getRendererId(): String = ID

    override fun getNativeRendererId(): String = "opengles"

    override fun getUniqueIdentifier(): String = "c52d3bea-a5bd-4b85-82aa-2e7472582bc8"

    override fun getRendererName(): String = "ANGLE"

    override fun getRendererEnv(): Lazy<Map<String, String>> = lazy { emptyMap() }

    override fun getDlopenLibrary(): Lazy<List<String>> = lazy { emptyList() }

    override fun getRendererLibrary(): String = "libGLESv2_angle.so"

    override fun getRendererEGL(): String = "libEGL_angle.so"
}
