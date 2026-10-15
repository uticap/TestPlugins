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

    // Cache team logos so we don't hammer TheSportsDB
    private val teamLogoCache = mutableMapOf<String, String>()

    private suspend fun teamLogo(teamName: String): String? {
        if (teamName.isBlank()) return null
        teamLogoCache[teamName]?.let { return it.ifBlank { null } }
        try {
            val url = "https://www.thesportsdb.com/api/v1/json/3/searchteams.php?t=" +
                    java.net.URLEncoder.encode(teamName, "UTF-8")
            val txt = app.get(url).text
            val j = org.json.JSONObject(txt)
            val teams = j.optJSONArray("teams")
            if (teams == null || teams.length() == 0) {
                teamLogoCache[teamName] = ""
                return null
            }
            val badge = teams.getJSONObject(0).optString("strBadge", "")
            val logo = if (badge.isNotBlank()) badge
                       else teams.getJSONObject(0).optString("strLogo", "")
            teamLogoCache[teamName] = logo
            return logo.ifBlank { null }
        } catch (_: Exception) {
            teamLogoCache[teamName] = ""
            return null
        }
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
                val firstUrl = o.optString("first_url", "")

                if (firstUrl.isBlank()) continue

                // first_url comes as "/live/34751?url=..." — prepend mainUrl
                // Add panel auth via u/p params
                val baseUrl = if (firstUrl.startsWith("http")) firstUrl else mainUrl + firstUrl
                val u = cred("user")
                val p = cred("pass")
                val sep = if (baseUrl.contains("?")) "&" else "?"
                val fullUrl = "$baseUrl${sep}u=$u&p=$p"
                android.util.Log.d("NineBik", "Match URL: " + fullUrl)

                val displayName = "$home vs $away"
                val logo = teamLogo(home)
                val item = newLiveSearchResponse(
                    displayName,
                    fullUrl,
                    TvType.Live
                ) {
                    this.posterUrl = logo
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
