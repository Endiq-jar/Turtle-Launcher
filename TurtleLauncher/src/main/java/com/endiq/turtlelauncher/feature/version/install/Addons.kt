package com.endiq.turtlelauncher.feature.version.install

enum class Addon(val addonName: String) {
    OPTIFINE("OptiFine"),
    FORGE("Forge"),
    NEOFORGE("NeoForge"),
    FABRIC("Fabric"),
    FABRIC_API("Fabric API"),
    QUILT("Quilt"),
    QSL("QSL"),
    CLEANROOM("Cleanroom"),
    // Not selectable from the addon picker rows above (checkIncompatible() is never
    // called for it) - only ever added directly to InstallGameFragment's taskMap from
    // the InstallExtrasDialog "Turtle Client" toggle. Exists as an Addon entry purely
    // so it can ride the same isMod InstallTaskItem pipeline FABRIC_API/QSL already use.
    TURTLE_CLIENT("Turtle Client");

    companion object {
        private val compatibleMap = mapOf(
            OPTIFINE to setOf(OPTIFINE, FORGE),
            FORGE to setOf(OPTIFINE, FORGE),
            NEOFORGE to setOf(NEOFORGE),
            FABRIC to setOf(FABRIC, FABRIC_API),
            FABRIC_API to setOf(FABRIC, FABRIC_API),
            QUILT to setOf(QUILT, QSL),
            QSL to setOf(QUILT, QSL),
            // Cleanroom is its own self-contained Forge fork/replacement - not
            // verified compatible with anything else selectable here, so it's
            // exclusive with everything including itself-paired-with-others.
            CLEANROOM to setOf(CLEANROOM),
            TURTLE_CLIENT to setOf(TURTLE_CLIENT)
        )

        fun getCompatibles(addon: Addon) = compatibleMap[addon]
    }
}