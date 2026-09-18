package com.endiq.turtlelauncher.feature.turtle

import com.endiq.turtlelauncher.feature.mod.parser.ModInfo
import com.endiq.turtlelauncher.feature.mod.parser.ModParser
import com.endiq.turtlelauncher.feature.mod.parser.ModParserListener
import com.endiq.turtlelauncher.feature.version.Version
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object DependencyGraphExporter {

    /** Synchronously parses [version]'s mods folder and renders a dependency tree. Blocks the calling thread — call off the main thread. */
    @JvmStatic
    fun buildTextTree(version: Version): String {
        val latch = CountDownLatch(1)
        var result: List<ModInfo> = emptyList()

        // ModParser.checkAllMods is the same call site VersionAdapter's own dependency
        // graph dialog uses — it locates version.getGameDir()/mods internally and
        // handles the "no mods folder" case by calling onParseEnded(emptyList()).
        ModParser.checkAllMods(version, object : ModParserListener {
            override fun onProgress(recentlyParsedModInfo: ModInfo, totalFileCount: Int) {}
            override fun onParseEnded(modInfoList: List<ModInfo>) {
                result = modInfoList
                latch.countDown()
            }
        })
        latch.await(30, TimeUnit.SECONDS)

        if (result.isEmpty()) return "No mods found in this version's mods folder."

        val byId = result.associateBy { it.id.lowercase() }
        val hasIncoming = HashSet<String>()
        result.forEach { mod -> mod.dependencies.keys.forEach { dep -> hasIncoming.add(dep.lowercase()) } }

        // Roots = mods nothing else depends on; makes the tree read top-down naturally.
        val roots = result.filter { it.id.lowercase() !in hasIncoming }.ifEmpty { result }

        val sb = StringBuilder()
        val visited = HashSet<String>()

        fun renderNode(mod: ModInfo, depth: Int) {
            val indent = "  ".repeat(depth)
            sb.append(indent).append(if (depth == 0) "• " else "└─ ").append(mod.name.ifBlank { mod.id }).append('\n')
            if (!visited.add(mod.id.lowercase())) {
                sb.append(indent).append("   (already shown above — circular or shared dependency)\n")
                return
            }
            mod.dependencies.keys.forEach { depId ->
                val depMod = byId[depId.lowercase()]
                if (depMod != null) {
                    renderNode(depMod, depth + 1)
                } else {
                    sb.append("  ".repeat(depth + 1)).append("└─ ").append(depId).append(" (not installed)\n")
                }
            }
        }

        roots.forEach { renderNode(it, 0) }
        return sb.toString()
    }
}
