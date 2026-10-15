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
        // Refresh branding from panel (once per call; cheap)
        refreshBranding()

        // If no credentials yet, show a "Login" prompt row
        if (cred("user").isBlank() || cred("pass").isBlank()) {
            val items = listOf(
                newLiveSearchResponse("⚠️ Tap the settings gear to activate", mainUrl, TvType.Live) {
                    this.posterUrl = null
                }
            )
            return newHomePageResponse(listOf(HomePageList("Activation required", items)))
        }

        val liveNow = mutableListOf<SearchResponse>()
        val upcoming = mutableListOf<SearchResponse>()
        val recent = mutableListOf<SearchResponse>()

        try {
            val url = mainUrl + "/api/fixtures"
            val resp = app.get(url).text
            val data = JSONObject(resp)
            val arr = data.optJSONArray("fixtures") ?: return newHomePageResponse(
                listOf(HomePageList("No matches available", emptyList()))
            )

            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (!o.optBoolean("has_stream", false)) continue

                val firstUrl = o.optString("first_url", "")
                if (firstUrl.isBlank()) continue

                val home = o.optString("home", "?")
                val away = o.optString("away", "?")
                val isLive = o.optBoolean("is_playing", false)
                val type = o.optString("type", "")

                val baseUrl = if (firstUrl.startsWith("http")) firstUrl else mainUrl + firstUrl
                val u = cred("user")
                val p = cred("pass")
                val sep = if (baseUrl.contains("?")) "&" else "?"
                val fullUrl = "$baseUrl${sep}u=$u&p=$p"

                val displayName = "$home vs $away"
                val logo = teamLogo(home)
                val item = newLiveSearchResponse(displayName, fullUrl, TvType.Live) {
                    this.posterUrl = logo
                }

                when {
                    isLive -> liveNow.add(item)
                    type.equals("Upcoming", ignoreCase = true) -> upcoming.add(item)
                    else -> recent.add(item)
                }
            }
        } catch (_: Exception) {}

        val lists = mutableListOf<HomePageList>()
        if (liveNow.isNotEmpty()) lists.add(HomePageList("🔴 Live Now", liveNow))
        if (upcoming.isNotEmpty()) lists.add(HomePageList("⏰ Upcoming", upcoming))
        if (recent.isNotEmpty()) lists.add(HomePageList("✅ Recent", recent))
        if (lists.isEmpty()) lists.add(HomePageList("No matches available", emptyList()))

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

    // ---------- Activation ----------
    companion object {
        suspend fun activateCode(ctx: Context, code: String): Boolean {
            val baseUrls = listOf(
                "https://cdn.9bik.win",
                "https://9bik.win",
                "https://backup.9bik.win"
            )
            val device = NineBikPlugin.getPref(ctx, "device_id", "").ifBlank {
                val id = UUID.randomUUID().toString()
                NineBikPlugin.setPref(ctx, "device_id", id)
                id
            }

            for (base in baseUrls) {
                try {
                    val url = "$base/activate?code=$code&device=$device"
                    val txt = app.get(url).text
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
