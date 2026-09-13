package com.endiq.turtlelauncher.feature.download.utils

import com.endiq.turtlelauncher.feature.download.Filters
import com.endiq.turtlelauncher.feature.download.enums.Classify
import com.endiq.turtlelauncher.utils.stringutils.StringUtils.containsChinese
import com.endiq.turtlelauncher.utils.stringutils.StringUtilsKt
import net.kdt.pojavlaunch.modloaders.modpacks.api.ApiHandler
import org.jackhuang.hmcl.ui.versions.ModTranslations
import org.jackhuang.hmcl.util.StringUtils

class PlatformUtils {
    companion object {
        /**
         * 修改自源代码：[HMCL Github](https://github.com/HMCL-dev/HMCL/blob/main/HMCL/src/main/java/org/jackhuang/hmcl/game/LocalizedRemoteModRepository.java#L44-#L104)
         * 原项目版权归原作者所有，遵循GPL v3协议
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

            // TODO 由于搜索逻辑与HMCL大不相同，这里就不做进一步的筛查逻辑了，直接返回本地匹配结果，作为平台的搜索关键词，不过无法保证结果的准确度
            return englishSearchFiltersSet.joinToString(" ")
        }

        inline fun <T> ApiHandler.safeRun(block: ApiHandler.() -> T): T? =
            runCatching(block).getOrNull()
    }
}