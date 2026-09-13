package com.endiq.turtlelauncher.renderer

import com.endiq.turtlelauncher.renderer.renderers.AngleRenderer
import com.endiq.turtlelauncher.renderer.renderers.FreedrenoRenderer
import com.endiq.turtlelauncher.renderer.renderers.HolyGL4ESRenderer
import com.endiq.turtlelauncher.renderer.renderers.LTWRenderer
import com.endiq.turtlelauncher.renderer.renderers.MobileGluesRenderer
import com.endiq.turtlelauncher.renderer.renderers.NWRenderer
import com.endiq.turtlelauncher.renderer.renderers.VGPURenderer
import com.endiq.turtlelauncher.renderer.renderers.VirGLRenderer
import com.endiq.turtlelauncher.renderer.renderers.ZinkRenderer


/**
 * Cross-cutting renderer metadata that doesn't belong on [RendererInterface] itself -
 * compatibility plugins don't have it, and it's about how renderers relate to each other
 * (recommendation badge), not any one renderer's own behavior.
 *
 * Adding a new built-in renderer: one new RendererInterface class, one entry here (or
 * none, if it doesn't need a version range/badge), one line in Renderers.addRenderers.
 * Nothing else needs to change.
 */
object RendererCatalog {
    enum class Badge { RECOMMENDED, STABLE, EXPERIMENTAL }

    data class Entry(
        /** Inclusive lower bound, e.g. "1.16.5" for VGPU. Null = no lower bound. */
        val minMinecraftVersion: String? = null,
        /** Inclusive upper bound, e.g. "1.21.4" for Holy GL4ES. Null = no upper bound. */
        val maxMinecraftVersion: String? = null,
        val badge: Badge,
        /**
         * Whether shader packs (Iris/OptiFine-style) actually render under this renderer.
         * Not string-verifiable the way library exports are - this is an architectural claim
         * from FCL-Team/FoldCraftLauncher's own README ("shader support (needs the VirGL/Zink/MG renderer)" -
         * shader support requires the VirGL/Zink/MobileGlues renderer), which is a reasonable
         * source here since this launcher's VirGL/Zink renderer classes are themselves sourced
         * directly from FCL's RendererManager.kt (see their own doc comments). Plain GL4ES-family
         * translation (Holy GL4ES/LTW/NW/ANGLE) and the other Mesa gallium backends
         * (Freedreno/VGPU) are left false pending their own confirmation - false is the safe
         * default (an incorrectly-false renderer just gets an unnecessary warning; an
         * incorrectly-true one sends someone chasing a shader bug that isn't fixable).
         */
        val supportsShaderPacks: Boolean = false
    )

    private val entries: Map<String, Entry> = mapOf(
        // TurtleLauncher: Holy GL4ES was the RECOMMENDED default until MobileGlues became
        // this launcher's default renderer (see AllSettings.renderer). Its own documented
        // ceiling stays - 1.21.4, above which real crash logs show it failing on GL calls it
        // doesn't implement (see LaunchGame.kt's RendererCatalog range warning) - so it drops
        // to STABLE: still fine, just no longer the pick this launcher suggests first.
        HolyGL4ESRenderer.ID to Entry(
            maxMinecraftVersion = "1.21.4",
            badge = Badge.STABLE
        ),
        // LTW: actively maintained upstream (MojoLauncher's own featured renderer,
        // ongoing changelog entries), badged the same as Krypton Wrapper's old slot.
        LTWRenderer.ID to Entry(badge = Badge.STABLE),
        // MobileGlues: real, actively developed (1.3.4 -> 2.0.0 in this round alone) and
        // fully self-contained (libmobileglues.so exports its own EGL/GLES - see
        // MobileGluesRenderer's doc comment). Promoted to RECOMMENDED together with becoming
        // AllSettings.renderer's default: it's a real GL implementation on GLES 3.x rather
        // than a GL->GLES translator, and it's the GL-family renderer that actually renders
        // shader packs (per FCL's README - see supportsShaderPacks doc comment above).
        // Known upstream caveat, worth stating plainly rather than hiding behind a badge:
        // Amethyst-Android's own release notes list MobileGlues (and Krypton Wrapper) as
        // crashing on MC 26.3-snapshot4+ "due to changes in how SDL creates EGL window",
        // so on those SDL-backend versions Zink/LTW are the safer pick - see CrashAnalyzer's
        // sdl3_android_init_sigsegv rule and TurtleAssistant's renderer topic.
        MobileGluesRenderer.ID to Entry(badge = Badge.RECOMMENDED, supportsShaderPacks = true),
        // NW: GL4ES-family translator, no prior TurtleLauncher track record and no
        // upstream source/changelog to check maturity claims against - kept cautious
        // like MobileGlues/VirGL/Freedreno/VGPU until it's been run on real devices.
        NWRenderer.ID to Entry(badge = Badge.EXPERIMENTAL),
        VirGLRenderer.ID to Entry(badge = Badge.EXPERIMENTAL, supportsShaderPacks = true),
        ZinkRenderer.ID to Entry(badge = Badge.STABLE, supportsShaderPacks = true),
        FreedrenoRenderer.ID to Entry(badge = Badge.EXPERIMENTAL),
        VGPURenderer.ID to Entry(
            minMinecraftVersion = "1.16.5",
            badge = Badge.EXPERIMENTAL
        ),
        // ANGLE: .so pair was already bundled (jniLibs) but had no renderer class wiring it
        // up until now - no TurtleLauncher track record at all, so EXPERIMENTAL like the
        // other newly-registered/unproven renderers.
        AngleRenderer.ID to Entry(badge = Badge.EXPERIMENTAL)
    )

    fun get(rendererId: String): Entry? = entries[rendererId]

    /** False (including for unknown/unlisted renderer ids) is the safe default - see
     *  [Entry.supportsShaderPacks]'s doc comment for why. */
    fun supportsShaderPacks(rendererId: String): Boolean = entries[rendererId]?.supportsShaderPacks ?: false
}
