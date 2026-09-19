package com.endiq.turtlelauncher.feature.mod.modpack.install

/**
 * Key information of a modpack.
 * @param name modpack name, used when creating the new version
 * @param type modpack type (CurseForge, Modrinth, MCBBS)
 */
data class ModPackInfo(val name: String?, val type: ModPackUtils.ModPackEnum)
