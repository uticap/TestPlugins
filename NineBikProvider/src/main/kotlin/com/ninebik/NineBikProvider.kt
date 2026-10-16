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
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject

class NineBikProvider : MainAPI() {
    override var mainUrl = "https://cdn.9bik.win"
    override var name = "9bik IPTV"
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Live, TvType.Movie)
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasDownloadSupport = false

    override val mainPage = mainPageOf(
        "live_matches" to "🔴 Live Matches",
        "movies_trending" to "🔥 Trending Movies",
        "movies_popular" to "⭐ Popular Movies",
        "movies_top_rated" to "🏆 Top Rated",
        "movies_now_playing" to "🎬 Now Playing",
    )

    private val teamLogoCache = mutableMapOf<String, String>()

    private fun pref(key: String, def: String = ""): String {
        val ctx = NineBikPlugin.appContext ?: return def
        return NineBikPlugin.getPref(ctx, key, def)
    }

    private fun cred(key: String): String = pref(key, "")

    // ---------- MAIN PAGE ----------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        refreshBranding()

        if (cred("user").isBlank() || cred("pass").isBlank()) {
            return newHomePageResponse(
                listOf(HomePageList("Activation required", listOf(
                    newLiveSearchResponse("⚠️ Tap the settings gear to activate", mainUrl, TvType.Live) {
                        this.posterUrl = null
                    }
                )))
            )
        }

        return when {
            request.data == "live_matches" -> fetchLiveMatches(request)
            request.data.startsWith("movies_") -> {
                val cat = request.data.removePrefix("movies_")
                fetchMovies(cat, request)
            }
            else -> newHomePageResponse(listOf(HomePageList(request.name, emptyList())))
        }
    }

    private suspend fun fetchLiveMatches(request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<SearchResponse>()
        try {
            val url = mainUrl + "/api/fixtures"
            val txt = app.get(url).text
            val data = JSONObject(txt)
            val arr = data.optJSONArray("fixtures") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (!o.optBoolean("has_stream", false)) continue
                val firstUrl = o.optString("first_url", "")
                if (firstUrl.isBlank()) continue

                val home = o.optString("home", "?")
                val away = o.optString("away", "?")
                val baseUrl = if (firstUrl.startsWith("http")) firstUrl else mainUrl + firstUrl
                val u = cred("user")
                val p = cred("pass")
                val sep = if (baseUrl.contains("?")) "&" else "?"
                val fullUrl = "$baseUrl${sep}u=$u&p=$p"

                val displayName = "$home vs $away"
                val logo = teamLogo(home)
                items.add(newLiveSearchResponse(displayName, fullUrl, TvType.Live) {
                    this.posterUrl = logo
                })
            }
        } catch (_: Exception) {}
        return newHomePageResponse(listOf(HomePageList(request.name, items)))
    }

    private suspend fun fetchMovies(catId: String, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<SearchResponse>()
        try {
            val url = mainUrl + "/player_api.php?username=" + cred("user") +
                    "&password=" + cred("pass") + "&action=get_vod_streams&category_id=" + catId
            val txt = app.get(url).text
            val arr = JSONArray(txt)
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val tid = m.optInt("stream_id")
                val nm = m.optString("name", "?")
                val poster = m.optString("stream_icon", "")
                if (tid == 0) continue
                items.add(newMovieSearchResponse(nm, "movie:$tid", TvType.Movie) {
                    this.posterUrl = poster.ifBlank { null }
                })
            }
        } catch (_: Exception) {}
        return newHomePageResponse(listOf(HomePageList(request.name, items)))
    }

    // ---------- LOAD (details page) ----------
    override suspend fun load(url: String): LoadResponse? {
        if (url.startsWith("movie:")) {
            val tid = url.removePrefix("movie:")
            try {
                val infoUrl = mainUrl + "/player_api.php?username=" + cred("user") +
                        "&password=" + cred("pass") + "&action=get_vod_info&vod_id=" + tid
                val txt = app.get(infoUrl).text
                val j = JSONObject(txt)
                val info = j.optJSONObject("info") ?: JSONObject()
                val movie = j.optJSONObject("movie_data") ?: JSONObject()

                val nm = info.optString("name", movie.optString("name", "Movie"))
                val plot = info.optString("plot", info.optString("description", ""))
                val poster = info.optString("movie_image", info.optString("cover_big", ""))

                return newMovieLoadResponse(nm, url, TvType.Movie, url) {
                    this.posterUrl = poster.ifBlank { null }
                    this.plot = plot.ifBlank { null }
                }
            } catch (_: Exception) {
                return null
            }
        }
        // Live stream
        return newLiveStreamLoadResponse(name, url, url)
    }

    // ---------- LOAD LINKS (play button) ----------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.startsWith("movie:")) {
            val tid = data.removePrefix("movie:")
            try {
                val playUrl = mainUrl + "/vod/play/" + tid
                val txt = app.get(playUrl).text
                val j = JSONObject(txt)
                val sources = j.optJSONArray("sources")
                if (sources != null && sources.length() > 0) {
                    // Prefer HLS
                    var picked: JSONObject? = null
                    for (i in 0 until sources.length()) {
                        val s = sources.getJSONObject(i)
                        if (s.optString("type") == "hls") { picked = s; break }
                    }
                    if (picked == null) picked = sources.getJSONObject(0)

                    val url = picked.optString("url", "")
                    val quality = picked.optString("quality", "1080p")
                    if (url.isNotBlank()) {
                        val qInt = when {
                            quality.contains("1080") -> Qualities.P1080.value
                            quality.contains("720") -> Qualities.P720.value
                            quality.contains("480") -> Qualities.P480.value
                            else -> Qualities.Unknown.value
                        }
                        callback(newExtractorLink(
                            source = this.name,
                            name = "9bik VOD",
                            url = url,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.quality = qInt
                        })
                        return true
                    }
                }
            } catch (_: Exception) {}
            return false
        }
        // Live stream URL — pass through
        callback(newExtractorLink(
            source = this.name,
            name = "9bik Live",
            url = data,
            type = ExtractorLinkType.M3U8
        ) {
            this.quality = Qualities.Unknown.value
        })
        return true
    }

    // ---------- HELPERS ----------
    private suspend fun teamLogo(teamName: String): String? {
        if (teamName.isBlank()) return null
        teamLogoCache[teamName]?.let { return it.ifBlank { null } }
        try {
            val url = "https://www.thesportsdb.com/api/v1/json/3/searchteams.php?t=" +
                    java.net.URLEncoder.encode(teamName, "UTF-8")
            val txt = app.get(url).text
            val j = JSONObject(txt)
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

    private suspend fun refreshBranding() {
        val last = pref("brand_refreshed", "0").toLongOrNull() ?: 0L
        if (System.currentTimeMillis() - last < 3_600_000L) return

        val urls = listOf(
            "https://cdn.9bik.win/plugin/config",
            "https://9bik.win/plugin/config"
        )
        for (url in urls) {
            try {
                val txt = app.get(url).text
                val j = JSONObject(txt)
                val b = j.optJSONObject("branding") ?: continue

                val ctx = NineBikPlugin.appContext ?: continue
                NineBikPlugin.setPref(ctx, "brand_name", b.optString("name", "9bik IPTV"))
                NineBikPlugin.setPref(ctx, "brand_tagline", b.optString("tagline", ""))
                NineBikPlugin.setPref(ctx, "brand_welcome", b.optString("welcome_text", ""))
                NineBikPlugin.setPref(ctx, "brand_button", b.optString("button_label", "Activate"))
                NineBikPlugin.setPref(ctx, "brand_footer", b.optString("footer_text", ""))
                NineBikPlugin.setPref(ctx, "brand_primary", b.optString("primary_color", "#5c6bc0"))
                NineBikPlugin.setPref(ctx, "brand_secondary", b.optString("secondary_color", "#0a0e27"))
                NineBikPlugin.setPref(ctx, "brand_accent", b.optString("accent_color", "#7986cb"))
                NineBikPlugin.setPref(ctx, "brand_refreshed", System.currentTimeMillis().toString())
                return
            } catch (_: Exception) { continue }
        }
    }

    // ---------- Activation (synchronous, called from plugin dialog) ----------
    companion object {
        fun activateCode(ctx: Context, code: String): Boolean {
            val baseUrls = listOf(
                "https://cdn.9bik.win",
                "https://9bik.win"
            )
            var device = NineBikPlugin.getPref(ctx, "device_id", "")
            if (device.isBlank()) {
                device = java.util.UUID.randomUUID().toString()
                NineBikPlugin.setPref(ctx, "device_id", device)
            }

            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            for (base in baseUrls) {
                try {
                    val url = "$base/activate?code=$code&device=$device"
                    val req = okhttp3.Request.Builder()
                        .url(url)
                        .header("User-Agent", "9bik-plugin")
                        .build()
                    val resp = client.newCall(req).execute()
                    val txt = resp.body?.string() ?: continue
                    resp.close()
                    val j = JSONObject(txt)
                    val user = j.optString("user", "")
                    val pass = j.optString("pass", "")
                    if (user.isBlank() || pass.isBlank()) continue

                    NineBikPlugin.setPref(ctx, "user", user)
                    NineBikPlugin.setPref(ctx, "pass", pass)
                    NineBikPlugin.setPref(ctx, "activation_code", code)
                    return true
                } catch (_: Exception) {
                    continue
                }
            }
            return false
        }
    }
}
