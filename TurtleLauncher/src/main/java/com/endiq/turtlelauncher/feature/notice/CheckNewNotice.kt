package com.endiq.turtlelauncher.feature.notice

import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.utils.ZHTools
import com.endiq.turtlelauncher.utils.http.CallUtils
import com.endiq.turtlelauncher.utils.http.CallUtils.CallbackListener
import com.endiq.turtlelauncher.utils.path.UrlManager
import com.endiq.turtlelauncher.utils.stringutils.StringUtils
import net.endiq.launcher.Tools
import okhttp3.Call
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

class CheckNewNotice {
    companion object {
        @JvmStatic
        var noticeInfo: NoticeInfo? = null
        private var isChecking = false

        private fun checkCooling(): Boolean {
            return ZHTools.getCurrentTimeMillis() - AllSettings.noticeCheck.getValue() > 2 * 60 * 1000 // 2 minute cooldown
        }

        @JvmStatic
        fun checkNewNotice(listener: CheckNoticeListener) {
            if (isChecking) {
                return
            }
            isChecking = true

            noticeInfo?.let {
                listener.onSuccessful(noticeInfo)
                isChecking = false
                return
            }

            if (!checkCooling()) {
                return
            } else {
                AllSettings.noticeCheck.put(ZHTools.getCurrentTimeMillis()).save()
            }

            CallUtils(object : CallbackListener {
                override fun onFailure(call: Call?) {
                    isChecking = false
                }

                @Throws(IOException::class)
                override fun onResponse(call: Call?, response: Response?) {
                    if (response == null || !response.isSuccessful) {
                        Logging.e("CheckNewNotice", "Unexpected code ${response?.code}")
                    } else {
                        runCatching {
                            val responseBody = response.body?.string()
                                ?: throw IOException("Empty response body")

                            val originJson = JSONObject(responseBody)
                            val rawBase64 = originJson.getString("content")
                            // Base64-decode, because the text stored here is Base64-encoded.
                            val rawJson = StringUtils.decodeBase64(rawBase64)

                            val noticeJson = Tools.GLOBAL_GSON.fromJson(rawJson, NoticeJsonObject::class.java)

                            // Fetch the notice message.
                            val language = ZHTools.getSystemLanguage()
                            val title = getLanguageText(language, noticeJson.title)
                            val content = getLanguageText(language, noticeJson.content)

                            noticeInfo = NoticeInfo(title, content, noticeJson.date, noticeJson.numbering)
                            listener.onSuccessful(noticeInfo)
                        }.getOrElse { e ->
                            Logging.e("Check New Notice", "Failed to resolve the notice.", e)
                        }
                    }
                    isChecking = false
                }
            }, "${UrlManager.URL_GITHUB_HOME}launcher_notice.json", null).enqueue()
        }

        private fun getLanguageText(language: String, text: NoticeJsonObject.Text): String {
            return when (language) {
                "zh_cn" -> text.zhCN
                "zh_tw" -> text.zhTW
                else -> text.enUS
            }
        }
    }
}
