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
import java.util.UUID

class NineBikProvider : MainAPI() {
    override var mainUrl = "https://cdn.9bik.win"
    override var name = "9bik IPTV"
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Live)
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasDownloadSupport = false

    private val teamLogoCache = mutableMapOf<String, String>()

    // Multi-URL bootstrap for panel (in case main URL changes)
    private val bootstrapUrls = listOf(
        "https://cdn.9bik.win/plugin/config",
        "https://9bik.win/plugin/config",
        "https://backup.9bik.win/plugin/config"
    )

    // ---------- Preferences ----------
    private fun pref(key: String, def: String = ""): String {
        val ctx = NineBikPlugin.appContext ?: return def
        return NineBikPlugin.getPref(ctx, key, def)
    }

    private fun savePref(key: String, value: String) {
        val ctx = NineBikPlugin.appContext ?: return
        NineBikPlugin.setPref(ctx, key, value)
    }

    private fun deviceId(): String {
        var id = pref("device_id", "")
        if (id.isBlank()) {
            id = UUID.randomUUID().toString()
            savePref("device_id", id)
        }
        return id
    }

    private fun cred(key: String): String = pref(key, "")

    // ---------- Bootstrap from panel ----------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Refresh branding + content from panel
        refreshBranding()

        // If no credentials yet, show activation prompt
        if (cred("user").isBlank() || cred("pass").isBlank()) {
            val items = listOf(
                newLiveSearchResponse("⚠️ Tap the settings gear to activate", mainUrl, TvType.Live) {
                    this.posterUrl = null
                }
            )
            return newHomePageResponse(listOf(HomePageList("Activation required", items)))
        }

        val lists = mutableListOf<HomePageList>()

        // === 1. LIVE MATCHES ===
        try {
            val url = mainUrl + "/api/fixtures"
            val resp = app.get(url).text
            val data = JSONObject(resp)
            val arr = data.optJSONArray("fixtures")

            if (arr != null) {
                val liveByLeague = mutableMapOf<String, MutableList<SearchResponse>>()
                val soonByLeague = mutableMapOf<String, MutableList<SearchResponse>>()

                val now = System.currentTimeMillis()

                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)

                    val home = o.optString("home", "?")
                    val away = o.optString("away", "?")
                    val league = o.optString("competition", "Other")
                    val isLive = o.optBoolean("is_playing", false)
                    val hasStream = o.optBoolean("has_stream", false)
                    val firstUrl = o.optString("first_url", "")
                    val timeStr = o.optString("time", "")

                    // Skip matches that already finished
                    val type = o.optString("type", "")
                    if (type.equals("Finished", ignoreCase = true)) continue

                    val displayName = "$home vs $away"
                    val logo = teamLogo(home)

                    val fullUrl: String = if (hasStream && firstUrl.isNotBlank()) {
                        val baseUrl = if (firstUrl.startsWith("http")) firstUrl else mainUrl + firstUrl
                        val u = cred("user")
                        val p = cred("pass")
                        val sep = if (baseUrl.contains("?")) "&" else "?"
                        "$baseUrl${sep}u=$u&p=$p"
                    } else {
                        mainUrl + "/no-stream?match=" + o.optInt("id", 0)
                    }

                    // Check start time — how many minutes until kickoff?
                    var minutesToStart = Long.MAX_VALUE
                    try {
                        // Parse ISO 8601: 2026-10-15T16:45:00.000000Z
                        val cleanTime = timeStr.replace("Z", "+00:00").substringBefore(".")
                        val dt = java.time.OffsetDateTime.parse(cleanTime + "Z")
                        val kickoffMs = dt.toInstant().toEpochMilli()
                        minutesToStart = (kickoffMs - now) / 60000L
                    } catch (_: Exception) {}

                    val item = newLiveSearchResponse(displayName, fullUrl, TvType.Live) {
                        this.posterUrl = logo
                    }

                    if (isLive) {
                        liveByLeague.getOrPut(league) { mutableListOf() }.add(item)
                    } else if (minutesToStart in -5..30) {
                        // Upcoming within 30 min (or kicked off up to 5 min ago but not marked live)
                        soonByLeague.getOrPut(league) { mutableListOf() }.add(item)
                    }
                    // else: too far in future, skip
                }

                // Emit live categories (grouped by league)
                for ((league, items) in liveByLeague.toList().sortedBy { it.first }) {
                    lists.add(HomePageList("🔴 LIVE - $league", items))
                }

                // Emit upcoming categories (grouped by league)
                for ((league, items) in soonByLeague.toList().sortedBy { it.first }) {
                    lists.add(HomePageList("⏰ STARTS SOON - $league", items))
                }
            }
        } catch (_: Exception) {}

        // === 2. CURATED CHANNELS ===
        try {
            val selectedCats = pref("content_categories", "").split("||").filter { it.isNotBlank() }
            val incChannels = pref("content_include_channels", "false") == "true"

            if (incChannels && selectedCats.isNotEmpty()) {
                val url = mainUrl + "/player_api.php?username=" + cred("user") +
                        "&password=" + cred("pass") + "&action=get_live_streams"
                val resp = app.get(url).text
                val arr = org.json.JSONArray(resp)

                // Group by category
                val byCat = mutableMapOf<String, MutableList<SearchResponse>>()

                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val sid = o.optString("stream_id")
                    val nm = o.optString("name")
                    val icon = o.optString("stream_icon", "")
                    val catId = o.optString("category_id", "")

                    // Resolve category name — plugin will match against selected list by ID
                    // Simpler: match by checking if any selected category name is contained
                    // We use a flat match against all selected categories passed from panel
                    val u = cred("user")
                    val p = cred("pass")
                    val streamUrl = "$mainUrl/live/$u/$p/$sid.ts"

                    val item = newLiveSearchResponse(nm, streamUrl, TvType.Live) {
                        this.posterUrl = icon.ifBlank { null }
                    }
                    byCat.getOrPut(catId) { mutableListOf() }.add(item)
                }

                // Now emit rows for the selected categories
                // We need to know which cat_id maps to which name.
                // Fetch categories list.
                try {
                    val catUrl = mainUrl + "/player_api.php?username=" + cred("user") +
                            "&password=" + cred("pass") + "&action=get_live_categories"
                    val catResp = app.get(catUrl).text
                    val catArr = org.json.JSONArray(catResp)

                    for (i in 0 until catArr.length()) {
                        val co = catArr.getJSONObject(i)
                        val cid = co.optString("category_id")
                        val cname = co.optString("category_name")

                        if (cname !in selectedCats) continue

                        val items = byCat[cid] ?: continue
                        if (items.isEmpty()) continue

                        lists.add(HomePageList(cname, items))
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        if (lists.isEmpty()) {
            lists.add(HomePageList("No content - check panel selection", emptyList()))
        }

        return newHomePageResponse(lists)
    }

    // ---------- Branding refresh ----------
    private suspend fun refreshBranding() {
        // Only refresh if older than 1 hour
        val last = pref("brand_refreshed", "0").toLongOrNull() ?: 0L
        if (System.currentTimeMillis() - last < 3_600_000L) return

        for (url in bootstrapUrls) {
            try {
                val txt = app.get(url).text
                val j = JSONObject(txt)
                val b = j.optJSONObject("branding") ?: continue
                val a = j.optJSONObject("api")

                savePref("brand_name", b.optString("name", "9bik IPTV"))
                savePref("brand_tagline", b.optString("tagline", ""))
                savePref("brand_welcome", b.optString("welcome_text", ""))
                savePref("brand_button", b.optString("button_label", "Activate"))
                savePref("brand_footer", b.optString("footer_text", ""))
                savePref("brand_primary", b.optString("primary_color", "#5c6bc0"))
                savePref("brand_secondary", b.optString("secondary_color", "#0a0e27"))
                savePref("brand_accent", b.optString("accent_color", "#7986cb"))

                // Update mainUrl if panel says so
                val newBase = a?.optString("base_url", "") ?: ""
                if (newBase.isNotBlank()) {
                    mainUrl = newBase
                    name = b.optString("name", "9bik IPTV")
                }

                // Save content selection
                val content = j.optJSONObject("content")
                if (content != null) {
                    val cats = content.optJSONArray("categories")
                    val catNames = mutableListOf<String>()
                    if (cats != null) {
                        for (ci in 0 until cats.length()) {
                            catNames.add(cats.getString(ci))
                        }
                    }
                    savePref("content_categories", catNames.joinToString("||"))
                    savePref("content_include_channels",
                        content.optBoolean("include_channels", false).toString())
                    savePref("content_include_matches",
                        content.optBoolean("include_matches", true).toString())
                }

                savePref("brand_refreshed", System.currentTimeMillis().toString())
                return
            } catch (_: Exception) {
                continue
            }
        }
    }

    // ---------- Team logo ----------
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

    override suspend fun search(query: String): List<SearchResponse>? = null

    override suspend fun load(url: String): LoadResponse? {
        return newLiveStreamLoadResponse(name = name, url = url, dataUrl = url)
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

    // ---------- Activation (synchronous, no coroutines needed) ----------
    companion object {
        fun activateCode(ctx: Context, code: String): Boolean {
            val baseUrls = listOf(
                "https://cdn.9bik.win",
                "https://9bik.win",
                "https://backup.9bik.win"
            )
            var device = NineBikPlugin.getPref(ctx, "device_id", "")
            if (device.isBlank()) {
                device = UUID.randomUUID().toString()
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
