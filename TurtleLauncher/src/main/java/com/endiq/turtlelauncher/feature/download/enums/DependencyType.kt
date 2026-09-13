package com.endiq.turtlelauncher.feature.download.enums

/**
 * Mod dependency types, each with its own representative colour for easy distinction.
 * @param curseforge name of the category on CurseForge
 * @param modrinth name of the category on Modrinth
 * @param color representative colour of the type
 */
enum class DependencyType(val curseforge: String?, val modrinth: String?, val color: Int) {
    /**
     * Required: the project cannot work correctly without this dependency.
     *
     * CurseForge: "3"
     * Modrinth: "required"
     * Colour: 0x4CFF9800 (orange, 30% alpha)
     */
    REQUIRED("3", "required", 0x4CFF9800),

    /**
     * Optional: not required, but adds extra features to the project.
     *
     * CurseForge: "2"
     * Modrinth: "optional"
     * Colour: 0x4C34C759 (light green, 30% alpha)
     */
    OPTIONAL("2", "optional", 0x4C34C759),

    /**
     * Incompatible: this dependency conflicts with certain other projects or dependencies;
     * using them together is not recommended and may cause errors or failures.
     *
     * CurseForge: "5"
     * Modrinth: "incompatible"
     * Colour: 0x4CEF5350 (light red, 30% alpha)
     */
    INCOMPATIBLE("5", "incompatible", 0x4CEF5350),

    /**
     * Embedded: already shipped inside the project, no separate install needed; they keep the
     * project running correctly.
     *
     * CurseForge: "1"
     * Modrinth: "embedded"
     * Colour: 0x4CFFD54F (light yellow, 30% alpha)
     */
    EMBEDDED("1", "embedded", 0x4CFFD54F),

    /**
     * Tooling: dependencies used to develop or operate the project, not needed at runtime.
     *
     * CurseForge: "4"
     * Modrinth: null
     * Colour: 0x4CBDBDBD (grey, 30% alpha)
     */
    TOOL("4", null, 0x4CBDBDBD),

    /**
     * Includes: files or resources bundled with the project; not core functionality, but they
     * provide extra support or features.
     *
     * CurseForge: "6"
     * Modrinth: null
     * Colour: 0x4C9575CD (purple, 30% alpha)
     */
    INCLUDE("6", null, 0x4C9575CD)
}