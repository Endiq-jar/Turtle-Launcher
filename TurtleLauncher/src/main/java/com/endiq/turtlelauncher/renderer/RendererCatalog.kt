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
        HolyGL4ESRenderer.ID to Entry(
            maxMinecraftVersion = "1.21.4",
            badge = Badge.STABLE
        ),
        // LTW: actively maintained upstream (MojoLauncher's own featured renderer,
        // ongoing changelog entries), badged the same as Krypton Wrapper's old slot.
        LTWRenderer.ID to Entry(badge = Badge.STABLE),
        MobileGluesRenderer.ID to Entry(badge = Badge.RECOMMENDED, supportsShaderPacks = true),
        NWRenderer.ID to Entry(badge = Badge.EXPERIMENTAL),
        VirGLRenderer.ID to Entry(badge = Badge.EXPERIMENTAL, supportsShaderPacks = true),
        ZinkRenderer.ID to Entry(badge = Badge.STABLE, supportsShaderPacks = true),
        FreedrenoRenderer.ID to Entry(badge = Badge.EXPERIMENTAL),
        VGPURenderer.ID to Entry(
            minMinecraftVersion = "1.16.5",
            badge = Badge.EXPERIMENTAL
        ),
        AngleRenderer.ID to Entry(badge = Badge.EXPERIMENTAL)
    )

    fun get(rendererId: String): Entry? = entries[rendererId]

    /** False (including for unknown/unlisted renderer ids) is the safe default - see
     *  [Entry.supportsShaderPacks]'s doc comment for why. */
    fun supportsShaderPacks(rendererId: String): Boolean = entries[rendererId]?.supportsShaderPacks ?: false
}
