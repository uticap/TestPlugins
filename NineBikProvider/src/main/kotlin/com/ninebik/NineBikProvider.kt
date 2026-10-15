package com.ninebik

import android.content.Context
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

class NineBikProvider : MainAPI() {
    override var mainUrl = "https://cdn.9bik.win"
    override var name = "9bik IPTV"
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasDownloadSupport = false

    private fun cred(key: String): String {
        val ctx: Context? = NineBikPlugin.appContext
        val stored = ctx?.getSharedPreferences("ninebik", Context.MODE_PRIVATE)
            ?.getString(key, "") ?: ""
        if (stored.isNotBlank()) return stored
        return when (key) {
            "user" -> "xtream"
            "pass" -> "xn-uxwr6NQf12z55"
            else -> ""
        }
    }

    // Convert raw stream URL to full panel proxy URL
    private fun proxiedStreamUrl(rawUrl: String): String {
        val u = cred("user")
        val p = cred("pass")
        // /live/<match_id>?url=<encoded> is the panel's format
        // Fixtures already return first_url like "/live/34751?url=..."
        // But we don't have access to first_url in this context — use raw and encode
        val enc = java.net.URLEncoder.encode(rawUrl, "UTF-8")
        return "$mainUrl/live/0?url=$enc&u=$u&p=$p"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val liveNow = mutableListOf<SearchResponse>()
        val upcoming = mutableListOf<SearchResponse>()
        val finished = mutableListOf<SearchResponse>()

        try {
            val url = "$mainUrl/api/fixtures"
            val resp = app.get(url).text
            val data = JSONObject(resp)
            val arr = data.optJSONArray("fixtures") ?: return newHomePageResponse(
                listOf(HomePageList("No matches", emptyList()))
            )

            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val hasStream = o.optBoolean("has_stream", false)
                if (!hasStream) continue

                val home = o.optString("home", "?")
                val away = o.optString("away", "?")
                val comp = o.optString("competition", "Match")
                val isLive = o.optBoolean("is_playing", false)
                val type = o.optString("type", "")
                val id = o.optInt("id", 0)
                val rawUrl = o.optString("raw_stream_url", "")

                if (rawUrl.isBlank() || id == 0) continue

                val displayName = "$home vs $away"
                val item = newLiveSearchResponse(
                    displayName,
                    proxiedStreamUrl(rawUrl),
                    TvType.Live
                ) {
                    this.posterUrl = null
                }

                when {
                    isLive -> liveNow.add(item)
                    type.equals("Upcoming", ignoreCase = true) -> upcoming.add(item)
                    else -> finished.add(item)
                }
            }
        } catch (_: Exception) {
        }

        val lists = mutableListOf<HomePageList>()
        if (liveNow.isNotEmpty()) lists.add(HomePageList("🔴 Live Now", liveNow))
        if (upcoming.isNotEmpty()) lists.add(HomePageList("⏰ Upcoming", upcoming))
        if (finished.isNotEmpty()) lists.add(HomePageList("✅ Recent", finished))
        if (lists.isEmpty()) lists.add(HomePageList("No matches available", emptyList()))

        return newHomePageResponse(lists)
    }

    override suspend fun search(query: String): List<SearchResponse>? = null

    override suspend fun load(url: String): LoadResponse? {
        return newLiveStreamLoadResponse(
            name = name,
            url = url,
            dataUrl = url
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val userAgent = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36"
        callback(
            newExtractorLink(
                source = this.name,
                name = "9bik Live",
                url = data,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = mainUrl
                this.headers = mapOf("User-Agent" to userAgent)
            }
        )
        return true
    }
}
