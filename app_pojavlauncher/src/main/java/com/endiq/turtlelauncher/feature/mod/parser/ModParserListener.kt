package com.endiq.turtlelauncher.feature.mod.parser

/**
 * Mod parse progress listener, reporting processed mods versus the total.
 */
interface ModParserListener {
    /**
     * Progress callback reporting how far the current mod parse has got.
     * @param recentlyParsedModInfo the mod info that was just parsed
     * @param totalFileCount number of files that have to be checked
     */
    fun onProgress(recentlyParsedModInfo: ModInfo, totalFileCount: Int)

    /**
     * Callback carrying the parse result once parsing completes.
     * @param modInfoList list of every mod info
     */
    fun onParseEnded(modInfoList: List<ModInfo>)
}