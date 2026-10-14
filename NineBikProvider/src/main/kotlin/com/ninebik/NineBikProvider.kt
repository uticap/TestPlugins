package com.ninebik

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
import com.lagradost.cloudstream3.plugins.Plugin
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

    private val xtreamUser: String
        get() = Plugin.context
            ?.getSharedPreferences("ninebik", 0)
            ?.getString("xtream_user", "")
            ?: ""

    private val xtreamPass: String
        get() = Plugin.context
            ?.getSharedPreferences("ninebik", 0)
            ?.getString("xtream_pass", "")
            ?: ""

    private fun streamUrl(sid: String): String =
        "$mainUrl/live/$xtreamUser/$xtreamPass/$sid.ts"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<SearchResponse>()

        val user = xtreamUser
        val pass = xtreamPass
        if (user.isBlank() || pass.isBlank()) {
            // No credentials — show a hint card
            items.add(
                newLiveSearchResponse("⚠️ Set your login in plugin settings", mainUrl, TvType.Live) {
                    this.posterUrl = null
                }
            )
            return newHomePageResponse(listOf(HomePageList("Login required", items)))
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
        return newLiveStreamLoadResponse(name, url, "")
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        callback(
            newExtractorLink(
                source = this.name,
                name = "9bik Live",
                url = data,
                type = ExtractorLinkType.M3U8
            )
        )
        return true
    }
}
