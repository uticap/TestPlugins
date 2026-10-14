package com.ninebik

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.SubtitleFile
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

class NineBikProvider : MainAPI() {
    override var mainUrl = "https://cdn.9bik.win"
    override var name = "9bik IPTV"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "en"
    override val hasMainPage = true

    // Hardcoded for now — will add a settings screen in the next iteration
    private val xtreamUser = "xtream"
    private val xtreamPass = "xn-uxwr6NQf12z55"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<SearchResponse>()
        try {
            val url = "$mainUrl/player_api.php?username=$xtreamUser&password=$xtreamPass&action=get_live_streams"
            val resp = app.get(url).text
            val arr = org.json.JSONArray(resp)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val sid = o.optString("stream_id")
                val nm = o.optString("name")
                val icon = o.optString("stream_icon", "")
                items.add(newLiveSearchResponse(nm, "channel:$sid", this.name) {
                    this.posterUrl = icon.ifBlank { null }
                })
            }
        } catch (e: Exception) {
            // log and return empty
        }
        return newHomePageResponse(listOf(com.lagradost.cloudstream3.HomePageList("Live Channels", items)))
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return null
    }

    override suspend fun load(url: String): LoadResponse? {
        if (url.startsWith("channel:")) {
            val sid = url.removePrefix("channel:")
            return newLiveStreamLoadResponse(name, url, null)
        }
        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.startsWith("channel:")) {
            val sid = data.removePrefix("channel:")
            val streamUrl = "$mainUrl/live/$xtreamUser/$xtreamPass/$sid.ts"
            callback(
                newExtractorLink(
                    source = this.name,
                    name = "9bik Live",
                    url = streamUrl,
                    type = ExtractorLinkType.M3U8
                )
            )
            return true
        }
        return false
    }
}
