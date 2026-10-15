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
import org.json.JSONArray

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

    private fun streamUrl(sid: String): String {
        val u = cred("user")
        val p = cred("pass")
        return "$mainUrl/live/$u/$p/$sid.ts"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<SearchResponse>()
        val user = cred("user")
        val pass = cred("pass")

        if (user.isBlank() || pass.isBlank()) {
            return newHomePageResponse(
                listOf(HomePageList("Login required", emptyList()))
            )
        }

        try {
            val url = "$mainUrl/player_api.php?username=$user&password=$pass&action=get_live_streams"
            val resp = app.get(url).text
            val arr = JSONArray(resp)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val sid = o.optString("stream_id")
                val nm = o.optString("name")
                val icon = o.optString("stream_icon", "")
                items.add(
                    newLiveSearchResponse(nm, streamUrl(sid), TvType.Live) {
                        this.posterUrl = icon.ifBlank { null }
                    }
                )
            }
        } catch (_: Exception) {
        }

        return newHomePageResponse(listOf(HomePageList("Live Channels", items)))
    }

    override suspend fun search(query: String): List<SearchResponse>? = null

    override suspend fun load(url: String): LoadResponse? {
        // Return a live load response with the direct stream URL
        // CloudStream should immediately show a Play button
        return newLiveStreamLoadResponse(
            name = name,
            dataUrl = url
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // "data" is the full stream URL from load()
        val u = cred("user")
        val p = cred("pass")
        val userAgent = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36"

        callback(
            newExtractorLink(
                source = this.name,
                name = "9bik Live",
                url = data,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = mainUrl
                this.quality = com.lagradost.cloudstream3.utils.Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to userAgent
                )
            }
        )
        return true
    }
}
