package com.ninebik

import android.content.Context
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
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
    override val supportedTypes = setOf(TvType.Live, TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasDownloadSupport = false

    private val teamLogoCache = mutableMapOf<String, String>()

    private fun pref(key: String, def: String = ""): String {
        val ctx = NineBikPlugin.appContext ?: return def
        return NineBikPlugin.getPref(ctx, key, def)
    }

    private fun cred(key: String): String = pref(key, "")

    // ==========================================================
    // MAIN PAGE
    // ==========================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Refresh branding + content from panel once/hour
        refreshPanelConfig()

        if (cred("user").isBlank() || cred("pass").isBlank()) {
            return newHomePageResponse(
                listOf(HomePageList("⚠️ Activation required", listOf(
                    newLiveSearchResponse("Tap the settings gear to activate", mainUrl, TvType.Live) {
                        this.posterUrl = null
                    }
                )))
            )
        }

        val lists = mutableListOf<HomePageList>()

        // === 1. LIVE MATCHES GROUPED BY LEAGUE ===
        try {
            val liveByLeague = mutableMapOf<String, MutableList<SearchResponse>>()
            val soonByLeague = mutableMapOf<String, MutableList<SearchResponse>>()

            val txt = app.get("$mainUrl/api/fixtures").text
            val data = JSONObject(txt)
            val arr = data.optJSONArray("fixtures") ?: JSONArray()

            val nowMs = System.currentTimeMillis()

            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (!o.optBoolean("has_stream", false)) continue

                val home = o.optString("home", "?")
                val away = o.optString("away", "?")
                val league = o.optString("competition", "Other")
                val isLive = o.optBoolean("is_playing", false)
                val firstUrl = o.optString("first_url", "")
                val timeStr = o.optString("time", "")
                val type = o.optString("type", "")

                if (type.equals("Finished", ignoreCase = true)) continue
                if (firstUrl.isBlank()) continue

                // Compute minutes to start
                var minutesToStart = Long.MAX_VALUE
                try {
                    val cleanTime = timeStr.replace("Z", "+00:00").substringBefore(".")
                    val dt = java.time.OffsetDateTime.parse(cleanTime + "Z")
                    val kickoffMs = dt.toInstant().toEpochMilli()
                    minutesToStart = (kickoffMs - nowMs) / 60000L
                } catch (_: Exception) {}

                val baseUrl = if (firstUrl.startsWith("http")) firstUrl else mainUrl + firstUrl
                val u = cred("user"); val p = cred("pass")
                val sep = if (baseUrl.contains("?")) "&" else "?"
                val fullUrl = "$baseUrl${sep}u=$u&p=$p"

                val logo = teamLogo(home)
                val item = newLiveSearchResponse("$home vs $away", fullUrl, TvType.Live) {
                    this.posterUrl = logo
                }

                when {
                    isLive -> liveByLeague.getOrPut(league) { mutableListOf() }.add(item)
                    minutesToStart in -5..30 -> soonByLeague.getOrPut(league) { mutableListOf() }.add(item)
                }
            }

            for ((league, items) in liveByLeague.toList().sortedBy { it.first }) {
                lists.add(HomePageList("🔴 LIVE - $league", items))
            }
            for ((league, items) in soonByLeague.toList().sortedBy { it.first }) {
                lists.add(HomePageList("⏰ STARTS SOON - $league", items))
            }
        } catch (_: Exception) {}

        // === 2. CURATED CHANNELS (from panel) ===
        val selCats = pref("content_categories", "").split("||").filter { it.isNotBlank() }
        val incChannels = pref("content_include_channels", "false") == "true"

        if (incChannels && selCats.isNotEmpty()) {
            try {
                val u = cred("user"); val p = cred("pass")
                val catTxt = app.get("$mainUrl/player_api.php?username=$u&password=$p&action=get_live_categories").text
                val catArr = JSONArray(catTxt)
                val catNameById = mutableMapOf<String, String>()
                for (i in 0 until catArr.length()) {
                    val c = catArr.getJSONObject(i)
                    catNameById[c.optString("category_id")] = c.optString("category_name")
                }

                val chTxt = app.get("$mainUrl/player_api.php?username=$u&password=$p&action=get_live_streams").text
                val chArr = JSONArray(chTxt)
                val byCat = mutableMapOf<String, MutableList<SearchResponse>>()

                for (i in 0 until chArr.length()) {
                    val o = chArr.getJSONObject(i)
                    val catId = o.optString("category_id", "")
                    val catName = catNameById[catId] ?: ""
                    if (catName !in selCats) continue

                    val sid = o.optString("stream_id")
                    val nm = o.optString("name", "?")
                    val icon = o.optString("stream_icon", "")
                    val streamUrl = "$mainUrl/live/$u/$p/$sid.ts"
                    byCat.getOrPut(catName) { mutableListOf() }.add(
                        newLiveSearchResponse(nm, streamUrl, TvType.Live) {
                            this.posterUrl = icon.ifBlank { null }
                        }
                    )
                }
                for ((catName, chans) in byCat) {
                    lists.add(HomePageList("📺 $catName", chans))
                }
            } catch (_: Exception) {}
        }

        // === 3. MOVIES (only if enabled) ===
        val incMovies = pref("content_include_movies", "true") == "true"
        if (incMovies) {
            try {
                val u = cred("user"); val p = cred("pass")
                val catUrl = "$mainUrl/player_api.php?username=$u&password=$p&action=get_vod_categories"
                val catTxt = app.get(catUrl).text
                val catArr = JSONArray(catTxt)
                var added = 0
                for (i in 0 until catArr.length()) {
                    val c = catArr.getJSONObject(i)
                    val cid = c.optString("category_id")
                    val cnm = c.optString("category_name", "Movies")
                    if (cid.isBlank()) continue
                    val movs = fetchMovies(cid)
                    if (movs.isNotEmpty()) {
                        lists.add(HomePageList(cnm, movs))
                        added++
                    }
                }
                if (added == 0) {
                    lists.add(HomePageList("🔥 Trending Movies", fetchMovies("trending")))
                    lists.add(HomePageList("⭐ Popular Movies", fetchMovies("popular")))
                    lists.add(HomePageList("🏆 Top Rated", fetchMovies("top_rated")))
                }
            } catch (_: Exception) {
                lists.add(HomePageList("🔥 Trending Movies", fetchMovies("trending")))
                lists.add(HomePageList("⭐ Popular Movies", fetchMovies("popular")))
                lists.add(HomePageList("🏆 Top Rated", fetchMovies("top_rated")))
            }
        }

        // === 4. TV SERIES (only if enabled) ===
        val incSeries = pref("content_include_series", "true") == "true"
        if (incSeries) {
            try {
                val u = cred("user"); val p = cred("pass")
                val catUrl = "$mainUrl/player_api.php?username=$u&password=$p&action=get_series_categories"
                val catTxt = app.get(catUrl).text
                val catArr = JSONArray(catTxt)
                for (i in 0 until catArr.length()) {
                    val c = catArr.getJSONObject(i)
                    val cid = c.optString("category_id")
                    val cnm = c.optString("category_name", "TV Series")
                    if (cid.isBlank()) continue
                    val shows = fetchSeriesFolder(cid)
                    if (shows.isNotEmpty()) {
                        lists.add(HomePageList(cnm, shows))
                    }
                }
            } catch (_: Exception) {}
        }

        if (lists.isEmpty()) {
            lists.add(HomePageList("No content", emptyList()))
        }

        return newHomePageResponse(lists)
    }

    // ==========================================================
    // MOVIES
    // ==========================================================
    private suspend fun fetchMovies(catId: String): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        try {
            val url = "$mainUrl/player_api.php?username=${cred("user")}&password=${cred("pass")}&action=get_vod_streams&category_id=$catId"
            val txt = app.get(url).text
            val arr = JSONArray(txt)
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val tid = m.optString("stream_id", "")
                val nm = m.optString("name", "?")
                val poster = m.optString("stream_icon", "")
                if (tid.isBlank()) continue
                // Use our new custom scheme
                val movieUrl = "https://9bik.local/movie/$tid"
                items.add(newMovieSearchResponse(nm, movieUrl, TvType.Movie) {
                    this.posterUrl = poster.ifBlank { null }
                })
            }
        } catch (_: Exception) {}
        return items
    }

    // ==========================================================
    // TV SERIES FOLDER
    // ==========================================================
    private suspend fun fetchSeriesFolder(catId: String): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        try {
            val url = "$mainUrl/player_api.php?username=${cred("user")}&password=${cred("pass")}&action=get_series&category_id=$catId"
            val txt = app.get(url).text
            val arr = JSONArray(txt)
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                val sid = s.optString("series_id", "")
                val nm = s.optString("name", "?")
                val poster = s.optString("cover", "")
                if (sid.isBlank()) continue
                val seriesUrl = "https://9bik.local/series/" + sid
                items.add(newTvSeriesSearchResponse(nm, seriesUrl, TvType.TvSeries) {
                    this.posterUrl = poster.ifBlank { null }
                })
            }
        } catch (_: Exception) {}
        return items
    }

    // ==========================================================
    // LOAD
    // ==========================================================
    override suspend fun load(url: String): LoadResponse? {
        if (url.contains("9bik.local/movie/")) {
            val tid = url.substringAfter("9bik.local/movie/").substringBefore("/").substringBefore("?")
            try {
                val u = cred("user"); val p = cred("pass")
                val infoUrl = "$mainUrl/player_api.php?username=$u&password=$p&action=get_vod_info&vod_id=$tid"
                val txt = app.get(infoUrl).text
                val j = JSONObject(txt)
                val info = j.optJSONObject("info") ?: JSONObject()
                
                var nm = info.optString("name", "")
                if (nm.isBlank() || nm == "Movie") nm = info.optString("o_name", "Unknown Movie")
                
                val plot = info.optString("plot", info.optString("description", "No plot available."))
                var poster = info.optString("movie_image", info.optString("cover_big", ""))
                if (poster.isBlank()) poster = info.optString("backdrop_path", "")

                val streamUrl = "$mainUrl/movie/$u/$p/$tid.mp4"
                
                return newMovieLoadResponse(nm, url, TvType.Movie, streamUrl) {
                    this.posterUrl = poster.ifBlank { null }
                    this.plot = plot
                }
            } catch (_: Exception) { return null }
        }

        // --- SERIES detail ---
        if (url.contains("9bik.local/series/")) {
            val sid = url.substringAfter("9bik.local/series/").substringBefore("/").substringBefore("?")
            try {
                val u = cred("user"); val p = cred("pass")
                val infoUrl = "$mainUrl/player_api.php?username=$u&password=$p&action=get_series_info&series_id=$sid"
                val txt = app.get(infoUrl).text
                val j = JSONObject(txt)
                val info = j.optJSONObject("info") ?: JSONObject()
                val nm = info.optString("name", "Series")
                val plot = info.optString("plot", info.optString("description", ""))
                val poster = info.optString("cover", info.optString("cover_big", ""))
                val ratingStr = info.optString("rating", "")
                val rating = ratingStr.toFloatOrNull()?.let { (it * 10).toInt() }

                val episodesList = mutableListOf<Episode>()
                val eps = j.optJSONObject("episodes")
                if (eps != null) {
                    val keys = eps.keys()
                    while (keys.hasNext()) {
                        val seasonKey = keys.next()
                        val seasonArr = eps.optJSONArray(seasonKey) ?: JSONArray()
                        val seasonNum = seasonKey.toIntOrNull() ?: 1
                        for (i in 0 until seasonArr.length()) {
                            val ep = seasonArr.getJSONObject(i)
                            val epId = ep.optString("id", "")
                            val epNum = ep.optInt("episode_num", i + 1)
                            val epTitle = ep.optString("title", "Episode " + epNum)
                            val epInfo = ep.optJSONObject("info") ?: JSONObject()
                            val epImg = epInfo.optString("movie_image", "")
                            if (epId.isBlank()) continue
                            val epUrl = "$mainUrl/series/$u/$p/$epId.mp4"
                            episodesList.add(newEpisode(epUrl) {
                                this.name = epTitle
                                this.episode = epNum
                                this.season = seasonNum
                                this.posterUrl = epImg.ifBlank { null }
                            })
                        }
                    }
                }
                return newTvSeriesLoadResponse(nm, url, TvType.TvSeries, episodesList) {
                    this.posterUrl = poster.ifBlank { null }
                    this.plot = plot.ifBlank { null }
                }
            } catch (_: Exception) { return null }
        }

        return newLiveStreamLoadResponse(name, url, url)
    }

    // ==========================================================
    // LOAD LINKS (play button)
    // ==========================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            // Pass the stream URL and force the proper Referer header
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "9bik Stream",
                    url = data,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.quality = Qualities.Unknown.value
                    this.referer = "$mainUrl/"
                    this.headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                        "Referer" to "https://123moviesfree.club/",
                        "Origin" to "https://123moviesfree.club"
                    )
                }
            )
            return true
        } catch (e: Exception) {
            return false
        }
    }

    // ==========================================================
    // TEAM LOGO
    // ==========================================================
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
            val logo = if (badge.isNotBlank()) badge else teams.getJSONObject(0).optString("strLogo", "")
            teamLogoCache[teamName] = logo
            return logo.ifBlank { null }
        } catch (_: Exception) {
            teamLogoCache[teamName] = ""
            return null
        }
    }

    // ==========================================================
    // PANEL CONFIG (branding + content)
    // ==========================================================
    private suspend fun refreshPanelConfig() {
        val last = pref("brand_refreshed", "0").toLongOrNull() ?: 0L
        if (System.currentTimeMillis() - last < 3_600_000L) return

        val urls = listOf("https://cdn.9bik.win/plugin/config", "https://9bik.win/plugin/config")
        for (url in urls) {
            try {
                val txt = app.get(url).text
                val j = JSONObject(txt)
                val b = j.optJSONObject("branding")
                val content = j.optJSONObject("content")
                val ctx = NineBikPlugin.appContext ?: continue

                // Branding
                if (b != null) {
                    NineBikPlugin.setPref(ctx, "brand_name", b.optString("name", "9bik IPTV"))
                    NineBikPlugin.setPref(ctx, "brand_tagline", b.optString("tagline", ""))
                    NineBikPlugin.setPref(ctx, "brand_welcome", b.optString("welcome_text", ""))
                    NineBikPlugin.setPref(ctx, "brand_button", b.optString("button_label", "Activate"))
                    NineBikPlugin.setPref(ctx, "brand_footer", b.optString("footer_text", ""))
                    NineBikPlugin.setPref(ctx, "brand_primary", b.optString("primary_color", "#5c6bc0"))
                    NineBikPlugin.setPref(ctx, "brand_secondary", b.optString("secondary_color", "#0a0e27"))
                    NineBikPlugin.setPref(ctx, "brand_accent", b.optString("accent_color", "#7986cb"))
                }

                // Content curation
                if (content != null) {
                    val cats = content.optJSONArray("categories")
                    val names = mutableListOf<String>()
                    if (cats != null) for (ci in 0 until cats.length()) names.add(cats.getString(ci))
                    NineBikPlugin.setPref(ctx, "content_categories", names.joinToString("||"))
                    NineBikPlugin.setPref(ctx, "content_include_channels", content.optBoolean("include_channels", false).toString())
                    NineBikPlugin.setPref(ctx, "content_include_matches", content.optBoolean("include_matches", true).toString())
                }

                NineBikPlugin.setPref(ctx, "brand_refreshed", System.currentTimeMillis().toString())
                return
            } catch (_: Exception) { continue }
        }
    }

    // ==========================================================
    // ACTIVATION
    // ==========================================================
    companion object {
        fun activateCode(ctx: Context, code: String): Boolean {
            val baseUrls = listOf("https://cdn.9bik.win", "https://9bik.win")
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
                    val req = okhttp3.Request.Builder().url(url).header("User-Agent", "9bik-plugin").build()
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
                } catch (_: Exception) { continue }
            }
            return false
        }
    }
}
