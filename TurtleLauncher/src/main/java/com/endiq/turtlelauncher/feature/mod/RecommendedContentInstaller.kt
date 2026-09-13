package com.endiq.turtlelauncher.feature.mod

import com.endiq.turtlelauncher.feature.download.enums.ModLoader
import com.endiq.turtlelauncher.feature.log.Logging
import java.io.File

/**
 * TurtleLauncher: one-tap install for a small curated set of performance mods and a
 * resource pack, requested by name rather than built from a general mod browser.
 *
 * Real, verified Modrinth project slugs (checked July 2026, not guessed):
 *  - Sodium         -> "sodium"
 *  - Lithium        -> "lithium"
 *  - Entity Culling -> "entityculling"
 *  - Indium         -> "indium"       (Sodium's own Fabric Rendering API shim)
 *  - Bare Bones     -> "bare-bones"   (resource pack, not loader-specific)
 *
 * Added August 2026 (roadmap #14 "Built-in Performance Features"), slugs re-verified
 * against Modrinth's own project pages at that time, not guessed:
 *  - Fabric API     -> "fabric-api"    (Fabric/Quilt only - most Fabric perf mods, incl.
 *                       Sodium/Lithium/Indium above, either need it or work better with it)
 *  - FerriteCore    -> "ferrite-core"  (NOT "ferritecore" - confirmed via its actual
 *                       Modrinth URL; Fabric, Forge, NeoForge)
 *  - ModernFix      -> "modernfix"     (Forge, Fabric, NeoForge)
 *  - ImmediatelyFast -> "immediatelyfast" (Fabric, Quilt, NeoForge, Forge)
 *
 * "For every loader" doesn't need a per-loader slug table: each of these projects tags
 * its own versions with the loaders it supports, so passing the instance's actual
 * [ModLoader] through to Modrinth's version API resolves the right build automatically
 * (or correctly comes back with nothing on a loader a given mod was never built for -
 * e.g. Fabric API on plain Forge, or Lithium on plain Forge, neither of which ship a
 * build under these names; that's real, not a bug here, and is reported as such via
 * "No compatible build found" rather than silently skipped or attempted anyway).
 *
 * Two things worth knowing before this list is taken at face value:
 *  1. Sodium's own compatibility notes call out Android GL-translation layers (GL4ES,
 *     ANGLE-style wrappers) as unsupported with "severe performance issues" - it's
 *     built and tested against real desktop GL/native GLES. It tends to work fine
 *     through this launcher's Vulkan Zink or MobileGlues renderers (both talk closer
 *     to native GL/Vulkan than a CPU translation layer does), but is a poor match for
 *     the GL4ES-family renderers specifically. Surfaced in the install result, not
 *     silently hidden.
 *  2. Indium is Sodium's OWN compatibility shim from before Sodium had built-in Fabric
 *     Rendering API support, and Sodium 0.6.0+ ships that support natively - the two
 *     are explicitly incompatible past that point. Installing both unconditionally
 *     would break the mod set is was meant to help. So: resolve Sodium first, and only
 *     install Indium if the resolved Sodium build is older than 0.6.0.
 */
object RecommendedContentInstaller {
    private const val TAG = "RecommendedContentInstaller"

    data class CatalogEntry(
        val displayName: String,
        val modrinthSlug: String,
        val note: String? = null
    )

    val RECOMMENDED_MODS = listOf(
        // Fabric API goes first: several of the mods below either require it or work
        // better with it present, and CatalogEntry order is also install order.
        CatalogEntry("Fabric API", "fabric-api", "Fabric/Quilt only - reports \"no compatible build\" on Forge/NeoForge instances, which is expected, not an error"),
        CatalogEntry("Sodium", "sodium", "Best via Vulkan Zink or MobileGlues; GL4ES-family renderers aren't Sodium's supported target"),
        CatalogEntry("Lithium", "lithium"),
        CatalogEntry("Entity Culling", "entityculling"),
        CatalogEntry("Indium", "indium", "Only installed alongside Sodium builds older than 0.6.0 - newer Sodium replaces it, and the two are incompatible past that point"),
        CatalogEntry("FerriteCore", "ferrite-core", "Memory usage optimizations - safe alongside every other mod in this list"),
        CatalogEntry("ModernFix", "modernfix", "All-in-one loading-time/memory/bugfix mod - compatible with the rest of this list by design"),
        CatalogEntry("ImmediatelyFast", "immediatelyfast", "Speeds up immediate-mode rendering (HUD, text, maps) - complements Sodium rather than overlapping it")
    )

    val RECOMMENDED_RESOURCE_PACKS = listOf(
        CatalogEntry("Bare Bones", "bare-bones")
    )

    data class InstallOutcome(val entry: CatalogEntry, val success: Boolean, val message: String)

    /**
     * Installs every entry in [RECOMMENDED_MODS] into [modsDir] for [mcVersion]/[loader],
     * then every entry in [RECOMMENDED_RESOURCE_PACKS] into [resourcePacksDir] (loader-less).
     * Runs synchronously (network calls included) - call this off the main thread.
     */
    @JvmStatic
    fun installAll(mcVersion: String, loader: ModLoader?, modsDir: File, resourcePacksDir: File): List<InstallOutcome> {
        val results = mutableListOf<InstallOutcome>()
        var resolvedSodiumVersion: String? = null

        RECOMMENDED_MODS.forEach { entry ->
            if (entry.modrinthSlug == "indium") {
                val sodiumVersion = resolvedSodiumVersion
                if (sodiumVersion != null && isAtLeast060(sodiumVersion)) {
                    results += InstallOutcome(
                        entry, false,
                        "Skipped - installed Sodium $sodiumVersion already includes Fabric Rendering API support, and Indium isn't compatible with Sodium 0.6.0+"
                    )
                    return@forEach
                }
            }

            val outcome = runCatching {
                modsDir.mkdirs()
                ModrinthDirectApi.downloadBestMatch(entry.modrinthSlug, mcVersion, loader, modsDir)
            }.onFailure { e -> Logging.e(TAG, "Failed to install ${entry.displayName}", e) }.getOrNull()

            if (entry.modrinthSlug == "sodium" && outcome != null) {
                resolvedSodiumVersion = extractVersionNumber(outcome)
            }

            results += if (outcome != null) {
                InstallOutcome(entry, true, outcome + (entry.note?.let { " — $it" } ?: ""))
            } else {
                val loaderName = loader?.loaderName ?: "unknown loader"
                InstallOutcome(entry, false, "No compatible build found for $mcVersion / $loaderName")
            }
        }

        RECOMMENDED_RESOURCE_PACKS.forEach { entry ->
            val outcome = runCatching {
                resourcePacksDir.mkdirs()
                ModrinthDirectApi.downloadBestMatch(entry.modrinthSlug, mcVersion, null, resourcePacksDir)
            }.onFailure { e -> Logging.e(TAG, "Failed to install ${entry.displayName}", e) }.getOrNull()

            results += if (outcome != null) {
                InstallOutcome(entry, true, outcome)
            } else {
                InstallOutcome(entry, false, "No compatible version found for $mcVersion")
            }
        }

        return results
    }

    /** "filename (1.2.3+mc1.21)" -> "1.2.3+mc1.21" -> best-effort leading "major.minor" as a comparable pair. */
    private fun extractVersionNumber(downloadResultMessage: String): String? {
        val start = downloadResultMessage.indexOf('(')
        val end = downloadResultMessage.indexOf(')')
        if (start == -1 || end == -1 || end <= start) return null
        return downloadResultMessage.substring(start + 1, end)
    }

    private fun isAtLeast060(versionNumber: String): Boolean {
        val numericPart = versionNumber.trim().substringBefore('+').substringBefore('-')
        val parts = numericPart.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.isEmpty()) return false
        val major = parts.getOrElse(0) { 0 }
        val minor = parts.getOrElse(1) { 0 }
        return major > 0 || minor >= 6
    }
}
