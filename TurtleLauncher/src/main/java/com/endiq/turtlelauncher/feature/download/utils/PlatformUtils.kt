package com.endiq.turtlelauncher.feature.download.utils

import com.endiq.turtlelauncher.feature.download.Filters
import com.endiq.turtlelauncher.feature.download.enums.Classify
import com.endiq.turtlelauncher.utils.stringutils.StringUtils.containsChinese
import com.endiq.turtlelauncher.utils.stringutils.StringUtilsKt
import net.endiq.launcher.modloaders.modpacks.api.ApiHandler
import org.jackhuang.hmcl.ui.versions.ModTranslations
import org.jackhuang.hmcl.util.StringUtils

class PlatformUtils {
    companion object {
        /**
         * Adapted from [HMCL Github](https://github.com/HMCL-dev/HMCL/blob/main/HMCL/src/main/java/org/jackhuang/hmcl/game/LocalizedRemoteModRepository.java#L44-#L104)
         * All rights of the original project belong to their authors, licensed under GPL v3.
         */
        fun searchModLikeWithChinese(
            filters: Filters,
            isMod: Boolean
        ): String? {
            if (!containsChinese(filters.name)) return null
            val classify = if (isMod) Classify.MOD else Classify.MODPACK

            val englishSearchFiltersSet: MutableSet<String> = HashSet(16)

            for ((count, mod) in ModTranslations.getTranslationsByRepositoryType(classify)
                .searchMod(filters.name).withIndex()
            ) {
                for (englishWord in StringUtils.tokenize(if (StringUtilsKt.isNotBlank(mod.subname)) mod.subname else mod.name)) {
                    if (englishSearchFiltersSet.contains(englishWord)) continue
                    englishSearchFiltersSet.add(englishWord)
                }
                if (count >= 3) break
            }

            // TODO Our search logic differs a lot from HMCL, so no extra screening is done here:
            // the local match is returned as the platform search keyword, accuracy not guaranteed.
            return englishSearchFiltersSet.joinToString(" ")
        }

        inline fun <T> ApiHandler.safeRun(block: ApiHandler.() -> T): T? =
            runCatching(block).getOrNull()
    }
}